# How sending works

## The outbox

Whatever the app sends is first copied into its own storage, the outbox:
one folder per file, `data` with the bytes and `meta.properties` with the
path on the laptop, the size, the file's time and the day it was added. A folder is
built under a `.tmp-` name and renamed when complete, so a half-copied
file is never sent. Names start with the time they were added: files go
in that order.

`SendJob` empties the outbox. Android starts it when the phone is on wifi,
also after a reboot. With no laptop it plans the next try (15 s, 30 s,
1 min, 2 min, then every 5 min); a new file, or opening the app, starts
over at once. Android stops a job after about ten minutes; the upload then
continues in the next run.

## Uploads

Each outbox item has an id, `20261005_091403_123_0001_1a2b3c4d`.

    GET  /upload/:id     -> x-upload-offset: bytes the server holds
                            x-file-saved: where it went, once done
    POST /upload/:id        body: the file from x-upload-offset on
         x-upload-offset    must equal what the server holds, else 409
         x-file-size        the whole size
         x-file-path        relative path, encodeURIComponent
         x-file-mtime       ms since 1970
         x-file-day         YYYY-MM-DD, the day it was added; not used now

The server appends the body to `data/uploads/<id>.part`. A lost
connection leaves what arrived; the app asks `GET` and goes on from there.
The last byte moves the part to `<path>` in the drop folder (a taken name
gets `_1`, `_2`; the same bytes are kept once) and records the id as
done, so repeating a finished upload changes nothing. Only then does the
app delete the file from the phone.

A server that hears nothing from a connection for a minute closes it,
and a new connection can take the upload over.

## Finding the laptop

The app tries the last address that answered. When it does not answer:

1. It sends `file-drop?` over udp to the broadcast addresses of the wifi,
   on the same port. The server answers with
   `{"name":"file-drop","host":...,"port":...}`, and the answer's source
   address is the laptop.
2. No answer in 1.5 s (a firewall that lets only tcp in, a router that
   drops broadcasts): it scans the phone's own network. Each of the 254
   addresses next to its own gets `GET /info` on the port, 64 at a time,
   in a few seconds. Only tcp is needed.

**laptop → Find** in the app runs both and lists every File Drop server it
found. `GET /info` confirms a server and tells the apk version it serves.
