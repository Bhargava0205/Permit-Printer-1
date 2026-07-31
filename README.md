# Permit Print — single Android app (converter + Bluetooth printer)

## HOW TO GET THE APK (no coding needed)

1. Upload ALL the files in this folder to a GitHub repository
   (GitHub website -> Add file -> Upload files -> drag everything in -> Commit).
2. Go to the Actions tab. The build starts on its own (or click
   "Build APK" -> "Run workflow"). Wait about 5-8 minutes for a green tick.
3. Go to the Code tab -> Releases (right side) -> "Latest APK" ->
   download app-debug.apk and open it on the Android phone to install.

Permanent link once built (share this):
https://github.com/YOUR-USERNAME/YOUR-REPO/releases/latest/download/app-debug.apk

## UPDATING THE APP LATER
Edit version.json in this repo (raise "version"), push, and let Actions build.
Every phone that opens the app then sees an "Update available" prompt.
NOTE: the repository must be PUBLIC for update checks and APK downloads to work.

## HOW TO USE THE APP
- Pair the Bluetooth thermal printer in Android Settings first (PIN 0000/1234).
- Open a permit in WhatsApp -> Share -> "Permit Print".
- The receipt preview appears -> tap Print.
  The first print asks which paired device is the printer, then remembers it.

## WHAT IT DOES
Auto-detects Market Yard and Check Post permits, reads every field exactly,
supports multi-commodity permits, accepts photos/screenshots (OCR + QR data),
rebuilds the QR module-perfect and verifies it decodes identically before
printing, and offers a 58mm / 80mm paper switch.
