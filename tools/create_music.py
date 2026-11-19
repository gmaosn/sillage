#!/usr/bin/env python3
"""La guitare du feu de camp : un arpège en ré (I–vi–IV–V), cordes pincées par Karplus-Strong, une boucle
sans couture d'environ 25 s, encodée en Ogg Vorbis par ffmpeg (libvorbis). Le moteur la lit avec son propre
décodeur (sillage.audio.Vorbis). Sans bibliothèque Python.
Usage : python3 tools/create_music.py   (depuis la racine du projet)"""
import math, os, random, struct, subprocess, tempfile, wave

RATE = 44100
BPM = 76
EIGHTH = 60 / BPM / 2
random.seed(2026)

def hz(name):
    notes = {'C': -9, 'C#': -8, 'D': -7, 'D#': -6, 'E': -5, 'F': -4, 'F#': -3, 'G': -2, 'G#': -1, 'A': 0, 'A#': 1, 'B': 2}
    n, o = name[:-1], int(name[-1])
    return 440 * 2 ** ((notes[n] + 12 * (o - 4)) / 12)

# Doigté : basse, quinte, octave, tierce, puis redescente ; la mélodie du second tour monte d'un cran.
BARS = [
    ['D3', 'A3', 'D4', 'F#4', 'A4', 'F#4', 'D4', 'A3'],
    ['B2', 'F#3', 'B3', 'D4', 'F#4', 'D4', 'B3', 'F#3'],
    ['G2', 'D3', 'G3', 'B3', 'D4', 'B3', 'G3', 'D3'],
    ['A2', 'E3', 'A3', 'C#4', 'E4', 'C#4', 'A3', 'E3'],
]
MELODY = [('F#5', 0), ('E5', 3), ('D5', 6), ('B4', 8), ('D5', 11), ('A4', 14), ('G4', 16), ('B4', 19), ('D5', 22),
          ('A4', 24), ('C#5', 27), ('E5', 30)]

def pluck(f, seconds, velocity, brightness=.5):
    """Une corde pincée : un retard de rate/f échantillons, rebouclé par une moyenne (Karplus-Strong)."""
    n = max(2, int(round(RATE / f)))
    # Le bruit du pincement, adouci (moins de brillance : un doigt plutôt qu'un médiator).
    buf = [random.uniform(-1, 1) for _ in range(n)]
    for _ in range(int(3 * (1 - brightness)) + 1):
        buf = [(buf[i] + buf[i - 1]) * .5 for i in range(n)]
    mean = sum(buf) / n
    buf = [(x - mean) * velocity for x in buf]
    # Décroissance par période : les cordes graves sonnent plus longtemps.
    decay = .9965 if f < 200 else .9945 if f < 400 else .992
    out = []
    i = 0
    total = int(seconds * RATE)
    prev = buf[-1]
    while len(out) < total:
        x = buf[i]
        y = decay * .5 * (x + prev)
        prev = x
        buf[i] = y
        out.append(x)
        i += 1
        if i == n: i = 0
    # Attaque : quelques millisecondes, pour qu'elle ne claque pas.
    for k in range(min(40, len(out))): out[k] *= k / 40
    return out

def render():
    cycle = len(BARS) * 2 * 8 * EIGHTH             # deux tours de la grille
    total = int(3 * cycle * RATE) + RATE * 3
    mix = [0.0] * total
    def add(sound, at):
        s = int(at * RATE)
        for k, v in enumerate(sound):
            if s + k < total: mix[s + k] += v
    for c in range(3):
        base = c * cycle
        for turn in range(2):
            for b, bar in enumerate(BARS):
                for e, note in enumerate(bar):
                    t = base + ((turn * len(BARS) + b) * 8 + e) * EIGHTH + random.uniform(-.008, .008)
                    v = (.55 if e == 0 else .32) * random.uniform(.85, 1.1)
                    add(pluck(hz(note), 2.6 if e == 0 else 1.6, v, .35 if e == 0 else .5), t)
        for note, eighth in MELODY:
            t = base + (len(BARS) * 8 + eighth) * EIGHTH + random.uniform(-.01, .01)
            add(pluck(hz(note), 2.2, .38 * random.uniform(.9, 1.05), .6), t)
    # Le cycle du milieu : les notes du cycle d'avant résonnent dans son début, comme celles de sa fin
    # résonneront dans le suivant : bouclé, il n'a pas de couture.
    a = int(cycle * RATE); b = int(2 * cycle * RATE)
    loop = mix[a:b]
    # Un peu de caisse : passe-bas doux, puis normalisation à −3 dB.
    y = 0.0; k = 1 - math.exp(-2 * math.pi * 3800 / RATE)
    for x in loop: y += (x - y) * k                 # un premier tour pour l'état du filtre à la fin de la boucle
    for i in range(len(loop)): y += (loop[i] - y) * k; loop[i] = y      # le vrai, parti de cet état : sans couture
    peak = max(abs(x) for x in loop)
    return [x * .7 / peak for x in loop]

def main():
    samples = render()
    out = 'demo/resources/sons/guitare.ogg'
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with tempfile.TemporaryDirectory() as d:
        wav = os.path.join(d, 'guitare.wav')
        with wave.open(wav, 'wb') as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
            w.writeframes(b''.join(struct.pack('<h', int(max(-1, min(1, x)) * 32767)) for x in samples))
        subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', wav, '-c:a', 'libvorbis', '-q:a', '3', out], check=True)
    print('%s : %.1f s, %d octets' % (out, len(samples) / RATE, os.path.getsize(out)))

if __name__ == '__main__':
    main()
