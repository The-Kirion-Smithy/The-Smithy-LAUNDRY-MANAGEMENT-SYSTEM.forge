# JANE P1 Storage Alpha — Explicitly Incomplete

Issue #4. Based on NOX P1 contract candidate `f1c2d508d4416286e4b8f52546813358d8083be6`.

**Implemented in this alpha branch only:**
- Room-annotated entities for customers, services, orders, lines, payments, stages, outbox and local metadata.
- Transactional DAO commands that create order/customer/payment events with the associated database mutation; uniqueness on event_id and origin_device_id+origin_sequence.
- SQLCipher (modern `net.zetetic:sqlcipher-android`) integration and Keystore AES-GCM wrapping of randomly generated database passphrase.
- Explicit fail-closed gate if old plaintext `kirion_laundry_prototype.db` is present, and if encrypted database exists without a usable key.

**NOT IMPLEMENTED / DO NOT MERGE:**
- Verified plaintext v1 → encrypted Room migration or column mapping.
- Import of legacy orders, payments or ticket sequences; therefore the new store is NOT wired into existing UI.
- Portable Argon2id/AES-GCM backup and tested restore on a second Android handset.
- Hub event ACKs, server custody enforcement, actual Android-at-rest inspection, device tests.
- P1 integration and production user-data acceptance.

The existing UI still works through the old `LaundryDatabase` on NOX's prototype lane. That legacy plaintext database remains insecure for live customer records. Treat this PR as a **code foundation, not feature completion**.

**Verification required:** Gradle compiler/Room processor must pass; then implement, test and review the migration and portable restore before any bridge into the UI. Never silently empty or reset a legacy database.
