-- Rekonsiliasi dilakukan terhadap SATU rekening mitra (settlement) pada SATU periode.

ALTER TABLE recon_batches ADD COLUMN partner_account_id UUID NOT NULL REFERENCES accounts(id);
ALTER TABLE recon_batches ADD COLUMN period_from DATE NOT NULL;
ALTER TABLE recon_batches ADD COLUMN period_to   DATE NOT NULL;
ALTER TABLE recon_batches ADD COLUMN file_sha256 CHAR(64) NOT NULL;
ALTER TABLE recon_batches ADD COLUMN duplicates INT NOT NULL DEFAULT 0;
ALTER TABLE recon_batches ADD CONSTRAINT chk_recon_period CHECK (period_to >= period_from);

-- File yang persis sama untuk rekening + periode yang sama tidak boleh diproses dua kali.
CREATE UNIQUE INDEX uq_recon_same_file
    ON recon_batches (file_sha256, partner_account_id, period_from, period_to);

-- Baris ganda di file mitra dicatat sebagai temuan tersendiri.
ALTER TABLE recon_items DROP CONSTRAINT IF EXISTS recon_items_result_check;
ALTER TABLE recon_items ADD CONSTRAINT recon_items_result_check
    CHECK (result IN ('MATCH','MISSING_INTERNAL','MISSING_PARTNER','AMOUNT_DIFF','DUPLICATE_PARTNER'));
