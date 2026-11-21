#!/bin/sh
# Essai fenêtré de l'interface dans un KWin isolé, jamais sur le bureau : kwin_wayland --virtual sur sa propre
# prise, ses réglages (disposition « fr ») et ses caches dans build/tmp/kwin. Le jeu (sillage.qa.FenetreKt)
# s'y ouvre ; tools/kwin_clavier.py y tape de vraies touches AZERTY et clique par le protocole de KWin.
# Usage, depuis la racine du projet, après ./kotlin build -m qa :
#   tools/lourd.sh sh tools/kwin_essai_interface.sh      → build/tmp/fenetre.log, build/tmp/fenetre-pause.png
set -u
cd "$(dirname "$0")/.."
t=$PWD/build/tmp/kwin
if [ "${1:-}" = "--dedans" ]; then
	export WAYLAND_DISPLAY=sillage-texte
	unset DISPLAY
	log=build/tmp/fenetre.log
	: > $log
	./kotlin run -m qa --main-class sillage.qa.FenetreKt --jvm-args="--enable-native-access=ALL-UNNAMED -Xmx2g" -- build/tmp/fenetre-pause.png >> $log 2>&1 &
	jeu=$!
	python3 tools/kwin_clavier.py $log >> $log 2>&1
	echo "SCENARIO_FIN $?" >> $log
	wait $jeu
	echo "JEU_FIN $?" >> $log
	exit 0
fi
mkdir -p "$t/config" "$t/data" "$t/cache" "$t/state"
printf '[Layout]\nLayoutList=fr\nUse=true\n' > "$t/config/kxkbrc"
exec env XDG_CONFIG_HOME="$t/config" XDG_DATA_HOME="$t/data" XDG_CACHE_HOME="$t/cache" XDG_STATE_HOME="$t/state" \
	KWIN_WAYLAND_NO_PERMISSION_CHECKS=1 dbus-run-session -- env -u DISPLAY \
	kwin_wayland --virtual --no-lockscreen --width 1280 --height 800 --socket sillage-texte --exit-with-session "sh $PWD/tools/kwin_essai_interface.sh --dedans"
