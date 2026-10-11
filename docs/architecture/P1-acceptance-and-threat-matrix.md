# P1 acceptance and threat matrix — PROD-ARCH-001

**Important:** A passing Gradle build is not runtime, migration, encryption or production acceptance.

| Scenario | Required result | Evidence owner |
|---|---|---|
| Flight mode for full shift | Orders/payments/stage changes persist with no network; outbox remains PENDING | LUCIEN device test |
| Battery/process loss during payment | Atomic payment + ledger + outbox; no double charge on replay | JANE instrumentation |
| Duplicate event upload | Reusing event_id returns prior ACK, never second payment | JANE fixture / future hub |
| Concurrent offline payment on two devices | Requires custody; unresolved conflict, no last-write-wins | NOX P4 hold |
| Customer/service price changed | Prior orders retain original snapshot | JANE unit/instrumentation |
| Prototype schema v1 upgrade | All old data, ticket seq, payments, ledger, daily totals intact | JANE migration |
| Lost / invalidated key | Explicit recovery error, no silent empty reset | JANE Android test |
| Bad encrypted backup | Reject before current DB replacement | JANE restore |
| Phone replacement | Owner-passphrase restore works on new Android device; re-enroll new device | JANE device test |
| Offline staff revoked centrally | Cannot be known immediately; bounded lease, no privileged publication | NOX P2 hold |
| LAN/WAN outage | Only `SAVED_LOCALLY` or `PENDING`, never unearned `ACKNOWLEDGED` | LUCIEN |
| Server unavailable for price change | Draft stored locally; cannot publish new price | P2 JANE/LUCIEN |
| Shop hub compromised or stolen | TLS, secret rotation, backup and least privilege required | P2/P6 |
| Wrong roles invoking admin API | Hub rejects and audits, including forged UI requests | P2 JANE |

## Exact acceptance gates
1. NOX contract PR receives JANE security/data and LUCIEN UI boundary acknowledgments.
2. JANE P1 PR demonstrates Android 8+ tests, migration invariants, encryption and a **portable cross-device restore**; reviewer confirms tested SHA.
3. LUCIEN P1 PR demonstrates flight-mode full workflow, crash/restart persistence and honest UI status on real device.
4. NOX adjudicates P1; freeze integrated accepted SHA. **Only then** P2 contract/implementation gate opens.
5. Existing NOX PR #1 and LUCIEN PR #2 remain draft until integration is explicitly approved; main untouched.
