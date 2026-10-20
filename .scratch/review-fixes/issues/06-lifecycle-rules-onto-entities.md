# Move the two stray lifecycle rules onto the entities

Type: task
Status: claimed
Blocked by: none

## Question

Nothing here needs deciding. It brings the code into line with ADR-1: "rules on the entities, orchestration in services".

- `BookingService.java:66` refuses BOOKED→OPEN in the service. Move this into `Slot.changeStatus()`.
- `AppointmentController.java:68` enforces "only COMPLETED can be set directly" in a controller. Move this onto `Appointment`.

Behaviour, HTTP status codes and messages stay the same. Add or adjust a test for each rule. Done when `mvn -q test` passes. (AFK)
