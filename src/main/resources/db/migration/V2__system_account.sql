-- Tipe rekening: CUSTOMER (nasabah) dan SYSTEM (kas internal bank).
-- Rekening SYSTEM boleh bersaldo negatif: itu sumber uang untuk setoran (sisi lawan di double-entry).
-- Nasabah tetap TIDAK boleh minus.

ALTER TABLE accounts
    ADD COLUMN type VARCHAR(10) NOT NULL DEFAULT 'CUSTOMER'
    CHECK (type IN ('CUSTOMER','SYSTEM'));

ALTER TABLE accounts DROP CONSTRAINT IF EXISTS accounts_balance_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_balance_nonneg
    CHECK (type = 'SYSTEM' OR balance >= 0);

-- User sistem: hash '!NOLOGIN' bukan BCrypt yang valid, sehingga tidak bisa dipakai login.
INSERT INTO users (id, username, password_hash, role)
VALUES ('00000000-0000-0000-0000-0000000000aa', 'system', '!NOLOGIN', 'OPERATOR');

INSERT INTO accounts (id, account_number, owner_id, balance, type)
VALUES ('00000000-0000-0000-0000-000000000001', '0000000001',
        '00000000-0000-0000-0000-0000000000aa', 0, 'SYSTEM');
