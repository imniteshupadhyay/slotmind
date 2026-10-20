# How do we make the no-double-booking guarantee honest under a single SQLite connection?

Type: grilling
Status: claimed
Blocked by: none

## Question

ADR-3 says the second accept "fails its UPDATE … WHERE version = ?". Review finding (Spec c3):

- With `hikari.maximum-pool-size=1` (`application.properties:11`), two accepts run one after the other. The loser fails in `Slot.book()` (`Slot.java:34`), never on `@Version`.
- `AcceptConcurrencyTest` therefore passes because of that serialisation, not because of optimistic locking.
- `Provider` has no `@Version`, so the "provider went offline" check (`BookingService.java:94`) only holds while the pool stays at size 1.

The pool size is fixed (map Notes). Decide:

1. whether to add `@Version` to `Provider`;
2. how a test proves the version check itself, e.g. a stale-entity test: load in one transaction, change and commit in another, then commit the first;
3. what ADR-3 should say: "SQLite's single writer serialises accepts, the state check in `Slot.book()` rejects the loser, and `@Version` is the backstop if the pool ever grows".

Recommendation: all three.

## Decision

2026-10-20: the user accepted the recommendation above as written (via /implement-spec). Implement it.
