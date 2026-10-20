# ADR-0004: Append-only result amendments

- Status: Accepted
- Date: 2026-10-20

## Context

ADR-0002 made a released result immutable in the first slice and left
correction as a later workflow. Clinicians still need to correct a released
value, and the correction must not erase the original result or hide who
changed it.

## Decision

- Keep `lab_results` as the current effective result. Add `amended_by` and
  `amended_at` so readers can tell that it changed after release.
- Add `lab_result_amendments` as an append-only history. Each row stores the
  previous and next value, unit, and reference range, plus a required reason,
  the actor, and the timestamp.
- Allow amendments only when the order is `released`, and only for `clinician`
  and `admin` roles.
- Lock the order row with `SELECT ... FOR UPDATE` before inserting, then update
  the current result. The audit entry `lab_result.amended` records the previous
  and next values and the reason in the same transaction.
- Expose `POST /api/lab-orders/{id}/amendments` and
  `GET /api/lab-orders/{id}/amendments`. Readers see the current result in the
  order payload and the history through the amendments endpoint.

## Consequences

- The original released value remains recoverable from the amendment history
  and the audit chain.
- A released order can have many amendments; the UI shows the newest first.
- Amendments are still corrections of a single result row. Versioned reports,
  separate specimen revisions, and external lab instrument reconciliation are
  deferred.
