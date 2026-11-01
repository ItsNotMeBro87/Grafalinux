# Grafalinux System Monitor

Customizable live dashboards of your Linux PC's sensors on an Android phone.

* `agent/` - **linmon-agent**: a tiny Python service (stdlib only) that runs on the PC and serves
  CPU, RAM, GPU, disk, network and every hwmon sensor (temperatures, fans, voltages) as JSON.
* `app/` - the Android app (plain Java, no third-party libraries, Android 7.0+ / API 24+).

Version: v0.1.0 Beta - License: GPLv3 (put the full text from
https://www.gnu.org/licenses/gpl-3.0.txt in a file called `LICENSE`).

## Agent

    python3 agent/linmon-agent.py --once              # print one snapshot
    python3 agent/linmon-agent.py --token mysecret    # serve on port 8787

See `agent/linmon-agent.service` to run it as a systemd service. Allow port 8787 from your LAN
only, e.g. `sudo ufw allow from 192.168.1.0/24 to any port 8787 proto tcp`.

## Build the APK (GitHub Actions)

Push this folder to a GitHub repository. The workflow in `.github/workflows/build.yml` builds
`app-debug.apk`; download it from the run's **Artifacts** section. No Android Studio needed.

## Status (v0.4.0 Beta)

* Servers (manual add / edit / remove), categorised sensor browser, this phone's battery.
* Several dashboards with a default; grid with padding; drag-and-drop, resize, undo/redo, lock.
* Widgets: number, gauge, bar (horizontal / vertical), line, area, donut / pie, text, clock
  (digital or analog); icons; stacked bars and rings; title above, floating or hidden.
* Colour wheel, threshold colours (optionally gradual), gradient or image backgrounds,
  units (C/F, bytes/bits, RPM/%), fans convertible between % and RPM, fullscreen, rotation.
* v0.4.0: soft cards and sharp corners are the defaults (both can be set globally and per widget),
  battery thresholds (low is bad, default 30 / 15), every dialog scrolls in landscape, duplicate widget,
  and "Find servers on my network" (agent 0.2.0 answers on UDP 8788; allow it in the firewall).
* Panel styles: Grafana or soft cards (soft is the default), set globally and overridable per widget. Grafana:
  panel header, number with sparkline, gauge with threshold bands and min / max labels, gradient /
  LCD / solid bars, smooth graphs with axis labels and a legend table (last, max).
* Experimental: wake the screen when USB power connects, or by checking the PC every few seconds.

Requires Android 5.0 or newer. Not done yet: server discovery, token pairing and HTTPS,
desktop GUI. Traffic is plain HTTP with an optional token:
use it on a trusted home network only.

## Desktop server (Grafalinux Desktop Server 0.1.0)
`desktop/` has a small GUI-less manager with a local settings page and optional tray icon: start/stop,
port, interval, trusted devices (one token per device) and autostart. Install with `desktop/install.sh`.
The plain `agent/linmon-agent.py` still works on its own (one shared `--token`).
