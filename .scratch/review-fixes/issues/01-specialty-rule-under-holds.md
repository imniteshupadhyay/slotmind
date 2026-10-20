# What is the specialty rule when holds or scarcity leave no same-specialty slot?

Type: grilling
Status: resolved
Blocked by: none

## Question

When should a reschedule ever cross specialty?

- Brief T-2: "if none, nearest open slot with matching specialty".
- Impl §4: "If that leaves nothing, the reservation is relaxed".

Review findings:

- **Spec c1.** `ReschedulingService.java:119-120` relaxes holds only when *no* unheld slot is left at all. The specialty guard (`SchedulingEngine.java:96,112-115`) then checks only that shortened list. So if other patients' pending suggestions hold every same-specialty slot, the AI is allowed to pick another specialty. The demo's "Reject Emily → Suggest another" step triggers this.
- **Spec c2.** The demo says the engine "would never offer a pediatrician", but the prompt allows a cross-specialty pick at confidence below 0.5 (`PromptFactory.java:32-33`).

Sub-decisions:

1. **Relaxing holds.** Should holds be relaxed when no *unheld same-specialty* slot remains? The patient would then share a held slot, the first accept wins, and the loser is re-proposed.
2. **No same-specialty slot at all.** Options: produce no suggestion and log "needs staff" to the activity feed (strict), or allow a cross-specialty slot at low confidence (current behaviour).

Recommendation: relax holds within the same specialty first. Be strict when none exists at all (no suggestion, plus an activity-feed entry), so that the demo's claim becomes true.

## Answer

Resolved 2026-10-20 with the user.

**Decisions**

1. **Never cross specialty.** If no slot of the original specialty exists, there is no suggestion. The activity feed shows "No GENERAL slot available for <patient> (<id>), needs a call from staff". Manual Suggest returns a 409 and SSE sends `error`, both saying "No open slot with the right specialty is available for this appointment". The controller has no access to the specialty, so these two messages don't name it.
2. **Filter up front.** `ReschedulingService.buildContext` only passes same-specialty slots to the strategies. The engine's specialty guard now always applies, with no `sameSpecialtyAvailable` escape, and stays as defence in depth.
3. **Relax Holds within the specialty.** If no unheld same-specialty slot is left, held ones become candidates. The first accept wins, and the loser is re-proposed through the existing supersede path.
4. **Term:** **Hold** (added to `GLOSSARY.md`). It replaces "soft reservation" and "soft-reserve" in the docs and comments.

**Code changes**

- `SchedulingEngine`: the guard is unconditional, and `sameSpecialtyAvailable` is removed.
- `ReschedulingService`: adds the specialty filter, the Hold-relaxation comment and the feed message.
- `PromptFactory`: removes the "<0.5 cross-specialty" escape, the redundant "prefer SAME specialty" line and the `same specialty:` slot annotation.
- `AppointmentController`: a `NO_SLOT` constant for both paths.

**Tests**

- `SchedulingEngineTest.engineNeverCrossesSpecialtyEvenWhenNoMatchingSlotExists` (engine seam).
- `SpecialtyHoldTest.heldSameSpecialtySlotBeatsAFreeSlotOfAnotherSpecialty` (`propose()` seam).
- Both were red first and are green now. The full `mvn -q test` passes.

**Docs**

- `SlotMind-Implementation.md`: §4 candidate slots, the guard, the prompt rules, the slot-line example and the test table.
- `SlotMind-Demo.md` and `ADR.md`: the Hold wording.
- The demo's "would never offer a pediatrician" line is now true, so it was left as it was.
