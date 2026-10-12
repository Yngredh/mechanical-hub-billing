CREATE TABLE budgets (
    budget_id      UUID           PRIMARY KEY,
    order_id       UUID           NOT NULL,
    transaction_id UUID           NOT NULL,
    labor_total    NUMERIC(12, 2) NOT NULL,
    material_total NUMERIC(12, 2) NOT NULL,
    total          NUMERIC(12, 2) NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_budgets_transaction_id ON budgets (transaction_id);

CREATE TABLE budget_items (
    id          UUID           PRIMARY KEY,
    budget_id   UUID           NOT NULL REFERENCES budgets (budget_id),
    item_type   VARCHAR(20)    NOT NULL CHECK (item_type IN ('REPAIR', 'MATERIAL')),
    item_ref    UUID           NOT NULL,
    quantity    INTEGER        NOT NULL CHECK (quantity > 0),
    unit_price  NUMERIC(12, 2) NOT NULL,
    total_price NUMERIC(12, 2) NOT NULL
);

CREATE INDEX idx_budget_items_budget_id ON budget_items (budget_id);

CREATE TABLE payments (
    payment_id     UUID           PRIMARY KEY,
    order_id       UUID           NOT NULL,
    transaction_id UUID           NOT NULL,
    budget_id      UUID           NOT NULL REFERENCES budgets (budget_id),
    mp_order_id    VARCHAR(100)   NOT NULL,
    checkout_url   VARCHAR(2048)  NOT NULL,
    amount         NUMERIC(12, 2) NOT NULL,
    status         VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'CANCELED')),
    mp_status      VARCHAR(50),
    due_at         TIMESTAMPTZ    NOT NULL,
    expires_at     TIMESTAMPTZ    NOT NULL,
    paid_at        TIMESTAMPTZ,
    canceled_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_payments_mp_order_id UNIQUE (mp_order_id)
);

CREATE INDEX idx_payments_status_due_at ON payments (status, due_at);
CREATE INDEX idx_payments_transaction_id ON payments (transaction_id);
