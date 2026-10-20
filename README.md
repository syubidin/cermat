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
