# Phone

## Install

1. Phone and laptop on the same wifi; `bin/run` running on the laptop.
2. Open the URL `bin/run` printed (e.g. `http://192.168.1.23:8080/`) in
   Chrome on the phone, and tap **Install the Android app**.
3. Allow Chrome to install unknown apps when Android asks (once), install,
   open. Allow notifications: they show what is being sent.

The app finds the laptop on the wifi by itself. **laptop** in the top right
corner sets the address by hand, for a network that blocks the search.

## Sending

- **Take photos**: the phone's camera opens. After each shot it opens
  again for the next one; Back ends the series. The photos are not kept in
  the gallery: they exist in the app until the laptop has them.
- **Record voice**: tap to start, tap again to stop. The recording goes as
  `voice_<time>.m4a`.
- **Choose files**: the system picker; select several at once.
- **Choose folder**: a whole folder with its subfolders; the laptop keeps
  the tree, `data/<day>/<folder>/...`.
- **Send text**: what you typed goes as `note_<time>.txt`.
- From any other app: **Share → File Drop** (several photos in the gallery,
  a PDF, a link).

The screen shows the laptop it found, how many files wait, and what is
being sent. The switch next to **laptop** turns the screen light or dark.

## When the laptop is away

Every file is first copied into the app, then sent. With no laptop on the
wifi the files wait on the phone and the app tries again by itself, sooner
at first, then every five minutes, also after a reboot; it never uses
mobile data. A file is deleted from the phone only after the laptop
confirms it. A connection lost mid-file goes on from where it stopped.

Files picked with **Choose files**, **Choose folder** or **Share** are
copied; the originals stay where they were.

## Updates

When the laptop serves a newer app, the app shows a notification and,
when opened, offers **Download**. Android then asks to confirm the
install; an app cannot update itself without that tap.
