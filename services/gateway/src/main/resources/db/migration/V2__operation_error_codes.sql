ALTER TABLE operations ADD COLUMN error_code varchar(128)
    CHECK (error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{0,127}$');
