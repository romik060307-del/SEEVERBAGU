# Perubahan sisi server v1.3.0

- Menambah API terautentikasi dan penyimpanan privat untuk grup, admin, anggota, pengaturan grup, reaksi/bintang/arsip/sematan pesan, forward, receipt, dan metadata pesan sekali lihat.
- Menambah pengaturan privasi status per akun, pilihan pemirsa status, hapus/lihat/komentar/reaksi/repost/arsip/sorotan serta pencatatan jumlah kunjungan.
- Menambah unggah/unduh media generik dan data lokasi/kartu kontak, favorit stiker, kontak yang diarsipkan/difavoritkan/disematkan, daftar blokir, presence, pengaturan chat, riwayat panggilan, metadata live, ekspor chat dan backup/impor lokal.
- Menambahkan pemeriksaan akses untuk grup, blokir, media status, serta pembatasan pembukaan pesan sekali lihat per akun penerima. Media pesan sekali lihat tidak dihapus dari server, sesuai rancangan server-only yang diminta.
- Menaikkan versi proyek ke 1.3.0 (versionCode 7).

## Status verifikasi

Kompilasi Java dilakukan terhadap stub Android dan JSON untuk memeriksa sintaks dan rujukan metode. Ini **bukan** hasil build Gradle/Android SDK penuh dan bukan tes dua perangkat. Fitur metadata live tidak menangani aliran video; koneksi media, UI, OAuth Google Drive, notifikasi OS, dan fitur UI lanjutan membutuhkan sisi klien.
