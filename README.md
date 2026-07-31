# AMC Permit Print — single Android app (converter + Bluetooth printer)

Auto-detects Market Yard and Check Post permits, reads every field exactly,
rebuilds the permit QR module-perfect (verified to decode identically before
printing), and prints to a 58mm or 80mm Bluetooth thermal printer.

--------------------------------------------------------------------
ONE-TIME SETUP ON GITHUB (do this once)
--------------------------------------------------------------------
1. Make the repository PUBLIC
   Settings -> scroll to the bottom -> Change visibility -> Public.
   (Needed so phones can download the APK and check for updates.)

2. Add the signing key so future updates install over the old app
   Settings -> Secrets and variables -> Actions -> New repository secret
     Name:   KEYSTORE_B64
     Value:  paste the whole contents of KEYSTORE_BASE64.txt
   Then add three more secrets:
     KEYSTORE_PASSWORD = amcpalamaner
     KEY_ALIAS         = permitprint
     KEY_PASSWORD      = amcpalamaner
   Keep permitprint-signing-key.jks safe. If it is lost, users must
   uninstall and reinstall to get future updates.

3. Actions tab -> the build runs on every push (about 5-8 minutes).
   The APK is published at:
   https://github.com/USER/REPO/releases/latest/download/app-permit-print.apk

--------------------------------------------------------------------
RELEASING AN UPDATE LATER
--------------------------------------------------------------------
Change BOTH of these to the new number, then push:
  * app/src/main/java/com/example/permitprint/MainActivity.kt -> APP_VERSION
  * version.json -> "version"  (and write what changed in "notes")
Every phone shows an "Update available" prompt the next time the app opens.

--------------------------------------------------------------------
USING THE APP
--------------------------------------------------------------------
* Pair the thermal printer once: Android Settings -> Bluetooth (PIN 0000/1234).
* Open the app -> "Printer" button -> pick your printer (it is remembered).
* Choose 58mm or 80mm to match the printer.
* WhatsApp -> permit PDF -> Share -> AMC Permit Print -> Print.

Works with no internet after installation. Reading text out of photos
(OCR) is the only feature that needs a connection the first time.
