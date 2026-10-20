#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8083}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8084}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-meridian}"
KEYCLOAK_CLIENT_ID="${KEYCLOAK_CLIENT_ID:-meridian-cli}"
KEYCLOAK_PASSWORD="${KEYCLOAK_PASSWORD:-meridian}"

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

token() {
  local username="$1"
  curl -fsS -X POST "$KEYCLOAK_URL/realms/$KEYCLOAK_REALM/protocol/openid-connect/token" \
    -H 'content-type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=password' \
    --data-urlencode "client_id=$KEYCLOAK_CLIENT_ID" \
    --data-urlencode "username=$username" \
    --data-urlencode "password=$KEYCLOAK_PASSWORD" | jq -r '.access_token'
}

api() {
  local token="$1"
  shift
  curl -fsS -H "Authorization: Bearer $token" "$@"
}

status_for() {
  local token="$1"
  shift
  curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" "$@"
}

echo "health"
curl -fsS "$BASE_URL/api/health" >/dev/null
curl -fsS "$BASE_URL/actuator/health" | jq -e '.status == "UP"' >/dev/null

echo "tokens"
FRONT_TOKEN="$(token frontdesk)"
CLINICIAN_TOKEN="$(token clinician)"
LAB_TOKEN="$(token lab)"
PHARMACIST_TOKEN="$(token pharmacist)"
AUDITOR_TOKEN="$(token auditor)"
OTHER_FACILITY_TOKEN="$(token otherfacility)"
for token_name in FRONT_TOKEN CLINICIAN_TOKEN LAB_TOKEN PHARMACIST_TOKEN AUDITOR_TOKEN OTHER_FACILITY_TOKEN; do
  [[ -n "${!token_name}" && "${!token_name}" != "null" ]] || fail "could not obtain $token_name"
done
assert_eq "$(status_for "$FRONT_TOKEN" "$BASE_URL/api/lab-orders")" "200" "front desk can read the lab queue"
assert_eq "$(curl -sS -o /dev/null -w '%{http_code}' "$BASE_URL/api/patients")" "401" "unauthenticated request is rejected"

audit_before="$(api "$AUDITOR_TOKEN" "$BASE_URL/api/audit?limit=500" | jq 'length')"
expected_audit=$((audit_before + 16))

echo "register patient"
captured_at="2026-10-20T12:00:00Z"
patient_id="$(cat /proc/sys/kernel/random/uuid)"
patient_payload="$(jq -nc --arg id "$patient_id" \
  '{id:$id,givenName:"Sana",familyName:"Smoke",dateOfBirth:"1992-02-03",phone:"+00000000001"}')"
patient_json="$(api "$FRONT_TOKEN" -X POST "$BASE_URL/api/patients" \
  -H 'content-type: application/json' \
  -H "X-Meridian-Captured-At: $captured_at" \
  -d "$patient_payload")"
[[ "$patient_id" != "null" && -n "$patient_id" ]] || fail "patient id missing"
assert_eq "$(jq -r '.id' <<<"$patient_json")" "$patient_id" "client patient id is preserved"
assert_eq "$(jq -r '.givenName' <<<"$patient_json")" "Sana" "patient given name"

echo "idempotent patient replay"
patient_replay="$(api "$FRONT_TOKEN" -X POST "$BASE_URL/api/patients" \
  -H 'content-type: application/json' \
  -H "X-Meridian-Captured-At: $captured_at" \
  -d "$patient_payload")"
assert_eq "$(jq -r '.id' <<<"$patient_replay")" "$patient_id" "patient replay returns the same chart"
assert_eq "$(api "$AUDITOR_TOKEN" "$BASE_URL/api/audit?limit=500" | jq 'length')" "$((audit_before + 1))" "patient replay does not duplicate audit"
assert_eq "$(status_for "$FRONT_TOKEN" -X POST "$BASE_URL/api/patients" \
  -H 'content-type: application/json' \
  -d "$(jq -nc --arg id "$patient_id" '{id:$id,givenName:"Sana",familyName:"Different",dateOfBirth:"1992-02-03"}')")" "409" "patient id conflict is rejected"
assert_eq "$(status_for "$OTHER_FACILITY_TOKEN" -X POST "$BASE_URL/api/patients" \
  -H 'content-type: application/json' \
  -d "$patient_payload")" "404" "unknown facility cannot reuse a patient id"

echo "search patient"
assert_eq "$(api "$FRONT_TOKEN" "$BASE_URL/api/patients?q=Smoke" | jq --arg id "$patient_id" '[.[] | select(.id == $id)] | length')" "1" "patient search finds record"

echo "create encounter"
encounter_id="$(cat /proc/sys/kernel/random/uuid)"
encounter_payload="$(jq -nc --arg id "$encounter_id" --arg patient_id "$patient_id" \
  '{id:$id,patientId:$patient_id,clinicianId:"clinician-smoke",notes:"smoke encounter",vitals:{temperature_c:37.1,heart_rate:80}}')"
encounter_json="$(api "$FRONT_TOKEN" -X POST "$BASE_URL/api/encounters" \
  -H 'content-type: application/json' \
  -H "X-Meridian-Captured-At: $captured_at" \
  -d "$encounter_payload")"
assert_eq "$(jq -r '.id' <<<"$encounter_json")" "$encounter_id" "client encounter id is preserved"
assert_eq "$(jq -r '.patientId' <<<"$encounter_json")" "$patient_id" "encounter binds patient"

echo "idempotent encounter replay"
encounter_replay="$(api "$FRONT_TOKEN" -X POST "$BASE_URL/api/encounters" \
  -H 'content-type: application/json' \
  -H "X-Meridian-Captured-At: $captured_at" \
  -d "$encounter_payload")"
assert_eq "$(jq -r '.id' <<<"$encounter_replay")" "$encounter_id" "encounter replay returns the same encounter"
assert_eq "$(api "$AUDITOR_TOKEN" "$BASE_URL/api/audit?limit=500" | jq 'length')" "$((audit_before + 2))" "encounter replay does not duplicate audit"
assert_eq "$(status_for "$FRONT_TOKEN" -X POST "$BASE_URL/api/encounters" \
  -H 'content-type: application/json' \
  -d "$(jq -nc --arg id "$encounter_id" --arg patient_id "$patient_id" '{id:$id,patientId:$patient_id,clinicianId:"someone-else",notes:"conflict"}')")" "409" "encounter id conflict is rejected"

echo "list encounters"
assert_eq "$(api "$CLINICIAN_TOKEN" "$BASE_URL/api/patients/$patient_id/encounters" | jq 'length')" "1" "patient has one encounter"

echo "order lab test"
order_json="$(api "$CLINICIAN_TOKEN" -X POST "$BASE_URL/api/lab-orders" \
  -H 'content-type: application/json' \
  -d "$(jq -nc --arg encounter_id "$encounter_id" '{encounterId:$encounter_id,testCode:"cbc"}')")"
order_id="$(jq -r '.id' <<<"$order_json")"
assert_eq "$(jq -r '.status' <<<"$order_json")" "ordered" "order starts ordered"
assert_eq "$(jq -r '.testCode' <<<"$order_json")" "CBC" "test code normalized"

echo "reject result before collection"
assert_eq "$(status_for "$LAB_TOKEN" -X POST "$BASE_URL/api/lab-orders/$order_id/results" \
  -H 'content-type: application/json' \
  -d '{"valueText":"13.2"}')" "409" "result before collection is rejected"

echo "collect sample"
collected_json="$(api "$LAB_TOKEN" -X POST "$BASE_URL/api/lab-orders/$order_id/collect")"
assert_eq "$(jq -r '.status' <<<"$collected_json")" "collected" "sample collected"

echo "record result"
resulted_json="$(api "$LAB_TOKEN" -X POST "$BASE_URL/api/lab-orders/$order_id/results" \
  -H 'content-type: application/json' \
  -d '{"valueText":"13.2","unit":"g/dL","referenceRange":"12.0-16.0"}')"
assert_eq "$(jq -r '.status' <<<"$resulted_json")" "resulted" "result recorded"
assert_eq "$(jq -r '.result.valueText' <<<"$resulted_json")" "13.2" "result value stored"

echo "reject duplicate result"
assert_eq "$(status_for "$LAB_TOKEN" -X POST "$BASE_URL/api/lab-orders/$order_id/results" \
  -H 'content-type: application/json' \
  -d '{"valueText":"99"}')" "409" "duplicate result is rejected"

echo "reject release by front desk"
assert_eq "$(status_for "$FRONT_TOKEN" -X POST "$BASE_URL/api/lab-orders/$order_id/release" \
  -H 'content-type: application/json' \
  -d '{"releasedBy":"front-desk-smoke"}')" "403" "front desk cannot release a result"

echo "release result"
released_json="$(api "$CLINICIAN_TOKEN" -X POST "$BASE_URL/api/lab-orders/$order_id/release" \
  -H 'content-type: application/json' \
  -d '{"releasedBy":"clinician-smoke"}')"
assert_eq "$(jq -r '.status' <<<"$released_json")" "released" "result released"
assert_eq "$(jq -r '.result.releasedBy' <<<"$released_json")" "clinician-smoke" "released by recorded"
assert_eq "$(jq -r '.result.releasedAt != null' <<<"$released_json")" "true" "released timestamp recorded"

echo "reject amendment by lab"
assert_eq "$(status_for "$LAB_TOKEN" -X POST "$BASE_URL/api/lab-orders/$order_id/amendments" \
  -H 'content-type: application/json' \
  -d '{"valueText":"13.5","reason":"correction"}')" "403" "lab cannot amend a released result"

echo "amend released result"
amended_json="$(api "$CLINICIAN_TOKEN" -X POST "$BASE_URL/api/lab-orders/$order_id/amendments" \
  -H 'content-type: application/json' \
  -d '{"valueText":"13.5","unit":"g/dL","referenceRange":"12.0-16.0","reason":"Transcription correction"}')"
assert_eq "$(jq -r '.status' <<<"$amended_json")" "released" "amended order stays released"
assert_eq "$(jq -r '.result.valueText' <<<"$amended_json")" "13.5" "amended value is current"
assert_eq "$(jq -r '.result.amendedBy' <<<"$amended_json")" "clinician" "amended by recorded"

echo "amendment history"
amendments_json="$(api "$CLINICIAN_TOKEN" "$BASE_URL/api/lab-orders/$order_id/amendments")"
assert_eq "$(jq 'length' <<<"$amendments_json")" "1" "one amendment stored"
assert_eq "$(jq -r '.[0].previousValueText' <<<"$amendments_json")" "13.2" "previous value preserved"
assert_eq "$(jq -r '.[0].reason' <<<"$amendments_json")" "Transcription correction" "amendment reason stored"

echo "create hl7 order"
hl7_order_json="$(api "$CLINICIAN_TOKEN" -X POST "$BASE_URL/api/lab-orders" \
  -H 'content-type: application/json' \
  -d "$(jq -nc --arg encounter_id "$encounter_id" '{encounterId:$encounter_id,testCode:"bmp"}')")"
hl7_order_id="$(jq -r '.id' <<<"$hl7_order_json")"
assert_eq "$(jq -r '.status' <<<"$hl7_order_json")" "ordered" "hl7 order starts ordered"

echo "ingest hl7 result"
hl7_message="$(printf 'MSH|^~\\&|ANALYZER|LAB|MERIDIAN|CLINIC|20261020120000||ORU^R01|MSG%s|P|2.5\r\nPID|1||%s||Smoke^Sana\r\nOBR|1||%s|BMP^Basic metabolic panel|||20261020115500\r\nOBX|1|NM|GLU^Glucose||5.4|mmol/L|3.5-5.5|N|||F\r\n' \
  "$(date +%s)" "$patient_id" "$hl7_order_id")"
hl7_ack="$(api "$LAB_TOKEN" -X POST "$BASE_URL/api/interfaces/hl7" \
  -H 'content-type: application/hl7-v2' \
  --data-binary "$hl7_message")"
grep -q 'MSA|AA' <<<"$hl7_ack" || fail "hl7 result was not accepted"
echo "ok: hl7 result acknowledged"
assert_eq "$(api "$CLINICIAN_TOKEN" "$BASE_URL/api/lab-orders/$hl7_order_id" | jq -r '.status')" "resulted" "hl7 result is recorded"
assert_eq "$(api "$CLINICIAN_TOKEN" "$BASE_URL/api/lab-orders/$hl7_order_id" | jq -r '.result.valueText')" "5.4" "hl7 result value stored"

echo "billing"
invoice_id="$(cat /proc/sys/kernel/random/uuid)"
invoice_payload="$(jq -nc --arg id "$invoice_id" --arg encounter_id "$encounter_id" \
  '{id:$id,encounterId:$encounter_id}')"
invoice_json="$(api "$FRONT_TOKEN" -X POST "$BASE_URL/api/billing/invoices" \
  -H 'content-type: application/json' \
  -H "X-Meridian-Captured-At: $captured_at" \
  -d "$invoice_payload")"
assert_eq "$(jq -r '.id' <<<"$invoice_json")" "$invoice_id" "client invoice id is preserved"
assert_eq "$(jq -r '.status' <<<"$invoice_json")" "open" "invoice starts open"
assert_eq "$(jq -r '.totalCents' <<<"$invoice_json")" "0" "invoice starts empty"

echo "idempotent invoice replay"
invoice_replay="$(api "$FRONT_TOKEN" -X POST "$BASE_URL/api/billing/invoices" \
  -H 'content-type: application/json' \
  -H "X-Meridian-Captured-At: $captured_at" \
  -d "$invoice_payload")"
assert_eq "$(jq -r '.id' <<<"$invoice_replay")" "$invoice_id" "invoice replay returns the same invoice"

charge_id="$(cat /proc/sys/kernel/random/uuid)"
charge_payload="$(jq -nc --arg id "$charge_id" '{id:$id,description:"Visit fee",amountCents:12000}')"
invoice_charged="$(api "$FRONT_TOKEN" -X POST "$BASE_URL/api/billing/invoices/$invoice_id/charges" \
  -H 'content-type: application/json' \
  -H "X-Meridian-Captured-At: $captured_at" \
  -d "$charge_payload")"
assert_eq "$(jq -r '.totalCents' <<<"$invoice_charged")" "12000" "invoice charge is added"
assert_eq "$(jq -r '.charges[0].id' <<<"$invoice_charged")" "$charge_id" "client charge id is preserved"
assert_eq "$(jq -r '.charges[0].description' <<<"$invoice_charged")" "Visit fee" "invoice charge is stored"

echo "idempotent charge replay"
invoice_recharged="$(api "$FRONT_TOKEN" -X POST "$BASE_URL/api/billing/invoices/$invoice_id/charges" \
  -H 'content-type: application/json' \
  -H "X-Meridian-Captured-At: $captured_at" \
  -d "$charge_payload")"
assert_eq "$(jq -r '.totalCents' <<<"$invoice_recharged")" "12000" "charge replay does not double the total"
assert_eq "$(jq '.charges | length' <<<"$invoice_recharged")" "1" "charge replay does not duplicate the line"
assert_eq "$(status_for "$FRONT_TOKEN" -X POST "$BASE_URL/api/billing/invoices/$invoice_id/charges" \
  -H 'content-type: application/json' \
  -d "$(jq -nc --arg id "$charge_id" '{id:$id,description:"Different",amountCents:12000}')")" "409" "charge id conflict is rejected"

invoice_paid="$(api "$FRONT_TOKEN" -X POST "$BASE_URL/api/billing/invoices/$invoice_id/pay" \
  -H "X-Meridian-Captured-At: $captured_at")"
assert_eq "$(jq -r '.status' <<<"$invoice_paid")" "paid" "invoice is paid"
assert_eq "$(jq -r '.paidBy' <<<"$invoice_paid")" "frontdesk" "invoice payment actor is recorded"

echo "idempotent payment replay"
invoice_paid_again="$(api "$FRONT_TOKEN" -X POST "$BASE_URL/api/billing/invoices/$invoice_id/pay" \
  -H "X-Meridian-Captured-At: $captured_at")"
assert_eq "$(jq -r '.status' <<<"$invoice_paid_again")" "paid" "payment replay stays paid"
assert_eq "$(jq -r '.paidBy' <<<"$invoice_paid_again")" "frontdesk" "payment replay keeps the actor"

echo "pharmacy"
item_sku="AMOX-${patient_id:0:8}"
item_json="$(api "$PHARMACIST_TOKEN" -X POST "$BASE_URL/api/pharmacy/items" \
  -H 'content-type: application/json' \
  -d "$(jq -nc --arg sku "$item_sku" '{sku:$sku,name:"Amoxicillin",unit:"capsule",stockQuantity:10,reorderLevel:4}')")"
item_id="$(jq -r '.id' <<<"$item_json")"
assert_eq "$(jq -r '.stockQuantity' <<<"$item_json")" "10" "pharmacy item starts with stock"

item_adjusted="$(api "$PHARMACIST_TOKEN" -X POST "$BASE_URL/api/pharmacy/items/$item_id/stock" \
  -H 'content-type: application/json' \
  -d '{"delta":5}')"
assert_eq "$(jq -r '.stockQuantity' <<<"$item_adjusted")" "15" "pharmacy stock adjustment is applied"

dispense_json="$(api "$PHARMACIST_TOKEN" -X POST "$BASE_URL/api/pharmacy/dispenses" \
  -H 'content-type: application/json' \
  -d "$(jq -nc --arg item_id "$item_id" --arg encounter_id "$encounter_id" '{itemId:$item_id,encounterId:$encounter_id,quantity:2}')")"
assert_eq "$(jq -r '.quantity' <<<"$dispense_json")" "2" "dispense quantity is recorded"
assert_eq "$(jq -r '.patientId' <<<"$dispense_json")" "$patient_id" "dispense binds patient"
assert_eq "$(api "$PHARMACIST_TOKEN" "$BASE_URL/api/pharmacy/items/$item_id" | jq -r '.stockQuantity')" "13" "dispense decrements stock"

echo "audit chain"
audit_json="$(api "$AUDITOR_TOKEN" "$BASE_URL/api/audit?limit=500")"
assert_eq "$(jq 'length' <<<"$audit_json")" "$expected_audit" "sixteen new audit entries"
verify_json="$(api "$AUDITOR_TOKEN" "$BASE_URL/api/audit/verify")"
assert_eq "$(jq -r '.valid' <<<"$verify_json")" "true" "audit chain valid"
assert_eq "$(jq -r '.entries' <<<"$verify_json")" "$expected_audit" "audit chain entry count"

echo "fhir facade"
assert_eq "$(api "$CLINICIAN_TOKEN" "$BASE_URL/fhir/metadata" | jq -r '.resourceType')" "CapabilityStatement" "fhir metadata"
assert_eq "$(api "$CLINICIAN_TOKEN" "$BASE_URL/fhir/Patient/$patient_id" | jq -r '.name[0].family')" "Smoke" "fhir patient"
fhir_observation="$(api "$CLINICIAN_TOKEN" "$BASE_URL/fhir/Observation/$hl7_order_id")"
assert_eq "$(jq -r '.status' <<<"$fhir_observation")" "preliminary" "fhir observation status"
assert_eq "$(jq -r '.valueQuantity.value' <<<"$fhir_observation")" "5.4" "fhir observation value"
fhir_report="$(api "$CLINICIAN_TOKEN" "$BASE_URL/fhir/DiagnosticReport/$order_id")"
assert_eq "$(jq -r '.status' <<<"$fhir_report")" "corrected" "fhir report status"
assert_eq "$(jq -r '.result[0].reference' <<<"$fhir_report")" "Observation/$order_id" "fhir report links observation"
assert_eq "$(api "$CLINICIAN_TOKEN" "$BASE_URL/fhir/Patient?name=Smoke" | jq -r '.resourceType')" "Bundle" "fhir patient search"

echo "facility isolation"
assert_eq "$(status_for "$OTHER_FACILITY_TOKEN" "$BASE_URL/api/patients/$patient_id")" "404" "patient is hidden from another facility"
assert_eq "$(status_for "$OTHER_FACILITY_TOKEN" "$BASE_URL/fhir/Patient/$patient_id")" "404" "fhir patient is hidden from another facility"

echo "PASS"
