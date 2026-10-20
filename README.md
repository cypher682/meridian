# Meridian

A small clinic workspace for patient check-in, encounters, lab orders, results,
HL7/FHIR interoperability, visit billing, pharmacy stock, and a verifiable audit
trail.

> Local demo only. It has demo authentication, no production hardening, and no
> real patient data. Do not use it with protected health information.

## What it does

- Register and search patients, open a chart, and create an encounter.
- Order a lab test from an encounter and send it to the lab queue.
- Collect a sample, record a result, and release it.
- Amend a released result without losing the previous value or reason.
- Ingest an HL7 v2 `ORU^R01` result from a lab analyzer.
- Read a small FHIR R4 facade for patients, encounters, observations, and reports.
- Create a local visit invoice, add charges, and mark it paid.
- Track pharmacy stock, adjust inventory, and record dispenses.
- Keep every mutation in a facility-scoped hash chain and verify the chain.
- Isolate all reads and writes by facility.

## Run

```bash
docker compose up --build
```

Open http://localhost:8083 and sign in. Keycloak runs on http://localhost:8084
and imports the demo realm from `keycloak/meridian-realm.json` on start.

```bash
curl http://localhost:8083/api/health
curl http://localhost:8083/actuator/health
```

The database is published on port `5436`. Flyway applies the schema and synthetic
seed data on first start. `make down` stops the stack and deletes local data.

The compose file uses local-only demo database credentials. Set
`POSTGRES_PASSWORD` to override the default before exposing the stack beyond
localhost.

## Authentication

The UI uses Authorization Code + PKCE through Keycloak. The API is a stateless
OAuth2 resource server; send `Authorization: Bearer <access token>`.

Facility and actor are read from the token. The `facility_id` claim scopes every
read and write, and `preferred_username` is recorded as the audit actor.

| User | Password | Roles |
| --- | --- | --- |
| `admin` | `meridian` | front desk, clinician, lab, pharmacist, auditor, admin |
| `frontdesk` | `meridian` | front desk |
| `clinician` | `meridian` | clinician |
| `lab` | `meridian` | lab |
| `pharmacist` | `meridian` | pharmacist |
| `auditor` | `meridian` | auditor |
| `otherfacility` | `meridian` | front desk, separate facility |

Role gates:

- Front desk registers patients and creates encounters.
- Clinicians create lab orders and release results.
- Lab users collect samples, record results, and post HL7 results.
- Front desk users create invoices, add charges, and collect payment.
- Pharmacists manage stock and record dispenses.
- Auditors read and verify the audit chain.
- Admins can use every view.

## API

All `/api/**` routes except `/api/health` require a bearer token.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/patients` | Register a patient |
| GET | `/api/patients?q=` | Search patients by name |
| GET | `/api/patients/{id}` | Read a patient |
| GET | `/api/patients/{id}/encounters` | List a patient's encounters |
| POST | `/api/encounters` | Create an encounter |
| GET | `/api/encounters/{id}` | Read an encounter |
| POST | `/api/lab-orders` | Order a lab test from an encounter |
| GET | `/api/lab-orders?status=&patientId=` | List lab orders |
| GET | `/api/lab-orders/{id}` | Read an order and its result |
| POST | `/api/lab-orders/{id}/collect` | Mark the sample collected |
| POST | `/api/lab-orders/{id}/results` | Record a result |
| POST | `/api/lab-orders/{id}/release` | Release a result |
| POST | `/api/lab-orders/{id}/amendments` | Amend a released result |
| GET | `/api/lab-orders/{id}/amendments` | Read amendment history |
| POST | `/api/interfaces/hl7` | Ingest an HL7 v2 `ORU^R01` result |
| GET | `/api/billing/invoices?status=&patientId=` | List invoices |
| GET | `/api/billing/invoices/{id}` | Read an invoice and its charges |
| POST | `/api/billing/invoices` | Create an invoice for an encounter |
| POST | `/api/billing/invoices/{id}/charges` | Add a charge |
| POST | `/api/billing/invoices/{id}/pay` | Mark an invoice paid |
| GET | `/api/pharmacy/items?q=` | List pharmacy stock |
| POST | `/api/pharmacy/items` | Add a pharmacy item |
| POST | `/api/pharmacy/items/{id}/stock` | Adjust stock by a delta |
| GET | `/api/pharmacy/dispenses?patientId=` | List dispenses |
| POST | `/api/pharmacy/dispenses` | Record a dispense |
| GET | `/api/audit?limit=` | Read the newest audit entries |
| GET | `/api/audit/verify` | Recompute and verify the audit chain |

The lab lifecycle is `ordered -> collected -> resulted -> released`. Invalid
transitions return `409`; unknown or cross-facility records return `404`.

A released result is amended by appending to `lab_result_amendments`, keeping
the previous value, unit, reference range, and a required reason. The current
result and the audit chain both reflect the amendment.

## HL7 result interface

`POST /api/interfaces/hl7` accepts a pipe-delimited HL7 v2.5 `ORU^R01`
message with `Content-Type: application/hl7-v2`. It is limited to the `lab` and
`admin` roles. The order id is read from `OBR-3-1`, falling back to `OBR-2-1`;
the value and optional unit/reference range come from `OBX-5-1`, `OBX-6-1`, and
`OBX-7-1`.

The endpoint collects an `ordered` sample and records the result in one
transaction. It does not auto-release the result. It returns an HL7 ACK with
`MSA|AA` on success or `MSA|AE` with a short reason on a rejected message.

```bash
printf 'MSH|^~\\&|ANALYZER|LAB|MERIDIAN|CLINIC|20261020120000||ORU^R01|MSG1|P|2.5\r\nPID|1||PATIENT\r\nOBR|1||ORDER_ID|CBC^Complete blood count\r\nOBX|1|NM|HGB^Hemoglobin||13.2|g/dL|12.0-16.0|N|||F\r\n' \
  | curl -fsS -X POST http://localhost:8083/api/interfaces/hl7 \
      -H "Authorization: Bearer $LAB_TOKEN" \
      -H 'content-type: application/hl7-v2' \
      --data-binary @-
```

`ORDER_ID` must be a Meridian lab-order UUID in the caller's facility.
Multiple OBX segments and order messages (`ORM^O01`) are not supported yet.

## FHIR facade

A read-only FHIR R4 JSON facade is available under `/fhir`. It uses the same
bearer token and facility scoping as the main API.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/fhir/metadata` | CapabilityStatement |
| GET | `/fhir/Patient/{id}` | Read a patient |
| GET | `/fhir/Patient?name=` | Search patients |
| GET | `/fhir/Encounter/{id}` | Read an encounter |
| GET | `/fhir/Encounter?patient=` | List a patient's encounters |
| GET | `/fhir/Observation/{id}` | Read a lab result as an Observation |
| GET | `/fhir/Observation?patient=` | List lab results for a patient |
| GET | `/fhir/DiagnosticReport/{id}` | Read a lab order as a DiagnosticReport |
| GET | `/fhir/DiagnosticReport?patient=` | List lab reports for a patient |

The facade is intentionally narrow: read-only, JSON only, no `_include`,
`_revinclude`, paging, or write operations. Lab codes use the local
`urn:meridian:test-code` system rather than LOINC. Released results are `final`,
amended results are `corrected`, and unreleased results are `preliminary` or
`registered`.

## Billing and pharmacy

The billing module is a local demo ledger. One invoice can be created per
encounter, charges are stored in cents, and payment only changes the invoice
status to `paid`. There is no claims clearinghouse, insurance adjudication, tax
calculation, or card processing.

The pharmacy module tracks local stock and dispenses. Pharmacists can add
items, adjust stock by a positive or negative delta, and dispense against an
encounter. A dispense decrements stock atomically and is rejected if stock is
insufficient. There are no prescriptions, interaction checks, lot/expiry
tracking, or external pharmacy feeds.

Both modules are facility-scoped and audited like the rest of the workspace.

## Test

```bash
make test
make smoke
```

`make test` runs Maven through Docker. `make smoke` needs the stack running plus
`curl` and `jq`; it obtains tokens for each role, exercises the clinical, HL7,
billing, and pharmacy flows against PostgreSQL, checks unauthorized and forbidden
requests, verifies the audit chain, and checks facility isolation. It is safe to
run repeatedly.

## Audit design

Every mutation appends an audit row in the same database transaction:

```text
SHA256(prev_hash | facility_id | actor_id | action | entity_type | entity_id | payload)
```

The facility chain is serialized with a PostgreSQL advisory transaction lock.
`/api/audit/verify` recomputes it from the first entry and reports the first
broken entry. The exact payload string used for hashing is stored separately
from the `jsonb` copy so key normalization cannot invalidate the chain.

## Layout

```text
src/main/java/com/meridian/core/    service, models, audit chain
src/main/java/com/meridian/api/     HTTP controllers and error mapping
src/main/java/com/meridian/fhir/    read-only FHIR facade
src/main/java/com/meridian/interop/ HL7 parsing and ACK support
src/main/resources/static/          clinic workspace UI and vendored Keycloak JS
src/main/resources/db/migration/    Flyway migrations and seed data
src/test/java/com/meridian/         tests
keycloak/                           demo realm, users, roles, and clients
scripts/smoke.sh                    end-to-end clinic, lab, auth, billing, and pharmacy flow
docs/adr/                           decisions
```
