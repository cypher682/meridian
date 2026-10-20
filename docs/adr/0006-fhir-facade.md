# ADR-0006: Read-only FHIR facade

- Status: Accepted
- Date: 2026-10-20

## Context

ADR-0001 deferred FHIR beyond the first slice. A small read facade is now useful
for demonstrating how the same facility-scoped data can be exposed to FHIR
clients without building a full FHIR server or adding terminology infrastructure.

## Decision

- Add a read-only `/fhir` API that returns FHIR R4 JSON with
  `Content-Type: application/fhir+json`.
- Reuse the existing bearer token and `facility_id` scope. Every resource read
  is facility-scoped; cross-facility ids return a FHIR `OperationOutcome` with
  HTTP 404.
- Map patients, encounters, lab results (Observation), and lab orders
  (DiagnosticReport) from the existing relational model. No new tables or
  synchronization pipeline.
- Use the local `urn:meridian:test-code` coding system for lab tests instead of
  inventing a LOINC mapping.
- Return `final` for released results, `corrected` for amended results, and
  `preliminary`/`registered` for earlier states.
- Keep the surface deliberately small: `metadata`, read, and simple patient
  search. No writes, paging, `_include`, `$everything`, subscriptions, or
  SMART launch.

## Consequences

- A FHIR client can read the MVP's data without a separate integration path.
- The facade is not a certified FHIR server and does not claim full
  conformance. Terminology, search parameters, and write interactions are
  deferred.
- Any future write path must go through the same service and audit rules rather
  than writing tables directly from a FHIR adapter.
