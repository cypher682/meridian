# ADR-0003: Authentication and facility scope from OIDC tokens

- Status: Accepted
- Date: 2026-10-20

## Context

ADR-0002 scoped requests with `X-Facility-ID` and `X-Actor-ID` headers. That was
acceptable for a local workflow demo, but any caller could choose a facility and
actor, so the audit trail and facility isolation were not trustworthy. The
public demo needs a real sign-in boundary without adopting a server-side session
store or building an identity provider.

## Decision

- Use Keycloak as the OpenID Provider and import a demo realm from
  `keycloak/meridian-realm.json` on start.
- Keep the API stateless and use Spring Security's OAuth2 resource server. Every
  route except `/api/health`, `/actuator/health`, and static assets requires a
  bearer token.
- Use Authorization Code + PKCE in the browser through the vendored
  `keycloak-js` client. The UI does not hold a session cookie or run a BFF.
- Read the facility from the `facility_id` token claim and the audit actor from
  `preferred_username`. Missing or invalid claims return `403`.
- Map Keycloak realm roles to Spring authorities and enforce role gates at the
  controller boundary:
  - `front_desk`: register patients and create encounters.
  - `clinician`: create encounters and lab orders; release results.
  - `lab`: collect samples and record results.
  - `auditor`: read and verify the audit chain.
  - `admin`: all of the above.
- Keep a public `meridian-cli` client with direct access grants only for the
  local smoke test. The browser client uses PKCE and does not allow direct
  grants.

## Consequences

- The stack now depends on Keycloak on port `8084`; the API fetches signing keys
  from the Keycloak service inside the compose network.
- Tokens expire and the UI refreshes them in the background. A 401 sends the
  user back through login.
- Facility isolation and audit attribution are now based on signed claims, not
  caller-controlled headers. This supersedes the header-based scope in
  ADR-0002.
- Demo users and the bootstrap admin use the shared local password `meridian`.
  This is not a production identity design.
- Role checks are coarse. Per-patient or per-encounter authorization and
  production identity lifecycle management remain deferred.
