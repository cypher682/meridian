# ADR-0005: HL7 v2 result ingestion

- Status: Accepted
- Date: 2026-10-20

## Context

The MVP records results through the UI. Real analyzers usually emit HL7 v2,
and a first integration should prove the message path without committing to a
full interface engine or an external vendor account.

## Decision

- Expose `POST /api/interfaces/hl7` for pipe-delimited HL7 v2.5 `ORU^R01`
  messages, authenticated with the same bearer token and limited to `lab` and
  `admin` roles.
- Use HAPI HL7 v2 (`hapi-base` and `hapi-structures-v25`) for parsing and ACK
  generation. No external service or credential is required.
- Match the order by `OBR-3-1`, falling back to `OBR-2-1`. Read the value from
  `OBX-5-1`, and the optional unit and reference range from `OBX-6-1` and
  `OBX-7-1`.
- Collect an `ordered` sample and record the result in one transaction. Do not
  auto-release; release remains a clinician action.
- Return `MSA|AA` on acceptance and `MSA|AE` with a short reason on rejection.
  Malformed messages return HTTP 400 with an error ACK.

## Consequences

- An analyzer can deliver a result without a custom UI integration, and every
  ingest still produces the normal audit entries.
- Only the first OBX segment is read. Multiple observations, orders, patient
  demographics updates, TLS/mTLS, and interface-engine queuing are deferred.
- The interface is demo-grade: a production deployment needs per-instrument
  credentials, replay/idempotency keys, and a retry/dead-letter path.
