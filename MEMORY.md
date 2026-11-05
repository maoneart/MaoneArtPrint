# Persistent Memory — MaoneArt Print (`/sdcard/www/MaoneArtPrint`)

> **Catatan Kerja Persisten Antigravity AI — Proyek MaoneArt Print**
> Terakhir diperbarui: 5 November 2026

---

## 📌 Ringkasan Arsitektur
- **Application ID**: `id.maoneart.print`
- **Target Device**: Android Tablet & Smartphone (`minSdk 26`, `targetSdk 34`, Jetpack Compose Material 3).
- **Keystore**: Permanent MaoneArt Release Keystore (`keystore/release.jks`, alias `maoneart`, berlaku hingga 2054).
- **Komponen Inti**:
  1. `UsbOtgPrinterManager.kt`: Mengelola `UsbManager`, izin USB Host OTG, deteksi Epson (`VID 0x04B8`) & `USB_CLASS_PRINTER`, pengiriman bulk transfer, serta fitur Eject/Reset (`ESC @` + Form Feed).
  2. `EpsonUsbProtocolEngine.kt`: Mesin protokol byte-level untuk **ESC/P-R** (RGB & Monochrome PackBits RLE 360 DPI dengan auto-skip baris putih), **ESC/P2** (Floyd-Steinberg dithering + RLE), dan **ESC/POS** (`GS v 0` untuk struk kasir 58mm/80mm).
  3. `DocumentRenderEngine.kt`: Dekoder multi-halaman `PdfRenderer` untuk PDF, dekoder JPG/PNG/WebP (komposit latar putih untuk PNG transparan), generator Pas Foto 3×4 & 4×6, serta halaman Test Print Kalibrasi CMYK.
  4. `MaoneArtPrintService.kt`: Implementasi `android.printservice.PrintService` agar printer USB OTG Epson muncul di menu Print bawaan sistem Android.
  5. `MainActivity.kt`: Studio UI responsif (Split-View 2 panel untuk Tablet & Tab View untuk HP) dengan fitur pilih halaman, filter rentang halaman, zoom pratinjau, dan MaoneArt Glassmorphic Modal.
