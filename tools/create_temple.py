#!/usr/bin/env python3
"""Les modèles de la seconde démo, « L'oracle endormi » : un temple dorique en ruine sur une île, la nuit.
Sans Blender ni bibliothèque tierce (tools/glbkit.py). Repère glTF : Y vers le haut, mètres ; le temple est
au centre, son grand axe le long de Z, sa façade vers +Z (d'où l'on arrive).

Rien n'est une boîte nue : les fûts sont cannelés et galbés, les murs montés en assises de blocs taillés,
les pierres tombées érodées, la statue drapée, les arbres bosselés ; chaque sommet porte une teinte
(salissures au pied, coulures, mousse sur les dessus, creux assombris).

Fichiers produits dans temple/resources/models :
  ile.glb      le sol : plateau, pente vers le lac, bassin devant le temple, voie dallée, rochers du rivage
  temple.glb   degrés, colonnes debout ou brisées, entablement, fronton arrière, cella, autel, blocs épars
  tambour.glb  un tambour de colonne (ceux qui gisent devant la façade, et qui se relèveront)
  chapiteau.glb le chapiteau de la colonne à relever
  brasero.glb  un trépied de bronze et sa vasque
  statue.glb   une figure drapée sans tête sur son socle
  cypres.glb, olivier.glb  les arbres
"""
from pathlib import Path
import math, random
import glbkit
from glbkit import *

ROOT = Path(__file__).resolve().parents[1]
glbkit.OUT = ROOT / 'temple/resources/models'
glbkit.OUT.mkdir(parents=True, exist_ok=True)
random.seed(7)

# Couleurs linéaires : un marbre d'os, des verts éteints, du bronze ; la nuit et la brume font le reste.
glbkit.COLORS.update({
    'marbre': (.62, .60, .54), 'marbre_use': (.52, .51, .47), 'marbre_sombre': (.30, .30, .29), 'mousse': (.16, .24, .10),
    'dalle': (.42, .41, .38), 'herbe': (.20, .24, .11), 'herbe_seche': (.30, .29, .15), 'sable': (.46, .42, .32), 'roche': (.27, .26, .25),
    'bronze': (.20, .13, .05), 'bronze_vert': (.10, .24, .19), 'braise': (1.0, .32, .06), 'cendre': (.07, .07, .07),
    'cypres': (.035, .09, .05), 'olivier': (.20, .26, .19), 'tronc': (.14, .10, .07), 'lierre': (.07, .17, .06),
    # Ce qui se voit sans faire obstacle (dalles, parement des degrés, gravats) : on marche sur des volumes nets, dessous.
    'parement': (.52, .51, .47), 'parement_clair': (.62, .60, .54), 'gravat': (.52, .51, .47),
    'touffe': (.22, .26, .12), 'fleur_blanche': (.80, .80, .74), 'fleur_tige': (.12, .20, .08), 'or': (.90, .62, .10),
})

# ---------------------------------------------------------------- bruit et teintes
def hash3(i, j, k):
    h = (i * 374761393 + j * 668265263 + k * 2147483647) & 0xffffffff
    h = ((h ^ (h >> 13)) * 1274126177) & 0xffffffff
    return ((h ^ (h >> 16)) & 0xffff) / 65535.0
def vnoise(x, y, z):
    """Bruit de valeur lissé, 0..1."""
    i, j, k = math.floor(x), math.floor(y), math.floor(z); fx, fy, fz = x - i, y - j, z - k
    sx, sy, sz = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy), fz * fz * (3 - 2 * fz)
    def L(a, b, t): return a + (b - a) * t
    c = [[[hash3(i + a, j + b, k + cc) for cc in (0, 1)] for b in (0, 1)] for a in (0, 1)]
    return L(L(L(c[0][0][0], c[1][0][0], sx), L(c[0][1][0], c[1][1][0], sx), sy), L(L(c[0][0][1], c[1][0][1], sx), L(c[0][1][1], c[1][1][1], sx), sy), sz)
def fbm(x, y, z, o=3):
    s = 0; a = .5; f = 1
    for _ in range(o): s += a * vnoise(x * f, y * f, z * f); a *= .5; f *= 2.03
    return s / (1 - .5 ** o)
def smooth(a, b, t): t = min(1.0, max(0.0, (t - a) / (b - a))); return t * t * (3 - 2 * t)
def mix3(a, b, t): return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)

STONE = ('marbre', 'marbre_use', 'marbre_sombre', 'dalle', 'parement', 'parement_clair', 'gravat')
def weather(base):
    """Teinte d'une pierre posée à la hauteur `base` : sombre au contact du sol, tachée par plaques, striée de
    coulures, verdie de mousse sur les dessus et au pied."""
    def f(p, n, mat):
        if mat not in STONE: return (1, 1, 1)
        x, y, z = p
        k = .84 + .30 * fbm(x * .55, y * .55, z * .55)                      # grandes plaques
        k *= .92 + .16 * vnoise(x * 3.1, y * 3.1, z * 3.1)                   # grain
        k *= 1 - .38 * math.exp(-max(0.0, y - base) / .32)                  # le pied, sale
        streak = vnoise(x * 2.3 + 7, 0, z * 2.3) ; k *= 1 - .22 * smooth(.58, .9, streak) * (1 - abs(n[1]))   # coulures sur les faces droites
        c = (k * 1.02, k, k * .97)
        moss = smooth(.45, .75, fbm(x * .8 + 3, y * .8, z * .8 + 9)) * (smooth(.5, .95, n[1]) * .75 + .35 * math.exp(-max(0.0, y - base) / .5))
        return mix3(c, (.42, .62, .30), min(.8, moss))
    return f

def sweep(m, pts, radii, mat, n=12, round_end=True, end_mat=None):
    """Un membre d'un seul tenant : une peau continue le long d'un chemin (aucun joint entre les tronçons), le
    premier bout ouvert (il part de l'intérieur de ce qui le porte), le dernier arrondi ou tranché net."""
    rings = []; u = None
    for i, c in enumerate(pts):
        a = pts[max(0, i - 1)]; b = pts[min(len(pts) - 1, i + 1)]; t = norm(sub(b, a))
        if u is None:
            ref = (0, 1, 0) if abs(t[1]) < .9 else (1, 0, 0); u = norm(cross(t, ref))
        else:
            u = norm(sub(u, mul(t, dot(u, t))))                    # le repère suit le chemin sans vriller
        v = cross(t, u)
        rings.append(([add(c, add(mul(u, math.cos(k * TAU / n) * radii[i]), mul(v, math.sin(k * TAU / n) * radii[i]))) for k in range(n)], t))
    if round_end:                                                  # une calotte : deux anneaux qui se resserrent, puis la pointe
        c = pts[-1]; t = rings[-1][1]; r = radii[-1]; v = cross(t, u)
        for ang in (math.pi / 5, 2 * math.pi / 5):
            cc = add(c, mul(t, math.sin(ang) * r)); rr = math.cos(ang) * r
            rings.append(([add(cc, add(mul(u, math.cos(k * TAU / n) * rr), mul(v, math.sin(k * TAU / n) * rr))) for k in range(n)], t))
    for (ra, _), (rb, _) in zip(rings, rings[1:]):
        for k in range(n):
            j = (k + 1) % n
            m.quad_out([ra[k], rb[k], rb[j], ra[j]], mat, mul(add(ra[k], ra[(k + n // 2) % n]), .5))
    last = rings[-1][0]; t = rings[-1][1]
    tip = add(pts[-1], mul(t, radii[-1])) if round_end else pts[-1]
    for k in range(n): m.face([last[k], last[(k + 1) % n], tip], end_mat or mat, t)

# ---------------------------------------------------------------- île
SIZE = 78.0; CELLS = 150
def noise(x, z): return (math.sin(x * .37 + 1.3) * math.cos(z * .41 - .7) + .5 * math.sin(x * .9 - z * .7) + .3 * math.cos(x * 1.7 + z * 1.3)) / 1.8
def basin(x, z):
    """Le bassin devant le temple : 1 au fond, 0 dehors, bords raides."""
    return (1 - smooth(3.0, 3.5, abs(x))) * (1 - smooth(3.6, 4.1, abs(z - 19.0)))
def height(x, z):
    r = math.hypot(x, z * .92)
    rough = smooth(14.0, 22.0, r)                                  # plat sous le temple et son parvis
    y = noise(x, z) * .22 * rough + noise(x * 2.3, z * 2.1) * .06 * rough + (fbm(x * .9, 0, z * .9) - .5) * .12 * smooth(8.0, 13.0, r)
    y -= smooth(24.0, 35.0, r) * 1.6                               # la pente vers le lac (niveau −0,35)
    y -= smooth(34.0, 39.0, r) * 3.0
    return y * (1 - basin(x, z)) - .55 * basin(x, z)
def ground_mat(x, z, y):
    if basin(x, z) > .5: return 'dalle'
    if abs(x) < 2.1 and 11.6 < z < 31.5 and not (abs(z - 19) < 4.6): return 'dalle'        # la voie dallée, de part et d'autre du bassin
    if 2.9 < abs(x) < 4.9 and abs(z - 19) < 5.2: return 'dalle'                             # les quais du bassin
    if abs(x) < 4.9 and 4.0 < abs(z - 19) < 5.2: return 'dalle'
    if y < -.15: return 'sable'
    return 'herbe' if noise(x * .6 + 5, z * .6) > -.15 else 'herbe_seche'
def ground_tint(p, n, mat):
    x, y, z = p
    k = .72 + .5 * fbm(x * .35, 0, z * .35) ; k *= .9 + .2 * vnoise(x * 2.7, 0, z * 2.7)
    if mat == 'dalle': return (k * .95, k * .95, k * .93)
    if mat == 'sable': w = smooth(-.2, -.36, y); return mix3((k, k, k), (.55, .58, .6), w)   # mouillé près de l'eau
    worn = smooth(.55, .8, fbm(x * .5 + 11, 0, z * .5))                                       # plaques de terre nue
    return mix3((k * .95, k, k * .9), (1.25, 1.0, .72), worn * .6)
ile = Mesh(); ile.shade = ground_tint
step = SIZE / CELLS
def P(i, j): x = -SIZE / 2 + i * step; z = -SIZE / 2 + j * step; return (x, height(x, z), z)
for i in range(CELLS):
    for j in range(CELLS):
        a, b, c, d = P(i, j), P(i + 1, j), P(i + 1, j + 1), P(i, j + 1)
        if max(a[1], b[1], c[1], d[1]) < -2.5: continue                                     # sous le lac, loin : rien à dessiner
        m = ground_mat((a[0] + c[0]) / 2, (a[2] + c[2]) / 2, (a[1] + c[1]) / 2)
        ile.tri(a, d, c, m); ile.tri(a, c, b, m)

def rock(m, x, y, z, sx, sy, sz, seed, mat='roche', n=14, rings=9, rough=.28):
    """Une pierre : une boule déformée par du bruit, aplatie dessous."""
    def p(i, j):
        a = math.pi * i / rings; b = TAU * j / n
        d = (math.sin(a) * math.cos(b), math.cos(a), math.sin(a) * math.sin(b))
        k = 1 + rough * (fbm(d[0] * 1.7 + seed, d[1] * 1.7, d[2] * 1.7 + seed * .37) - .5) * 2
        yy = d[1] * sy * k
        return (x + d[0] * sx * k, y + max(yy, -sy * .35), z + d[2] * sz * k)
    for i in range(rings):
        for j in range(n): m.quad_out([p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)], mat, (x, y, z))
ile.shade = lambda p, n, mat: (lambda k: (k, k, k * 1.03))(.7 + .5 * fbm(p[0] * .9, p[1] * .9, p[2] * .9) - .25 * math.exp(-max(0, p[1] + .35) / .25))
for q in range(46):                                                # les rochers du rivage, et quelques-uns dans l'herbe
    a = q * 2.399 + .3; r = 27.5 + 5 * hash3(q, 1, 0) if q % 4 else 15 + 9 * hash3(q, 2, 0)
    x, z = math.cos(a) * r, math.sin(a) * r / .92
    if abs(x) < 6 and z > 10: continue
    s = .35 + 1.0 * hash3(q, 3, 0) ** 2
    rock(ile, x, height(x, z) + s * .18, z, s * (1 + .5 * hash3(q, 4, 0)), s * .7, s * (1 + .5 * hash3(q, 5, 0)), q * 1.7)
single(ile, 'ile.glb')

# ---------------------------------------------------------------- pierres taillées
def ashlar(m, x, y, z, w, h, d, mat, seed=0, chip=.012, n=10, rings=6, e=.09):
    """Un bloc taillé : arêtes adoucies, faces à peine bosselées, un rien de travers."""
    cx, cy, cz = x, y + h / 2, z; a, b, c = w / 2, h / 2, d / 2
    def sp(v, mm): return math.copysign(abs(v) ** mm, v)
    def p(i, j):
        th = -math.pi / 2 + math.pi * i / rings; ph = -math.pi + TAU * j / n
        px = a * sp(math.cos(th), e) * sp(math.cos(ph), e); py = b * sp(math.sin(th), e); pz = c * sp(math.cos(th), e) * sp(math.sin(ph), e)
        k = 1 + chip * (fbm(px * 2.2 + seed, py * 2.2, pz * 2.2 + seed * .61) - .5) * 2 / max(.2, min(a, b, c))
        return (round(cx + px * k, 5) + 0.0, round(cy + py * k, 5) + 0.0, round(cz + pz * k, 5) + 0.0)   # arrondi : les deux bords de la couture tombent au même point
    for i in range(rings):
        for j in range(n): m.quad_out([p(i, j % n), p(i, (j + 1) % n), p(i + 1, (j + 1) % n), p(i + 1, j % n)], mat, (cx, cy, cz))

T = Mesh()
TOP = .9                                                            # le haut du stylobate
T.shade = weather(0.0)
# Trois degrés de 30 cm : un massif (ce sur quoi l'on marche), et ses blocs de parement, joints visibles.
for k in range(3):
    grow = (2 - k) * .5; W = 11.6 + 2 * grow; D = 23.6 + 2 * grow; y = k * .3
    T.box(0, y, 0, W - .06, .295, D - .06, 'marbre_use')
    mat = 'parement' if k < 2 else 'parement_clair'
    for side in (-1, 1):
        nz = int(D / 1.55); lz = D / nz
        for q in range(nz): ashlar(T, side * (W / 2 - .2), y, -D / 2 + lz * (q + .5), .42, .3, lz - .012, mat, seed=k * 31 + q + side)
        nx = int((W - .8) / 1.55); lx = (W - .8) / nx
        for q in range(nx): ashlar(T, -W / 2 + .4 + lx * (q + .5), y, side * (D / 2 - .2), lx - .012, .3, .42, mat, seed=k * 57 + q + side * 3)
# Le dallage du stylobate : des dalles, pas une nappe.
for i in range(7):
    for j in range(15):
        x = -5.4 + i * 1.8; z = -11.2 + j * 1.6
        T.mul = .9 + .2 * hash3(i, j, 5)
        ashlar(T, x, TOP - .05, z, 1.78, .06, 1.58, 'parement_clair' if (i + j) % 3 else 'parement', seed=i * 7 + j, chip=.004, n=8, rings=4, e=.05)
T.mul = 1.0

COLS_X = [-5.0, -3.0, -1.0, 1.0, 3.0, 5.0]
COLS_Z = [-10.5 + 2.1 * k for k in range(11)]
H = 5.4                                                             # fût
FLUTES = 20; PER = 3
def flute_ring(x, y, z, r, tilt=0.0, phase=0.0):
    """Un anneau de fût : vingt cannelures creusées entre des arêtes vives."""
    pts = []; darks = []
    for i in range(FLUTES * PER):
        t = (i % PER) / PER; a = i * TAU / (FLUTES * PER) + phase
        rr = r * (1 - .052 * math.sin(math.pi * t) ** .8)
        pts.append((x + math.cos(a) * rr, y + tilt * math.cos(a + 1.0) * rr, z + math.sin(a) * rr)); darks.append(1 - .30 * math.sin(math.pi * t))
    return pts, darks
def shaft(m, x, z, h, base=TOP, mat='marbre', broken=False):
    """Un fût dorique : cannelé, galbé, monté en tambours dont on voit les joints ; brisé, il finit en cassure."""
    def r(t): return .52 - .10 * t - .016 * math.sin(math.pi * t)   # 0,52 en bas, 0,42 en haut, un léger renflement
    ys = []
    y = 0.0
    while y < h - 1e-6:                                             # un joint tous les 1,08 m
        nxt = min(h, (math.floor(y / 1.08 + 1e-6) + 1) * 1.08)
        ys += [(y + .012, .78), (y + .2, 1.0), ((y + nxt) / 2, 1.0), (nxt - .012, 1.0 if nxt >= h else .78)]
        y = nxt
    ys[0] = (0.0, .8)
    rings = []
    for (yy, dark) in ys:
        last = broken and yy >= h - .02
        pts, d = flute_ring(x, base + yy, z, r(yy / H), .5 if last else 0.0)
        if last: pts = [(p[0], p[1] + .16 * (fbm(p[0] * 5, 3, p[2] * 5) - .5), p[2]) for p in pts]
        rings.append((pts, [k * dark for k in d]))
    sh = m.shade; n = FLUTES * PER
    for k in range(len(rings) - 1):
        (pa, da), (pb, db) = rings[k], rings[k + 1]
        for i in range(n):
            j = (i + 1) % n
            for tri, dk in (((pa[i], pb[i], pb[j]), (da[i], db[i], db[j])), ((pa[i], pb[j], pa[j]), (da[i], db[j], da[j]))):
                nn = norm(cross(sub(tri[1], tri[0]), sub(tri[2], tri[0])))
                m.tri(*tri, mat, col=[mul(sh(p, nn, mat), q) for p, q in zip(tri, dk)])
    top = rings[-1][0]; c = (x, base + h + (.12 if broken else 0), z)
    for i in range(n): m.tri(top[i], c, top[(i + 1) % n], 'marbre_sombre' if broken else mat)
def lathe(m, x, y, z, profile, mat, n=32):
    """Un solide de révolution : `profile` est une suite de (rayon, hauteur)."""
    for k in range(len(profile) - 1):
        (r0, y0), (r1, y1) = profile[k], profile[k + 1]
        for i in range(n):
            a = i * TAU / n; b = (i + 1) * TAU / n
            m.quad((x + math.cos(a) * r0, y + y0, z + math.sin(a) * r0), (x + math.cos(a) * r1, y + y1, z + math.sin(a) * r1),
                   (x + math.cos(b) * r1, y + y1, z + math.sin(b) * r1), (x + math.cos(b) * r0, y + y0, z + math.sin(b) * r0), mat)
def capital(m, x, z, y, mat='marbre'):
    # Trois annelets, puis l'échine qui s'évase en courbe, puis l'abaque.
    prof = [(.42, 0), (.435, .012), (.42, .03), (.435, .042), (.42, .06), (.435, .072), (.425, .09)]
    prof += [(.425 + .235 * math.sin(t * math.pi / 2) ** 1.4, .09 + .16 * t) for t in [i / 7 for i in range(1, 8)]]
    lathe(m, x, y, z, prof, mat, 28)
    m.face([(x + math.cos(i * TAU / 28) * .66, y + .25, z + math.sin(i * TAU / 28) * .66) for i in range(28)], mat, (0, -1, 0))
    ashlar(m, x, y + .25, z, 1.38, .2, 1.38, mat, seed=x * 3 + z, chip=.006, e=.06)
ENT = TOP + H + .45                                                 # dessous de l'architrave

standing = set(); stumps = {}
for i, x in enumerate(COLS_X): standing.add((i, 0))                 # tout le fond
for k in range(1, 8): standing.add((0, k))                          # le long côté gauche, presque entier
for k in range(1, 4): standing.add((5, k))                          # le droit : quatre colonnes, puis des souches
stumps[(5, 4)] = 2.1; stumps[(5, 6)] = .9; stumps[(0, 9)] = 1.4
standing.add((1, 10)); standing.add((0, 10))                        # deux colonnes de façade tiennent encore
stumps[(2, 10)] = 1.2; stumps[(4, 10)] = 2.6; stumps[(5, 10)] = .7
REBUILT = (3, 10)                                                   # celle-ci se relèvera : sa place reste vide, ses tambours gisent devant
T.shade = weather(TOP)
for (i, k) in sorted(standing):
    shaft(T, COLS_X[i], COLS_Z[k], H); capital(T, COLS_X[i], COLS_Z[k], TOP + H)
for (i, k), h in sorted(stumps.items()): shaft(T, COLS_X[i], COLS_Z[k], h, broken=True, mat='marbre_use')

# L'entablement : architrave, frise à triglyphes, corniche débordante. Sa teinte : sombre dessous, coulures.
ent_w = weather(TOP)
T.shade = lambda p, n, mat: mul(ent_w((p[0], p[1] - ENT + TOP + 1.2, p[2]), n, mat), .78 if n[1] < -.5 else 1.0)
def beam(a, b):
    (xa, za), (xb, zb) = a, b
    along_x = xa != xb
    cx, cz = (xa + xb) / 2, (za + zb) / 2; L = abs(xb - xa) + abs(zb - za)
    def box(y, h, wide, deep, mat, seed, e=.07):
        w, d = (L + wide + .03, 1.16 + deep) if along_x else (1.16 + deep, L + wide + .03)        # chaque poutre mord sur la suivante
        ashlar(T, cx, y, cz, w, h, d, mat, seed=seed + cx * 5 + cz * 3, chip=.008, e=e, n=12)
    box(ENT, .62, 0, 0, 'marbre', 1); box(ENT + .62, .07, .04, .1, 'marbre_use', 2, e=.3)                    # architrave et son listel
    box(ENT + .69, .5, -.02, -.04, 'marbre_use', 3)                                                          # frise
    for q in range(-1, 2):                                                                                    # triglyphes : trois baguettes en saillie
        for g in (-.11, 0, .11):
            off = q * L / 2 * .98 + g
            for s in (-1, 1):
                if along_x: ashlar(T, cx + off, ENT + .70, cz + s * .585, .075, .47, .06, 'marbre', seed=q + g, chip=0, n=6, rings=4, e=.3)
                else: ashlar(T, cx + s * .585, ENT + .70, cz + off, .06, .47, .075, 'marbre', seed=q + g, chip=0, n=6, rings=4, e=.3)
    box(ENT + 1.19, .11, .22, .36, 'marbre', 4, e=.25); box(ENT + 1.30, .12, .34, .56, 'marbre', 5, e=.35)  # corniche
def pos(i, k): return (COLS_X[i], COLS_Z[k])
for i in range(5): beam(pos(i, 0), pos(i + 1, 0))
for k in range(0, 7): beam(pos(0, k), pos(0, k + 1))
for k in range(0, 3): beam(pos(5, k), pos(5, k + 1))
beam(pos(0, 10), pos(1, 10))
# Le fronton du fond, entamé à droite : un tympan en retrait, deux rampants moulurés.
gz = COLS_Z[0]; gy = ENT + 1.42
for s in (-.26, .26):
    T.face([(-5.9, gy, gz + s), (3.4, gy, gz + s), (3.4, gy + .80, gz + s), (0, gy + 2.0, gz + s)], 'marbre_use', (0, 0, s))
def rake(x0, y0, x1, y1, seed):
    """Un rampant : une suite de blocs le long de la pente."""
    n = 5; ang = math.atan2(y1 - y0, x1 - x0)
    for q in range(n):
        t = (q + .5) / n; cx = x0 + (x1 - x0) * t; cy = y0 + (y1 - y0) * t; L = math.hypot(x1 - x0, y1 - y0) / n
        sub_m = Mesh(); sub_m.shade = T.shade
        ashlar(sub_m, 0, -.11, 0, L + .02, .22, .95, 'marbre', seed=seed + q, e=.12)
        ca, sa = math.cos(ang), math.sin(ang)
        for mat, pts in sub_m.groups.items():
            rot = [(cx + p[0] * ca - p[1] * sa, cy + p[0] * sa + p[1] * ca, gz + p[2]) for p in pts]
            T.groups.setdefault(mat, []).extend(rot); T.colors.setdefault(mat, []).extend(sub_m.colors[mat])
rake(-6.1, gy + .02, 0, gy + 2.1, 1); rake(0, gy + 2.1, 3.5, gy + .86, 9)
T.face([(3.4, gy, gz - .26), (3.4, gy, gz + .26), (3.4, gy + .80, gz + .26), (3.4, gy + .80, gz - .26)], 'marbre_sombre', (1, 0, 0))

# La cella : des murs en assises de blocs, joints croisés, dont la ruine a mangé le haut ; la porte en façade.
T.shade = weather(TOP)
def wall(x0, z0, x1, z1, top, seed, gap=None):
    """Monte un mur de (x0, z0) à (x1, z1) ; `top(t)` donne sa hauteur le long, de 0 à 1 ; `gap` : une baie (t0, t1)."""
    L = math.hypot(x1 - x0, z1 - z0); course = .46; rows = 11
    along_x = abs(x1 - x0) > abs(z1 - z0)
    for row in range(rows):
        y = TOP + row * course; t = -(.5 if row % 2 else 0) * 1.3 / L
        q = 0
        while t < 1:
            w = (1.05 + .5 * hash3(seed, row, q)) / L; a = max(0.0, t); b = min(1.0, t + w); t += w; q += 1
            if b - a < .12 / L: continue
            mid = (a + b) / 2
            if gap and gap[0] < mid < gap[1]: continue
            hgt = top(mid) + .3 * (hash3(seed + 5, row, q) - .5)
            if y + course * .5 > TOP + hgt: continue                                  # au-dessus de la ruine : plus de bloc
            cx = x0 + (x1 - x0) * mid; cz = z0 + (z1 - z0) * mid; ln = (b - a) * L + .012        # les blocs se recouvrent d'un rien : des joints, pas des jours
            # Le cœur du mur, derrière le parement : par un joint entrouvert on voit de la pierre sombre, jamais le jour.
            T.mul = .55
            if along_x: T.box(cx, y, cz, ln + .3, course, .4, 'marbre_sombre')
            else: T.box(cx, y, cz, .4, course, ln + .3, 'marbre_sombre')
            T.mul = .86 + .26 * hash3(seed + 9, row, q)
            jitter = .02 * (hash3(seed + 2, row, q) - .5)
            if along_x: ashlar(T, cx, y, cz + jitter, ln, course + .006, .56, 'marbre' if (row + q) % 4 else 'marbre_use', seed=seed * 13 + row * 5 + q, n=8, rings=4, e=.055)
            else: ashlar(T, cx + jitter, y, cz, .56, course + .006, ln, 'marbre' if (row + q) % 4 else 'marbre_use', seed=seed * 13 + row * 5 + q, n=8, rings=4, e=.055)
    T.mul = 1.0
def ruin(*pts):
    """Une ligne brisée lissée : la hauteur de la ruine le long du mur."""
    def f(t):
        x = t * (len(pts) - 1); i = min(len(pts) - 2, int(x)); u = x - i; u = u * u * (3 - 2 * u)
        return pts[i] + (pts[i + 1] - pts[i]) * u
    return f
wall(-3.2, -7.5, -3.2, 6.0, ruin(4.8, 4.6, 4.8, 4.0, 3.2, 3.5, 2.2, 1.4, .9), 1)
wall(3.2, -7.5, 3.2, 6.0, ruin(4.8, 4.8, 3.2, 2.4, 1.0, .6, 1.7, 2.3, 1.2), 2)
wall(-3.2, -7.5, 3.2, -7.5, ruin(4.8, 5.0, 5.0, 4.8), 3)
wall(-3.2, 6.0, 3.2, 6.0, ruin(2.8, 3.6, 3.4, 2.0, .9), 4, gap=(.33, .67))
# L'autel : un socle à degrés, une table moulurée, la vasque de l'oracle.
ashlar(T, 0, TOP, -4.6, 2.3, .22, 1.6, 'marbre_use', seed=41); ashlar(T, 0, TOP + .22, -4.6, 2.0, .16, 1.3, 'marbre', seed=42)
ashlar(T, 0, TOP + .38, -4.6, 1.6, .62, 1.0, 'marbre', seed=43, e=.05); ashlar(T, 0, TOP + 1.0, -4.6, 1.86, .12, 1.22, 'marbre_use', seed=44, e=.3)
T.shade = None
bowl = [(.0, .0), (.16, .0), (.2, .05), (.16, .1), (.3, .16), (.5, .24), (.62, .36), (.66, .47), (.63, .5), (.58, .47), (.46, .37), (.2, .3), (0, .29)]
lathe(T, 0, TOP + 1.12, -4.6, bowl, 'bronze_vert', 36)

# Blocs tombés, sur les degrés et dans l'herbe : des pierres taillées de travers, des éclats.
T.shade = weather(0.0)
def tumbled(x, z, w, h, d, yaw, tilt, mat, seed):
    base = max(height(x, z), TOP if abs(x) < 5.8 and abs(z) < 11.8 else -9)
    sub_m = Mesh(); sub_m.shade = lambda p, n, m_: (1, 1, 1)
    ashlar(sub_m, 0, 0, 0, w, h, d, mat, seed=seed, chip=.03, e=.14, n=14, rings=8)
    cy, sy_, ct, st_ = math.cos(yaw), math.sin(yaw), math.cos(tilt), math.sin(tilt)
    low = min(p[1] * ct - p[0] * st_ for pts in sub_m.groups.values() for p in pts)
    for m_, pts in sub_m.groups.items():
        for i in range(0, len(pts), 3):
            tri = []
            for p in pts[i:i + 3]:
                px, py = p[0] * ct + p[1] * st_, p[1] * ct - p[0] * st_
                tri.append((x + px * cy - p[2] * sy_, base + py - low - .05, z + px * sy_ + p[2] * cy))
            T.tri(*tri, m_)
for q, (x, z, w, h, d, m) in enumerate([(6.9, 3.2, 1.3, .62, 1.2, 'marbre_use'), (7.6, 5.1, 1.9, .5, 1.1, 'marbre'), (6.4, 8.2, 1.2, .5, 1.2, 'marbre_use'),
                           (9.0, 11.6, 2.4, .62, 1.2, 'marbre'), (-6.6, 12.4, 1.2, .6, 1.3, 'marbre_use'), (8.8, -1.5, 1.4, .7, 1.0, 'marbre'),
                           (-7.4, -6.0, 1.1, .5, 1.0, 'marbre_use'), (2.0, 4.2, .9, .4, .7, 'marbre_use'), (-1.6, 1.0, .7, .3, .9, 'marbre'),
                           (1.9, -1.2, 1.2, .46, .56, 'marbre'), (-2.2, 3.4, 1.1, .46, .56, 'marbre_use'), (9.6, 7.4, 1.5, .6, 1.1, 'marbre')]):
    tumbled(x, z, w, h, d, q * 1.3, (hash3(q, 8, 1) - .5) * .5, m, q * 3 + 1)
for q in range(34):                                                 # éclats et gravats au pied des murs et des colonnes
    a = q * 2.399; r = 2 + 8 * hash3(q, 2, 2)
    x, z = math.cos(a) * r * .75, math.sin(a) * r * 1.5
    base = max(height(x, z), TOP if abs(x) < 5.8 and abs(z) < 11.8 else -9)
    s = .09 + .16 * hash3(q, 3, 2)
    rock(T, x, base + s * .25, z, s * 1.3, s * .8, s, q * 2.1, mat='gravat', n=8, rings=5, rough=.4)
# Un tambour de la colonne droite, couché sur les degrés (cannelé comme les autres).
def lying_drum(m, c, axis, r, length):
    ax = norm(axis); ref = (0, 1, 0); u = norm(cross(ax, ref)); v = cross(ax, u)
    ends = []
    for s in (-.5, .5):
        pts, darks = flute_ring(0, 0, 0, r)
        ends.append(([add(c, add(mul(ax, s * length), add(mul(u, p[0]), mul(v, p[2])))) for p in pts], darks))
    n = FLUTES * PER
    for i in range(n):
        j = (i + 1) % n; (pa, da), (pb, db) = ends
        m.quad(pa[i], pb[i], pb[j], pa[j], 'marbre_use')
    for (pts, _), s in zip(ends, (-1, 1)):
        cc = add(c, mul(ax, s * .5 * length))
        for i in range(n): m.tri(pts[i], cc, pts[(i + 1) % n], 'marbre_sombre') if s > 0 else m.tri(pts[(i + 1) % n], cc, pts[i], 'marbre_sombre')
lying_drum(T, (7.4, .5, -3.65), (1, 0, -.5), .5, 1.08)
# Lierre sur les colonnes du fond et le mur gauche (des feuilles en losanges, qui bougent au vent).
T.shade = lambda p, n, mat: (lambda k: (k, k, k))(.7 + .6 * vnoise(p[0] * 9, p[1] * 9, p[2] * 9))
def ivy(cx, cz, r, y0, y1, a0, count):
    for q in range(count):
        a = a0 + random.uniform(-.9, .9); y = random.uniform(y0, y1) ** 1.0; s = random.uniform(.07, .14)
        c = (cx + math.cos(a) * (r + .03), y, cz + math.sin(a) * (r + .03)); out = (math.cos(a), random.uniform(-.2, .3), math.sin(a))
        side = (-math.sin(a), 0, math.cos(a)); lean = random.uniform(-.5, .5)
        tip = add(c, (side[0] * lean * s, -s * 1.5, side[2] * lean * s))
        T.face([add(c, mul(side, -s)), tip, add(c, mul(side, s)), add(c, (0, s * .9, 0))], 'lierre', out)
for i in (0, 1, 4): ivy(COLS_X[i], COLS_Z[0], .5, TOP + .1, TOP + 3.6, -math.pi / 2 + i, 150)
ivy(COLS_X[0], COLS_Z[3], .5, TOP + .1, TOP + 2.4, math.pi, 90)
for q in range(120):                                                # et le long du mur du fond
    x = random.uniform(-3, 3); y = TOP + random.uniform(.1, 3.4) * (1 - abs(x) / 5); s = random.uniform(.07, .13)
    c = (x, y, -7.5 + .31)
    T.face([add(c, (-s, 0, 0)), add(c, (random.uniform(-.05, .05), -s * 1.5, 0)), add(c, (s, 0, 0)), add(c, (0, s * .9, 0))], 'lierre', (0, 0, 1))
# Herbes et asphodèles : des touffes de brins courbés, serrées au pied des pierres.
def tuft(x, z, mat='touffe', k=7, h=.34):
    y = height(x, z)
    for q in range(k):
        a = q * TAU / k + random.random(); r = random.uniform(.02, .2); bx = x + math.cos(a) * r; bz = z + math.sin(a) * r
        hh = h * random.uniform(.5, 1.3); w = .022; lean = random.uniform(.05, .22)
        dx, dz = math.cos(a) * lean, math.sin(a) * lean; px, pz = -math.sin(a) * w, math.cos(a) * w
        mid = (bx + dx * .35, y + hh * .6, bz + dz * .35); tip = (bx + dx, y + hh, bz + dz)
        T.tri((bx - px, y, bz - pz), (bx + px, y, bz + pz), (mid[0] + px * .6, mid[1], mid[2] + pz * .6), mat)
        T.tri((bx - px, y, bz - pz), (mid[0] + px * .6, mid[1], mid[2] + pz * .6), (mid[0] - px * .6, mid[1], mid[2] - pz * .6), mat)
        T.tri((mid[0] - px * .6, mid[1], mid[2] - pz * .6), (mid[0] + px * .6, mid[1], mid[2] + pz * .6), tip, mat)
T.shade = lambda p, n, mat: (lambda k: (k, k * 1.05, k * .9))(.55 + .9 * smooth(0, .4, p[1] - height(p[0], p[2])))   # brins sombres au pied, clairs en haut
for q in range(900):
    a = random.uniform(0, TAU); r = random.uniform(7.5, 31)
    x, z = math.cos(a) * r, math.sin(a) * r / .92
    if abs(x) < 7.4 and abs(z) < 13.2: continue
    if ground_mat(x, z, height(x, z)) in ('dalle', 'sable'): continue
    if fbm(x * .25, 0, z * .25) < .42 and q % 3: continue             # par plaques, pas partout
    tuft(x, z, h=.22 + .3 * fbm(x * .3, 1, z * .3))
    if q % 11 == 0:                                                  # un asphodèle : une hampe et une grappe pâle
        y = height(x, z)
        T.tube((x, y, z), (x + .03, y + .7, z), .012, .008, 'fleur_tige', 5)
        for s in range(6): T.sphere(x + .03 + random.uniform(-.04, .04), y + .5 + s * .055, z + random.uniform(-.04, .04), .03, .04, .03, 'fleur_blanche', 6, 3)
# La margelle du bassin, en blocs, et une marche à chaque bout (on ressort du bassin en marchant).
T.shade = weather(-.1)
for (x0, z0, x1, z1) in [(-3.75, 14.9, -3.75, 23.1), (3.75, 14.9, 3.75, 23.1), (-3.75, 14.9, 3.75, 14.9), (-3.75, 23.1, 3.75, 23.1)]:
    L = math.hypot(x1 - x0, z1 - z0); n = int(L / 1.3)
    for q in range(n):
        t = (q + .5) / n; cx = x0 + (x1 - x0) * t; cz = z0 + (z1 - z0) * t
        if x0 == x1: ashlar(T, cx, -.07, cz, .5, .13, L / n - .012, 'marbre_use', seed=q + x0)
        else: ashlar(T, cx, -.07, cz, L / n - .012, .13, .5, 'marbre_use', seed=q + z0)
for z in (15.5, 22.5): ashlar(T, 0, -.56, z, 6.6, .29, .8, 'marbre_use', seed=z, e=.05)
single(T, 'temple.glb')

# ---------------------------------------------------------------- pièces de la colonne à relever
drum = Mesh()                                                       # un tambour d'un mètre, centré, axe Y, cannelé
lo, dl = flute_ring(0, -.5, 0, .5); hi, dh = flute_ring(0, .5, 0, .5); n = FLUTES * PER
for i in range(n):
    j = (i + 1) % n
    drum.tri(lo[i], hi[i], hi[j], 'marbre', col=[(dl[i],) * 3, (dh[i],) * 3, (dh[j],) * 3]); drum.tri(lo[i], hi[j], lo[j], 'marbre', col=[(dl[i],) * 3, (dh[j],) * 3, (dl[j],) * 3])
    drum.tri(lo[j], (0, -.5, 0), lo[i], 'marbre_sombre', col=[(1, 1, 1)] * 3); drum.tri(hi[i], (0, .5, 0), hi[j], 'marbre_sombre', col=[(1, 1, 1)] * 3)
single(drum, 'tambour.glb')
cap = Mesh(); cap.shade = lambda p, n, mat: (1, 1, 1); capital(cap, 0, 0, -.22)
single(cap, 'chapiteau.glb')

# ---------------------------------------------------------------- brasero
br = Mesh()
for k in range(3):                                                  # trois pieds galbés d'un seul jet, qui entrent dans la vasque
    a = k * TAU / 3
    path = [(math.cos(a) * r, y, math.sin(a) * r) for r, y in [(.14, 1.06), (.15, .9), (.18, .6), (.3, .25), (.39, .06), (.43, .03)]]
    sweep(br, path, [.026, .027, .029, .031, .034, .04], 'bronze', n=10)
lathe(br, 0, .4, 0, [(.2, 0), (.215, .012), (.2, .025)], 'bronze', 24)                                   # l'entretoise
lathe(br, 0, .93, 0, [(0, 0), (.1, .0), (.16, .04), (.14, .08), (.24, .13), (.35, .21), (.39, .28), (.375, .3), (.33, .27), (.2, .22), (0, .2)], 'bronze_vert', 28)
lathe(br, 0, 1.135, 0, [(0, 0), (.3, .0)], 'cendre', 20)
for k in range(9):
    a = k * 2.4; r = .04 + .2 * (k % 4) / 3
    rock(br, math.cos(a) * r, 1.16, math.sin(a) * r, .055, .04, .05, k * 1.3, mat='braise', n=7, rings=4, rough=.5)
single(br, 'brasero.glb')

# ---------------------------------------------------------------- statue
st = Mesh(); st.shade = weather(0.0)
ashlar(st, 0, 0, 0, 1.16, .26, 1.16, 'marbre_use', seed=3); ashlar(st, 0, .22, 0, .96, .68, .96, 'marbre', seed=4, e=.06); ashlar(st, 0, .84, 0, 1.06, .2, 1.06, 'marbre_use', seed=5, e=.3)   # les trois assises du socle se recouvrent : pas de jour entre elles
# Le corps : un profil tourné, plus large que profond aux épaules, creusé de plis qui tombent et s'écartent en bas.
def body(y):
    """(rayon, élargissement en x, déhanchement) à la hauteur y au-dessus du socle (0 à 1,78)."""
    keys = [(0, .40, 1.0), (.25, .37, 1.0), (.75, .31, 1.02), (1.0, .285, 1.08), (1.18, .245, 1.14), (1.38, .27, 1.3), (1.56, .255, 1.45), (1.66, .17, 1.3), (1.72, .1, 1.05), (1.78, .09, 1.0)]
    for (y0, r0, e0), (y1, r1, e1) in zip(keys, keys[1:]):
        if y <= y1: t = (y - y0) / (y1 - y0); t = t * t * (3 - 2 * t); return (r0 + (r1 - r0) * t, e0 + (e1 - e0) * t, .05 * math.sin(y / 1.78 * math.pi))
    return (.09, 1.0, 0)
RI = 44; SG = 36; base_y = 1.0
def sp(i, j):
    y = 1.78 * i / RI; a = TAU * j / SG; r, e, sway = body(y)
    folds = (1 - smooth(.95, 1.25, y)) * (.055 + .05 * (1 - y)) * math.sin(11 * a + 1.4 * math.sin(3 * a) + y * .8)      # le drapé : des plis verticaux, plus amples vers le bas
    sash = .03 * math.exp(-((y - 1.12 - .18 * math.cos(a)) / .07) ** 2)                                                 # une ceinture en biais
    rr = r * (1 + folds) + sash
    return (math.cos(a) * rr * e + sway, base_y + y, math.sin(a) * rr / (e ** .5))
for i in range(RI):
    for j in range(SG):
        st.quad(sp(i, j), sp(i + 1, j), sp(i + 1, j + 1), sp(i, j + 1), 'marbre')
st.face([sp(RI, j) for j in range(SG)], 'marbre_sombre', (0, 1, 0))                                                    # le cou, sans tête
# Les bras sortent de l'épaule (leur départ est dans le corps) : l'un replié vers la taille, l'autre brisé au coude.
sweep(st, [(-.2, 2.56, 0), (-.36, 2.53, .0), (-.45, 2.34, .03), (-.48, 2.08, .1), (-.42, 1.9, .23), (-.31, 1.78, .33)], [.1, .1, .09, .078, .066, .055], 'marbre', n=14)
sweep(st, [(.2, 2.56, 0), (.37, 2.53, 0), (.46, 2.36, .0), (.5, 2.2, .02)], [.1, .1, .09, .085], 'marbre', n=14, round_end=False, end_mat='marbre_sombre')
single(st, 'statue.glb')

# ---------------------------------------------------------------- arbres
cy = Mesh()
cy.shade = lambda p, n, mat: (1, 1, 1) if mat == 'tronc' else (lambda k: (k * .9, k, k * .95))(.45 + .9 * vnoise(p[0] * 3.1, p[1] * 1.3, p[2] * 3.1) * smooth(-.2, .9, math.hypot(p[0], p[2]) / .5))
lathe(cy, 0, 0, 0, [(.16, 0), (.12, .3), (.1, 1.0)], 'tronc', 10)
RI = 34; SG = 26
def cp(i, j):
    t = i / RI; a = TAU * j / SG; y = .7 + 6.9 * t
    r = .78 * (math.sin(math.pi * min(1, t * 1.08)) ** .62) * (1 - t) ** .32 + .02
    r *= 1 + .16 * math.sin(7 * a + 9 * t) * (1 - t) + .22 * (fbm(math.cos(a) * 2.2, t * 9, math.sin(a) * 2.2) - .5)          # des flammes de feuillage, pas un cône
    return (math.cos(a) * r + .06 * math.sin(t * 5), y, math.sin(a) * r)
for i in range(RI):
    for j in range(SG): cy.quad(cp(i, j), cp(i + 1, j), cp(i + 1, j + 1), cp(i, j + 1), 'cypres')
single(cy, 'cypres.glb')
ol = Mesh()
ol.shade = lambda p, n, mat: (lambda k: (k, k, k))(.6 + .7 * vnoise(p[0] * 4, p[1] * 4, p[2] * 4)) if mat == 'tronc' else (lambda k: (k * .95, k, k * 1.05))(.5 + .45 * smooth(-.6, .9, n[1]) + .4 * vnoise(p[0] * 2.5, p[1] * 2.5, p[2] * 2.5))
def gnarled(pts, r0, r1, n=12):
    """Un tronc noueux d'un seul tenant, dont le rayon se tord."""
    k = len(pts) - 1
    ol_radii = [(r0 + (r1 - r0) * i / k) * (1 + .16 * math.sin(i * 2.1 + pts[0][0] * 9)) for i in range(k + 1)]
    sweep(ol, pts, ol_radii, 'tronc', n=n)
gnarled([(0, -.1, 0), (.03, .15, .0), (.08, .4, .03), (.03, .7, -.04), (.12, 1.0, .05), (.06, 1.32, .1)], .27, .15)
gnarled([(.08, 1.15, .08), (-.08, 1.45, .16), (-.25, 1.75, .18), (-.44, 2.08, .05), (-.6, 2.42, -.08)], .135, .05)
gnarled([(.1, .9, .03), (.28, 1.2, -.08), (.46, 1.45, -.2), (.66, 1.78, -.3), (.8, 2.12, -.25)], .125, .05)
gnarled([(.06, 1.2, .1), (.1, 1.5, .24), (.1, 1.85, .38), (.05, 2.22, .5)], .105, .04)
for q, (x, y, z, s) in enumerate([(-.6, 2.5, -.05, .85), (.75, 2.2, -.3, .8), (.05, 2.75, .4, .75), (-.95, 2.05, .5, .6), (1.05, 2.55, .2, .6), (.2, 2.3, -.85, .65),
                                  (-.2, 2.95, -.2, .6), (.5, 2.85, .0, .55), (-.4, 2.3, .75, .5)]):
    rock(ol, x, y - s * .1, z, s * .82, s * .62, s * .78, q * 3.1 + 2, mat='olivier', n=14, rings=9, rough=.42)
single(ol, 'olivier.glb')

for f in sorted(glbkit.OUT.glob('*.glb')): print(f'{f.name}: {f.stat().st_size} octets')
print('rebuilt column at', pos(*REBUILT), 'top of stylobate', TOP, 'shaft', H)
