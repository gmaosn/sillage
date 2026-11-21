#!/bin/sh
# Lance une tâche lourde (compilation, tests, contrôle qualité, banc) sous limites, pour que
# plusieurs sessions ou agents ne remplissent jamais la mémoire du portable :
#
#   tools/lourd.sh ./kotlin test
#   tools/lourd.sh --banc ./kotlin run -m bench -- pile 3000
#
# - mémoire : SILLAGE_LOURD_MEMOIRE (6G par défaut), sans échange ; atteindre la limite
#   arrête la tâche, et c'est un résultat : ne pas la relancer plus grosse ;
# - au plus SILLAGE_LOURD_PLACES tâches (3) à la fois pour tout l'ordinateur, les autres
#   attendent leur tour ;
# - --banc : une seule mesure à la fois (attend son tour, puis une place) ;
# - durée : SILLAGE_LOURD_DUREE (45m), priorité basse, pas de fichier core.
set -u
places=${SILLAGE_LOURD_PLACES:-3}
memoire=${SILLAGE_LOURD_MEMOIRE:-6G}
duree=${SILLAGE_LOURD_DUREE:-45m}
dossier=${XDG_RUNTIME_DIR:-/tmp}/sillage-lourd
mkdir -p "$dossier"

case "${1:-}" in
"")
	echo "usage : tools/lourd.sh [--banc] commande…" >&2
	exit 2
	;;
--dedans)
	shift
	ulimit -c 0
	exec systemd-run --user --scope -q -p MemoryMax="$memoire" -p MemorySwapMax=0 \
		--nice=10 -- timeout "$duree" "$@"
	;;
--banc)
	shift
	exec flock "$dossier/banc" "$0" "$@"
	;;
esac

while :; do
	i=1
	while [ $i -le "$places" ]; do
		flock -n -E 200 "$dossier/place$i" "$0" --dedans "$@"
		rc=$?
		if [ $rc -ne 200 ]; then exit $rc; fi
		i=$((i + 1))
	done
	sleep 2
done
