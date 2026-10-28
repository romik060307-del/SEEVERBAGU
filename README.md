# RKM Server — paket API server v1.3.0

Paket ini memperbarui **sisi server** RKM Chat untuk tablet Android (minimum API 19 / Android 4.4). Server menyimpan akun, kontak, pesan, status, metadata grup, media dan data fitur di penyimpanan privat aplikasi pada tablet. Port HTTP lokal: **8080**.

## Penting — batas pembaruan ini

- HTML yang disertakan masih klien RKM Chat versi sebelumnya dan **tidak diubah** pada paket server-only ini. API baru tidak otomatis membuat tombol, gestur geser, pop-up, kontrol font, stiker, UI live, atau UI pengaturan muncul. Hal-hal tersebut perlu dipanggil/ditampilkan oleh web atau aplikasi klien.
- Backend menyediakan penyimpanan dan pengaturan/aksi untuk banyak fitur, termasuk grup, privasi status, komentar/reaksi, arsip/favorit/pin, pemblokiran, ekspor/impor cadangan, media, riwayat panggilan dan metadata sesi live.
- API metadata live **bukan** server streaming video. Panggilan video, kamera/mikrofon, berbagi layar, filter, dan co-host tetap bergantung pada implementasi WebRTC/media di klien, izin browser/perangkat dan jaringan. TURN mungkin diperlukan.
- Pencadangan lokal dan ekspor media tersedia. Upload terjadwal ke Google Drive tidak dilakukan otomatis oleh tablet: itu membutuhkan OAuth Google dan scheduler di klien.
- Server tidak memberikan enkripsi end-to-end dan belum menjalani audit keamanan profesional. Jangan gunakan untuk materi sensitif atau produksi kritis.

## Membuat APK dengan GitHub Actions

1. Simpan/unduh ZIP ini.
2. Pada repository GitHub, unggah ZIP sebagai `RKM-Server-Source.zip` di root repo **atau** ekstrak seluruh isi folder `rk-server-android` langsung ke root repo.
3. Pastikan workflow berada di `.github/workflows/build-apk.yml`. File workflow yang disertakan bisa dipakai dengan cara mengunggahnya pada lokasi itu.
4. Buka **Actions → Build RKM Server APK → Run workflow**. Jika build berhasil, unduh artifact **RKM-Server-APK**.
5. Instal APK pada tablet server. Sebelum memperbarui APK lama, buat backup dari akun terlebih dahulu. Jika tanda tangan APK berbeda, Android mungkin menolak pemasangan di atas aplikasi lama; jangan uninstall aplikasi lama sebelum memastikan data sudah dicadangkan.

Aku tidak mengklaim APK sudah berhasil dibuat di lingkungan ini: pengecekan yang tersedia adalah kompilasi Java terhadap stub Android/JSON, bukan build Gradle/Android SDK penuh atau uji perangkat nyata.

## Menjalankan server

1. Buka aplikasi RKM Server di tablet dan tekan **MULAI SERVER**.
2. Tes `http://ALAMAT-IP-TABLET:8080/health` dari perangkat di Wi-Fi yang sama. Hasil normal memuat `ok:true` dan versi `1.3.0`.
3. Gunakan konfigurasi RK Tunnel Lite yang sebelumnya sudah bekerja untuk memublikasikan port 8080. URL quick tunnel bisa berubah setiap kali tunnel dimulai ulang.
4. Selama server dan tunnel aktif, tablet harus tetap menyala dan aplikasi tidak boleh dihentikan paksa. Jangan hapus data aplikasi karena data server berada di penyimpanan privat aplikasi.

## Penyimpanan dan batas

- Akun, sesi, kontak, pesan, status, sinyal panggilan, serta koleksi fitur disimpan sebagai JSON pada penyimpanan privat aplikasi.
- Lampiran generik dibatasi sekitar **5 MB per file**; foto lama memakai batas lebih kecil (sekitar 180 KB). Ekspor backup dengan media dibatasi sekitar **4 MB per respons**, ekspor satu chat sekitar **6 MB media** agar lebih aman untuk tablet dengan RAM kecil.
- Pesan sekali lihat menyimpan catatan bahwa akun penerima telah membuka pesan, tetapi berkas/media tetap di server sesuai rancangan. Penghapusan file permanen oleh administrator belum otomatis dilakukan oleh aksi sekali lihat.
- Arsip hasil restore ditandai sebagai arsip lokal agar tidak disebarkan ulang ke akun lain secara otomatis.
