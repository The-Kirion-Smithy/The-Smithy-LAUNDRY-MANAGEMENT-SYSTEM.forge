# ADR-001 — Single-shop offline-first topology (P1 candidate)

Status: **PROPOSED — independent review required**. Applies to one shop only.

## Authority
- Each Android device writes first to its own *encrypted* SQLite/Room database. Orders, payments, stage movements and an immutable outbox entry must commit in the **same local transaction**.
- The future on-prem hub serves authenticated LAN clients, with PostgreSQL authoritative for **reconciled shop-wide** records, staff permissions and *published* catalog revisions.
- A local write means `SAVED_LOCALLY`, not globally accepted. `ACKNOWLEDGED` requires the hub to persist and respond with a server revision/cursor. The hub must return explicit rejections/conflicts, never quietly discard payments.
- Phase P1 ships **no hub synchronization**. Outbox rows remain `PENDING` until a real implementation acknowledges them. The device has no reason to request INTERNET for this phase.
- Connectivity selection later: healthy authenticated LAN endpoint first; enrolled Cloudflare private route only as an alternate **authenticated** path; otherwise local-only. Cloudflare never writes PostgreSQL directly, and no public PostgreSQL port exists.
- No absolute ordering exists between disconnected devices. Wall-clock event timestamps are informational only. Per-device sequence orders one device's events; hub revision determines accepted global order.

## Stable identities
- `shop_id` is a UUID created by authorized bootstrap and assigned at enrollment. In P1 before enrollment it is **null** (not a hardcoded demo tenant).
- A device generates a strong random `device_id` before its first production mutation, kept through normal app upgrades.
- Every outbox event uses a random UUID `event_id`, preserved across retries and backup export.
- All offline-created aggregate IDs are UUIDs. A printable ticket may contain date and *full collision-resistant device identifier or sufficiently strong random component*. Never restart a bare per-device sequence on restore.
- Reinstall/restore onto new hardware creates a *new current device identity*. Restored events retain their historical origin and event IDs. The old device must be retired or reconciled before dual-active financial writing resumes.

## Command ownership and money
- P1 maintains local tickets and payment ledgers with historical unit-price snapshots (integer centavos, decimal quantity with specified rounding).
- P4 establishes order custody: at most one enrolled device may collect/release the same order while disconnected. A hub-issued revocable custody lease is necessary before any offline reassignment.
- A conflicting settlement is **CONFLICT**, not last-write-wins. All money entries are append-only; corrections require audited compensating events.
- No offline owner override for unpaid pickup; no reconciliation that invents money or erases previously accepted cash.
- Existing v1 prototype does not enforce P4 custody. Until that gate, test with only one writable device for each live order and use fictional customer data.

## UX states
`SAVED_LOCALLY` → `PENDING` → `ACKNOWLEDGED`; terminal/permitted review state `CONFLICT`; transient `RETRYABLE_ERROR`. `ACKNOWLEDGED` is never inferred from the presence of network connectivity.

## Out of scope
Cross-device financial sync, remote Cloudflare deployment, P2 staff access and production field use are not accepted by this ADR.
