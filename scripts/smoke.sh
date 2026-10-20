#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8083}"
FACILITY_ID="${FACILITY_ID:-00000000-0000-0000-0000-000000000001}"
ACTOR_ID="${ACTOR_ID:-smoke-user}"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

assert_eq() {
  local actual="$1"
  local expected="$2"
  local label="$3"
  if [[ "$actual" != "$expected" ]]; then
    fail "$label: expected '$expected', got '$actual'"
  fi
  echo "ok: $label"
}

api() {
  curl -fsS -H "X-Facility-ID: $FACILITY_ID" -H "X-Actor-ID: $ACTOR_ID" "$@"
}

echo "health"
api "$BASE_URL/api/health" >/dev/null
curl -fsS "$BASE_URL/actuator/health" | jq -e '.status == "UP"' >/dev/null
audit_before="$(api "$BASE_URL/api/audit?limit=500" | jq 'length')"
expected_audit=$((audit_before + 6))

echo "register patient"
patient_json="$(api -X POST "$BASE_URL/api/patients" \
  -H 'content-type: application/json' \
  -d '{"givenName":"Sana","familyName":"Smoke","dateOfBirth":"1992-02-03","phone":"+00000000001"}')"
patient_id="$(jq -r '.id' <<<"$patient_json")"
[[ "$patient_id" != "null" && -n "$patient_id" ]] || fail "patient id missing"
assert_eq "$(jq -r '.givenName' <<<"$patient_json")" "Sana" "patient given name"

echo "search patient"
assert_eq "$(api "$BASE_URL/api/patients?q=Smoke" | jq --arg id "$patient_id" '[.[] | select(.id == $id)] | length')" "1" "patient search finds record"

echo "create encounter"
encounter_json="$(api -X POST "$BASE_URL/api/encounters" \
  -H 'content-type: application/json' \
  -d "$(jq -nc --arg patient_id "$patient_id" '{patientId:$patient_id,clinicianId:"clinician-smoke",notes:"smoke encounter",vitals:{temperature_c:37.1,heart_rate:80}}')")"
encounter_id="$(jq -r '.id' <<<"$encounter_json")"
assert_eq "$(jq -r '.patientId' <<<"$encounter_json")" "$patient_id" "encounter binds patient"

echo "list encounters"
assert_eq "$(api "$BASE_URL/api/patients/$patient_id/encounters" | jq 'length')" "1" "patient has one encounter"

echo "order lab test"
order_json="$(api -X POST "$BASE_URL/api/lab-orders" \
  -H 'content-type: application/json' \
  -d "$(jq -nc --arg encounter_id "$encounter_id" '{encounterId:$encounter_id,testCode:"cbc"}')")"
order_id="$(jq -r '.id' <<<"$order_json")"
assert_eq "$(jq -r '.status' <<<"$order_json")" "ordered" "order starts ordered"
assert_eq "$(jq -r '.testCode' <<<"$order_json")" "CBC" "test code normalized"

echo "reject result before collection"
status="$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "X-Facility-ID: $FACILITY_ID" -H "X-Actor-ID: $ACTOR_ID" \
  -X POST "$BASE_URL/api/lab-orders/$order_id/results" \
  -H 'content-type: application/json' \
  -d '{"valueText":"13.2"}')"
assert_eq "$status" "409" "result before collection is rejected"

echo "collect sample"
collected_json="$(api -X POST "$BASE_URL/api/lab-orders/$order_id/collect")"
assert_eq "$(jq -r '.status' <<<"$collected_json")" "collected" "sample collected"

echo "record result"
resulted_json="$(api -X POST "$BASE_URL/api/lab-orders/$order_id/results" \
  -H 'content-type: application/json' \
  -d '{"valueText":"13.2","unit":"g/dL","referenceRange":"12.0-16.0"}')"
assert_eq "$(jq -r '.status' <<<"$resulted_json")" "resulted" "result recorded"
assert_eq "$(jq -r '.result.valueText' <<<"$resulted_json")" "13.2" "result value stored"

echo "reject duplicate result"
status="$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "X-Facility-ID: $FACILITY_ID" -H "X-Actor-ID: $ACTOR_ID" \
  -X POST "$BASE_URL/api/lab-orders/$order_id/results" \
  -H 'content-type: application/json' \
  -d '{"valueText":"99"}')"
assert_eq "$status" "409" "duplicate result is rejected"

echo "release result"
released_json="$(api -X POST "$BASE_URL/api/lab-orders/$order_id/release" \
  -H 'content-type: application/json' \
  -d '{"releasedBy":"clinician-smoke"}')"
assert_eq "$(jq -r '.status' <<<"$released_json")" "released" "result released"
assert_eq "$(jq -r '.result.releasedBy' <<<"$released_json")" "clinician-smoke" "released by recorded"
assert_eq "$(jq -r '.result.releasedAt != null' <<<"$released_json")" "true" "released timestamp recorded"

echo "audit chain"
audit_json="$(api "$BASE_URL/api/audit?limit=500")"
assert_eq "$(jq 'length' <<<"$audit_json")" "$expected_audit" "six new audit entries"
verify_json="$(api "$BASE_URL/api/audit/verify")"
assert_eq "$(jq -r '.valid' <<<"$verify_json")" "true" "audit chain valid"
assert_eq "$(jq -r '.entries' <<<"$verify_json")" "$expected_audit" "audit chain entry count"

echo "facility isolation"
status="$(curl -sS -o /dev/null -w '%{http_code}' \
  -H 'X-Facility-ID: 00000000-0000-0000-0000-000000000099' \
  "$BASE_URL/api/patients/$patient_id")"
assert_eq "$status" "404" "patient is hidden from another facility"

echo "PASS"
