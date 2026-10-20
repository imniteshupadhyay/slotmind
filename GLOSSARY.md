# SlotMind

Reactive re-scheduling for a clinic front desk: when a provider goes offline or a slot is cancelled, affected appointments get a suggested new slot for staff to accept.

## Language

**Hold**:
The claim a pending suggestion places on its recommended slot. It steers other proposals away from that slot but never blocks booking it, and is relaxed when no other slot of the right specialty is free.
_Avoid_: Soft reservation, soft-reserve, reservation
