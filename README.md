# Permit Print — single Android app (converter + printer)

One APK containing the fully verified permit engine (auto-detects Market Yard /
Check Post, multi-commodity, photos+OCR, QR module-perfect with verification,
58/80mm switch) AND a built-in Bluetooth ESC/POS printer driver. No RawBT, no
website needed after install. Appears in WhatsApp's Share menu.

## Build the APK (GitHub Actions - no Android Studio needed)
1. Push this folder to a GitHub repo.
2. Actions tab -> wait for "Build APK" -> download the artifact / release APK.
   The included workflow also publishes a permanent
   releases/latest/download/app-debug.apk link.

## Or in Android Studio: open folder, Build -> Build APK(s).

## Use
- Pair the printer in Android Bluetooth settings once (PIN 0000/1234).
- WhatsApp permit -> Share -> Permit Print -> preview -> Print.
- First Print asks which paired device is the printer, then remembers it.
- Internet needed on first launch (loads the PDF/QR/OCR libraries), then cached.
