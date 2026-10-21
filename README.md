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
