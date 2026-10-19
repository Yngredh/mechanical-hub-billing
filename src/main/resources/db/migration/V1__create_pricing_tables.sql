CREATE TABLE service_prices (
    service_id  UUID           PRIMARY KEY,
    labor_price NUMERIC(12, 2) NOT NULL CHECK (labor_price >= 0),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE material_prices (
    material_id UUID           PRIMARY KEY,
    unit_price  NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);
