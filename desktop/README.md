# Grafalinux Desktop Server (v0.1.0)

Start/stop the stats server, set the port and update interval, manage trusted devices (one token each)
and start it automatically. Python 3.8+, no extra packages needed.

## Install (any Linux distro, no root)
    ./install.sh
    grafalinux-desktop          # opens the settings page (tray icon if pystray + pillow are installed)

## How it works
* The monitoring is `linmon-agent.py` (still usable on its own, see ../agent).
* Settings page: http://127.0.0.1:8790 (this PC only, protected by a key in ~/.config/grafalinux/admin.key).
* First start creates a device called "My phone". Type its token into the phone app.
* Allow the port (default TCP 8787) and UDP 8788 (phone search) in your firewall for your home network.
* Settings and tokens: ~/.config/grafalinux/config.json (readable by you only).

## Start automatically
Use the Startup card on the settings page. With systemd it creates a user service; the second box also
runs `loginctl enable-linger` so it starts at boot before you log in. Without systemd it uses an XDG
autostart entry. Both can be switched off again on the same page, or with `uninstall.sh`.

## Tray icon (optional)
    pip install --user pystray pillow
GNOME needs the "AppIndicator" extension to show tray icons. Without a tray, the page opens in the browser.
