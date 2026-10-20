ALTER TABLE audit_log ADD COLUMN payload_text text;

UPDATE audit_log SET payload_text = payload::text;

ALTER TABLE audit_log ALTER COLUMN payload_text SET NOT NULL;
