# What happens to pending suggestions when their target provider goes offline or slot is cancelled?

Type: grilling
Status: open
Blocked by: none

## Question

Impl §6 says "No silent drops". Today both agent listeners only handle `CONFIRMED` and `RESCHEDULED` appointments (`RescheduleAgent.java:27,45,54`). An appointment in `RESCHEDULE_PENDING` is skipped even when its only suggestion points at a provider who has just gone OFFLINE, or at a slot that has just been CANCELLED.

- Accepting such a suggestion then returns a 409 (`BookingService.java:94-99`), and nothing re-proposes.
- Repro: Suggest on John, which picks Chen's SLT-008. Then take Chen OFFLINE.

Decide:

- whether those pending suggestions are rejected inside the same status-change transaction, followed by `SuggestionsSuperseded` so the existing re-proposal path takes over;
- which trigger the re-proposal carries (the original one, or the new disruption's).

Recommendation: reject them in the same transaction and publish `SuggestionsSuperseded`. The re-proposal carries the new disruption's trigger, so the prompt explains the right situation.
