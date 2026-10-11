# ADR-003 — P1/P2 security boundary and pricing

Status: **PROPOSED**. Defers policy implementation to P2 while reserving schema contracts.

- UI navigation is never an authorization boundary. Owner, Manager, Cashier, Attendant and Auditor are configurable records with permission grants; backend must verify every privileged command.
- No master PIN, universal password, hardcoded `shop_id`, customer catalog prices, domain, TLS or Tunnel secrets in the APK. Seeded **sample** service rates remain visibly non-production fixtures until an owner publishes shop-specific pricing.
- Local role lease must have signed/scoped bounds, device binding, issue/expiry and policy revision, plus a documented offline revocation lag. Admin publication of price changes, staff grants, voids/refunds and backup restoration require fresh hub approval.
- Price schedule uses `catalog_revision`, `service_id`, pricing unit, integer centavos, effective time, publisher and reason. A published revision is hub-authoritative, immutable and audited.
- Order line freezes name/unit/price/quantity/tax/discount according to the effective catalog snapshot used for its local acceptance. A disconnected device must present catalog revision and its last verified time.
- Offline creation may use an acknowledged cached catalog within an explicit validity policy; unacknowledged offline admin edits are drafts, not globally published prices.
- P2 starts only after NOX signs off P1 integration and Android runtime/migration/restore tests. Neither Cloudflare nor multi-device financial sync is in P2 acceptance.
