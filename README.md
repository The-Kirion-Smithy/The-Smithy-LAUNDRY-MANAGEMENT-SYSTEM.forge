# Kirion Laundry Management System — Forge

**RP-001: Android-first offline rapid prototype.** Source-of-truth product requirements: *PRD: Offline Laundry Management System (Android-first)* (2026-10-11).

## Goal
Prove the on-device loop: customer → service → price-locked order → deposit/partial payment → laundry stage → paid pickup → daily report.

## Build
Android Studio (JDK 17, Android SDK 35): open this directory and run \`app\` on Android 8+.
CLI with Android SDK and Gradle 8.9 installed: \`gradle :app:testDebugUnitTest :app:assembleDebug\`.
A debug APK is also uploaded as a GitHub Actions artifact after a successful build. Do not distribute a debug APK to shops.

## What this prototype actually implements
- Native Android UI with Jetpack Compose; no INTERNET permission
- Local SQLite database; persistent customer, service, order, line-item, payment and stage records
- Transactional order creation, payment recording, forward stage transitions
- Unique device-local ticket sequence, with a date prefix (not reused)
- Captured unit price/line total, so later price edits do not alter older orders
- Full/partial/unpaid intake; manual cash, GCash, bank, or card records
- Pickup blocked when an order still has a balance
- Local daily counts, received cash, payment totals and unpaid balance
- Shared-text ticket handoff as a **temporary prototype**, not the PRD's PDF/image receipt

## Important: not ready for real shop data or deployment
The prototype is **not** the PRD's complete P0 MVP. It does **not yet** implement PIN-authenticated roles, SQLCipher/Keystore encryption, encrypted backup/restore, proper PDF/image receipt, owner-controlled edits/voids, full workflow configuration, tax, loyalty, day-close or 30-day pilot. Treat device contents as unprotected test data. Do not record real customer PII until security and backup are complete. No production signing or release approval is implied.

## Development rule
Keep \`main\` unchanged until the prototype branch builds, runs on an Android device and is explicitly approved. Improvements are vertical slices; avoid frontend-polish work before behavior exists.
