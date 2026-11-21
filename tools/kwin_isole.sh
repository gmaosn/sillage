#!/bin/sh
# Lance une commande dans un KWin Wayland sans écran, isolé du bureau du propriétaire : sa
# configuration (XDG_CONFIG_HOME…) vit dans build/tmp/kwin-<nom>, jamais dans ~/.config.
#
#   tools/kwin_isole.sh nom [--scale 2] -- commande…
#
# La commande voit WAYLAND_DISPLAY=sillage-<nom> ; KWin s'arrête avec elle.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
nom=$1; shift
scale=1
if [ "${1:-}" = "--scale" ]; then scale=$2; shift 2; fi
[ "${1:-}" = "--" ] && shift
base="$root/build/tmp/kwin-$nom"
rm -rf "$base"; mkdir -p "$base/config" "$base/data" "$base/cache" "$base/state"
cat > "$base/config/kwinoutputconfig.json" <<JSON
[{"name":"outputs","data":[{"connectorName":"Virtual-0","scale":$scale,"mode":{"width":$((1280*scale)),"height":$((800*scale)),"refreshRate":60000}}]},
 {"name":"setups","data":[{"lidClosed":false,"outputs":[{"enabled":true,"outputIndex":0,"position":{"x":0,"y":0},"priority":0}]}]}]
JSON
# La commande, ses arguments gardés tels quels (espaces compris), lancée depuis le dossier courant.
{
	printf '#!/bin/sh\ncd '\''%s'\'' || exit 1\nexec' "$(pwd)"
	for a in "$@"; do printf " '%s'" "$(printf %s "$a" | sed "s/'/'\\\\''/g")"; done
	echo
} > "$base/run.sh"
chmod +x "$base/run.sh"
exec env -u DISPLAY -u WAYLAND_DISPLAY XDG_CONFIG_HOME="$base/config" XDG_DATA_HOME="$base/data" XDG_CACHE_HOME="$base/cache" XDG_STATE_HOME="$base/state" \
	dbus-run-session -- kwin_wayland --virtual --no-lockscreen --width 1280 --height 800 --socket "sillage-$nom" \
	--exit-with-session "$base/run.sh"
