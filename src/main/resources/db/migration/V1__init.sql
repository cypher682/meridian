CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE facilities (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE patients (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    facility_id uuid NOT NULL REFERENCES facilities (id) ON DELETE CASCADE,
    given_name text NOT NULL,
    family_name text NOT NULL,
    date_of_birth date,
    phone text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX patients_name_idx ON patients (facility_id, family_name, given_name);

CREATE TABLE encounters (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    facility_id uuid NOT NULL REFERENCES facilities (id) ON DELETE CASCADE,
    patient_id uuid NOT NULL REFERENCES patients (id),
    clinician_id text NOT NULL,
    notes text,
    vitals jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE lab_orders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    facility_id uuid NOT NULL REFERENCES facilities (id) ON DELETE CASCADE,
    encounter_id uuid NOT NULL REFERENCES encounters (id),
    patient_id uuid NOT NULL REFERENCES patients (id),
    test_code text NOT NULL,
    status text NOT NULL DEFAULT 'ordered'
        CHECK (status IN ('ordered', 'collected', 'resulted', 'released', 'cancelled')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE lab_results (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    lab_order_id uuid NOT NULL UNIQUE REFERENCES lab_orders (id) ON DELETE CASCADE,
    value_text text NOT NULL,
    unit text,
    reference_range text,
    released_by text,
    released_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id bigserial PRIMARY KEY,
    facility_id uuid NOT NULL REFERENCES facilities (id) ON DELETE CASCADE,
    actor_id text NOT NULL,
    action text NOT NULL,
    entity_type text NOT NULL,
    entity_id uuid NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    prev_hash text,
    entry_hash text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX audit_log_facility_idx ON audit_log (facility_id, created_at);
