# File Drop

Select photos or any files on your Android phone, tap **Share → File Drop**,
and they land in a folder on your laptop, under their own names. No cloud, no
cable, no account: the laptop runs a small server on the home wifi, and the
phone app finds it by itself.

## Start

    bin/configure
    bin/build
    cd ~/Downloads/phone
    /path/to/file-drop/bin/run

`bin/run` starts the server in the current directory; Ctrl-C stops it. The
startup log prints the URL to open on the phone, where the page links to the
Android app.
