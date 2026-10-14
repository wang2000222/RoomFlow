# RoomFlow · Multi-Store Self-Service Mahjong Management

[简体中文](README.md) | **English**

**Java backend + customer/staff WeChat mini-program + PC operations dashboard for one merchant operating multiple venues.**

Version 0.2 is a commercial-scenario simulation build. Payments, refunds, locks and power control are simulated. It is not a production-ready commercial release.

![PC dashboard](docs/images/pc-dashboard.png)

## Implemented

- Store and room configuration, availability, maintenance, operating hours and pricing packages.
- Transactional booking conflict checks including early entry and cleaning buffers; pricing/rule snapshots.
- Extension holds that block conflicting reservations, expire after five minutes and take effect after simulated payment.
- Cancellation deadlines, separate refund records, failed refunds, retry and simulated reconciliation.
- Device commands, simulated acknowledgements, failure injection, retries and alerts; a failed opening does not mark a booking as in use.
- Scheduled pending-order expiry, end-of-use handling, no-shows and in-app reminders.
- Customer ownership, store-scoped managers, staff accounts, PBKDF2 password hashing and session revocation after account changes.
- Server-side WeChat code2Session login implementation and owner-controlled staff identity binding. Unconfigured login is rejected.
- Order remarks, financial ledgers, health checks, Flyway migrations, H2 backups and isolated restore tests.

PC and the native mini-program both include operational interfaces. The mini-program source has syntax checks, but Developer Tools and real-device acceptance are pending.

## Screenshots

[Package booking and extension](docs/images/pc-orders.png) · [Configuration](docs/images/pc-configuration.png) · [Finance and device acknowledgements](docs/images/pc-finance.png)

## Run

Requirements: JDK 8 and Maven. H2 is the default local database.

```powershell
cd backend
mvn clean package
cd ..
./start.ps1
```

Open http://127.0.0.1:8091 and choose a demo role. Do not expose passwordless demo administrator login publicly.

Environment variables are listed in [.env.example](.env.example); the file is not auto-loaded. OWNER_USERNAME / OWNER_PASSWORD initialize the first owner account. WECHAT_APPID / WECHAT_SECRET enable server-side WeChat login. Credentials remain on the server.

Import `miniprogram` into WeChat Developer Tools, configure its AppID and API address. Trusted LAN testing requires an appropriate SERVER_ADDRESS; the backend binds to localhost by default.

## Verification

Run `mvn test` in `backend`. Tests cover booking conflicts, buffers, package pricing, extension HTTP flows, refund/device failures, retries, scheduled actions, authorization, revocation, mocked WeChat responses and isolated backup restoration. See [validation record](docs/VALIDATION.md) for results.

## Deployment & Limits

Docker, Compose, HTTPS proxy, MySQL backup and health-check resources are provided. MySQL, container deployment, public hosting and WeChat review have not been accepted in a real environment.

Sessions and scheduling currently support one instance. Session state is in memory, so restarting requires login again. A clustered deployment needs shared sessions and distributed task ownership.

Real WeChat payment/refund callbacks and reconciliation, vendor device protocols, subscription/SMS notifications, verified phone authorization and real-device testing remain outstanding. Disabling demo mode rejects simulated finance/device operations instead of treating them as live integrations.

Legacy order amounts are preserved during migration, but missing historical payment receipts are not fabricated as ledger entries. Coupons, stored-value wallets and loyalty points are outside this iteration.

[Operations guide (Chinese)](docs/OPERATIONS.md) · [Scope and integrations (Chinese)](docs/PRODUCT.md)
