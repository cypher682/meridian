INSERT INTO facilities (id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'Demo Clinic');

INSERT INTO patients (id, facility_id, given_name, family_name, date_of_birth, phone)
VALUES ('00000000-0000-0000-0000-000000000010',
        '00000000-0000-0000-0000-000000000001',
        'Amina',
        'Bello',
        '1990-04-12',
        '+00000000000');

INSERT INTO encounters (id, facility_id, patient_id, clinician_id, notes, vitals)
VALUES ('00000000-0000-0000-0000-000000000020',
        '00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000010',
        'clinician-demo',
        'Demo encounter created by seed data.',
        '{"temperature_c":36.8,"heart_rate":72}'::jsonb);

INSERT INTO lab_orders (id, facility_id, encounter_id, patient_id, test_code, status)
VALUES ('00000000-0000-0000-0000-000000000030',
        '00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000020',
        '00000000-0000-0000-0000-000000000010',
        'CBC',
        'ordered');
