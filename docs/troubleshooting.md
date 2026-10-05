# Troubleshooting

- **"no laptop on this wifi"**: is `bin/run` running, and is the phone on
  the same wifi? The firewall must allow tcp 8080 (`sudo ufw allow
  8080/tcp` on Ubuntu). Check by opening the URL `bin/run` printed in
  Chrome on the phone. A guest network or "AP isolation" keeps devices
  apart: no app gets through that.
- **Not found on a big network**: the scan covers the 254 addresses next
  to the phone's own. On a larger network, tap **laptop** and type the
  address `bin/run` printed; the app remembers it.
- **Files stay waiting on mobile data**: by design, the app sends on wifi
  only.
- **The laptop's address changed** (DHCP): nothing to do, the app searches
  again. A DHCP reservation in the router makes it stable.
- **Update fails to install** ("app not installed"): the apk was signed by
  another keystore, see [android.md](android.md#the-keystore).
- The app reports its start and every failure to the server console as
  `[...][phone_report]` lines: check them first.
