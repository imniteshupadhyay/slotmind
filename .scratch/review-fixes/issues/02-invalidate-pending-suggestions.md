# What happens to pending suggestions when their target provider goes offline or slot is cancelled?

Type: grilling
Status: resolved
Blocked by: none

## Question

Impl §6 says "No silent drops". Today both agent listeners only handle `CONFIRMED` and `RESCHEDULED` appointments (`RescheduleAgent.java:27,45,54`). An appointment in `RESCHEDULE_PENDING` is skipped even when its only suggestion points at a provider who has just gone OFFLINE, or at a slot that has just been CANCELLED.

- Accepting such a suggestion then returns a 409 (`BookingService.java:94-99`), and nothing re-proposes.
- Repro: Suggest on John, which picks Chen's SLT-008. Then take Chen OFFLINE.

Decide:

- whether those pending suggestions are rejected inside the same status-change transaction, followed by `SuggestionsSuperseded` so the existing re-proposal path takes over;
- which trigger the re-proposal carries (the original one, or the new disruption's).

Recommendation: reject them in the same transaction and publish `SuggestionsSuperseded`. The re-proposal carries the new disruption's trigger, so the prompt explains the right situation.

## Answer

Resolved 2026-10-20 with the user. The trigger recommendation above was revised during grilling.

**Decisions**

1. **Invalidate in the same transaction.** `changeProviderStatus(OFFLINE)` rejects every PENDING suggestion pointing at that provider's slots, and `changeSlotStatus(CANCELLED)` rejects every one pointing at that slot. The activity feed says "Suggestion for <patient> superseded (<disruption>) - re-proposing".
2. **Trigger rule.** If the disruption also hit the patient's **own** booking, the re-proposal uses the disruption's trigger. If it hit only the suggested slot, the suggestion's original trigger is kept. The reason: the `PROVIDER_OFFLINE` prompt claims the patient's own provider is unavailable.
3. **RESCHEDULE_PENDING patients count.** Bookings on the affected provider or slot that have no pending suggestion left (e.g. after a reject) join the batch. Ones still waiting on a valid suggestion elsewhere are left alone.
4. **One triaged batch per disruption.** The directly hit bookings and the invalidated patients are triaged together, urgent first. `SuggestionsSuperseded` is now only used by accept.

**Code changes**

- `AgentEvents`: `ProviderWentOffline` and `SlotWasCancelled` now carry `invalidated: List<Superseded>`.
- `BookingService.invalidatePending(...)`: new.
- `RescheduleAgent`: `BOOKED` + `needingPlan()` replace `ACTIVE`. `batch()` merges direct and invalidated patients (direct wins) and triages them. `rescheduleAll` now takes per-item triggers.

**Tests**

`DisruptionInvalidationTest`, with three scenarios:
- Suggested provider goes offline: John is re-planned onto the Held Chen slot and keeps `INITIAL`. Red, then green.
- Suggested slot is cancelled: re-planned, keeps `INITIAL`. This one went green straight away, because the first slice's shared code already covered it. It is kept as a regression lock.
- Emily rejected, then Chen goes offline: Emily gets `PROVIDER_OFFLINE`. Red, then green.

`AgentTriageTest` was updated for the new event signature. The full `mvn -q test` passes.

**Docs:** `ADR.md` (ADR-4 listener paragraph) and `SlotMind-Implementation.md` §6 (events, listeners, the new Invalidation bullet).

**Map effects:** ticket 04 is narrowed to the accept-supersede path plus the manual/SSE race. Ticket 08's "active booking" bullet is updated.
