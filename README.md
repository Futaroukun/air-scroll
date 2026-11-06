# AirScroll 🖐️📱
**Touchless Gesture-Based Social Media Scroller for Android**

AirScroll memungkinkan Anda melakukan scrolling feed media sosial (TikTok, Instagram Reels, YouTube Shorts, Twitter/X, Reddit, Web) hanya dengan lambaian tangan di depan kamera depan HP tanpa perlu menyentuh layar.

100% On-Device, Open Source, dan Tanpa Malware.

---

## ⚡ Fitur Utama

- **Deteksi Tangan On-Device**: Menggunakan Google MediaPipe Tasks Vision (`hand_landmarker.task`) langsung di perangkat tanpa mengirim data video ke internet.
- **Injeksi Sentuhan Tanpa Root**: Menggunakan standar Android `AccessibilityService` (`dispatchGesture`) yang aman dan resmi didukung sistem operasi.
- **Floating Overlay Camera**: Tampilan floating bubble kamera mengambang yang dapat digeser bebas saat membuka aplikasi sosmed.
- **Hemat Daya & Efisien**: Analisis frame CameraX dengan resolusi optimal (320x240) dan debounce pintar (mencegah double-scroll).
- **Automated CI/CD**: Workflow GitHub Actions bawaan yang otomatis mengompilasi APK setiap kali ada push atau tag release.

---

## 🖐️ Cara Penggunaan Gestur

| Gestur Tangan | Aksi Layar |
| :--- | :--- |
| **Lambaikan tangan ke BAWAH** | Scroll ke postingan / video berikutnya (*Next Reel/Short*) |
| **Lambaikan tangan ke ATAS** | Scroll ke postingan / video sebelumnya (*Previous Reel/Short*) |

---

## 🚀 Cara Build & Download APK via GitHub Actions

1. **Buat Repository Baru di GitHub**:
   - Buka [GitHub](https://github.com/new) dan buat repo baru (misal: `air-scroll`).

2. **Inisialisasi & Push Kode dari Termux**:
   ```bash
   cd ~/air-scroll
   git init
   git add .
   git commit -m "feat: initial commit AirScroll touchless gesture scroller"
   git branch -M main
   git remote add origin https://github.com/<USERNAME-ANDA>/air-scroll.git
   git push -u origin main
   ```

3. **Download APK Hasil Build**:
   - Buka tab **Actions** di repositori GitHub Anda.
   - Klik workflow run yang sedang berjalan (*Build AirScroll APK*).
   - Setelah selesai (tanda centang hijau), gulir ke bagian **Artifacts**.
   - Download file **AirScroll-Debug-APK.zip**, ekstrak, lalu install `app-debug.apk` di HP Anda!

4. *(Opsional)* **Buat Rilis Tag Otomatis**:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
   GitHub Actions akan otomatis membuat halaman GitHub Release dan melampirkan file APK siap unduh.

---

## ⚙️ Persyaratan Izin di HP

Setelah install APK, buka aplikasi dan aktifkan 3 izin:
1. **Kamera**: Untuk mendeteksi gestur tangan via kamera depan.
2. **Tampil di Atas Aplikasi Lain (Display over other apps)**: Agar bubble floating tracker tetap aktif saat membuka sosmed.
3. **Layanan Aksesibilitas (Accessibility Service)**: Izin sistem Android resmi untuk mengeksekusi swipe layar tanpa sentuh.
