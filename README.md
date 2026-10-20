# Meridian

A small clinic workspace for patient check-in, encounters, lab orders, results,
and a verifiable audit trail.

> Local demo only. It has no authentication, no production hardening, and no
> real patient data. Do not use it with protected health information.

## What it does

- Register and search patients, open a chart, and create an encounter.
- Order a lab test from an encounter and send it to the lab queue.
- Collect a sample, record a result, and release it.
- Keep every mutation in a facility-scoped hash chain and verify the chain.
- Isolate all reads and writes by facility.

## Run

```bash
docker compose up --build
```

Open http://localhost:8083. The API and UI are served from the same origin.

```bash
curl http://localhost:8083/api/health
curl http://localhost:8083/actuator/health
```

The database is published on port `5436`. Flyway applies the schema and synthetic
seed data on first start. `make down` stops the stack and deletes local data.

## API

`X-Facility-ID` and `X-Actor-ID` default to the seeded demo facility and
`demo-user` for local use.

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
| GET | `/api/audit?limit=` | Read the newest audit entries |
| GET | `/api/audit/verify` | Recompute and verify the audit chain |

The lab lifecycle is `ordered -> collected -> resulted -> released`. Invalid
transitions return `409`; unknown or cross-facility records return `404`.

## Test

```bash
make test
make smoke
```

`make test` runs Maven through Docker. `make smoke` needs the stack running plus
`curl` and `jq`; it exercises the full flow against PostgreSQL, including audit
verification and facility isolation, and is safe to run repeatedly.

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
src/main/resources/static/          clinic workspace UI
src/main/resources/db/migration/    Flyway migrations and seed data
src/test/java/com/meridian/         tests
scripts/smoke.sh                    end-to-end clinic and lab flow
docs/adr/                           decisions
```
