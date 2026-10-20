# Should manual Suggest reuse a pending suggestion from any trigger?

Type: grilling
Status: resolved
Blocked by: none

## Question

Impl §8: `POST /appointments/{id}/suggest` "returns existing pending one if present". Review finding (Spec c5):

- Dedupe is per trigger (`ReschedulingService.java:84-87`).
- So clicking Suggest on an appointment that already has a `PROVIDER_OFFLINE` suggestion creates a second PENDING one with trigger `INITIAL`.
- The UI shows only the newest (`Views.java:84`).

Decide whether manual Suggest returns *any* existing PENDING suggestion for the appointment, whatever its trigger. The agent's own dedupe would stay per trigger.

Recommendation: yes.

## Decision

2026-10-20: the user accepted the recommendation above as written (via /implement-spec). Implement it.

## Answer

Implemented in `b84b2a8` (merged in `aba910d`) and extended in the review fixes (`4258f31`).

- **Manual dedupe.** `ReschedulingService.suggestManually` reuses any PENDING suggestion for the appointment, whatever its trigger, checking both before the engine runs and inside the write tx. Both `/suggest` endpoints use it.
- **Agent dedupe.** The agent reuses a pending suggestion with the same trigger. A new suggestion with a different trigger supersedes (rejects) the older one, so there's **at most one PENDING suggestion per appointment** and the UI's "newest only" view can't hide a live one.
- **Tests.** `ManualSuggestDedupeTest`.
