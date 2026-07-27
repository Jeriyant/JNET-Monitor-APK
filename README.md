# JNET-MONITOR

<div align="center">

![JNET-MONITOR Logo](app/src/main/res/drawable/ic_launcher.png)

**Aplikasi Android untuk Monitoring Hotspot MikroTik & Cetak Cepat Voucher Thermal Bluetooth**

[![Release](https://img.shields.io/github/v/release/Jeriyant/JNET-Monitor-APK?color=3B82F6&label=Versi+Terbaru&style=for-the-badge)](https://github.com/Jeriyant/JNET-Monitor-APK/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android-green?style=for-the-badge&logo=android)](https://android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-Android%207.0%20(API%2024)-blue?style=for-the-badge)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Native-7F52FF?style=for-the-badge&logo=kotlin)](https://kotlinlang.org)
[![Material 3](https://img.shields.io/badge/Material%203-Design-757575?style=for-the-badge&logo=materialdesign)](https://m3.material.io)

</div>

---

## 📱 Tentang Aplikasi

**JNET-MONITOR** adalah aplikasi Android WebView native berbasis **Kotlin + Material 3** yang dirancang khusus untuk sistem manajemen dan monitoring jaringan hotspot MikroTik / Mikhmon. Aplikasi ini menyediakan:

- 🌐 **Akses penuh** ke panel web Mikhmon / MikroTik langsung dari HP Android
- 🖨️ **Cetak Cepat Voucher** Thermal Bluetooth menggunakan QuickPrinter & RAWBT
- 📶 **Monitoring real-time** sesi aktif, pengguna, dan traffic jaringan
- 🔄 **Pembaruan otomatis wajib (Force Update)** melalui GitHub Releases API

---

## ✨ Fitur Lengkap

| Fitur | Keterangan |
|---|---|
| 🖨️ **Cetak Cepat Voucher** | Integrasi penuh QuickPrinter & RAWBT dengan intercept `Location.prototype.href` anti-SyntaxError V8 Chromium |
| 🌐 **WebView Penuh** | Mendukung JavaScript, DOM Storage, AJAX, Multi-Window, `window.open()` |
| 🪟 **Handler Tab Baru** | Dukungan `target="_blank"` & `window.open()` anti-crash dengan pembersihan memori otomatis |
| 📜 **Riwayat Browser** | Simpan & buka kembali halaman yang pernah dikunjungi (maks. 30 halaman) |
| ⚙️ **Pengaturan URL** | URL Default tersimpan di `SharedPreferences`, lengkap tombol *Set ke URL Saat Ini* |
| 🔌 **Driver Printer** | Unduh langsung 4 driver printer thermal Bluetooth populer |
| ℹ️ **Tentang & Update** | Info pembuat, versi, dan tombol Cek Pembaruan dalam satu dialog |
| 🌗 **Dark Mode** | Toolbar, Status Bar, Dialog, dan WebView mengikuti Mode Terang/Gelap sistem HP |
| 🔒 **Force Update** | Pengguna wajib update saat versi baru tersedia (dialog non-cancelable) |
| 🔵 **Bluetooth** | Izin runtime Bluetooth lengkap (Android 12+) |

---

## 🖨️ Cara Kerja Cetak Cepat

Situs web Mikhmon menjalankan fungsi `sendToQuickPrinterChrome()` yang mencoba mengeksekusi:
```javascript
window.location.href = "intent://...#Intent;scheme=quickprinter;package=pe.diegoveloper.printerserverapp;end;"
```

Di Android WebView modern (Chromium V8 terbaru), URL berformat `intent://` yang berisi ESC/POS thermal text **ditolak** dan melempar `SyntaxError`.

**Solusi JNET-MONITOR:**

Mencegat setter `Location.prototype.href` langsung pada level prototipe DOM **sebelum** validasi URL V8 Chromium berjalan:
```kotlin
// Injeksi JavaScript bridge setiap kali halaman selesai dimuat
webView.evaluateJavascript("""
    var origSetter = Object.getOwnPropertyDescriptor(Location.prototype, 'href').set;
    Object.defineProperty(Location.prototype, 'href', {
        set: function(val) {
            if (val.indexOf('intent://') === 0 || val.indexOf('quickprinter:') === 0) {
                AndroidPrintInterface.sendIntent(val); // Diteruskan ke Kotlin Native
                return;
            }
            origSetter.call(this, val);
        }
    });
""", null)
```

---

## 🔌 Driver Printer yang Didukung

Unduh driver printer thermal Bluetooth langsung dari menu aplikasi:

| Aplikasi | Versi | Unduh |
|---|---|---|
| **Quick Printer** | v1.4.8 Full | [Download](http://jeriyant.my.id/.DriverPrinterBT/QuickPrinter_v1.4.8_full.apk) |
| **RAWBT** | v6.0.7 Full | [Download](http://jeriyant.my.id/.DriverPrinterBT/RAWBT_v_6.0.7_Full.apk) |
| **PrinterShare** | v12.24.5 Premium | [Download](http://jeriyant.my.id/.DriverPrinterBT/PrinterShare%20v12.24.5-PREMIUM.apk) |
| **NokoPrint** | v5.27.0 Premium | [Download](http://jeriyant.my.id/.DriverPrinterBT/NokoPrint%20v5.27.0-PREMIUM.apk) |

---

## 📦 Instalasi APK

### Cara Tercepat (Download Langsung)
1. Kunjungi halaman **[GitHub Releases Terbaru](https://github.com/Jeriyant/JNET-Monitor-APK/releases/latest)**
2. Unduh berkas `JNET-MONITOR-vX.X.apk`
3. Aktifkan **Izin Sumber Tidak Diketahui** di HP Android Anda
4. Pasang dan jalankan aplikasi

### Build dari Source Code

**Prasyarat:**
- Android Studio Hedgehog / Ladybug atau lebih baru
- JDK 17+
- Android SDK (API 24 – 34)

**Langkah Build:**
```bash
git clone https://github.com/Jeriyant/JNET-Monitor-APK.git
cd JNET-Monitor-APK
gradlew.bat assembleDebug       # Windows
./gradlew assembleDebug         # Linux / macOS
```

APK output akan tersedia di: `app/build/outputs/apk/debug/app-debug.apk`

---

## 📋 Persyaratan Sistem

| Spesifikasi | Detail |
|---|---|
| OS Android | Android 7.0 (API 24) ke atas |
| Bluetooth Printer | Printer thermal ESC/POS berbasis Bluetooth |
| Koneksi | WiFi / LAN untuk akses panel Mikhmon |
| Aplikasi Driver | QuickPrinter ATAU RAWBT harus sudah terpasang |

---

## 🔐 Izin Aplikasi

| Izin | Alasan |
|---|---|
| `INTERNET` | Mengakses panel web Mikhmon / MikroTik |
| `ACCESS_NETWORK_STATE` | Memantau status koneksi jaringan |
| `BLUETOOTH` | Koneksi printer thermal Bluetooth (API < 31) |
| `BLUETOOTH_ADMIN` | Manajemen perangkat Bluetooth (API < 31) |
| `BLUETOOTH_CONNECT` | Koneksi Bluetooth runtime (Android 12+) |
| `BLUETOOTH_SCAN` | Pemindaian perangkat Bluetooth (Android 12+) |
| `QUERY_ALL_PACKAGES` | Visibilitas paket QuickPrinter & RAWBT |

---

## 📋 Daftar Versi

| Versi | Perubahan Utama |
|---|---|
| **v2.9.0** | Handler New Tab (`window.open`) anti-crash & pembersihan memori otomatis |
| **v2.8.0** | Ikon menu seragam + penyesuaian Dark Mode menyeluruh (Toolbar, Status Bar, WebView) |
| **v2.7.0** | Ikon visual di seluruh menu titik tiga + NokoPrint Premium v5.27.0 |
| **v2.6.0** | Menu Driver Printer, Cek Pembaruan di dialog Tentang |
| **v2.5.0** | Riwayat Browser, Set ke URL Saat Ini, Force Update, System Dark Mode |
| **v2.4.0** | **FIX: Intercept `Location.prototype.href` — Cetak Cepat berjalan di WebView modern** |
| **v2.3.0** | Izin Bluetooth runtime Android 12+ |
| **v2.0.0** | Toolbar ringkas 42dp, nama launcher JNET-MONITOR |
| **v1.0.0** | Rilis perdana WebView + Print Manager |

---

## 👨‍💻 Pembuat Aplikasi

<div align="center">

**JERIYANT-BARAMCITY**

Dikembangkan untuk mendukung kemudahan pengelolaan jaringan hotspot MikroTik di lapangan.

---

📧 Pertanyaan & Laporan Bug: [GitHub Issues](https://github.com/Jeriyant/JNET-Monitor-APK/issues)

⭐ Jika aplikasi ini membantu, jangan lupa **beri bintang** pada repositori ini!

</div>
