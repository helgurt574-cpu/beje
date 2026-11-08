#!/bin/sh
# Bêje'yi uygulama menüsüne ekler (yönetici izni gerekmez, dosya kopyalanmaz).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
ICONS="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/256x256/apps"
mkdir -p "$APPS" "$ICONS" "$HOME/.local/bin"
chmod +x "$HERE/beje" "$HERE/runtime/bin/"* "$HERE/runtime/lib/jspawnhelper" 2>/dev/null || true
cp "$HERE/beje.png" "$ICONS/beje.png"
cat > "$APPS/beje.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Bêje
Comment=Çevrimdışı dikte / Offline dictation
Exec="$HERE/beje" %F
Icon=beje
Terminal=false
Categories=Utility;Office;Accessibility;
StartupWMClass=com-example-orato-desktop-OratoAppKt
DESKTOP
ln -sf "$HERE/beje" "$HOME/.local/bin/beje"
command -v update-desktop-database >/dev/null && update-desktop-database "$APPS" 2>/dev/null || true
echo "Bêje uygulama menüsüne eklendi. Terminalden 'beje' komutuyla da açabilirsiniz."
echo "Her yerden dikte için sistem klavye kısayollarına şu komutu ekleyin:  $HERE/beje --toggle"
