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


### Hasil Eksperimen Validasi Konkurensi (Studi Kasus Locking)
Dalam fase pengembangan, dilakukan pengujian untuk melihat perilaku sistem di bawah tekanan traffic tinggi dengan menonaktifkan `@Lock(LockModeType.PESSIMISTIC_WRITE)` pada `AccountRepository`:
- **Kegagalan Optimistic Lock**: Sistem memicu `ObjectOptimisticLockingFailureException` secara masif pada pengujian 150 transfer simultan. Walaupun mekanisme `@Version` berhasil menjaga integritas saldo agar tidak minus, efek sampingnya adalah tingginya angka kegagalan transaksi yang merugikan pengguna.
- **Deteksi Deadlock**: Muncul indikasi `deadlock` pada transaksi bolak-balik (A⇄B) yang berjalan bersamaan karena hilangnya urutan penguncian baris data.
- **Solusi**: Mengaktifkan kembali kombinasi *Pessimistic Write Lock* dengan urutan penguncian tetap (`ORDER BY id`) terbukti menyelesaikan kedua kendala di atas secara total, membuat seluruh 17 *automated tests* kembali lulus sempurna (Hijau).


## Implementasi Keamanan & Audit Log (Minggu 4)

Sistem kini menerapkan arsitektur *Zero-Trust* secara default di seluruh endpoint API dengan pemetaan kepatuhan terhadap standar **OWASP API Security Top 10**:

1. **OWASP API1: Broken Object Level Authorization (BOLA/IDOR)**
   - Proteksi ketat pada pengaksesan rekening antar-nasabah. Jika Nasabah A mencoba menembak ID Rekening milik Nasabah B, sistem sengaja merespons dengan kode **404 Not Found (bukan 403 Forbidden)** untuk mencegah kebocoran informasi keberadaan data (*resource enumeration*). Dibuktikan via pengujian `nasabah_cannotAccessAnotherCustomersAccount_idor`.

2. **OWASP API2: Broken Authentication**
   - Penanganan brute-force pada login. Pesan kegagalan login disamakan baik untuk kondisi "Username Tidak Ditemukan" maupun "Password Salah". Proses enkripsi *BCrypt* tetap dieksekusi secara artifisial meskipun pengguna tidak terdaftar di database untuk menjaga konsistensi waktu respons (*timing attack protection*).

3. **OWASP API3: Broken Object Property Level Authorization (Mass Assignment)**
   - Endpoint registrasi mandiri nasabah diproteksi penuh dari manipulasi properti request body. Upaya menyisipkan parameter `"role": "OPERATOR"` atau `"role": "AUDITOR"` secara ilegal akan diabaikan dan otomatis gagal melalui skenario uji `register_cannotEscalateRole_viaRequestBody`.

4. **OWASP A09: Security Logging and Monitoring**
   - Implementasi tabel `audit_logs` yang bersifat *append-only* (dilindungi trigger database) untuk mencatat aktivitas krusial (*Actor, Action, Timestamp, Source IP*). 
   - **Desain Transaksi Audit**:
     - *Atomik dengan Bisnis*: Log sukses (`TRANSFER`, `DEPOSIT`) dibungkus dalam transaksi database yang sama sehingga ikut ter-rollback jika operasi gagal (mencegah log palsu).
     - *Transaksi Terpisah (Requires New)*: Log krusial kegagalan (`REJECTED`, `ACCESS_DENIED`, `LOGIN_FAILED`) dipaksa tetap tersimpan ke database walaupun transaksi utama mengalami kegagalan/rollback untuk kebutuhan forensik keamanan.

### Keterbatasan Keamanan Sistem saat Ini (Trade-off)
- **Mekanisme Brute Force**: Belum mengimplementasikan *rate limiting* atau penguncian akun sementara pada kegagalan login berulang.
- **Stateless Token Invalidation**: Menggunakan arsitektur JWT *stateless* murni dengan masa aktif 30 menit. Token yang aktif tidak dapat dicabut paksa dari sisi server sebelum masa kedaluwarsa habis (*blacklist* belum diterapkan).
- **Verifikasi Asal IP**: IP Client dibaca langsung dari properti koneksi dasar (`remoteAddr`) tanpa memvalidasi header `X-Forwarded-For` yang rentan terhadap *spoofing* di belakang struktur *reverse proxy*.


### Hasil Eksperimen Validasi RBAC (Role-Based Access Control)
Untuk memastikan kebijakan hak akses berjalan konsisten pada runtime, dilakukan simulasi bypass hak akses menggunakan Swagger UI:
- **Skenario Uji**: Akun dengan Role `NASABAH` mencoba menembak endpoint internal operator (`POST /api/deposits`) untuk melakukan setoran mandiri.
- **Hasil Runtime**: Spring Security berhasil mengintercept request secara real-time dan mengembalikan respons **403 Forbidden** dengan pesan *"Anda tidak memiliki akses untuk aksi ini"*. Hal ini memvalidasi efektivitas isolasi tugas (*segregation of duties*) antar-role dalam sistem.

## Deteksi Anomali (Rule Engine) (Minggu 5)

CERMAT memakai pendekatan **post-transaction monitoring**: transaksi tetap dieksekusi, lalu dievaluasi
oleh serangkaian aturan di dalam transaksi database yang sama. Jika ada aturan yang terpicu, transaksi
ditandai `FLAGGED` dan masuk antrean tinjauan operator.

| Aturan | Pemicu | Parameter default |
|---|---|---|
| `HIGH_AMOUNT` | Nominal melebihi batas | `max_amount = 100.000.000` |
| `VELOCITY` | Terlalu banyak transfer keluar dalam jendela waktu | `max_count = 5`, `window_seconds = 60` |
| `NEW_PAYEE_BIG` | Penerima baru dengan nominal besar | `min_amount = 20.000.000` |

Keputusan desain:
- **Logika di kode, parameter di database.** Operator mengubah ambang batas atau menonaktifkan aturan lewat
  `PUT /api/rules/{code}` tanpa deploy ulang. Setiap perubahan tercatat di audit log (nilai sebelum dan sesudah).
- **Klien tidak melihat status `FLAGGED`.** Memberi tahu pelaku bahwa ia terdeteksi hanya membantunya menghindar.
- **Evaluasi VELOCITY aman dari balapan** karena rekening asal sudah dikunci (`SELECT ... FOR UPDATE`) sebelum aturan dijalankan.
- **Parameter rusak = arahkan ke tinjauan manusia**, bukan meloloskan transaksi secara diam-diam.
- **Setoran dari kas sistem tidak dipantau.**
- **Tinjauan:** `CLEARED` (wajar) atau `CONFIRMED` (fraud; rekening pengirim dibekukan). Tinjauan atomik: dua operator
  yang meninjau bersamaan tidak bisa sama-sama berhasil.

## Future Roadmap (Rencana Pengembangan Lanjutan)

- **Pre-transaction hold:** transaksi mencurigakan ditahan berstatus `PENDING` dan baru dieksekusi setelah
  disetujui operator. Lebih ketat, tetapi membutuhkan status dana tertahan (*pending/hold*) dan alur kedaluwarsa.
- **Maker-checker untuk perubahan aturan:** perubahan ambang batas harus disetujui operator kedua.
- **Pemantauan setoran** dan pelaporan transaksi mencurigakan ke format regulator.
- **Cache aturan** dengan invalidasi saat operator mengubah aturan (saat ini dibaca dari DB tiap transfer).
- **Refresh token dan pencabutan token**, serta **rate limiting login**.
- **Memecah rekening kas sistem** untuk menghilangkan hot row, dibuktikan dengan benchmark.
- **Pembersihan idempotency key kedaluwarsa** lewat job terjadwal.

## Rekonsiliasi Otomatis (Minggu 6)

Operator mengunggah file CSV laporan mitra, lalu CERMAT mencocokkannya dengan ledger internal untuk
**satu rekening mitra (settlement) pada satu periode** (maksimal 31 hari, batas hari mengikuti WIB).

Format CSV: kolom `reference_no` dan `amount` (urutan bebas, huruf besar/kecil bebas).

| Hasil | Arti |
|---|---|
| `MATCH` | Referensi ada di kedua sisi dengan nominal sama |
| `AMOUNT_DIFF` | Referensi ada di kedua sisi, nominal berbeda (ditampilkan selisihnya) |
| `MISSING_INTERNAL` | Ada di file mitra, tidak ada di ledger kami |
| `MISSING_PARTNER` | Ada di ledger kami, tidak ada di file mitra |
| `DUPLICATE_PARTNER` | Referensi muncul lebih dari sekali di file mitra |

Keamanan unggahan (file dari pihak luar tidak dipercaya):
- **Batas ukuran 2 MB** dan **20.000 baris**, diperiksa di level server dan di service.
- **Whitelist ketat** untuk `reference_no` (`A-Z 0-9 _ -`) dan `amount` (angka positif, maks 2 desimal).
  Ini sekaligus menolak awalan `= + - @` yang dipakai pada serangan **CSV/formula injection**.
- Nama file disanitasi, ekstensi wajib `.csv`, tidak ada file yang ditulis ke disk.
- **File yang persis sama** (SHA-256) untuk rekening dan periode yang sama ditolak (409), mencegah proses ganda.
- Seluruh proses atomik (batch + temuan + audit log dalam satu transaksi database).
- Hanya OPERATOR yang boleh mengunggah. AUDITOR boleh membaca hasilnya.

Keterbatasan yang diketahui: belum ada penanganan *cut-off* (transaksi yang jatuh di tepi periode antara dua sistem),
belum ada ekspor laporan ke CSV/PDF, dan belum ada alur tindak lanjut per temuan.

## Future Roadmap (tambahan)
- **Cut-off handling:** tandai selisih yang disebabkan perbedaan waktu pencatatan di tepi periode.
- **Ekspor laporan temuan** ke CSV (dengan sanitasi sel agar aman dibuka di Excel).
- **Alur tindak lanjut** per temuan (assign, catatan, status selesai).
- **Pencocokan jamak** (satu setoran mitra untuk beberapa transaksi) dan toleransi selisih.
- **Unggah asinkron** untuk file sangat besar.