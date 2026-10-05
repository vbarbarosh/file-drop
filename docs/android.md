# Android app

Sources in `src/android/`, built without Gradle, like receipt-drop:
`aapt2` → `javac` → `d8` → `zipalign` → `apksigner`. `bin/configure`
downloads the toolchain once (JDK 17, build-tools 34, platform 34, about
350 MB into `src/android/toolchain/`); `src/android/bin/build` makes
`src/android/file-drop.apk`. No AndroidX, no libraries: Android 8.0
(API 26) and newer.

## Parts

- `Outbox`, `OutboxItem`: the files waiting on the phone, one folder each.
- `Uploader`, `Sender`, `Server`, `Discovery`, `Scan`: finding the laptop
  and sending; plain Java, tested on the JVM by `src/android/bin/test`.
- `SendJob`: the background job (JobScheduler) that empties the outbox.
- `Importer`: copies picked, shared and recorded files into the outbox.
- `MainActivity`, `ShareActivity`: the screen and the share target.
- `FilesProvider`: where the camera writes a photo, and where the
  installer reads an update.

## Publishing an update

1. Raise `android:versionCode` (and `versionName`) in
   `src/android/AndroidManifest.xml`.
2. `bin/build`, then restart `bin/run`.

The image serves the new apk and its version at `/info`; every app that
connects offers the download.

## The keystore

`src/android/keystore` is made by the first build and signs every apk.
Android installs an update only over an app signed with the same key, so
the keystore must never be lost: back it up. It is not in git. A clone
without it makes a new key, and the phone then has to uninstall the old
app first (and with it the files still waiting in its outbox).

## Tested here, and not

`src/android/bin/test` runs the sending core against the real server:
files waiting with no laptop and leaving once it comes, the laptop found
by its udp answer, uploads through a proxy that cuts every connection after
700 kB, a laptop that vanishes mid-file and comes back, a job stopped
mid-file. Android Lint reports no errors.

The app itself was run in an Android 9 emulator (software emulation, no
KVM), against the server from `bin/run`: send text; the laptop away (the
text waits, then goes by itself once `bin/run` is back); choose files;
choose folder (the tree arrives); share a photo; take photos (the camera
opens again after the shot); record voice (a playable `.m4a`); the dark
theme; the update offer, download and install of version 2 over version 1.

Not run: a real phone, Android 10 to 14 (the Android 11 image boot-looped
under software emulation), the notification permission prompt (Android
13+), and the udp search on a real wifi: the emulator's network is its
own, so there the address was typed. The search itself is tested on the
JVM and against the docker container.

## Trying it in an emulator

The emulator reaches the laptop through adb, not the wifi:

    adb reverse tcp:8080 tcp:8080

then **laptop** → `127.0.0.1:8080` in the app. (`10.0.2.2` answers ping
but did not pass tcp in this setup.)
