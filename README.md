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

## Status (v0.3.0 Beta)

* Servers (manual add / edit / remove), categorised sensor browser.
* Several dashboards with a default; grid with padding; drag-and-drop, resize, undo/redo, lock.
* Widgets: number, gauge, bar, line, area, donut, pie, text; multi-sensor; stacked bars and
  concentric gauges; optional sensor names; per-widget text size.
* Colours, threshold colours, gradient or image backgrounds, units (C/F, bytes/bits, RPM/%),
  update interval, keep-screen-on, fullscreen, rotation.

Not done yet: server discovery, token pairing and HTTPS, Tabler icon set, desktop GUI.
Traffic is currently plain HTTP with an optional token: use it on a trusted home network only.
