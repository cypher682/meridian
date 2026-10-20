CREATE TABLE billing_invoices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    facility_id uuid NOT NULL REFERENCES facilities (id) ON DELETE CASCADE,
    encounter_id uuid NOT NULL REFERENCES encounters (id),
    patient_id uuid NOT NULL REFERENCES patients (id),
    status text NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'paid', 'void')),
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    paid_at timestamptz,
    paid_by text,
    UNIQUE (facility_id, encounter_id)
);

CREATE INDEX billing_invoices_patient_idx ON billing_invoices (facility_id, patient_id, created_at DESC);

CREATE TABLE billing_charges (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    facility_id uuid NOT NULL REFERENCES facilities (id) ON DELETE CASCADE,
    invoice_id uuid NOT NULL REFERENCES billing_invoices (id) ON DELETE CASCADE,
    description text NOT NULL,
    amount_cents integer NOT NULL CHECK (amount_cents > 0),
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX billing_charges_invoice_idx ON billing_charges (invoice_id, created_at);

CREATE TABLE pharmacy_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    facility_id uuid NOT NULL REFERENCES facilities (id) ON DELETE CASCADE,
    sku text NOT NULL,
    name text NOT NULL,
    unit text NOT NULL DEFAULT 'unit',
    stock_quantity integer NOT NULL DEFAULT 0 CHECK (stock_quantity >= 0),
    reorder_level integer NOT NULL DEFAULT 0 CHECK (reorder_level >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (facility_id, sku)
);

CREATE INDEX pharmacy_items_name_idx ON pharmacy_items (facility_id, name);

CREATE TABLE pharmacy_dispenses (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    facility_id uuid NOT NULL REFERENCES facilities (id) ON DELETE CASCADE,
    item_id uuid NOT NULL REFERENCES pharmacy_items (id),
    encounter_id uuid NOT NULL REFERENCES encounters (id),
    patient_id uuid NOT NULL REFERENCES patients (id),
    quantity integer NOT NULL CHECK (quantity > 0),
    dispensed_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX pharmacy_dispenses_patient_idx ON pharmacy_dispenses (facility_id, patient_id, created_at DESC);
