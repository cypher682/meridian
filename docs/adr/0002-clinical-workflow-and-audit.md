# ADR-0002: Clinical workflow states and audit chain

- Status: Accepted
- Date: 2026-10-20

## Context

The MVP must prove the clinic and lab workflow from registration through a
released result, while keeping a tamper-evident record of who changed what. The
later product adds roles, amendments, instrument interfaces, and offline sync.
The first slice needs explicit state and audit rules that those features can
extend without rewriting the core.

## Decision

- Model the lab order lifecycle as `ordered -> collected -> resulted ->
  released`. Cancellation is reserved in the schema but not exposed in this
  slice. Invalid transitions return `409`.
- Enforce transitions with conditional `UPDATE ... WHERE status = <expected>`
  and check the affected row count, so two concurrent requests cannot both
  advance the same order.
- Keep results in `lab_results` with one row per lab order. A result starts
  unreleased and records `released_by` and `released_at` only on release.
- Scope every read and write by `facility_id`. The API takes `X-Facility-ID`
  and defaults to the seeded demo facility for local use. (Superseded by
  ADR-0003: facility and actor now come from OIDC token claims.)
- Append every mutation to `audit_log` in the same transaction as the data
  change. The entry hash is
  `SHA256(prev_hash | facility_id | actor_id | action | entity_type | entity_id | payload)`.
- Serialize the per-facility chain with `pg_advisory_xact_lock`. Store the exact
  payload string in `payload_text` and a `jsonb` copy for querying, because
  JSONB does not preserve key order and may reorder the payload.
- Provide `/api/audit/verify` to recompute the chain and report the first broken
  entry.

## Consequences

- A released result is immutable in this slice; correction and amendment will
  be a new audited workflow rather than an in-place edit.
- The audit chain is only as strong as database write access. A privileged user
  could rewrite the chain; external anchoring and monitors are deferred.
- Authentication, role-based access, and per-field authorization are deferred.
  The API currently trusts the actor header for local use.
- The audit table grows with every mutation. Retention, partitioning, and
  export are deferred.

## Alternatives considered

- Store only a status column and allow arbitrary updates: rejected because it
  loses the clinical workflow invariants and makes the audit trail ambiguous.
- Hash the JSONB payload directly during verification: rejected because JSONB
  normalizes key order and whitespace.
- Publish audit entries to an external log immediately: rejected for the first
  slice because it adds a network dependency to every local write.
