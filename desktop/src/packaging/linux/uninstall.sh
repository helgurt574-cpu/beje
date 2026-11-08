#!/bin/sh
# install.sh'nin eklediklerini kaldırır (bu klasöre ve modellere dokunmaz).
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
rm -f "$DATA/applications/beje.desktop" "$DATA/icons/hicolor/256x256/apps/beje.png" \
      "$HOME/.local/bin/beje" "${XDG_CONFIG_HOME:-$HOME/.config}/autostart/beje.desktop"
echo "Bêje menüden kaldırıldı."
