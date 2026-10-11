# P1 NOX contract packet

Issue #3 · Proposed architecture under independent review.

- ADR-001: data ownership, offline event semantics, LAN/hub/Cloudflare boundaries.
- ADR-002: encrypted Room/SQLite migration and portable disaster recovery.
- ADR-003: interface between P1 data and P2 identity/pricing controls.
- P1-acceptance-and-threat-matrix: non-negotiable gates and test fixtures.
- `contracts/v1/event-envelope.schema.json`: schema for local immutable events.
- `contracts/v1/sync-api.openapi.yaml`: **reserved future hub API**, not implemented in P1.
- `contracts/v1/fixtures/two-offline-devices.json`: non-mergable double-payment scenario.

Writer boundary: only `docs/architecture/` and `contracts/v1/`. No changes to production source, CI workflows, existing PR heads or `main`. Nothing here constitutes device verification, cryptographic implementation or Cloudflare deployment.
