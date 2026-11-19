#!/bin/sh
# Fichiers d'essai du décodeur Vorbis : des signaux variés (sons purs, glissando, bruit, silence, attaques),
# encodés par libvorbis (ffmpeg) à plusieurs qualités, débits et nombres de canaux ; la référence est le
# décodage de libvorbisfile (tools/vorbis_reference.py), en flottants 32 bits.
# Usage : sh tools/vorbis_fixtures.sh   (depuis la racine du projet ; ffmpeg avec libvorbis)
set -e
out=engine/test/fixtures/vorbis
mkdir -p "$out"
f() { ffmpeg -hide_banner -loglevel error -y "$@"; }
# Stéréo : à gauche un glissando et des clics, à droite un son pur, du bruit doux puis un silence.
stereo='aevalsrc=exprs=0.5*sin(2*PI*(200*t+900*t*t))+0.3*lt(mod(t\,0.25)\,0.004)|0.4*sin(2*PI*660*t)*lt(t\,0.5)+0.05*(random(0)-0.5)*lt(t\,0.6):s=44100:d=0.7'
mono='aevalsrc=exprs=0.6*sin(2*PI*330*t)*exp(-3*t)+0.2*sin(2*PI*2500*t)*gt(t\,0.4):s=22050:d=1.0'
f -f lavfi -i "$stereo" -c:a libvorbis -q:a 4 "$out/stereo-q4.ogg"
f -f lavfi -i "$stereo" -c:a libvorbis -q:a -1 "$out/stereo-qm1.ogg"
f -f lavfi -i "$stereo" -c:a libvorbis -q:a 10 "$out/stereo-q10.ogg"
f -f lavfi -i "$mono" -c:a libvorbis -q:a 2 "$out/mono-22k.ogg"
f -f lavfi -i "aevalsrc=exprs=0.5*sin(2*PI*1000*t):s=48000:d=0.03" -ac 1 -c:a libvorbis -q:a 5 "$out/court.ogg"
# La référence : libvorbis lui-même (le décodeur de ffmpeg rogne mal le début et la fin des flux).
python3 tools/vorbis_reference.py "$out"/*.ogg
ls -l "$out"
