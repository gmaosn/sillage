#!/usr/bin/env python3
"""Les modèles de la seconde démo, « L'oracle endormi » : un temple dorique en ruine sur une île, la nuit.
Sans Blender ni bibliothèque tierce (tools/glbkit.py). Repère glTF : Y vers le haut, mètres ; le temple est
au centre, son grand axe le long de Z, sa façade vers +Z (d'où l'on arrive).

Fichiers produits dans temple/resources/models :
  ile.glb      le sol : plateau, pente vers le lac, bassin devant le temple, voie dallée
  temple.glb   degrés, colonnes debout ou brisées, architraves, fronton arrière, cella, autel, blocs épars
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
    'marbre': (.62, .60, .54), 'marbre_use': (.50, .49, .45), 'marbre_sombre': (.30, .30, .29), 'mousse': (.16, .24, .10),
    'dalle': (.40, .39, .36), 'herbe': (.20, .24, .11), 'herbe_seche': (.30, .29, .15), 'sable': (.46, .42, .32), 'roche': (.24, .23, .22),
    'bronze': (.20, .13, .05), 'bronze_vert': (.10, .24, .19), 'braise': (1.0, .32, .06), 'cendre': (.07, .07, .07),
    'cypres': (.035, .09, .05), 'olivier': (.20, .26, .19), 'tronc': (.14, .10, .07), 'lierre': (.07, .17, .06),
    'touffe': (.22, .26, .12), 'fleur_blanche': (.80, .80, .74), 'fleur_tige': (.12, .20, .08), 'or': (.90, .62, .10),
})

# ---------------------------------------------------------------- île
SIZE = 78.0; CELLS = 104
def noise(x, z): return (math.sin(x * .37 + 1.3) * math.cos(z * .41 - .7) + .5 * math.sin(x * .9 - z * .7) + .3 * math.cos(x * 1.7 + z * 1.3)) / 1.8
def smooth(a, b, t): t = min(1.0, max(0.0, (t - a) / (b - a))); return t * t * (3 - 2 * t)
def basin(x, z):
    """Le bassin devant le temple : 1 au fond, 0 dehors, bords raides."""
    return (1 - smooth(3.0, 3.5, abs(x))) * (1 - smooth(3.6, 4.1, abs(z - 19.0)))
def height(x, z):
    r = math.hypot(x, z * .92)
    rough = smooth(14.0, 22.0, r)                                  # plat sous le temple et son parvis
    y = noise(x, z) * .22 * rough + noise(x * 2.3, z * 2.1) * .06 * rough
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
ile = Mesh()
step = SIZE / CELLS
def P(i, j): x = -SIZE / 2 + i * step; z = -SIZE / 2 + j * step; return (x, height(x, z), z)
for i in range(CELLS):
    for j in range(CELLS):
        a, b, c, d = P(i, j), P(i + 1, j), P(i + 1, j + 1), P(i, j + 1)
        if max(a[1], b[1], c[1], d[1]) < -2.5: continue                                     # sous le lac, loin : rien à dessiner
        m = ground_mat((a[0] + c[0]) / 2, (a[2] + c[2]) / 2, (a[1] + c[1]) / 2)
        ile.tri(a, d, c, m); ile.tri(a, c, b, m)
single(ile, 'ile.glb')

# ---------------------------------------------------------------- temple
T = Mesh()
TOP = .9                                                            # le haut du stylobate
for k in range(3):                                                  # trois degrés de 30 cm
    grow = (2 - k) * .5
    T.box(0, k * .3, 0, 11.6 + 2 * grow, .3, 23.6 + 2 * grow, 'marbre_use' if k < 2 else 'marbre')

COLS_X = [-5.0, -3.0, -1.0, 1.0, 3.0, 5.0]
COLS_Z = [-10.5 + 2.1 * k for k in range(11)]
H = 5.4                                                             # fût
def shaft(m, x, z, h, base=TOP, mat='marbre', broken=False, n=20):
    """Un fût dorique : vingt pans, galbe léger, posé sur le stylobate ; brisé, il finit en biseau."""
    levels = 6
    def r(t): return .52 - .10 * t - .015 * math.sin(math.pi * t)   # 0,52 en bas, 0,42 en haut
    def ring(t, tilt=0.0):
        rr = r(t * h / H)
        return [(x + math.cos(i * TAU / n) * rr, base + t * h + tilt * math.cos(i * TAU / n + 1.0) * rr, z + math.sin(i * TAU / n) * rr) for i in range(n)]
    rings = [ring(k / levels, .55 if (broken and k == levels) else 0.0) for k in range(levels + 1)]
    for k in range(levels):
        for i in range(n):
            j = (i + 1) % n
            m.quad(rings[k][i], rings[k + 1][i], rings[k + 1][j], rings[k][j], mat)
    m.face(rings[-1], 'marbre_sombre' if broken else mat, (0, 1, 0))
def capital(m, x, z, y, mat='marbre'):
    m.cone(x, y, z, .42, .66, .24, mat, 20)                         # échine
    m.box(x, y + .24, z, 1.36, .2, 1.36, mat)                       # abaque
ENT = TOP + H + .44                                                 # dessous de l'architrave

standing = set(); stumps = {}
for i, x in enumerate(COLS_X): standing.add((i, 0))                 # tout le fond
for k in range(1, 8): standing.add((0, k))                          # le long côté gauche, presque entier
for k in range(1, 4): standing.add((5, k))                          # le droit : quatre colonnes, puis des souches
stumps[(5, 4)] = 2.1; stumps[(5, 6)] = .9; stumps[(0, 9)] = 1.4
standing.add((1, 10)); standing.add((0, 10))                        # deux colonnes de façade tiennent encore
stumps[(2, 10)] = 1.2; stumps[(4, 10)] = 2.6; stumps[(5, 10)] = .7
REBUILT = (3, 10)                                                   # celle-ci se relèvera : sa place reste vide, ses tambours gisent devant
for (i, k) in sorted(standing):
    shaft(T, COLS_X[i], COLS_Z[k], H); capital(T, COLS_X[i], COLS_Z[k], TOP + H)
for (i, k), h in sorted(stumps.items()): shaft(T, COLS_X[i], COLS_Z[k], h, broken=True, mat='marbre_use')

def beam(a, b):
    """Architrave et frise entre deux colonnes debout voisines."""
    (xa, za), (xb, zb) = a, b
    cx, cz = (xa + xb) / 2, (za + zb) / 2
    w = abs(xb - xa) + 1.2 if xa != xb else 1.2; d = abs(zb - za) + 1.2 if za != zb else 1.2
    T.box(cx, ENT, cz, w, .62, d, 'marbre'); T.box(cx, ENT + .62, cz, w + .06, .5, d + .06, 'marbre_use')
    T.box(cx, ENT + 1.12, cz, w + .3, .2, d + .3, 'marbre')
def pos(i, k): return (COLS_X[i], COLS_Z[k])
for i in range(5): beam(pos(i, 0), pos(i + 1, 0))
for k in range(0, 7): beam(pos(0, k), pos(0, k + 1))
for k in range(0, 3): beam(pos(5, k), pos(5, k + 1))
beam(pos(0, 10), pos(1, 10))
# Le fronton du fond, entamé à droite.
gz = COLS_Z[0]; gy = ENT + 1.32
for s, top_x in ((-.3, -5.8), (.3, -5.8)):
    z = gz + s
    T.face([(-5.8, gy, z), (3.4, gy, z), (3.4, gy + .82, z), (0, gy + 2.0, z)], 'marbre', (0, 0, s))
T.face([(-5.8, gy, gz - .3), (-5.8, gy, gz + .3), (0, gy + 2.0, gz + .3), (0, gy + 2.0, gz - .3)], 'marbre_use', (-1, 1, 0))
T.face([(0, gy + 2.0, gz - .3), (0, gy + 2.0, gz + .3), (3.4, gy + .82, gz + .3), (3.4, gy + .82, gz - .3)], 'marbre_use', (1, 1, 0))
T.face([(3.4, gy, gz - .3), (3.4, gy, gz + .3), (3.4, gy + .82, gz + .3), (3.4, gy + .82, gz - .3)], 'marbre_sombre', (1, 0, 0))

# La cella : des murs à hauteur inégale, une porte vers la façade, l'autel au fond.
def wall(x0, z0, x1, z1, heights):
    n = len(heights)
    for s, h in enumerate(heights):
        t0, t1 = s / n, (s + 1) / n
        cx = x0 + (x1 - x0) * (t0 + t1) / 2; cz = z0 + (z1 - z0) * (t0 + t1) / 2
        w = abs(x1 - x0) / n + .001 if x0 != x1 else .55; d = abs(z1 - z0) / n + .001 if z0 != z1 else .55
        T.box(cx, TOP, cz, w, h, d, 'marbre_use' if s % 2 else 'marbre')
        if h > 1.2: T.box(cx, TOP + h, cz, w * .92, .08, d * .92, 'mousse')
wall(-3.2, -7.5, -3.2, 6.0, [4.6, 4.4, 4.6, 3.9, 3.2, 3.4, 2.1, 1.3, .8])
wall(3.2, -7.5, 3.2, 6.0, [4.6, 4.6, 3.1, 2.4, .9, .5, 1.6, 2.2, 1.1])
wall(-3.2, -7.5, 3.2, -7.5, [4.6, 4.8, 4.8, 4.6])
wall(-3.2, 6.0, -1.1, 6.0, [2.6, 3.4]); wall(1.1, 6.0, 3.2, 6.0, [1.9, .7])
T.box(0, TOP, -4.6, 2.2, .35, 1.5, 'marbre_use'); T.box(0, TOP + .35, -4.6, 1.7, .75, 1.1, 'marbre')       # l'autel
T.sphere(0, TOP + 1.32, -4.6, .62, .26, .62, 'bronze_vert', 18, 6)                                              # la vasque de l'oracle
T.cone(0, TOP + 1.1, -4.6, .2, .5, .16, 'bronze', 14)

# Blocs tombés, sur les degrés et dans l'herbe.
for (x, z, w, h, d, m) in [(6.9, 3.2, 1.3, .62, 1.2, 'marbre_use'), (7.6, 5.1, 1.9, .5, 1.1, 'marbre'), (6.4, 8.2, 1.2, .5, 1.2, 'marbre_use'),
                           (4.3, 13.3, 2.4, .62, 1.2, 'marbre'), (-6.6, 12.4, 1.2, .6, 1.3, 'marbre_use'), (8.8, -1.5, 1.4, .7, 1.0, 'marbre'),
                           (-7.4, -6.0, 1.1, .5, 1.0, 'marbre_use'), (2.0, 4.2, .9, .4, .7, 'marbre_use'), (-1.6, 1.0, .7, .3, .9, 'marbre')]:
    base = max(height(x, z), TOP if abs(x) < 5.8 and abs(z) < 11.8 else -9) - .06
    T.rounded(x, base, z, w, h, d, m, e=.16, n=12, rings=6)
# Un tambour de la colonne droite, couché sur les degrés.
T.tube((6.9, .52, -3.4), (7.9, .52, -3.9), .5, .47, 'marbre_use', 20)
# Lierre sur les colonnes du fond et le mur gauche (des feuilles en losanges, qui bougent au vent).
def ivy(cx, cz, r, y0, y1, a0, count):
    for q in range(count):
        a = a0 + random.uniform(-.9, .9); y = random.uniform(y0, y1); s = random.uniform(.10, .19)
        c = (cx + math.cos(a) * (r + .03), y, cz + math.sin(a) * (r + .03)); out = (math.cos(a), 0, math.sin(a))
        side = (-math.sin(a), 0, math.cos(a))
        T.face([add(c, mul(side, -s)), add(c, (0, -s * 1.3, 0)), add(c, mul(side, s)), add(c, (0, s * 1.3, 0))], 'lierre', out)
for i in (0, 1, 4): ivy(COLS_X[i], COLS_Z[0], .5, TOP + .1, TOP + 3.6, -math.pi / 2 + i, 60)
ivy(COLS_X[0], COLS_Z[3], .5, TOP + .1, TOP + 2.4, math.pi, 40)
# Touffes et asphodèles autour du parvis.
def tuft(x, z, mat='touffe', k=5, h=.34):
    y = height(x, z)
    for q in range(k):
        a = q * TAU / k + random.random(); r = random.uniform(.03, .16); bx = x + math.cos(a) * r; bz = z + math.sin(a) * r
        hh = h * random.uniform(.6, 1.2); w = .035
        T.face([(bx - w, y, bz), (bx + w, y, bz), (bx + math.cos(a) * .08, y + hh, bz + math.sin(a) * .08)], mat, (math.sin(a), .2, -math.cos(a)))
        T.face([(bx, y, bz - w), (bx, y, bz + w), (bx + math.cos(a) * .08, y + hh, bz + math.sin(a) * .08)], mat, (math.cos(a), .2, math.sin(a)))
for q in range(260):
    a = random.uniform(0, TAU); r = random.uniform(8.5, 30)
    x, z = math.cos(a) * r, math.sin(a) * r / .92
    if abs(x) < 7.4 and abs(z) < 13.2: continue
    if ground_mat(x, z, height(x, z)) in ('dalle', 'sable'): continue
    tuft(x, z)
    if q % 7 == 0:                                                   # un asphodèle : une hampe et une grappe pâle
        y = height(x, z)
        T.tube((x, y, z), (x + .03, y + .7, z), .012, .008, 'fleur_tige', 5)
        for s in range(5): T.sphere(x + .03 + random.uniform(-.04, .04), y + .55 + s * .06, z + random.uniform(-.04, .04), .035, .045, .035, 'fleur_blanche', 6, 3)
# La margelle du bassin.
for (x, z, w, d) in [(-3.75, 19, .5, 8.7), (3.75, 19, .5, 8.7), (0, 14.9, 8.0, .5), (0, 23.1, 8.0, .5)]:
    T.box(x, -.06, z, w, .12, d, 'marbre_use')
for z in (15.5, 22.5): T.box(0, -.56, z, 6.6, .29, .8, 'marbre_use')        # une marche à chaque bout : on ressort du bassin en marchant
single(T, 'temple.glb')

# ---------------------------------------------------------------- pièces de la colonne à relever
drum = Mesh()
n = 20
for i in range(n):                                                  # un tambour d'un mètre, centré, axe Y
    a = i * TAU / n; b = (i + 1) * TAU / n
    p = (math.cos(a) * .5, -.5, math.sin(a) * .5); q = (math.cos(b) * .5, -.5, math.sin(b) * .5)
    u = (math.cos(a) * .5, .5, math.sin(a) * .5); v = (math.cos(b) * .5, .5, math.sin(b) * .5)
    drum.quad(p, u, v, q, 'marbre'); drum.tri((0, -.5, 0), p, q, 'marbre_sombre'); drum.tri((0, .5, 0), v, u, 'marbre_sombre')
single(drum, 'tambour.glb')
cap = Mesh(); capital(cap, 0, 0, -.22)
single(cap, 'chapiteau.glb')

# ---------------------------------------------------------------- brasero
br = Mesh()
for k in range(3):
    a = k * TAU / 3
    br.tube((math.cos(a) * .34, 0, math.sin(a) * .34), (math.cos(a) * .13, 1.0, math.sin(a) * .13), .028, .022, 'bronze', 6)
br.cone(0, .42, 0, .2, .2, .03, 'bronze', 12)
br.cone(0, .98, 0, .12, .36, .2, 'bronze_vert', 16)                 # la vasque
br.cone(0, 1.15, 0, .30, .30, .025, 'cendre', 16)
for k in range(7):
    a = k * 2.4; r = .05 + .13 * (k % 3) / 2
    br.sphere(math.cos(a) * r, 1.19, math.sin(a) * r, .055, .04, .055, 'braise', 6, 3)
single(br, 'brasero.glb')

# ---------------------------------------------------------------- statue
st = Mesh()
st.box(0, 0, 0, 1.1, .5, 1.1, 'marbre_use'); st.box(0, .5, 0, .86, .5, .86, 'marbre')
st.cone(0, 1.0, 0, .40, .27, 1.05, 'marbre', 14)                    # le bas du drapé
for k in range(9):                                                  # ses plis
    a = k * TAU / 9
    st.tube((math.cos(a) * .40, 1.0, math.sin(a) * .40), (math.cos(a) * .27, 2.05, math.sin(a) * .27), .045, .03, 'marbre_use', 5)
st.rounded(0, 2.0, 0, .62, .7, .40, 'marbre', e=.7, n=14, rings=8)  # le torse
st.tube((-.33, 2.52, 0), (-.47, 2.08, .1), .09, .075, 'marbre', 8)  # un bras, l'autre est brisé
st.tube((-.47, 2.08, .1), (-.36, 1.74, .3), .075, .06, 'marbre', 8)
st.tube((.33, 2.52, 0), (.44, 2.3, .02), .09, .085, 'marbre_use', 8)
st.cone(0, 2.68, 0, .11, .10, .1, 'marbre_sombre', 10)             # le cou, sans tête
single(st, 'statue.glb')

# ---------------------------------------------------------------- arbres
cy = Mesh()
cy.cone(0, 0, 0, .13, .09, 1.1, 'tronc', 8)
for k, (y, r, h) in enumerate([(.8, .62, 2.6), (2.4, .52, 2.6), (4.0, .38, 2.4), (5.4, .22, 1.8)]):
    cy.cone(.03 * math.sin(k * 2.1), y, .03 * math.cos(k * 1.7), r, r * .28, h, 'cypres', 12)
cy.cone(0, 6.9, 0, .08, 0, .7, 'cypres', 8)
single(cy, 'cypres.glb')
ol = Mesh()
ol.tube((0, 0, 0), (.12, .9, .05), .22, .17, 'tronc', 9); ol.tube((.12, .9, .05), (-.25, 1.7, .2), .15, .09, 'tronc', 8)
ol.tube((.12, .9, .05), (.55, 1.6, -.25), .13, .08, 'tronc', 8); ol.tube((-.25, 1.7, .2), (-.5, 2.3, -.1), .08, .05, 'tronc', 6)
for (x, y, z, s) in [(-.5, 2.5, 0, .85), (.6, 2.1, -.3, .8), (0, 2.8, .3, .75), (-.9, 2.0, .5, .6), (.95, 2.5, .2, .6), (.2, 2.2, -.8, .65)]:
    ol.rounded(x, y - s * .45, z, s * 1.5, s * .9, s * 1.4, 'olivier', e=.8, n=10, rings=6)
single(ol, 'olivier.glb')

for f in sorted(glbkit.OUT.glob('*.glb')): print(f'{f.name}: {f.stat().st_size} octets')
print('rebuilt column at', pos(*REBUILT), 'top of stylobate', TOP, 'shaft', H)
