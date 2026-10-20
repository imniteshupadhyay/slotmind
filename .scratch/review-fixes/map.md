# Map: SlotMind review fixes

Label: wayfinder:map

## Destination

Every in-scope finding from the 2026-10-20 two-axis code review (Standards + Spec) is fixed in the code, the backend tests are green, and every step in `docs/SlotMind-Demo.md` is still true.

## Notes

- **Execution is allowed in this map** (overrides wayfinder's "plan, don't do"). When a grilling ticket is resolved, the same session implements the decision and adds a regression test (use the `tdd` skill). Task tickets are worked AFK.
- **Source of truth:** the brief (`slotmind-brief 1.html`) and `docs/SlotMind-Implementation.md` take precedence. If the demo script or the ADR disagrees with them, edit the doc to match. Don't bend the code.
- **Fixed constraints:** keep SQLite and `hikari.maximum-pool-size=1`, and change no stack. Every demo step must still work.
- **Skills:** run `grilling` and `domain-modeling` for grilling tickets, and `tdd` while implementing. If a fix changes behaviour that `ADR.md` describes, update `ADR.md`.
- Run tests with `mvn -q test` in `backend/`. Commit only when the user asks.
- The review findings are quoted in each ticket, with file:line refs as of commit 42c29f6.

## Decisions so far

<!-- one line per resolved ticket: [title](issues/NN-slug.md): gist -->

- [What is the specialty rule when holds or scarcity leave no same-specialty slot?](issues/01-specialty-rule-under-holds.md): never cross specialty. Candidates are filtered to the original specialty up front, Holds relax within it, and staff are told when nothing matches. Implemented and tested.
- [What happens to pending suggestions when their target provider goes offline or slot is cancelled?](issues/02-invalidate-pending-suggestions.md): rejected in the same transaction, and the patients join the disruption's urgent-first batch. They keep their original trigger unless their own booking was hit. RESCHEDULE_PENDING patients with nothing pending are included. Implemented and tested.
- [How do we make the no-double-booking guarantee honest under a single SQLite connection?](issues/03-concurrency-proof-single-connection.md): the single writer serialises accepts and `Slot.book()` rejects the loser. `@Version` (now on Provider too, force-incremented on accept) is the tested backstop. ADR-3 claims only what the tests prove.
- [Which proposal paths must respect urgent-first ordering?](issues/04-urgency-ordering-scope.md): every agent batch is triaged, including re-proposals after an accept. Manual/SSE proposals race, which is safe and documented in ADR-4.
- [Should manual Suggest reuse a pending suggestion from any trigger?](issues/05-manual-suggest-dedupe.md): yes. The agent reuses a suggestion with the same trigger and supersedes one with a different trigger, giving at most one pending suggestion per appointment.
- [Move the two stray lifecycle rules onto the entities](issues/06-lifecycle-rules-onto-entities.md): done in `Slot.changeStatus` and `Appointment.changeStatus`.
- [Return read views for Provider and Slot; fix the stale LlmProperties Javadoc](issues/07-read-views-and-stale-javadoc.md): done. The slot write responses deliberately became `SlotView`.
- [Give each duplicated rule one home](issues/08-dedupe-shared-rules.md): `StrategyLabel` + `fallback` field, `Provider.sameSpecialtyAs`, repository `require()`, `AppointmentStatus.occupiesSlot()`.

## Not yet specified

_Nothing left: the ADR-3/ADR-4 wording was settled with tickets 03 and 04._

## Out of scope

- **Waitlist panel** (Spec a1). It's a T-5 "ceiling" item, and the brief puts waitlist work in sprint 2/3. It's a new feature, not a fix.
- **Style-only smells** with no bug behind them:
  - `(appointmentId, trigger)` data clump
  - repeated `TriggerReason` switches (ADR-4 endorses two prompts)
  - CORS living in `AsyncConfig`
  - the duplicated "No open slot" message
  - the near-identical `onSuggest` lambdas
- **Stack changes**: swapping out SQLite, or raising the connection pool size.
