# CERMAT
**C**atatan **E**lektronik, **R**ekonsiliasi, **M**utasi, **A**udit, **T**ransaksi

> Setiap rupiah tercatat, tidak ada yang selisih.

Mini core-banking ledger: double-entry, idempotency, concurrency control,
deteksi anomali berbasis aturan, dan rekonsiliasi otomatis.

## Menjalankan (lokal)
1. Jalankan PostgreSQL portable, lalu buat database: `createdb -U postgres cermat`
2. `mvn spring-boot:run`
3. Buka http://localhost:8080/swagger-ui.html

> Seluruh data adalah data dummy.

## Jaminan Integritas Sistem Keuangan (Minggu 2)

Sistem perbankan ini mengimplementasikan kepatuhan ketat terhadap standar akuntansi komersial melalui pengujian otomatis menyeluruh:

1. **Global Invariants Validation (`globalInvariants_hold`)**:
   - **Keseimbangan Nol**: Jumlah seluruh saldo rekening (termasuk `KAS-SISTEM` internal) selalu tepat bernilai `0`. Uang tidak dapat tercipta atau hilang dari sistem secara gaib.
   - **Validitas Debit/Kredit**: Nilai total transaksi debit terbukti secara matematis selalu sama dengan total transaksi kredit.
   - **Rekonsiliasi Buku Besar**: Saldo akhir setiap rekening selalu sama dengan kalkulasi akumulatif riwayat entri ledger-nya.

2. **Buku Besar Append-Only (`ledger_isAppendOnly`)**:
   - Sistem menjamin bahwa entri transaksi yang sudah tercatat di dalam Ledger bersifat permanen. Segala bentuk modifikasi (UPDATE) atau penghapusan (DELETE) data ledger akan ditolak langsung di level database.

## Mekanisme Idempotensi & Konkurensi Tingkat Lanjut (Minggu 3)

Sistem telah ditingkatkan untuk menangani traffic tinggi secara aman melalui:
1. **Idempotency Replay (201 Created)**: Request dengan `Idempotency-Key` sama dan payload sama tidak lagi ditolak dengan 409, melainkan mengembalikan respons sukses yang sama persis beserta header `Idempotent-Replayed: true` tanpa memindahkan saldo lagi.
2. **Atomic Key Claim**: Menggunakan operasi database `INSERT ... ON CONFLICT DO NOTHING` untuk mencegah celah waktu (*race condition*) pada pengecekan key.
3. **Pertahanan Berlapis Konkurensi**: Lolos 5 uji konkurensi ekstrem (150 transfer simultan, penanganan deadlock transfer bolak-balik A⇄B, dan 20 request replay bersamaan).

### Analisis Batasan Sistem & Trade-off (Production Readiness)
Sebagai bentuk transparansi arsitektur, sistem saat ini memiliki batasan yang disengaja:
- **Hot Row Problem pada `KAS-SISTEM`**: Karena semua setoran mengunci baris rekening `KAS-SISTEM` internal (`SELECT ... FOR UPDATE`), proses setoran global akan mengantre satu per satu. Solusi produksi: Memecah (*sharding*) kas sistem menjadi beberapa akun terpisah untuk membagi beban penguncian.
- **Masa Aktif Idempotency Key**: Key yang tersimpan belum memiliki masa kedaluwarsa otomatis. Solusi produksi: Menambahkan kolom *timestamp* dan membuat background job pembersih (misal menghapus key yang berusia lebih dari 24 jam).
