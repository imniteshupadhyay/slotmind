# Give each duplicated rule one home

Type: task
Status: resolved
Blocked by: 01

## Question

Nothing here needs deciding: these are the review's smells that hide real drift risk. It waits for 01 because 01 changes the specialty check.

- **The "active booking" rule.** It still lives in `AppointmentStatus.isActiveBooking()` (which nothing calls) and `components.jsx:161`. After ticket 02, the agent no longer uses it: it uses `BOOKED` (which also includes RESCHEDULE_PENDING) plus `needingPlan()`. Decide whether `isActiveBooking()` is dead code to delete, or the rule the UI should get from the view.
- **The candidate limit.** `ReschedulingService.java:33` uses 25 and `PromptFactory.java:22` uses 20. Keep the two limits if the difference is intended (Impl §4 says "capped at 25 (the AI prompt shows the first 20)"), but name both and define them next to each other.
- **The appointment lookup.** `appointments.findById(..).orElseThrow(NotFoundException)` is repeated at `BookingService.java:90,132` and `ReschedulingService.java:91,129`. Extract one helper.
- **The same-specialty check.** After ticket 01, this check lives in three places:
  - the `ReschedulingService.buildContext` filter;
  - the `SchedulingEngine.firstValid` guard;
  - `RuleBasedStrategy`'s `sameSpecialty` filter, which is now redundant because the candidates are already filtered.

  `PromptFactory` no longer uses it. Give the check one home, e.g. `Provider.sameSpecialtyAs(...)` or `SchedulingContext.sameSpecialty(slot)`. Drop the redundant rule-strategy filter only if the engine guard still covers it.
- **The fallback label.** `SchedulingEngine.java:81` builds `"rule (fallback: …)"` as text, and `components.jsx:63` parses it back with `startsWith`. Expose a `fallback` boolean (plus a reason) on the suggestion view, and have the UI read that instead.

No behaviour change. Done when `mvn -q test` passes and the demo's fallback badge still shows. (AFK)

## Answer

Implemented in `d93ad34` (merged in `8692116`), with naming and placement tightened in the review fixes (`4258f31`).

- **Fallback label.** The suggestion view exposes `fallback` and `fallbackReason`. `engine/StrategyLabel` is the only code that writes or parses the label text. The UI badge reads `s.fallback`.
- **Same specialty.** `Provider.sameSpecialtyAs` is the one check, used by the context filter, the engine guard and the rule strategy.
- **Candidate limits.** `SchedulingContext.MAX_CANDIDATES = 25` and `MAX_PROMPT_CANDIDATES = 20`, defined side by side.
- **Lookups.** `require()` on the appointment, provider, slot and suggestion repositories replaces the repeated `findById(...).orElseThrow`.
- **Active booking.** `isActiveBooking()` was deleted. Its replacement on the domain is `AppointmentStatus.OCCUPYING_SLOT` / `occupiesSlot()`, used by the agent, `Views` and `buildContext`. The `components.jsx` button check stays a UI concern.
