# ADR-005 — Explicit v1 migration and portable backup envelope

Status: PROPOSED — encryption, migration and restore still require JANE device evidence.

## Existing legacy source
Legacy database `kirion_laundry_prototype.db` is SQLiteOpenHelper schema version 1 with tables customers, services, orders, order_lines, payments, stage_events and meta(ticket_sequence). It is plaintext and the Android manifest disables OS app backup. Do NOT reopen old plaintext in a cipher provider and assume automatic encryption.

## Non-destructive migration
1. Close old DB handles and lock writes. Open legacy read-only; run PRAGMA integrity_check and foreign_key_check; capture counts, ticket sequence, payment sums, total/paid per order, stage event counts and immutable IDs. On inconsistency: stop without overwriting source.
2. Create a fresh *separate* encrypted Room target DB in app-private staging, using a randomly generated key wrapped by Android Keystore. Verify reopen before migration.
3. In a target transaction copy explicit fields preserving customer/order/payment/event UUIDs, timestamps, price snapshots and ticket_sequence. Define explicit column mapping in JANE's implementation PR. New local mutations create outbox events, but **legacy historical money must not automatically be declared synchronized**.
4. Validate target PRAGMA integrity_check/foreign_key_check, row counts, payment sums, totals, ticket sequence, stage history, unique IDs and schema version. On any mismatch discard staging only; keep original.
5. Promote validated target atomically using an app migration state marker plus safe-close/fsync/rename. Verify after process death. Keep owner-assisted rollback available. Treat residual plaintext files as sensitive; no claim of secure deletion on flash storage.
6. Assign a new local device UUID and ensure future tickets are collision-resistant across devices, not just date+sequence. Restored historical events retain origin device/event identities; restored phone becomes a NEW device that requires later enrollment.

## Portable owner-controlled backup format proposal
`KIRION-LMS-BACKUP/1` has a minimal **authenticated** cleartext header: format/schema version, KDF Argon2id, >=16-byte random salt, calibrated Argon2id memory/time/parallelism (start validation at 64 MiB / 3 passes / 1 lane), cipher AES-256-GCM, random 12-byte nonce, encrypted payload length. The entire header is AEAD associated data.

Encrypted payload holds a consistent SQLite snapshot, format version, origin device ID, export timestamp, pending outbox state and integrity metadata. Password derivation uses **owner-entered recovery passphrase**, never only a source-device Keystore key. Never commit or log keys, export plaintext PII headers, or create insecure temporary archive copies. Use a reviewed encryption implementation; this ADR is NOT a coded backup feature.

Restoration: decrypt/authenticate to isolated staging, validate schema and financial invariants, ensure enough space, then atomically replace database only after successful validation and authorized confirmation. Wrong passphrase, tampered file, unsupported future schema and interrupted restore must leave active data intact.

## Stop gate
P1 cannot be accepted until a real v1→encrypted migration, at-rest ciphertext check and encrypted backup restoration on a *different* Android handset have passed. A build alone is insufficient.
