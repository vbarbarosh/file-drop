# Troubleshooting

- **"no laptop on this wifi"**: is `bin/run` running, and is the phone on
  the same wifi? A firewall must allow the port on tcp and udp (`sudo ufw
  allow 8080` on Ubuntu). A guest network or "AP isolation" keeps devices
  apart: no app gets through that.
- **Found by hand only**: some routers drop broadcasts. Tap **laptop** and
  type the address `bin/run` printed; the app remembers it.
- **Files stay waiting on mobile data**: by design, the app sends on wifi
  only.
- **The laptop's address changed** (DHCP): nothing to do, the app searches
  again. A DHCP reservation in the router makes it stable.
- **Update fails to install** ("app not installed"): the apk was signed by
  another keystore, see [android.md](android.md#the-keystore).
- The app reports its start and every failure to the server console as
  `[...][phone_report]` lines: check them first.
