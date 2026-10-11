# ADR-004 — Typed events, outbox uniqueness and truthful UI

Status: PROPOSED; corrects JANE/LUCIEN reviews on PR #9.

## Contract and persistence
`contracts/v1/domain-events.schema.json` refines the common immutable envelope with typed payload requirements for order, payment and stage events. This is only shape validation; the application must enforce stage preconditions, authorization, custody and all financial invariants at mutation time. Order lines freeze the original integer-centavos price and catalog provenance.

Outbox database constraints: UNIQUE(event_id), UNIQUE(origin_device_id, origin_sequence). Payment ID and order ID are also unique. Transactional sequence allocation and local order+event insertion happen together. Reuse event_id for retries; a future hub must record authoritative, idempotent outcomes.

## Truthful local write states
`SAVED_LOCALLY`: only the user's on-device database commit succeeded. Durable outbox event then remains `PENDING_SYNC` until actual hub processing exists. `ACKNOWLEDGED` requires a durable server response with matching event_id, authoritative revision and authenticated hub identity. P1 contains no hub and therefore **never displays ACKNOWLEDGED** for new P1 events.

`RETRYABLE_ERROR`: transport or temporary remote failure, local mutation stays saved. `CONFLICT`: hub has explicitly rejected/quarantined a specific event; do not auto-repeat without review. No live connection detection proves global synchronization.

## Catalog provenance
Persist `revision`, `last_verified_at` and `source` (`HUB_ACKNOWLEDGED`, `OFFLINE_CACHE`, `UNVERIFIED_LEGACY`). Pre-P2 sample rates are `UNVERIFIED_LEGACY`, never owner-published. A disconnected device displays last verified catalog version/time; a missing value must be shown as unknown.

## Recoverable user-visible errors
- DISK_FULL: preserve form input and pending writes; prompt to free space and retry after verification.
- KEY_UNAVAILABLE: never initialize an empty replacement over encrypted records; require owner-assisted verified recovery.
- MIGRATION_FAILED: old v1 database remains untouched, present retry/recovery.
- RESTORE_FAILED: active DB unchanged; prompt owner to verify passphrase/integrity.

P1 has no real user authentication. Never present fabricated role authorizations. P2 binds actor_id, grants and offline leases. These contracts do not grant production acceptance.
