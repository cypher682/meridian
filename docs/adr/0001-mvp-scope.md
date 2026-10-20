# ADR-0001: MVP scope and deferred infrastructure

- Status: Accepted
- Date: 2026-10-20

## Context

The full product specification describes a clinic and lab operating system with
FHIR, HL7 instrument interfaces, pharmacy, claims, referrals, patient portal,
terminology search, and an offline facility node. That is a multi-month product.
The immediate goal is a functional clinical and laboratory slice.

## Decision

Build a Spring Boot modular monolith on Java 21 with PostgreSQL and Flyway.
The first slice covers the patient registry, encounters, lab orders and results,
a hash-chained audit log, and a small clinic workspace UI. Run as a single local
instance that does not require internet access.

Defer cloud sync and conflict resolution, HL7 instrument interfaces, pharmacy,
billing and claims, referrals, patient portal, terminology search, and full FHIR
conformance.

## Consequences

- The core clinical flow can be demonstrated and tested locally.
- The MVP is not a multi-facility system and does not prove offline sync.
- Flyway migrations and synthetic seed data provide a reproducible environment.
- Module boundaries are package-level for now. Add Spring Modulith and ArchUnit
  before the module count grows.

## Alternatives considered

- Start with the offline sync protocol: rejected because it is the largest
  technical risk and should follow a working local clinical flow.
- Start with billing and claims: rejected because the clinical record and lab
  result flow are the foundation.
