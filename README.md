# JNET-MONITOR-APK

<div align="center">

**Browser Android Khusus untuk Sistem Monitoring & Manajemen Jaringan Hotspot**

[![Release](https://img.shields.io/github/v/release/Jeriyant/JNET-Monitor-APK?color=3B82F6&label=Versi+Terbaru&style=for-the-badge)](https://github.com/Jeriyant/JNET-Monitor-APK/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android-green?style=for-the-badge&logo=android)](https://android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-Android%207.0%20(API%2024)-blue?style=for-the-badge)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Native-7F52FF?style=for-the-badge&logo=kotlin)](https://kotlinlang.org)
[![Material 3](https://img.shields.io/badge/Material%203-Design-757575?style=for-the-badge&logo=materialdesign)](https://m3.material.io)

</div>

---

## 📱 Tentang Aplikasi

**JNET-MONITOR-APK** adalah aplikasi browser Android berbasis **WebView native (Kotlin + Material 3)** yang dirancang khusus untuk mengakses sistem pengelolaan jaringan hotspot berbasis web dari perangkat Android. Aplikasi ini berfungsi layaknya browser khusus yang sudah dikonfigurasi untuk:

- 🌐 Membuka panel web manajemen jaringan hotspot secara langsung dari HP Android
- 🖨️ Mendukung **Cetak Cepat Voucher** Thermal Bluetooth (QuickPrinter & RAWBT) tanpa perlu buka browser lain
- 🔗 Menangani semua jenis navigasi web termasuk tab baru, popup, dan redirect
- 🔄 Memperbarui diri secara otomatis melalui GitHub Releases

---

## ✨ Fitur Lengkap

| Fitur | Keterangan |
|---|---|
| 🖨️ **Cetak Cepat Voucher** | Dukungan QuickPrinter & RAWBT dengan solusi intercept `Location.prototype.href` khusus untuk WebView modern |
| 🌐 **Browser WebView Penuh** | JavaScript, DOM Storage, AJAX, Multi-Window, `window.open()`, tab baru |
| 🪟 **Handler Tab Baru Anti-Crash** | Dukungan `target="_blank"` & `window.open()` tanpa crash, memori dibersihkan otomatis |
| 📜 **Riwayat Browser** | Simpan & buka kembali halaman yang pernah dikunjungi (maks. 30 halaman) |
| ⚙️ **Pengaturan URL Default** | URL awal tersimpan di penyimpanan lokal, dilengkapi tombol *Set ke URL Saat Ini* |
| 🔌 **Driver Printer** | Unduh langsung 4 driver printer thermal Bluetooth populer dari dalam aplikasi |
| ℹ️ **Tentang & Cek Pembaruan** | Info versi dan tombol cek pembaruan langsung ke GitHub Releases |
| 🌗 **Dark / Light Mode Otomatis** | Toolbar, Status Bar, Dialog, dan isi browser mengikuti tema sistem HP |
| 🔒 **Force Update** | Pengguna wajib memperbarui saat versi baru tersedia |
| 🔵 **Bluetooth Runtime** | Izin Bluetooth lengkap termasuk runtime permission Android 12+ |

---

## 🖨️ Cara Kerja Cetak Cepat Voucher

Halaman web manajemen jaringan umumnya menggunakan fungsi JavaScript berikut untuk mencetak voucher ke printer thermal:

```javascript
window.location.href = "intent://...ESC/POS data...#Intent;scheme=quickprinter;package=pe.diegoveloper.printerserverapp;end;"
```

Pada Android WebView berbasis Chromium V8 modern, URL berformat `intent://` yang berisi data thermal ESC/POS **ditolak dan melempar `SyntaxError`** sehingga cetak tidak pernah terjadi.

**Solusi di JNET-MONITOR-APK:**

Mencegat setter `Location.prototype.href` langsung pada prototipe DOM **sebelum** validasi URL Chromium V8 berjalan:

```javascript
var origSetter = Object.getOwnPropertyDescriptor(Location.prototype, 'href').set;
Object.defineProperty(Location.prototype, 'href', {
    set: function(val) {
        if (val.indexOf('intent://') === 0 || val.indexOf('quickprinter:') === 0) {
            AndroidPrintInterface.sendIntent(val); // Diteruskan ke Kotlin native
            return;
        }
        origSetter.call(this, val);
    }
});
```

Perintah cetak kemudian diteruskan secara native ke aplikasi **QuickPrinter** atau **RAWBT** di Android.

---

## 🔌 Driver Printer yang Didukung

Unduh driver printer thermal Bluetooth langsung dari menu **Driver Printer** di dalam aplikasi:

| Aplikasi | Versi | Unduh |
|---|---|---|
| **Quick Printer** | v1.4.8 Full | [Download](http://jeriyant.my.id/.DriverPrinterBT/QuickPrinter_v1.4.8_full.apk) |
| **RAWBT** | v6.0.7 Full | [Download](http://jeriyant.my.id/.DriverPrinterBT/RAWBT_v_6.0.7_Full.apk) |
| **PrinterShare** | v12.24.5 Premium | [Download](http://jeriyant.my.id/.DriverPrinterBT/PrinterShare%20v12.24.5-PREMIUM.apk) |
| **NokoPrint** | v5.27.0 Premium | [Download](http://jeriyant.my.id/.DriverPrinterBT/NokoPrint%20v5.27.0-PREMIUM.apk) |

---

## 📦 Cara Instalasi APK

### Download Langsung (Cara Tercepat)
1. Buka halaman **[Releases Terbaru](https://github.com/Jeriyant/JNET-Monitor-APK/releases/latest)**
2. Unduh berkas `JNET-MONITOR-vX.X.apk`
3. Aktifkan **Izin Sumber Tidak Diketahui** di HP Android Anda
4. Pasang dan jalankan aplikasi

### Build dari Source Code

**Prasyarat:**
- Android Studio Hedgehog / Ladybug atau lebih baru
- JDK 17+
- Android SDK (API 24 – 34)

```bash
git clone https://github.com/Jeriyant/JNET-Monitor-APK.git
cd JNET-Monitor-APK
./gradlew assembleDebug        # Linux / macOS
gradlew.bat assembleDebug      # Windows
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`

---

## 📋 Persyaratan

| Spesifikasi | Detail |
|---|---|
| OS Android | Android 7.0 (API 24) ke atas |
| Printer | Printer thermal ESC/POS berbasis Bluetooth |
| Jaringan | WiFi / LAN untuk mengakses panel web |
| Driver | QuickPrinter **atau** RAWBT harus terpasang untuk fitur cetak cepat |

---

## 🔐 Izin Aplikasi

| Izin | Fungsi |
|---|---|
| `INTERNET` | Mengakses halaman web manajemen jaringan |
| `ACCESS_NETWORK_STATE` | Memantau status koneksi jaringan |
| `BLUETOOTH` + `BLUETOOTH_ADMIN` | Koneksi printer Bluetooth (Android < 12) |
| `BLUETOOTH_CONNECT` + `BLUETOOTH_SCAN` | Koneksi Bluetooth runtime (Android 12+) |
| `QUERY_ALL_PACKAGES` | Mendeteksi keberadaan QuickPrinter / RAWBT |

---

## 📋 Riwayat Versi

| Versi | Perubahan |
|---|---|
| **v3.3.1** | Fix Warna Asli Favicon Halaman Web pada Riwayat Browser (Menghapus Tint Masking XML) |
| **v3.3.0** | Kapasitas Riwayat Browser Tanpa Batas (*Unlimited*), Ikon Visual Riwayat, & Rilis Resmi |
| **v3.2.0** | Perataan versi Toolbar (`JNET-MONITOR v.3.2.0`), High-Tech Branding Badge `JERIYANT - BARAMCITY`, & penataan tampilan deskripsi |
| **v3.0.0** | Handler Download Aplikasi/Driver (`DownloadManager` & `DownloadListener` native) |
| **v2.9.0** | Handler tab baru (`window.open`, `target="_blank"`) anti-crash |
| **v2.8.0** | Ikon menu seragam + Dark Mode menyeluruh (Toolbar, Status Bar, WebView) |
| **v2.7.0** | Ikon visual di menu + NokoPrint Premium v5.27.0 |
| **v2.6.0** | Menu Driver Printer, Cek Pembaruan masuk ke dialog Tentang |
| **v2.5.0** | Riwayat Browser, Set ke URL Saat Ini, Force Update, System Dark Mode |
| **v2.4.0** | ✅ **FIX UTAMA: Cetak Cepat berjalan di WebView Chromium V8 modern** |
| **v2.3.0** | Izin Bluetooth runtime Android 12+ |
| **v2.0.0** | Toolbar ringkas 42dp, nama launcher JNET-MONITOR |
| **v1.0.0** | Rilis perdana WebView browser + Print Manager |

---

<div align="center">

**JERIYANT - BARAMCITY**

Dikembangkan untuk kemudahan akses dan pengelolaan jaringan hotspot dari genggaman tangan.

---

🐛 Laporan Bug & Saran: [GitHub Issues](https://github.com/Jeriyant/JNET-Monitor-APK/issues)

⭐ Jika aplikasi ini bermanfaat, silakan **beri bintang** pada repositori ini!

</div>
