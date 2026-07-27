# JNET Monitor - Android WebView Application

Aplikasi Android WebView Native (Kotlin) dengan fitur pencetakan Dokumen/Halaman (Print) ala Google Chrome, Toolbar dengan menu titik tiga (3-dot overflow menu), dan Dialog Pengaturan URL Default yang tersimpan secara permanen.

## 🚀 Fitur Utama

1. **WebView Performance & Compatibility**:
   - Mendukung JavaScript, DOM Storage, HTML5 Database, dan Cookie.
   - Mendukung pencetakan halaman web internal maupun eksternal.
   - `SwipeRefreshLayout` (Tarik ke bawah untuk memuat ulang halaman).
   - Penanganan navigasi tombol Kembali (Back Button) tanpa keluar aplikasi tiba-tiba.
   - Mendukung protokol `http://` (IP Lokal) dan `https://`.

2. **Cetak Halaman (Chrome-like Print)**:
   - **Manual Print**: Klik tombol Titik Tiga > **Cetak Halaman** untuk mencetak web atau menyimpan ke PDF dengan dialog cetak bawaan Android.
   - **Automatic JavaScript `window.print()`**: Menangkap pemanggilan script `window.print()` dari halaman website (misal: cetak resi, bukti bayar, faktur POS) dan langsung meneruskannya ke sistem `PrintManager` Android.

3. **Menu Titik Tiga (3-Dot Overflow Menu)**:
   - 🔄 **Muat Ulang (Reload)**: Memuat ulang halaman web yang sedang dibuka.
   - 🖨️ **Cetak Halaman (Print)**: Memicu fitur cetak Chrome-like.
   - ⚙️ **Pengaturan URL**: Membuka dialog pengaturan untuk mengubah URL default.
   - 🚪 **Keluar**: Menutup aplikasi.

4. **Pengaturan URL Default (Persistence)**:
   - Pengguna dapat menentukan URL utama yang langsung dimuat setiap kali aplikasi dibuka.
   - URL disimpan di `SharedPreferences` Android.
   - Otomatis memvalidasi dan menambahi awalan `https://` atau `http://` jika pengguna lupa mengisinya.
   - Tombol **Reset Default** untuk mengembalikan URL ke pengaturan awal.

---

## 🛠️ Cara Membuka & Build di Android Studio

1. Buka **Android Studio**.
2. Pilih **Open** dan pilih folder proyek: `e:/Cursor-Project/JNET-Monitor-APK`.
3. Tunggu hingga **Gradle Sync** selesai.
4. Hubungkan Perangkat Android / Emulator.
5. Klik tombol **Run (Shift + F10)** atau jalankan `./gradlew assembleDebug` dari terminal untuk menghasilkan file **APK**.

File APK hasil build akan tersimpan di:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📱 Panduan Penggunaan Pengaturan URL

1. Buka aplikasi **JNET Monitor**.
2. Klik tombol **Titik Tiga** di sudut kanan atas Toolbar.
3. Pilih **Pengaturan URL**.
4. Masukkan URL tujuan (contoh: `https://jnet.co.id` atau IP server lokal `http://192.168.1.100:8080`).
5. Klik **Simpan & Buka**. Aplikasi akan menyimpan URL dan langsung memuat halaman baru tersebut.
