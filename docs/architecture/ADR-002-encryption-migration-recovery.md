# ADR-002 — Storage, migration and portable recovery (P1 candidate)

Status: **PROPOSED — JANE security review required**.

## Database
- Room is the schema/transaction boundary, using an actively maintained Android-compatible encrypted SQLite engine. Validate cryptographic provider licensing, Android 8+ support, SQLCipher/Room factory integration and upgrade behavior before freezing the dependency.
- Android Keystore protects local random key material. Hardware-backed storage is best-effort, never assumed on inexpensive handsets. No plaintext DB copy, plaintext backup, static AES key or committed passphrase.
- No destructive migration. Existing prototype `kirion_laundry_prototype.db` v1 must migrate after a verified **transactionally safe** upgrade: customers, services, orders, lines, payments, stage events and `ticket_sequence`. A failed migration leaves the original source intact and displays a recovery path. Empty new installs use the new schema directly.
- Check consistency: payment sum equals `orders.paid_cents`, each line equals persisted snapshot, no duplicate ticket/event IDs, audit history and counts preserved.
- Store monetary values as signed 64-bit integer centavos; validate bounds on input to avoid overflow. Quantities use defined precision/rounding, never floating currency arithmetic.
- Exported `*.kirionbackup`: versioned manifest, integrity/authenticated-encryption tag, encrypted database snapshot plus metadata. Derive export key from a **user-controlled high-entropy recovery passphrase** with a memory-hard KDF where supported; record KDF params/salt (not passphrase). A Keystore-only encrypted file is not portable.
- Restore uses temporary isolated staging, authenticated decryption and schema/invariant checks **before** atomic swap. Wrong passphrase/truncated archive/unsupported version must leave current DB untouched. Restore requires owner authorization in P2.
- Do not silently regenerate an invalidated encryption key and overwrite unopenable records. Surface explicit key-loss recovery.
- Daily on-device copies alone are not disaster recovery. Add owner-controlled off-device backup check and verified restore onto **another Android device** before release.

## Test matrix
1. Instrumented v1 → Room/encrypted schema migration on Android 8+, 11+, 15; before/after record digests for money and IDs.
2. Kill process between order/payment write and outbox write; either all rows commit or none.
3. Restart with pending events; `event_id` and original device sequence unchanged.
4. Export and import with a passphrase onto a distinct test phone; verify data and uniqueness.
5. Wrong passphrase, corruption, insufficient storage and disk-full rollback do not replace existing DB.
6. Old-device/new-device simultaneous use after restore is blocked at future device enrollment/custody gate.

## Acceptance and stop
No authentic ciphertext/restore evidence → no P1 acceptance. No tested v1 migration → no code merge. The production signing/backup policy and user PINs are P2/P6, not assumed by the P1 storage layer.
