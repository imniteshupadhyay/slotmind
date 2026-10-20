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

## Not yet specified

- **ADR wording.** Once the concurrency story (03) and the triage scope (04) are settled, ADR-3 and ADR-4 will need to match. The specialty part is done.

## Out of scope

- **Waitlist panel** (Spec a1). It's a T-5 "ceiling" item, and the brief puts waitlist work in sprint 2/3. It's a new feature, not a fix.
- **Style-only smells** with no bug behind them:
  - `(appointmentId, trigger)` data clump
  - repeated `TriggerReason` switches (ADR-4 endorses two prompts)
  - CORS living in `AsyncConfig`
  - the duplicated "No open slot" message
  - the near-identical `onSuggest` lambdas
- **Stack changes**: swapping out SQLite, or raising the connection pool size.
