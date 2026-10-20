# Offline front-desk sync: implementation plan

- Status: Implemented
- ADR: `docs/adr/0008-offline-sync.md`
- Scope: active-session offline registration, encounters, billing, and payment

## Goal

Let a signed-in front-desk user keep working through an API or network outage.
Create requests are queued locally, replayed in order after reconnection, and
never duplicate rows or audit entries. Online behavior stays unchanged.

## Non-goals

- Offline cold start or offline sign-in.
- Persisting access or refresh tokens.
- Offline lab, pharmacy, HL7, FHIR, or audit writes.
- Concurrent editing, CRDTs, merge UI, or multi-device sync.
- Background Sync API or service-worker API caching.

## Architecture

```text
form submit
  -> generate UUID + capturedAt
  -> write IndexedDB outbox entry (always)
  -> try POST /api/... with the same UUID
       success -> merge response, remove queue entry
       network/5xx -> leave pending
       4xx -> mark failed and show it
  -> online event / timer / "Sync now"
       -> elect one flusher with Web Locks
       -> verify token facility + actor match the queue
       -> replay FIFO with the original UUID
       -> update cache and UI after each ack
```

Reads use the network first and fall back to the front-desk cache. Audit rows are
created only by the server, in the same transaction as the write.

## Server changes

No schema migration is required. The existing primary keys become replay keys.

### Request contracts

Add optional `UUID id` to:

- `src/main/java/com/meridian/api/RegisterPatientRequest.java`
- `src/main/java/com/meridian/api/CreateEncounterRequest.java`
- `BillingController.CreateInvoiceRequest`
- `BillingController.AddChargeRequest`

Add optional offline capture metadata via a request header:

```text
X-Meridian-Captured-At: 2026-10-20T12:34:56Z
```

The header is audit metadata only. It never sets row timestamps or audit order.

### Service behavior

Update `MeridianService`:

- `registerPatient`: use the supplied id or generate one; if the id already
  exists in the facility, compare normalized fields and return the existing row
  when equal, otherwise throw `ConflictException`. Do not audit a duplicate.
- `createEncounter`: same rule, comparing patient, clinician, notes, and
  normalized vitals JSON.
- `createInvoice`: same rule. A duplicate encounter under a different invoice id
  stays a `409`.
- `addCharge`: same rule, comparing invoice, description, and amount.
- `payInvoice`: if already `paid` by the same actor, return the invoice without
  a new audit entry; if paid by another actor or void, keep `409`.
- Catch `DuplicateKeyException` after the pre-check and repeat the lookup, so a
  race still resolves idempotently.
- Add `source=offline` and `captured_at` to the audit payload when the header is
  present.

Do not trust the client for facility, actor, or row timestamps.

### Server tests

Add or extend:

- `src/test/java/com/meridian/BillingControllerTest.java` for request mapping.
- A new `src/test/java/com/meridian/OfflineIdRequestTest.java` for DTO defaults if
  useful.
- `scripts/smoke.sh` is the integration test because `make test` has no database:
  - Create a patient with a fixed id, repeat the identical request, assert the
    same id and an unchanged audit count.
  - Repeat with a different name and the same id, assert `409`.
  - Repeat the same checks for encounter, invoice, and charge.
  - Pay twice as the same actor, assert success and one audit entry.
  - Try the patient id from another facility, assert `409` and no data leak.

## Client changes

### New module: `src/main/resources/static/offline.js`

No new dependency. Use a small IndexedDB wrapper and `crypto.randomUUID()`.
Export:

```text
openOfflineStore()
enqueue(entry)
listQueue(facilityId, actor)
markSyncing(id)
markFailed(id, error)
remove(id)
flushQueue()
cachePatients(patients)
searchCachedPatients(query)
cacheEncounters(patientId, encounters)
getCachedEncounters(patientId)
cacheInvoices(invoices)
listCachedInvoices()
clearOfflineData()
```

IndexedDB stores:

- `outbox`: keyPath `id`; indexes on `createdAt` and `status`.
- `patients`: keyPath `id`; includes `facilityId` and `_pending`.
- `encounters`: keyPath `id`; includes `facilityId`, `patientId`, `_pending`.
- `invoices`: keyPath `id`; includes `facilityId`, `patientId`, `_pending`.
- `meta`: schema version and last sync time.

Use `navigator.locks.request("meridian-outbox", ...)` for the flusher. If the
API is unavailable, leave the lock and try again later.

### Write path

Add a helper in `app.js`:

```text
submitOrQueue({ type, path, payload, optimistic })
```

Behavior:

1. Generate `payload.id` if the operation creates a row.
2. Add `capturedAt`.
3. Save the outbox entry before the network call.
4. If the browser is online, try the POST with a short timeout.
5. On success, remove the entry and merge the server response.
6. On a network error, timeout, `408`, `429`, or `5xx`, keep it pending.
7. On `400`, `403`, `404`, or `409`, mark it failed and show the error.

Replace the front-desk POST calls in `bindEvents`:

- patient registration;
- encounter creation;
- invoice creation;
- charge creation;
- invoice payment.

For invoice payment, the queue payload includes the invoice id and no body. The
server's idempotent payment rule handles replay.

### Auth and session

- Add `facilityId` to the app state from the `facility_id` claim.
- Change the existing token refresh timer and `api()` failure path so a network
  failure does not immediately redirect to Keycloak. Only a true `401` or an
  expired refresh token should require sign-in.
- Keep the current access token in memory only. Do not persist it.
- Pause queue replay while the token cannot be refreshed; retain the queue.

### Queue replay

- Trigger on `window.online`, every 30 seconds while online, and a manual
  "Sync now" control.
- On startup, reset any stale `syncing` entries to `pending`.
- Verify `keycloak.authenticated`, refresh the token, and compare the token's
  `facility_id` and `preferred_username` with the queue entry.
- Stop on the first failed entry to preserve dependency order.
- Retry network and `5xx` failures with exponential backoff capped at 5 minutes.
- Pause on `401` and prompt sign-in.
- Show `pending`, `syncing`, and `failed` counts in the header.

### Read cache

- After successful patient search, cache the returned patients.
- After opening a chart, cache encounters for that patient.
- After listing invoices, cache the list and each detail response.
- Offline search filters the cached patients by name.
- Offline chart rendering uses cached encounters.
- Offline billing renders cached invoices with a pending marker for queued rows.
- Never cache audit entries, tokens, or lab/pharmacy data.

### UI

- Add a sync pill next to `#connection`: `Online`, `Offline · 3 pending`,
  `Syncing…`, or `1 failed`.
- Add a "Sync now" button near the pill.
- Mark optimistic rows with `Pending sync`.
- Add a failed-row panel with `Retry` and `Discard` actions. Discard requires a
  confirmation.
- On sign-out, if the queue is non-empty, warn that pending changes will be
  discarded. Clear IndexedDB only after confirmation.
- Keep clinician, lab, pharmacy, and audit writes online-only and disable their
  submit buttons while offline.

## Tests and acceptance

### Automated

- Existing `make test` must stay green.
- `scripts/smoke.sh` must cover duplicate and conflicting ids, idempotent
  payment, audit counts, and facility isolation.
- Puppeteer/Playwright flow:
  1. Sign in as `admin` or `frontdesk`.
  2. Set the browser offline.
  3. Register a patient, create an encounter, create an invoice, add a charge,
     and pay it.
  4. Assert all rows show `Pending sync` and no uncaught browser errors.
  5. Restore the network and wait for the queue to drain.
  6. Assert the server has exactly one patient, encounter, invoice, charge, and
     payment audit entry, and `/api/audit/verify` is valid.
- Reload with a non-empty queue while online and confirm it still drains.
- Open two tabs and confirm only one flusher sends the queue.

### Manual

- Fill the queue with ten entries and verify FIFO ordering.
- Change users with a non-empty queue and verify replay is blocked.
- Force a `409` and verify the failed row is visible and later entries wait.
- Sign out with pending entries and verify the confirmation path.

### Definition of done

- A front-desk user can complete registration, encounter, invoice, charge, and
  payment while the API is unreachable.
- Reconnection drains the queue automatically and in dependency order.
- Replaying the same operation never duplicates rows or audit entries.
- A facility or actor mismatch cannot flush another user's queue.
- Online tests, smoke, and the browser suite pass.
- README and ADR 0008 describe the offline limits and the no-PHI constraint.

## Risks and mitigations

- **IndexedDB is unencrypted.** Demo data only; no real PHI. Clear on sign-out.
- **Access token expiry.** Replay pauses until the user signs in again; pending
  writes are retained.
- **No offline cold start.** The app must already be open and authenticated.
- **Queue eviction.** Browsers can evict storage under pressure. Show pending
  state and sync promptly; do not treat IndexedDB as durable storage.
- **Clock skew.** Capture time is metadata only; the server timestamp wins.
- **One bad entry blocks the queue.** Deliberate dependency safety; the UI must
  make retry/discard obvious.

## Rollout

1. Land the server idempotency changes first; they are backward compatible.
2. Land the outbox and front-desk writes behind a single `OFFLINE_SYNC_ENABLED`
   constant in `app.js`.
3. Enable by default after smoke and browser tests pass.
4. Keep the server changes if the client is rolled back; no database migration
   needs reverting.

## Implementation notes

The core slice is shipped: server idempotency, `offline.js`, queued front-desk
and billing writes, read cache, Web Locks replay, sync pill, `Sync now`, pending
and failed markers, sign-out clearing, smoke coverage, and the Puppeteer offline
flow all pass.

Remaining polish, not required for the first slice:

- Per-entry `Retry` and `Discard` controls; `Sync now` retries the whole queue,
  and sign-out clears it.
- Proactively disabling lab and pharmacy submit buttons while offline; those
  forms currently fail with the standard API-unavailable message.
- A dedicated offline DTO unit test; controller mapping and integration behavior
  are covered by `BillingControllerTest` and `scripts/smoke.sh`.
- The replay timer runs every 15 seconds rather than 30, and backoff caps at 60
  seconds rather than 5 minutes.
