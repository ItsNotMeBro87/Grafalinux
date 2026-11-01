#!/bin/sh
# Installs Grafalinux Desktop Server for the current user (no root needed). Works on any Linux with Python 3.8+.
set -e
command -v python3 >/dev/null 2>&1 || { echo "python3 is required."; exit 1; }
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' || { echo "Python 3.8 or newer is required."; exit 1; }

SRC=$(cd "$(dirname "$0")" && pwd)
DEST="$HOME/.local/share/grafalinux"
mkdir -p "$DEST" "$HOME/.local/bin" "$HOME/.local/share/applications"

cp "$SRC/grafalinux-desktop.py" "$DEST/"
if [ -f "$SRC/linmon-agent.py" ]; then cp "$SRC/linmon-agent.py" "$DEST/"
elif [ -f "$SRC/../agent/linmon-agent.py" ]; then cp "$SRC/../agent/linmon-agent.py" "$DEST/"
else echo "linmon-agent.py not found (expected in ../agent)."; exit 1; fi
chmod +x "$DEST/grafalinux-desktop.py"

cat > "$HOME/.local/bin/grafalinux-desktop" <<LAUNCH
#!/bin/sh
exec python3 "$DEST/grafalinux-desktop.py" "\$@"
LAUNCH
chmod +x "$HOME/.local/bin/grafalinux-desktop"

cat > "$HOME/.local/share/applications/grafalinux-desktop.desktop" <<ENTRY
[Desktop Entry]
Type=Application
Name=Grafalinux Desktop Server
Comment=Send this PC's stats to the Grafalinux phone app
Exec=$HOME/.local/bin/grafalinux-desktop
Terminal=false
Categories=System;Monitor;
ENTRY

echo "Installed. Start it from your app menu, or run:  grafalinux-desktop"
echo "(If the command is not found, add ~/.local/bin to your PATH.)"
echo "Optional tray icon:  pip install --user pystray pillow"
