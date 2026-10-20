# Which proposal paths must respect urgent-first ordering?

Type: grilling
Status: open
Blocked by: 02

## Question

Impl §6: "the batch is ordered URGENT → FOLLOW_UP → ROUTINE". Review finding (Spec c4):

- Re-proposals after a supersede run one at a time with no triage (`RescheduleAgent.java:62-65`).
- Manual and SSE proposals (`AppointmentController.java:90`, on a separate `streamExecutor`) run alongside the agent thread, so they can take held slots out of priority order.

Ticket 02 sends more traffic through the supersede path, which is why this ticket waits for it.

Decide:

- whether re-proposal batches get sorted by `AppointmentType.priority()`;
- whether manual/SSE proposals should be serialised with the agent, or allowed to race. Racing is safe because holds are soft and accept is atomic; the cost is that it gets documented in ADR-4.

Recommendation: sort re-proposal batches, and let human-initiated proposals race (document it in ADR-4).
