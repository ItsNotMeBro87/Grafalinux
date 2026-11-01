#!/bin/sh
# Removes Grafalinux Desktop Server. Your settings and tokens stay unless you pass --purge.
command -v systemctl >/dev/null 2>&1 && {
  systemctl --user disable --now grafalinux.service >/dev/null 2>&1
  rm -f "$HOME/.config/systemd/user/grafalinux.service"
  systemctl --user daemon-reload >/dev/null 2>&1
}
rm -f "${XDG_CONFIG_HOME:-$HOME/.config}/autostart/grafalinux.desktop"
rm -f "$HOME/.local/bin/grafalinux-desktop" "$HOME/.local/share/applications/grafalinux-desktop.desktop"
rm -rf "$HOME/.local/share/grafalinux"
[ "$1" = "--purge" ] && rm -rf "${XDG_CONFIG_HOME:-$HOME/.config}/grafalinux"
echo "Removed."
