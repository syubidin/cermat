# Arsitektur CERMAT

Dokumen ini menjelaskan struktur, model data, dan alur utama CERMAT. Diagram memakai Mermaid, sehingga tampil langsung di GitHub.

## 1. Gambaran komponen

```mermaid
flowchart LR
    C["Klien: Swagger UI / Postman"] -->|"HTTP + Bearer JWT"| F["Security Filter: validasi JWT dan role"]
    F --> CT["Controller: validasi input dan otorisasi per endpoint"]
    CT --> S["Service: aturan bisnis dan transaksi database"]
    S --> RE["Rule Engine: deteksi anomali"]
    S --> AU["Audit Service"]
    S --> R["Repository: JPA dan JDBC"]
    RE --> R
    AU --> R
    R --> DB[("PostgreSQL")]
    J["Job terjadwal: bersihkan idempotency key"] --> DB
```

Paket kode (`id.cermat`):

| Paket | Tanggung jawab |
|---|---|
| `user`, `security` | Registrasi, login, JWT, role, pembatas login |
| `account` | Rekening, kepemilikan, pembekuan |
| `transaction` | Transfer, setoran, kas sistem (shard), idempotency |
| `ledger` | Entri debit/kredit append-only dan mutasi |
| `idempotency` | Klaim key atomik, kedaluwarsa |
| `rule` | Aturan deteksi, flag fraud, tinjauan operator |
| `recon` | Rekonsiliasi file mitra |
| `audit` | Jejak audit append-only |
| `common` | Penanganan error terpusat, konfigurasi |

## 2. Model data (ERD)

```mermaid
erDiagram
    users ||--o{ accounts : memiliki
    accounts ||--o{ transactions : "rekening asal"
    accounts ||--o{ transactions : "rekening tujuan"
    transactions ||--|{ ledger_entries : "menghasilkan debit dan kredit"
    accounts ||--o{ ledger_entries : "dicatat pada"
    idempotency_keys |o--o| transactions : "menunjuk hasil"
    transactions ||--o{ fraud_flags : "ditandai"
    fraud_rules ||--o{ fraud_flags : "pemicu"
    users ||--o{ recon_batches : "mengunggah"
    accounts ||--o{ recon_batches : "rekening mitra"
    recon_batches ||--o{ recon_items : "berisi temuan"
    users ||..o{ audit_logs : "pelaku"

    users {
        uuid id PK
        string username UK
        string password_hash
        string role
    }
    accounts {
        uuid id PK
        string account_number UK
        uuid owner_id FK
        decimal balance
        string status
        string type
        bigint version
    }
    transactions {
        uuid id PK
        string reference_no UK
        string idem_key
        uuid from_account_id FK
        uuid to_account_id FK
        decimal amount
        string status
    }
    ledger_entries {
        bigint id PK
        uuid transaction_id FK
        uuid account_id FK
        string direction
        decimal amount
        decimal balance_after
    }
    idempotency_keys {
        string idem_key PK
        string request_hash
        uuid transaction_id FK
        timestamp created_at
    }
    fraud_rules {
        string code PK
        jsonb params
        boolean enabled
    }
    fraud_flags {
        bigint id PK
        uuid transaction_id FK
        string rule_code FK
        string review_status
        uuid reviewed_by
    }
    recon_batches {
        uuid id PK
        uuid partner_account_id FK
        date period_from
        date period_to
        string file_sha256
    }
    recon_items {
        bigint id PK
        uuid batch_id FK
        string reference_no
        string result
    }
    audit_logs {
        bigint id PK
        uuid actor_id
        string action
        string entity
        jsonb detail
    }
```

Catatan: `audit_logs.actor_id` sengaja tanpa foreign key agar jejak audit tidak pernah terblokir atau ikut terhapus oleh perubahan pada tabel lain.

## 3. Alur transfer

```mermaid
sequenceDiagram
    participant K as Klien
    participant F as Security Filter
    participant C as Controller
    participant S as TransferService
    participant D as PostgreSQL

    K->>F: POST /api/transfers dengan Idempotency-Key
    F->>C: JWT valid, role NASABAH
    C->>C: cek rekening asal milik pemanggil
    C->>S: transfer
    S->>D: INSERT idempotency key ON CONFLICT DO NOTHING
    alt key sudah ada
        S-->>K: jawaban ulang transaksi lama, tanpa memproses lagi
    else key baru
        S->>D: SELECT FOR UPDATE kedua rekening, urut berdasarkan id
        S->>S: validasi status, saldo, mata uang
        S->>S: evaluasi aturan fraud, tandai FLAGGED bila terpicu
        S->>D: UPDATE saldo, INSERT transaksi, INSERT 2 ledger entries, INSERT audit
        S->>D: COMMIT
        S-->>K: 201 Created
    end
```

Semua langkah setelah klaim key berada dalam **satu transaksi database**. Jika ada yang gagal, seluruhnya dibatalkan, termasuk key dan jejak audit.

## 4. Invarian yang dijaga (dan dibuktikan test)

1. Total debit sama dengan total kredit di seluruh ledger.
2. Jumlah seluruh saldo (termasuk kas sistem) selalu 0: uang tidak tercipta atau hilang.
3. Saldo tiap rekening sama dengan hasil menjumlahkan ulang ledger-nya.
4. Saldo nasabah tidak pernah negatif, bahkan di bawah 150 transfer bersamaan.
5. Satu Idempotency-Key hanya pernah menghasilkan satu transaksi, bahkan jika dikirim 20 kali bersamaan.
6. Ledger dan audit log tidak dapat diubah atau dihapus (trigger database).

## 5. Pengendalian konkurensi

| Masalah | Mekanisme |
|---|---|
| Dua transfer dari rekening yang sama | Kunci baris `SELECT ... FOR UPDATE` (pessimistic) |
| Deadlock A→B dan B→A | Kunci selalu diambil berurutan berdasarkan id |
| Request ganda | `INSERT ... ON CONFLICT DO NOTHING` pada idempotency key |
| Update basi sebagai jaring pengaman | Kolom `version` (optimistic locking) |
| Hot row kas sistem | 16 shard kas, dipilih dari hash idempotency key |
| Dua operator meninjau flag yang sama | `UPDATE ... WHERE review_status = 'PENDING'` (atomik) |

## 6. Peta keamanan

| Risiko | Penanganan |
|---|---|
| Akses tanpa login | Default seluruh API tertutup, hanya `/api/auth/**` dan dokumentasi yang terbuka |
| IDOR/BOLA | Nasabah yang meminta rekening orang lain mendapat 404 (keberadaan tidak bocor) |
| Mass assignment | Pendaftaran mandiri tidak menerima field `role` |
| Eskalasi hak | `@PreAuthorize` per endpoint; operator tidak bisa memindahkan uang nasabah |
| Brute force login | Pembatas dua lapis (user+IP, IP), BCrypt, pesan error seragam |
| Token dirusak | HS256, validasi `exp` dan `issuer` |
| CSV/formula injection | Whitelist ketat pada `reference_no` dan `amount` |
| Unggahan berbahaya | Batas 2 MB dan 20.000 baris, ekstensi `.csv`, tidak ditulis ke disk |
| Penyangkalan | Audit log append-only (sukses maupun penolakan) |

## 7. Catatan keputusan desain

| Keputusan | Alternatif | Alasan |
|---|---|---|
| `NUMERIC` untuk uang | `double` | Floating point menimbulkan selisih pembulatan |
| Ledger append-only; koreksi lewat entri pembalik | Update/delete baris | Jejak yang tidak bisa dimanipulasi |
| Pessimistic lock | Optimistic saja | Pada rekening ramai, optimistic menghasilkan banyak kegagalan dan retry |
| Opsi A: tandai, bukan tahan | Opsi B: tahan sampai disetujui | Lebih sederhana dan sesuai post-transaction monitoring; Opsi B ada di roadmap |
| Status `FLAGGED` disembunyikan dari klien | Tampilkan | Memberi tahu pelaku bahwa ia terdeteksi hanya membantunya menghindar |
| JDBC untuk kolom JSONB dan laporan | JPA | Kontrol penuh atas tipe JSONB dan query agregat |
| Pembatas login di memori | Redis | Cukup untuk satu instance; keterbatasan dicatat |
