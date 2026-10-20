ALTER TABLE lab_results
    ADD COLUMN amended_by text,
    ADD COLUMN amended_at timestamptz;

CREATE TABLE lab_result_amendments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    facility_id uuid NOT NULL REFERENCES facilities (id) ON DELETE CASCADE,
    lab_order_id uuid NOT NULL REFERENCES lab_orders (id) ON DELETE CASCADE,
    previous_value_text text NOT NULL,
    previous_unit text,
    previous_reference_range text,
    value_text text NOT NULL,
    unit text,
    reference_range text,
    reason text NOT NULL,
    amended_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX lab_result_amendments_order_idx
    ON lab_result_amendments (facility_id, lab_order_id, created_at);
