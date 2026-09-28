# Independent platform admin

Date: 2026-09-28
Status: awaiting review

## Goal

StockKart operators (platform admins) and shop users become two separate kinds of account with
separate sign-in and separate apps.

- An admin does not belong to a shop. A shop user can never act as an admin.
- Admins sign in to their own app and see only admin tools, never shop menus.
- A shop user's token cannot call admin APIs, and an admin's token cannot call shop APIs.

Today "platform admin" is a `platformRoles` entry on a shop user (`users` collection), and the
admin pages live inside the shop dashboard under `/dashboard/platform-admin/*`. This design
replaces that.

## Decisions

| Topic | Decision |
|---|---|
| Separation | Separate admin frontend app on its own address; shared backend |
| Accounts | Own `admin_users` collection and own sign-in API; tokens not interchangeable |
| Sign-in | Email and password |
| First admins | `PLATFORM_ADMIN_EMAILS` plus `PLATFORM_ADMIN_BOOTSTRAP_PASSWORD` at startup |
| Further admins | Added, disabled and reset by existing admins in the admin app |

## Backend (`inventory-api`)

### Data

`admin_users`

| Field | Notes |
|---|---|
| `id` | |
| `email` | Unique index; stored trimmed and lowercase |
| `name` | 1–60 characters |
| `passwordHash` | BCrypt via the existing `PasswordEncoder` bean |
| `active` | Disabled admins cannot sign in; their sessions are deleted |
| `mustChangePassword` | True for bootstrap and temporary passwords |
| `failedLoginCount`, `lockedUntil` | Lockout state |
| `createdByAdminId` | Null for bootstrap accounts |
| `createdAt`, `updatedAt`, `lastLoginAt` | |

`admin_sessions`

| Field | Notes |
|---|---|
| `id` | |
| `tokenHash` | SHA-256 of the session token; unique index. The raw token is never stored |
| `adminId` | Indexed |
| `createdAt` | |
| `expiresAt` | Created + 12 hours; TTL index removes expired rows |

Session tokens are 32 random bytes from `SecureRandom`, base64url-encoded.

### Sign-in API: `/api/v1/admin/auth`

| Endpoint | Behaviour |
|---|---|
| `POST /login {email, password}` | Returns `{token, expiresAt, admin}`. Wrong email or password gives the same 401 message. Disabled admin: 401. Five consecutive failures lock the account for 15 minutes (401 with a "try again later" message); a success resets the counter |
| `POST /logout` | Deletes the current session |
| `GET /me` | The signed-in admin: `{id, email, name, mustChangePassword}` |
| `POST /change-password {currentPassword, newPassword}` | New password: 10–128 characters and different from the current one. Clears `mustChangePassword`. Deletes the admin's other sessions |

While `mustChangePassword` is true, every admin endpoint except `me`, `change-password` and
`logout` returns 403 with code `PASSWORD_CHANGE_REQUIRED`.

### Admin management API: `/api/v1/admin/admins`

| Endpoint | Behaviour |
|---|---|
| `GET /` | All admins, newest first. Never includes password or lockout fields |
| `POST / {email, name}` | Creates an admin with a generated temporary password (16 characters). Returns the admin and `temporaryPassword` once. Duplicate email: 400 |
| `PATCH /{id}/active {active, reason}` | Cannot disable yourself or the last active admin. Disabling deletes that admin's sessions |
| `POST /{id}/reset-password {reason}` | New temporary password returned once; sets `mustChangePassword`; deletes that admin's sessions; clears lockout |

Every create, enable/disable and reset writes an audit entry (`targetType` `ADMIN_USER`,
`source` `ADMIN_UI`). Audit entries never contain passwords or hashes.

### Request authentication

- New `AdminAuthenticationInterceptor` on `/api/v1/admin/**`, excluding
  `/api/v1/admin/auth/login`. It reads the bearer token, looks up the session by hash, rejects
  missing, expired or unknown sessions and inactive admins with 401, applies the
  password-change rule, and sets request attributes `adminId` and `adminUser`.
- `AuthenticationInterceptor` (shop users) no longer runs on `/api/v1/admin/**`. A shop token
  sent to an admin path fails the admin lookup: 401. An admin token sent to a shop path fails
  the shop lookup: 401.
- `PlatformRoleInterceptor` is removed. The unused `/api/v1/shops/admin/**` pattern goes with it.
- Existing admin controllers (MIS, referrals, wallets, vouchers, campaigns, plans, add-ons) read
  the actor from `adminId` instead of `userId`. Their behaviour is otherwise unchanged.

### Startup

- **Admin bootstrap:** for each email in `PLATFORM_ADMIN_EMAILS` with no `admin_users` row,
  create one with `PLATFORM_ADMIN_BOOTSTRAP_PASSWORD` and `mustChangePassword=true`. Existing
  admins are never changed. If emails are set but the password is empty or shorter than 10
  characters, create nothing and log a warning. Audited with reason `platform.admin-emails`.
- **Cleanup:** `$unset platformRoles` on every `users` document. Idempotent.
- **Indexes:** unique `email` on `admin_users`; unique `tokenHash`, `adminId` and a TTL on
  `expiresAt` for `admin_sessions`.

### Removed from shop users

`UserAccount.platformRoles`, `PlatformRole`, `UserResponse.platformRoles`,
`LoginResponse.UserSummary.platformRoles`, and the old `PlatformAdminBootstrap` that granted the
role to shop users.

### Configuration

| Variable | Purpose |
|---|---|
| `PLATFORM_ADMIN_EMAILS` | Comma-separated first admins (existing variable, new meaning) |
| `PLATFORM_ADMIN_BOOTSTRAP_PASSWORD` | Starting password for bootstrap admins |
| `ADMIN_CLIENT_URL` | Admin app origin allowed by CORS, in addition to `CLIENT_URL` |

## Frontend (`inventory-platform`)

### New app: `apps/admin`

- Built like `apps/inventory` (Vite, React Router, ui-kit). Dev server on port 4400, preview on
  4500. Deployed separately, e.g. `admin.stockkart.in`; hosting and DNS are outside this work.
- Uses the shared API client. Because the app has its own origin, its stored token is separate
  from the shop app's. It never sets a shop ID, so no `X-Shop-Id` header is sent. On 401 it
  clears the session and goes to `/login`.

Routes:

| Path | Page |
|---|---|
| `/login` | Email and password |
| `/change-password` | Forced when `mustChangePassword`; also reachable from the header |
| `/` | Redirects to `/mis` |
| `/mis` | Revenue MIS (existing page) |
| `/referrals` | Referral operations (existing page) |
| `/vouchers` | Vouchers (existing page) |
| `/campaigns` | Sale campaigns (existing page) |
| `/catalogue` | Plans & add-ons (existing page) |
| `/admins` | New: list, add (temporary password shown once with a copy button), enable/disable, reset password |

Layout: a header with "StockKart Admin", the signed-in admin's name and sign-out, and a sidebar
with the six tools. It does not use the shop dashboard layout or navigation.

Session: the admin token and profile are held in an admin session store inside the app. Every
route except `/login` requires a session. A session with `mustChangePassword` can only reach
`/change-password`.

Code placement: the five existing tools stay in `core/plan` (pages, hooks, forms) and are
imported by the admin app. Admin sign-in, session, layout and the Admins page live in
`apps/admin`.

### Removed from the shop app

The `platformAdminRoutes` routes and `platformAdminNav` group, `isPlatformAdmin`,
`User.platformRoles`, `isPlatformAdminPath` / `filterPlatformAdminGroups` in the shell, the
route-guard redirect, and `/dashboard/platform-admin` in `PLAN_EXPIRY_ALLOWED_PATHS`. Every
reference goes, including those in `capabilityNav.ts`, `composed-nav.ts`, `DashboardLayout.tsx`
and `platform/session`; after this PR a search for `platformRoles` or `platform-admin` in the
shop app finds nothing.

## Delivery

Stacked PRs, each on the previous one:

1. **Backend**, based on inventory-api#226: everything in the Backend section.
2. **Frontend**, based on inventory-platform#191: `apps/admin`.
3. **Frontend**, based on PR 2: removal from the shop app.

## Testing

Backend unit tests:

- Sign-in: success, wrong password, unknown email, disabled admin, lockout after five failures
  and unlock after 15 minutes, counter reset on success.
- Sessions: expired, unknown, logout, other sessions removed on password change.
- Password-change rule: blocked endpoints return `PASSWORD_CHANGE_REQUIRED`; allowed ones pass.
- Management: create with duplicate email, cannot disable self or last active admin, reset
  clears lockout and sessions, audit entries without secrets.
- Interceptors: shop token on an admin path, admin token on a shop path, and no token all give
  401.
- Bootstrap: creates missing admins, leaves existing ones, refuses a short password.

Frontend unit tests: login and change-password form validation, the session guard's redirects,
the Admins page form, and the existing admin-tool tests.

Local check: backend against staging with `PLATFORM_ADMIN_EMAILS=sawansloka@gmail.com`; admin
app at `http://localhost:4400`; sign in with the bootstrap password, set a new one, use each
tool; the shop app at `http://localhost:4200` shows no admin menu.

## Out of scope

Admin roles or per-tool permissions (every admin can do everything), two-factor sign-in,
password reset by email, and deployment configuration for the admin address.
