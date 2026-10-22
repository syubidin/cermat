-- 1) HOT ROW KAS SISTEM: dari 1 rekening kas menjadi 16 shard.
--    Setiap setoran mengunci satu baris kas; dengan 1 baris, semua setoran antre satu per satu.
--    Shard ke-1 sudah ada sejak V2 (0000000001). Tambahkan shard 2..16.
INSERT INTO accounts (id, account_number, owner_id, balance, type)
SELECT ('00000000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid,
       lpad(n::text, 10, '0'),
       '00000000-0000-0000-0000-0000000000aa',
       0,
       'SYSTEM'
FROM generate_series(2, 16) AS n;

-- 2) KEDALUWARSA IDEMPOTENCY KEY: balik arah relasi.
--    Sebelumnya transactions -> idempotency_keys (FK), sehingga key tidak bisa dihapus.
--    Sekarang idempotency_keys.transaction_id -> transactions, dan transactions tidak lagi bergantung pada key.
ALTER TABLE idempotency_keys ADD COLUMN transaction_id UUID REFERENCES transactions(id);

UPDATE idempotency_keys k
   SET transaction_id = t.id
  FROM transactions t
 WHERE t.idem_key = k.idem_key;

-- Hapus FK dan UNIQUE pada transactions.idem_key (apa pun nama constraint-nya).
DO $$
DECLARE c record;
BEGIN
    FOR c IN
        SELECT con.conname
          FROM pg_constraint con
          JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = ANY (con.conkey)
         WHERE con.conrelid = 'transactions'::regclass
           AND a.attname = 'idem_key'
           AND con.contype IN ('f', 'u')
    LOOP
        EXECUTE format('ALTER TABLE transactions DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

-- Kolom idem_key di transactions tetap ada sebagai jejak informasi (bukan lagi kunci unik).
CREATE INDEX idx_tx_idem_key ON transactions (idem_key);
CREATE INDEX idx_idem_created ON idempotency_keys (created_at);
