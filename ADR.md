# SlotMind — Architecture Decision Record

Reactive re-scheduling engine for CareBridge clinics. When a provider goes `OFFLINE` or a slot is
`CANCELLED`, the system finds affected appointments, asks a pluggable scheduling engine (AI or
rule-based) for a replacement slot, and queues a patient-friendly suggestion for front-desk approval.

Stack: Spring Boot 3.4 (Java 17+), JPA/Hibernate on **SQLite**, React 18 + Vite, Zycus LiteLLM gateway
(`qwen-cursor`, OpenAI-compatible).

Each decision follows **Context → Options → Decision → Tradeoffs**.

---

## ADR-1 · Where does scheduling logic live?

**Context.** Booking rules (a slot can only be booked when OPEN, an appointment can only move along its
lifecycle) must hold no matter which caller — HTTP, the async agent, or a future batch job — changes state.

**Options.**
1. Anemic entities, all rules in services.
2. Rules on the entities (state machines), orchestration in services.
3. A separate domain-service layer per aggregate.

**Decision.** Option 2.
- `Slot.book()` refuses anything but `OPEN`; `Slot.release()` frees a booked slot but leaves a
  cancelled one cancelled; `Slot.changeStatus()` refuses direct `BOOKED`.
- `AppointmentStatus.next()` encodes `CONFIRMED → RESCHEDULE_PENDING → RESCHEDULED → COMPLETED`
  (a `RESCHEDULED` appointment can be disrupted again); `Appointment.transition()` enforces it.
- `RescheduleSuggestion.accept()/reject()` only from `PENDING`.
- `BookingService` (commands) and `ReschedulingService` (proposals) orchestrate; the **scheduling
  decision itself** lives behind the `SchedulingStrategy` contract (ADR-2), not in services or entities.

Booking conflicts are prevented at the domain boundary by `Slot.book()` and, under concurrency, by
`@Version` optimistic locking (ADR-3). Any violation throws `DomainException` → HTTP 409.

**Tradeoffs.** Entities reference each other by id (not JPA relations). That keeps snapshots cheap
to pass to strategies and avoids lazy-loading surprises, at the cost of a few explicit lookups.

---

## ADR-2 · Runtime strategy switching

**Context.** Rule-based and AI strategies must be swappable at runtime. HTTP and the async loop must use
the same contract. Sprint 2's `RoomConstraintStrategy` should need only "implement and register".

**Options.** `@Qualifier` bean map / factory with a `switch` / registry built from all beans.

**Decision.** A **registry**: `SchedulingEngine` collects every `SchedulingStrategy` bean into a
`Map<name, strategy>`. The active name lives in an `AtomicReference`. It is seeded from
`slotmind.strategy.active` and switched live through `PUT /config/strategy` (or the UI toggle).

```java
public interface SchedulingStrategy {
    String name();
    List<Recommendation> recommend(SchedulingContext ctx);                       // ordered, best first
    default List<Recommendation> recommend(SchedulingContext ctx, Consumer<String> progress) { ... } // streaming
}
```

`SchedulingContext` is an immutable snapshot: appointment, its urgency/type, original provider and slot,
trigger reason, OPEN candidate slots and the provider roster. Both callers (`POST /appointments/{id}/suggest`
and `RescheduleAgent`) go through `ReschedulingService.propose()` → `SchedulingEngine.recommend()`.

The engine also owns two cross-cutting concerns, so no strategy can skip them:
- **Validation.** Every recommendation must name a slot that is in the candidate set (OPEN, future,
  provider not offline). Hallucinated or stale ids are discarded. `alternateProviderId` is recomputed
  from the slot's owner. Confidence is clamped to 0..1.
- **Fallback.** If the active strategy throws or returns nothing valid, the rule-based strategy runs
  instead. The suggestion records `strategy = "rule (fallback: ai failed: …)"`, so a fallback is never
  silent, and the UI shows it.

**Tradeoffs.** A process-wide switch (not per-request). That is right for a clinic desk. A per-tenant
switch would move the active name into config storage.

---

## ADR-3 · Transactional accept

**Context.** Accepting must update the appointment, free the old slot, and book the new slot atomically,
with no double booking even if two coordinators accept suggestions for the same slot at once (AGT-3).

**Options.** Pessimistic `SELECT … FOR UPDATE` · optimistic locking (`@Version`) · DB unique constraint
on `appointments.slot_id`.

**Decision.** One `@Transactional` method, `BookingService.accept()`:
1. `suggestion.accept()` (must be `PENDING`)
2. reject if the new slot's provider has gone offline since the suggestion was made
3. `newSlot.book()` (must be `OPEN`)
4. `oldSlot.release()` (BOOKED → OPEN; a cancelled slot stays cancelled)
5. `appointment.rescheduleTo(newProvider, newSlot)` (must be `RESCHEDULE_PENDING`)
6. `flush()` to surface version conflicts inside the transaction
7. Housekeeping: other pending suggestions for this appointment are rejected. Pending suggestions for
   *other* appointments that pointed at the same slot are rejected, and an event asks the agent to
   re-propose for those patients.

Concurrency is handled by **optimistic locking**: `Slot`, `Appointment` and `RescheduleSuggestion` carry
`@Version`. If two accepts race for one slot, the second commit fails its
`UPDATE … WHERE version = ?` → `ObjectOptimisticLockingFailureException` → HTTP 409 with a friendly
message. SQLite additionally serialises writers (one pooled connection + `busy_timeout`).
`AcceptConcurrencyTest` fires two accepts at the same slot in parallel and asserts exactly one wins.

**Tradeoffs.** Optimistic locking needs no locks held across user think-time and suits low contention.
The loser gets a 409 and retries, which is acceptable at a front desk. A unique index on `slot_id` was
skipped because completed appointments keep their historical slot id.

---

## ADR-4 · Agentic loop decoupling

**Context.** `PATCH /providers/{id}/status → OFFLINE` and `PATCH /slots/{id}/status → CANCELLED` must
return immediately and trigger re-scheduling in the background. Provider-offline affects many
appointments; slot-cancelled affects one (AGT-1).

**Options.** Call the agent directly from the controller · `@Async` method call · domain events with
transactional listeners · a message broker.

**Decision.** **Separate domain events, one shared pipeline.**
- `BookingService` publishes `ProviderWentOffline` / `SlotWasCancelled` inside the state-change
  transaction.
- `RescheduleAgent` listens with `@TransactionalEventListener` (AFTER_COMMIT) + `@Async("agentExecutor")`,
  so the agent only runs if the status change actually committed, and the HTTP call returns 202 right away.
- The listeners differ only in *how affected appointments are found*: by provider vs by slot, among
  `CONFIRMED` and `RESCHEDULED` bookings. Both feed `rescheduleAll(ids, trigger)` → `ReschedulingService.propose()`.
- **Dedupe:** skip when a `PENDING` suggestion already exists for the same appointment + trigger
  (checked before the engine runs and again inside the write transaction).
- **No LLM call inside a transaction:** read snapshot (short tx) → engine (no tx) → write suggestion
  (short tx). A slow model never blocks the single SQLite connection.
- **Batch fairness:** the agent processes a batch sequentially on a single-thread executor. Each new
  pending suggestion places a Hold on its slot, so the next patient in the batch gets a different slot
  (provider offline → two patients → two different openings).
- **Failure:** AI failure → rule-based fallback (ADR-2). Any other exception is logged and shown in the
  agent activity feed, and the batch continues. Nothing is dropped silently.
- **Human checkpoint:** the agent only *proposes*. The appointment becomes `RESCHEDULE_PENDING`; the
  front desk confirms with the patient and accepts.

**Two prompts.** `PromptFactory` writes a different *situation* and *preference* block per trigger:
- Offline: the doctor is unavailable, so the patient must move to a different provider, preferably of
  the same specialty; urgency decides how much earlier matters.
- Cancelled: the doctor is still working, so the patient should strongly prefer the same provider's
  nearest slot, and switch only if there is none or the case is urgent.

The output contract is shared: `{"slotId","alternateProviderId","confidence","reasoning"}`.
`reasoning` is constrained to 1–3 warm sentences addressed to the patient, with no ids or jargon, so
staff can read it aloud (AGT-2).

**Tradeoffs.** In-process events are not durable: a crash between commit and agent run loses that
trigger. Production would add an outbox table or a broker. A single agent thread keeps ordering simple
but limits throughput. Fine for one clinic, and LLM latency dominates anyway.

---

## ADR-5 · Extensibility and exclusions

**Sprint 2 hooks.**
- `RoomConstraintStrategy`: implement `SchedulingStrategy`, annotate `@Component`, done. It shows up in
  `GET /config/strategy` and the UI toggle. For composition (rooms *and* AI), it can wrap another
  strategy and filter `ctx.candidateSlots()` first.
- Room/equipment requirements: add `roomId` / required equipment to `Slot` and an appointment-type
  requirements table; the context builder passes them in the snapshot.
- Patient preferences (morning vs afternoon), duration by type, provider daily caps: all are inputs
  to `SchedulingContext` + filters/scoring inside strategies. The engine's validation stays unchanged.

**Sprint 3 hooks.** Waitlist auto-fill reacts to the same "slot became OPEN" moment (`Slot.release()`)
through a new event + listener. Notifications are a listener on accept.

**Deliberately deferred.**
- Auth/roles, multi-clinic, patient portal, billing.
- Durable eventing (outbox), retries with backoff for LLM calls.
- Calendar-grade time handling (time zones; slots stored as local date-time).
- Suggestion expiry (stale pending suggestions are cleaned up on accept, not on a timer).

---

## Why SQLite (vs H2/Postgres in the brief)

Zero-install file DB that survives restarts, so the demo state persists. It is single-writer, which we
embrace: `maximum-pool-size=1`, `busy_timeout`, WAL. Combined with "no LLM inside a transaction",
contention stays negligible. Swapping to Postgres is a datasource/dialect change; optimistic locking
works unchanged.
