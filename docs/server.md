# Server

`bin/run` runs the `file-drop` image that `bin/build` made, with docker or
podman, on the laptop's own network. Ctrl-C stops it. The files it writes
belong to you.

## Folders

    data/2026-10-05/photo_2026-10-05_09-14-03.jpg   by the day the phone took it in
    data/2026-10-05/Trip/day 1/IMG_0042.jpg           a folder keeps its tree
    data/.uploads/                                    uploads in progress, and done ids

The day is the phone's: a file collected offline on Monday and sent on
Tuesday is in Monday's folder. A name already taken by other bytes gets a
number, `photo_2.jpg`; the same bytes sent twice are stored once. The file
keeps the phone's modification time.

`data/.uploads/` holds a part file per unfinished upload (removed after a
week) and a small `.done` file per finished one (removed after a month),
which lets a phone that lost the answer ask again.

## Port

`PORT` changes it, default 8080: `PORT=9000 bin/run`. A firewall needs to
let in only tcp on that port (`sudo ufw allow 8080/tcp` on Ubuntu). The
server also answers the app's udp broadcast on the same number: open udp
too and the app finds the laptop in a moment instead of a few seconds of
scanning. An app that knows the laptop on another port searches on that
port.

## Without docker

Node.js 24:

    npm install
    node src/http/index.js            # into data/
    node src/http/index.js ~/Inbox    # into another folder

The page then has no app to offer until `src/android/bin/build` has run and
`file-drop.apk` and `apk-version.txt` are copied into `src/http/public/`.

## Log

One line per event, `[group_uid][sender] details`:

    [3f9c0a1b2c3d][server_lan_url] http://192.168.1.23:8080/
    [3f9c0a1b2c3d][discovery_answer] to=192.168.1.57
    [3f9c0a1b2c3d][upload_interrupted] file=2026-10-05/video.mp4 offset=41943040 size=96468992 error=aborted
    [3f9c0a1b2c3d][upload_saved] file=2026-10-05/video.mp4 bytes=96468992 21.402s
    [3f9c0a1b2c3d][phone_report] version=apk-1 event=app-started detail="Pixel 7 android 14"

`phone_report` lines come from the app: start, failed sends, failed imports.
