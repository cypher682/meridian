# ADR-0007: Local billing ledger and pharmacy stock

- Status: Accepted
- Date: 2026-10-20

## Context

The deferred billing and pharmacy phases normally depend on external claims
clearinghouses, pharmacy networks, credentialing, and business associate
agreements. Those dependencies block a local, end-to-end demo. The product still
needs to show how money and medication actions fit the facility and audit model.

## Decision

- Add a local billing ledger: one invoice per encounter, positive charge line
  items in integer cents, and an `open -> paid` status transition. Payment is a
  status change, not a card or claims transaction.
- Add local pharmacy stock: item master with stock and reorder level, integer
  stock adjustments, and dispense records tied to an encounter and patient.
- Decrement stock in the same transaction as the dispense, with a guard that
  prevents negative stock.
- Scope every query and write by `facility_id`, and write audit entries for
  invoice creation, charges, payment, item creation, stock changes, and
  dispenses.
- Add a `pharmacist` demo role. Front desk and admin can bill; pharmacist and
  admin can manage pharmacy.
- Keep external integrations out of scope: no claims, remittance, eligibility,
  prescriptions, interaction checks, lot/expiry, or pharmacy network.

## Consequences

- The workflow is demonstrable without vendor credentials and still exercises
  authorization, facility isolation, and the audit chain.
- Billing totals are simple sums of charges; there are no adjustments, refunds,
  write-offs, or tax rules.
- Pharmacy stock is a local count, not a validated inventory system. Production
  use needs prescriptions, lot/expiry, controlled-substance controls, and
  external reconciliation.
