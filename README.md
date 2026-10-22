# CERMAT

**C**atatan **E**lektronik, **R**ekonsiliasi, **M**utasi, **A**udit, **T**ransaksi

> Setiap rupiah tercatat, tidak ada yang selisih.

![CI](https://github.com/syubidin/<REPO>/actions/workflows/ci.yml/badge.svg)

CERMAT adalah mini core-banking ledger yang mensimulasikan masalah inti sistem pembayaran:
uang tidak boleh ganda, hilang, atau salah. Seluruh data adalah data dummy.

## Fitur
- **Ledger double-entry append-only**: debit dan kredit selalu seimbang; tidak bisa diubah/dihapus (trigger database)
- **Idempotency key**: request ganda hanya diproses sekali, termasuk saat dikirim bersamaan
- **Concurrency control**: pessimistic locking dengan urutan kunci tetap (tanpa deadlock)
- **Deteksi anomali**: aturan yang parameternya bisa diubah operator tanpa deploy ulang, alur tinjauan, pembekuan rekening
- **Rekonsiliasi otomatis**: unggah CSV mitra, laporan cocok / selisih / hilang / ganda
- **JWT + 3 role** (nasabah, operator, auditor), **audit log** append-only, pembatas login

## Tech stack
Java 17, Spring Boot 3.3, Spring Security (JWT), PostgreSQL, Flyway, Maven, springdoc (Swagger UI),
JUnit 5, Mockito, JaCoCo, Git. Tanpa Docker; ringan untuk laptop spesifikasi rendah.

## Linimasa Pengerjaan (Development Timeline)
Proyek ini diselesaikan secara inkremental dalam waktu 8 minggu dengan fokus pada keandalan data dan pertahanan arsitektur:
*   **Minggu 0-1: Fondasi Ledger & Skema Data**
    *   Inisialisasi project Spring Boot portable tanpa install di drive `C:\`.
    *   Pembuatan skema database relasional awal dan tabel `users` serta `accounts` via Flyway.
    *   Implementasi ledger dasar berbasis arsitektur *double-entry bookkeeping* (Debit/Kredit).
*   **Minggu 2: Mekanisme Inti Transaksi & Invarian Keuangan**
    *   Penerapan mutasi saldo append-only terproteksi trigger database (data tidak bisa di-update/delete).
    *   Pembuatan akun `KAS-SISTEM` internal sebagai generator likuiditas setoran (deposit).
    *   Implementasi pengujian otomatis `globalInvariants_hold` untuk membuktikan jumlah sirkulasi saldo global bernilai tepat nol (`0`).
*   **Minggu 3: Idempotensi Atomik & Kontrol Konkurensi**
    *   Penyelesaian masalah *race condition* penarikan ganda via operasi `INSERT ... ON CONFLICT DO NOTHING`.
    *   Penerapan *Pessimistic Write Lock* (`SELECT ... FOR UPDATE`) dengan urutan kunci tetap (`ORDER BY id`) untuk memblokir celah *deadlock* transaksi konkuren.
*   **Minggu 4: Hardening Keamanan Terdistribusi (Owasp API & JWT)**
    *   Implementasi *Zero-Trust API* berbasis Stateless JWT Authentication (HS256).
    *   Penyusunan kontrol akses berbasis peran (Nasabah, Operator, Auditor).
    *   Proteksi celah kerentanan OWASP API Security (Mitigasi BOLA/IDOR dengan respon 404, proteksi Mass Assignment, dan Audit Log terpisah).
*   **Minggu 5: Rule Engine Deteksi Fraud & Kepatuhan Operasional**
    *   Pembangunan modul *Post-Transaction Monitoring* dengan evaluasi aturan runtime (`HIGH_AMOUNT`, `VELOCITY`, `NEW_PAYEE_BIG`) tanpa harus deploy ulang aplikasi.
    *   Fitur pembekuan rekening otomatis (`FROZEN`) dan interseptor transaksi atomik.
*   **Minggu 6: Mesin Rekonsiliasi Otomatis (File Upload)**
    *   Implementasi sistem pencocokan mutasi rekening eksternal via berkas `.csv` (Apache Commons CSV).
    *   Sistem proteksi *Formula Injection* via whitelisting karakter data dan anti-duplikasi pemrosesan via kalkulasi berkas hash SHA-256.
*   **Minggu 7: Pengerasan Skalabilitas (Cash Sharding & Rate Limiting)**
    *   Optimasi masalah *Hot Row contention* dengan memecah rekening kas terpusat menjadi 16 akun ter-shard dinamis.
    *   Implementasi *Sliding Window Rate Limiter* di level memori sebelum eksekusi BCrypt untuk menghemat resource CPU.
*   **Minggu 8: Penutupan Piramida Pengujian & Dokumentasi Final**
    *   Penulisan 44 unit test murni menggunakan Mockito framework.
    *   Generasi laporan visual cakupan kode (*Code Coverage*) via JaCoCo plugin dengan target pemenuhan >70% pada komponen logika utama.

## Menjalankan
Prasyarat: JDK 17, Maven, PostgreSQL (versi portable juga bisa).
```
createdb -U postgres cermat
createdb -U postgres cermat_test

# opsional: membuat user operator dan auditor pertama (jangan commit password)
set BOOTSTRAP_OPERATOR_PASSWORD=<password-kuat>
set BOOTSTRAP_AUDITOR_PASSWORD=<password-kuat>

mvn spring-boot:run
```
Buka http://localhost:8080/swagger-ui.html, daftar lewat `/api/auth/register`, login, lalu klik **Authorize**.

## Pengujian
```
mvn clean test
```
- `104` test: integrasi API, konkurensi, keamanan, dan unit test (Mockito) untuk logika inti
- Laporan coverage: `target/site/jacoco/index.html` (coverage keseluruhan `92%`)

Bukti yang dihasilkan test:
- 150 transfer bersamaan dari saldo yang cukup untuk 100: tepat 100 sukses, 50 ditolak, saldo tidak pernah minus
- 100 transfer dua arah bersamaan: tanpa deadlock
- 20 request identik (key sama) bersamaan: tepat 1 yang memproses
- Jumlah seluruh saldo selalu 0; saldo setiap rekening sama dengan hasil menjumlahkan ulang ledger

## Benchmark setoran (kas sistem 1 shard vs 16 shard)
400 setoran, 10 thread, laptop pengembangan spesifikasi rendah (satu kali jalan).

| Konfigurasi | Waktu total | Throughput | Latensi rata-rata | p95 |
|---|---|---|---|---|
| 1 shard | 3029 ms | 132,1 /detik | 73,6 ms | 222 ms |
| 16 shard | 2314 ms | 172,9 /detik | 49,0 ms | 232 ms |

Throughput naik ±31% dan latensi rata-rata turun ±33%. **p95 tidak membaik**: di mesin kecil, CPU dan disk database ikut
menjadi batas sehingga latensi ekor tidak ikut turun. Angka ini mengukur berkurangnya antrean kunci, bukan kapasitas produksi.

## Dokumentasi
- [Arsitektur, ERD, dan alur transfer](docs/ARCHITECTURE.md)

## Screenshots
<p align="center">
  <img src="docs/screenshots/swagger-dashboard.png" alt="Swagger Dashboard" width="100%">
  <br><em>Visualisasi seluruh antarmuka endpoint API CERMAT terbagi dalam pemisahan modul Otentikasi, Akun, Ledger, Rekonsiliasi, dan Kontrol Fraud.</em>
</p>

<p align="center">
  <img src="docs/screenshots/swagger-403-forbidden.png" alt="Frobidden Access" width="100%">
  <br><em>Bukti proteksi Role-Based Access Control (RBAC) pada runtime saat akun dengan hak akses `NASABAH` mencoba mengeksekusi fungsi internal milik `OPERATOR`.</em>
</p>

<p align="center">
  <img src="docs/screenshots/jacoco-coverage-report.png" alt="Jacoco Coverage Report" width="100%">
  <br><em>Hasil eksekusi kompilasi pengujian otomatis menunjukkan persentase penutupan proteksi baris logika kode menyentuh angka 92% (Total 104 test hijau).</em>
</p>

## Keamanan (ringkas)
Default API tertutup; IDOR dijawab 404; pendaftaran mandiri tidak menerima `role`; pembatas login (user+IP dan IP);
JWT HS256 dengan validasi `exp` dan `issuer`; unggahan CSV divalidasi whitelist (anti formula injection) dengan batas ukuran;
audit log mencatat sukses maupun penolakan. Detail di dokumen arsitektur.

## Keterbatasan yang diketahui
- Pembatas login tersimpan di memori (satu instance); untuk beberapa instance perlu Redis
- Token JWT tidak bisa dicabut sebelum kedaluwarsa (30 menit)
- Aturan fraud dibaca dari database tiap transfer (tanpa cache); setoran tidak dipantau; perubahan aturan belum maker-checker
- Rekonsiliasi belum menangani cut-off di tepi periode dan belum ada ekspor laporan
- Secret JWT bawaan hanya untuk development (aplikasi menolak start di profil `prod` jika tidak diganti)
- IP diambil dari koneksi langsung; di belakang reverse proxy perlu penyesuaian

## Future Roadmap (Rencana Pengembangan Lanjutan)
- **Pre-transaction hold:** transaksi mencurigakan ditahan `PENDING` sampai disetujui operator
- Refresh token dan pencabutan token; rate limiting di Redis (termasuk endpoint register)
- Maker-checker untuk perubahan aturan; pemantauan setoran; cache aturan dengan invalidasi
- Rekonsiliasi: cut-off handling, ekspor laporan, alur tindak lanjut per temuan, pencocokan jamak
- Observability (metrics, tracing) dan pola outbox untuk notifikasi
