#!/bin/sh
# Essai de bout en bout de « ./sillage dev » : un jeu temporaire tourne sans fenêtre pendant qu'on
# change son code (rechargement), y met une erreur de compilation puis une erreur à l'exécution
# (affichées, le jeu attend), change une ressource (modèle relu), puis change le moteur (redémarrage).
# Le joueur, mené par --route, doit rester où il est d'un rechargement à l'autre.
#
#   tools/essai_dev.sh            (environ deux minutes, dont la recompilation du moteur)
set -u
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
jeu=essaidev
log="$root/build/tmp/essai-dev.log"
mkdir -p build/tmp
cp project.yaml build/tmp/project.yaml.essai-dev
pid=""
fin() {
	pkill -f "dev-game $jeu" 2>/dev/null; [ -n "$pid" ] && kill "$pid" 2>/dev/null; pkill -f "socket sillage-essai-dev" 2>/dev/null
	rm -rf "$root/$jeu" "$root/build/sillage/$jeu.stamp" "$root/build/dev/$jeu" "$root/build/artifacts/CompiledJvmArtifact/${jeu}jvm"*
	cp "$root/build/tmp/project.yaml.essai-dev" "$root/project.yaml"
	[ -f "$root/build/tmp/DevOverlay.kt.essai-dev" ] && mv "$root/build/tmp/DevOverlay.kt.essai-dev" "$root/engine/src/sillage/dev/DevOverlay.kt"
}
trap fin EXIT INT TERM
echoue() { echo "ÉCHEC : $*"; echo "--- fin du journal ---"; tail -25 "$log"; exit 1; }
attend() { # motif, n-ième occurrence, secondes
	i=0
	while [ "$(grep -c -- "$1" "$log" 2>/dev/null)" -lt "$2" ]; do
		i=$((i + 1)); [ $i -gt $(($3 * 5)) ] && echoue "« $1 » (n° $2) pas vu en $3 s"
		sleep .2
	done
}

./sillage new $jeu >/dev/null || exit 1
main="$jeu/src/$jeu/Main.kt"
cp demo/resources/models/caisse.glb $jeu/resources/models/objet.glb
cat > "$main" <<'KT'
package essaidev

import sillage.*

fun main(args: Array<String>) { game("Essai", args, world) }

val world: Scene.() -> Unit = {
    ground()
    val m = model("models/objet.glb", at = vec(3, .5, -3))
    println("ESSAI version=1 triangles=" + m.mesh.triangleCount)
    player(at = vec(0, 0, 2))
}
KT
# FENETRE=1 : dans une vraie fenêtre Wayland, dans un KWin isolé (tools/kwin_isole.sh).
if [ "${FENETRE:-}" = 1 ]; then
	setsid tools/kwin_isole.sh essai-dev -- ./sillage dev $jeu --mute --frames 100000000 --route "4,-1 p100000" >"$log" 2>&1 &
else
	./sillage dev $jeu --headless --mute --frames 100000000 --route "4,-1 p100000" >"$log" 2>&1 &
fi
pid=$!
attend "ESSAI version=1" 1 240
attend "SILLAGE_DEV watching" 1 5
sleep 4                                   # le joueur arrive à (4, -1) ; le compilateur chauffe
echo "1. démarrage : ok"

sed -i 's/version=1/version=2/' "$main"
attend "SILLAGE_DEV_RELOADED code" 1 30
attend "ESSAI version=2" 1 1
ligne=$(grep "SILLAGE_DEV_RELOADED code" "$log" | head -1)
echo "2. code changé : $ligne"
echo "$ligne" | grep -Eq 'player at \(3\.[7-9][0-9]*|player at \(4\.[0-2]' || echoue "le joueur n'est pas resté vers (4, -1) : $ligne"

sed -i 's/version=2 triangles="/version=3 triangles=" + nimporte/' "$main"
attend "SILLAGE_DEV_ERROR compilation failed" 1 30
echo "3. erreur de compilation montrée : $(grep -m1 'nimporte' "$log" | cut -c1-120)"
sed -i 's/version=3 triangles=" + nimporte/version=3 triangles="/' "$main"
attend "SILLAGE_DEV_RELOADED code" 2 30
attend "ESSAI version=3" 1 1
echo "   corrigée : rechargé"

sed -i 's/    player(at = vec(0, 0, 2))/    player(at = vec(0, 0, 2))\n    onUpdate { if (time > 0f) error("panne voulue") }/' "$main"
attend "SILLAGE_DEV_ERROR IllegalStateException: panne voulue" 1 30
echo "4. erreur à l'exécution montrée : $(grep -m1 'panne voulue' "$log" | cut -c1-120)"
sed -i '/panne voulue/d' "$main"
attend "SILLAGE_DEV_RELOADED code" 4 30
echo "   corrigée : rechargé"

cp demo/resources/models/ballon.glb $jeu/resources/models/objet.glb
attend "SILLAGE_DEV_RELOADED resources" 1 30
t1=$(grep -m1 "ESSAI version=1" "$log" | sed 's/.*triangles=//')
t2=$(grep "ESSAI version=3" "$log" | tail -1 | sed 's/.*triangles=//')
echo "5. modèle changé : triangles $t1 -> $t2 ($(grep "SILLAGE_DEV_RELOADED resources" "$log" | head -1))"
[ "$t1" != "$t2" ] || echoue "le modèle n'a pas été relu"

cp -p engine/src/sillage/dev/DevOverlay.kt build/tmp/DevOverlay.kt.essai-dev
echo "// essai" >> engine/src/sillage/dev/DevOverlay.kt
attend "SILLAGE_DEV the engine changed" 1 30
mv build/tmp/DevOverlay.kt.essai-dev engine/src/sillage/dev/DevOverlay.kt
touch engine/src/sillage/dev/DevOverlay.kt
attend "SILLAGE_DEV watching" 2 400
echo "6. moteur changé : reconstruit et relancé"
echo "ESSAI DEV : tout est passé"
