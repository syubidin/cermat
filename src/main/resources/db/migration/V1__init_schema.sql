-- CERMAT: skema awal (PostgreSQL)
-- Prinsip: uang = NUMERIC (bukan float), ledger append-only, saldo tidak boleh minus.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- untuk gen_random_uuid()

-- ========== USER & ROLE ==========
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,              -- BCrypt
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('NASABAH','OPERATOR','AUDITOR')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ========== REKENING ==========
CREATE TABLE accounts (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_number VARCHAR(20)   NOT NULL UNIQUE,
    owner_id       UUID          NOT NULL REFERENCES users(id),
    currency       CHAR(3)       NOT NULL DEFAULT 'IDR',
    balance        NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (balance >= 0),  -- jaring pengaman terakhir
    status         VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    version        BIGINT        NOT NULL DEFAULT 0,                       -- optimistic locking
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ========== IDEMPOTENCY ==========
CREATE TABLE idempotency_keys (
    idem_key      VARCHAR(100) PRIMARY KEY,
    request_hash  CHAR(64)     NOT NULL,              -- SHA-256 body request
    response_code INT,
    response_body JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ========== TRANSAKSI ==========
CREATE TABLE transactions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference_no    VARCHAR(40)   NOT NULL UNIQUE,    -- nomor referensi untuk rekonsiliasi
    idem_key        VARCHAR(100)  NOT NULL UNIQUE REFERENCES idempotency_keys(idem_key),
    from_account_id UUID          NOT NULL REFERENCES accounts(id),
    to_account_id   UUID          NOT NULL REFERENCES accounts(id),
    amount          NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    status          VARCHAR(10)   NOT NULL CHECK (status IN ('SUCCESS','FAILED','FLAGGED')),
    description     VARCHAR(200),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (from_account_id <> to_account_id)
);
CREATE INDEX idx_tx_from_time ON transactions (from_account_id, created_at DESC);
CREATE INDEX idx_tx_to_time   ON transactions (to_account_id, created_at DESC);

-- ========== LEDGER (DOUBLE-ENTRY, APPEND-ONLY) ==========
CREATE TABLE ledger_entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id UUID          NOT NULL REFERENCES transactions(id),
    account_id     UUID          NOT NULL REFERENCES accounts(id),
    direction      VARCHAR(6)    NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount         NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    balance_after  NUMERIC(19,2) NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_account ON ledger_entries (account_id, id DESC);

-- ========== RULE ENGINE ==========
CREATE TABLE fraud_rules (
    code        VARCHAR(40) PRIMARY KEY,
    description VARCHAR(200) NOT NULL,
    params      JSONB        NOT NULL,                -- contoh: {"max_amount": 100000000}
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE fraud_flags (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id UUID        NOT NULL REFERENCES transactions(id),
    rule_code      VARCHAR(40) NOT NULL REFERENCES fraud_rules(code),
    reason         VARCHAR(300) NOT NULL,
    reviewed_by    UUID REFERENCES users(id),
    review_status  VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (review_status IN ('PENDING','CLEARED','CONFIRMED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ========== REKONSILIASI ==========
CREATE TABLE recon_batches (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_name   VARCHAR(50) NOT NULL,
    file_name      VARCHAR(200) NOT NULL,
    uploaded_by    UUID NOT NULL REFERENCES users(id),
    total_rows     INT NOT NULL DEFAULT 0,
    matched        INT NOT NULL DEFAULT 0,
    missing        INT NOT NULL DEFAULT 0,
    mismatched     INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE recon_items (
    id              BIGSERIAL PRIMARY KEY,
    batch_id        UUID NOT NULL REFERENCES recon_batches(id),
    reference_no    VARCHAR(40) NOT NULL,
    partner_amount  NUMERIC(19,2),
    internal_amount NUMERIC(19,2),
    result          VARCHAR(20) NOT NULL CHECK (result IN ('MATCH','MISSING_INTERNAL','MISSING_PARTNER','AMOUNT_DIFF'))
);
CREATE INDEX idx_recon_batch ON recon_items (batch_id, result);

-- ========== AUDIT LOG ==========
CREATE TABLE audit_logs (
    id         BIGSERIAL PRIMARY KEY,
    actor_id   UUID,
    action     VARCHAR(50)  NOT NULL,
    entity     VARCHAR(50)  NOT NULL,
    entity_id  VARCHAR(60),
    detail     JSONB,
    ip_address VARCHAR(45),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ========== PROTEKSI APPEND-ONLY ==========
CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Tabel % bersifat append-only: % tidak diizinkan', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER trg_audit_immutable
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- ========== SEED ATURAN AWAL ==========
INSERT INTO fraud_rules (code, description, params) VALUES
 ('HIGH_AMOUNT',   'Nominal melebihi batas',                       '{"max_amount": 100000000}'),
 ('VELOCITY',      'Terlalu banyak transfer dalam waktu singkat',  '{"max_count": 5, "window_seconds": 60}'),
 ('NEW_PAYEE_BIG', 'Rekening tujuan baru dengan nominal besar',    '{"min_amount": 20000000}');
