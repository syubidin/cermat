-- Kolom tinjauan operator + integritas data flag.

ALTER TABLE fraud_flags ADD COLUMN reviewed_at TIMESTAMPTZ;
ALTER TABLE fraud_flags ADD COLUMN review_note VARCHAR(300);

-- Satu aturan hanya boleh menandai satu transaksi satu kali.
ALTER TABLE fraud_flags ADD CONSTRAINT uq_flag_tx_rule UNIQUE (transaction_id, rule_code);

-- Flag yang sudah ditinjau WAJIB punya peninjau dan waktu tinjau.
ALTER TABLE fraud_flags ADD CONSTRAINT chk_flag_reviewed
    CHECK (review_status = 'PENDING' OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL));

CREATE INDEX idx_flags_status ON fraud_flags (review_status, id DESC);

-- Mempercepat pengecekan "penerima baru" (apakah pernah ada transaksi dari A ke B).
CREATE INDEX idx_tx_from_to ON transactions (from_account_id, to_account_id);
