# Move the two stray lifecycle rules onto the entities

Type: task
Status: resolved
Blocked by: none

## Question

Nothing here needs deciding. It brings the code into line with ADR-1: "rules on the entities, orchestration in services".

- `BookingService.java:66` refuses BOOKED→OPEN in the service. Move this into `Slot.changeStatus()`.
- `AppointmentController.java:68` enforces "only COMPLETED can be set directly" in a controller. Move this onto `Appointment`.

Behaviour, HTTP status codes and messages stay the same. Add or adjust a test for each rule. Done when `mvn -q test` passes. (AFK)

## Answer

Implemented in `e0c1029` (merged in `140ade2`).

- **Slot rule.** `Slot.changeStatus()` refuses BOOKED→OPEN.
- **Appointment rule.** `Appointment.changeStatus()` accepts only COMPLETED, then runs the lifecycle check. It replaces `complete()`. `BookingService.complete` became `changeAppointmentStatus`, and the controller passes the status straight through.
- **Behaviour.** Messages and the 409 are unchanged. One side effect: PATCH on an unknown appointment with a non-COMPLETED status now returns 404 instead of 409.
- **Tests.** `domain/SlotTest` and `domain/AppointmentTest`. ADR-1 lists both rules.
