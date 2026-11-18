#!/usr/bin/env python3
"""GLB 2.0 de la démo, générés sans Blender ni bibliothèque tierce.

Repère glTF : Y vers le haut, mètres, personnages tournés vers -Z (leur droite est +X).
Les modèles animés gardent leurs nœuds et portent des animations glTF (TRS, LINEAR) :
c'est ce que Blender exporte pour des objets en plusieurs pièces parentées.

Fichiers produits dans demo/resources/models :
  terrain.glb  sol de l'île et ponton (collisions)
  village.glb  décor fixe : maison, phare, rochers, fleurs, chemin...
  marin.glb    le héros, en pièces, clips idle / run / jump / fall
  palmiers.glb palmiers, clip « vent »
  ciel.glb     nuages et mouettes, clip « vol »
  port.glb     bateau, bouées, lampe du phare, fumée, clip « vie »
"""
from pathlib import Path
import json, math, struct, random

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'demo/resources/models'
OUT.mkdir(parents=True, exist_ok=True)
random.seed(17)
TAU = math.tau

# Couleurs linéaires (le shader applique le gamma).
COLORS = {
    'herbe': (.24, .52, .10), 'herbe_fonce': (.12, .36, .05), 'buisson': (.06, .27, .05),
    'sable': (.82, .60, .27), 'sable_mouille': (.62, .44, .18), 'roche': (.40, .29, .16), 'pierre': (.42, .42, .40),
    'bois': (.29, .13, .045), 'bois_clair': (.50, .30, .12), 'palme': (.075, .32, .07), 'palme_claire': (.16, .45, .08),
    'creme': (.87, .76, .49), 'toit': (.64, .13, .045), 'bleu': (.035, .15, .25), 'lin': (.90, .83, .62),
    'peau': (.67, .36, .16), 'corail': (.83, .075, .025), 'noir': (.025, .022, .02), 'blanc': (.78, .78, .72),
    'marine': (.03, .05, .16), 'brun': (.12, .05, .015), 'joue': (.80, .25, .15), 'bouche': (.35, .05, .03),
    'or': (.90, .62, .10), 'cuir': (.16, .07, .025), 'paille': (.80, .62, .25), 'semelle': (.05, .04, .03),
    'vitre': (.18, .36, .45), 'jaune': (.95, .75, .05), 'rose': (.90, .30, .45), 'violet': (.45, .20, .70),
    'fer': (.10, .10, .10), 'lampe': (1.0, .85, .35), 'orange': (.95, .35, .05), 'nuage': (.92, .94, .95),
    'fumee': (.70, .70, .70),
}


# ---------------------------------------------------------------- géométrie

def sub(a, b): return (a[0] - b[0], a[1] - b[1], a[2] - b[2])
def add(a, b): return (a[0] + b[0], a[1] + b[1], a[2] + b[2])
def mul(a, s): return (a[0] * s, a[1] * s, a[2] * s)
def cross(a, b): return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
def dot(a, b): return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
def norm(a):
    l = math.sqrt(dot(a, a)); return mul(a, 1 / l) if l else a


class Mesh:
    def __init__(self): self.groups = {}

    def tri(self, a, b, c, mat): self.groups.setdefault(mat, []).extend([a, b, c])
    def quad(self, a, b, c, d, mat): self.tri(a, b, c, mat); self.tri(a, c, d, mat)

    def face(self, pts, mat, toward):
        """Polygone convexe en éventail, orienté pour que sa normale regarde vers `toward`."""
        n = cross(sub(pts[1], pts[0]), sub(pts[2], pts[0]))
        if dot(n, toward) < 0: pts = pts[::-1]
        for i in range(1, len(pts) - 1): self.tri(pts[0], pts[i], pts[i + 1], mat)

    def box(self, x, y, z, w, h, d, mat):
        """Boîte alignée : (x, z) centre, y = dessous."""
        p = [(x + (-1 if i & 1 == 0 else 1) * w / 2, y + (0 if i & 2 == 0 else h), z + (-1 if i & 4 == 0 else 1) * d / 2) for i in range(8)]
        for f in [(0, 4, 6, 2), (1, 3, 7, 5), (0, 1, 5, 4), (2, 6, 7, 3), (0, 2, 3, 1), (4, 5, 7, 6)]: self.quad(*(p[i] for i in f), mat)

    def cone(self, x, y, z, r1, r2, h, mat, n=10):
        """Tronc de cône vertical, base en y."""
        for i in range(n):
            a = i * TAU / n; b = (i + 1) * TAU / n
            p = (x + math.cos(a) * r1, y, z + math.sin(a) * r1)
            q = (x + math.cos(b) * r1, y, z + math.sin(b) * r1)
            u = (x + math.cos(a) * r2, y + h, z + math.sin(a) * r2)
            v = (x + math.cos(b) * r2, y + h, z + math.sin(b) * r2)
            self.quad(p, u, v, q, mat)
            self.tri((x, y, z), p, q, mat)
            if r2 > 0: self.tri((x, y + h, z), v, u, mat)

    def tube(self, p0, p1, r0, r1, mat, n=8):
        """Tronc de cône entre deux points quelconques."""
        axis = norm(sub(p1, p0))
        ref = (1, 0, 0) if abs(axis[0]) < .9 else (0, 0, 1)
        u = norm(cross(axis, ref)); v = cross(axis, u)
        def ring(c, r): return [add(c, add(mul(u, math.cos(i * TAU / n) * r), mul(v, math.sin(i * TAU / n) * r))) for i in range(n)]
        a = ring(p0, r0); b = ring(p1, r1)
        for i in range(n):
            j = (i + 1) % n
            mid = mul(add(add(a[i], a[j]), add(b[i], b[j])), .25)
            self.face([a[i], a[j], b[j], b[i]], mat, sub(mid, add(p0, mul(sub(p1, p0), .5))))
        self.face(a, mat, mul(axis, -1)); self.face(b, mat, axis)

    def sphere(self, x, y, z, sx, sy, sz, mat, n=10, rings=5):
        def p(i, j):
            a = math.pi * i / rings; b = TAU * j / n
            return (x + math.sin(a) * math.cos(b) * sx, y + math.cos(a) * sy, z + math.sin(a) * math.sin(b) * sz)
        for i in range(rings):
            for j in range(n): self.quad(p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1), mat)

    def disk(self, x, y, z, rx, rz, mat, n=12):
        """Disque horizontal tourné vers le haut."""
        self.face([(x + math.cos(i * TAU / n) * rx, y, z + math.sin(i * TAU / n) * rz) for i in range(n)], mat, (0, 1, 0))

    def star(self, x, y, z, r, mat, a0=0.0):
        for i in range(5):
            a = a0 + i * TAU / 5
            tip = (x + math.cos(a) * r, y, z + math.sin(a) * r)
            l = (x + math.cos(a - TAU / 10) * r * .4, y, z + math.sin(a - TAU / 10) * r * .4)
            rr = (x + math.cos(a + TAU / 10) * r * .4, y, z + math.sin(a + TAU / 10) * r * .4)
            self.face([(x, y + .015, z), l, tip], mat, (0, 1, 0)); self.face([(x, y + .015, z), tip, rr], mat, (0, 1, 0))

    def triangles(self):
        return [(pts[i], pts[i + 1], pts[i + 2]) for pts in self.groups.values() for i in range(0, len(pts), 3)]


# ---------------------------------------------------------------- quaternions

def qaxis(ax, ay, az, ang):
    s = math.sin(ang / 2); return (ax * s, ay * s, az * s, math.cos(ang / 2))
def qx(a): return qaxis(1, 0, 0, a)
def qy(a): return qaxis(0, 1, 0, a)
def qz(a): return qaxis(0, 0, 1, a)
def qmul(a, b):
    x1, y1, z1, w1 = a; x2, y2, z2, w2 = b
    return (w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2, w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
            w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2, w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2)
def qeuler(x=0.0, y=0.0, z=0.0): return qmul(qy(y), qmul(qx(x), qz(z)))


def keys(duration, count):
    return [duration * i / (count - 1) for i in range(count)]


def turn_keys(duration, turns, sign=1, per_turn=8):
    """Rotation Y continue de `turns` tours : une clé tous les 1/per_turn de tour (slerp suit le plus court)."""
    n = turns * per_turn
    return [duration * i / n for i in range(n + 1)], [qy(sign * TAU * i / per_turn) for i in range(n + 1)]


# ---------------------------------------------------------------- GLB

class Asset:
    def __init__(self): self.nodes = []; self.anims = {}

    def node(self, name, mesh=None, parent=None, t=(0, 0, 0), r=(0, 0, 0, 1), s=(1, 1, 1)):
        self.nodes.append({'name': name, 'mesh': mesh, 'parent': parent, 't': t, 'r': r, 's': s})
        return len(self.nodes) - 1

    def channel(self, anim, node, path, times, values):
        assert len(times) == len(values), (anim, node, path)
        self.anims.setdefault(anim, []).append((node, path, times, values))

    def save(self, filename):
        binary = bytearray(); views = []; accessors = []; materials = []; matidx = {}; meshes = []

        def view(data, target=None):
            binary.extend(b'\0' * ((-len(binary)) % 4))
            v = {'buffer': 0, 'byteOffset': len(binary), 'byteLength': len(data)}
            if target: v['target'] = target
            binary.extend(data); views.append(v); return len(views) - 1

        def accessor(values, kind, target=None):
            width = {'SCALAR': 1, 'VEC3': 3, 'VEC4': 4}[kind]
            rows = [v if width > 1 else (v,) for v in values]
            flat = [c for row in rows for c in row]
            a = {'bufferView': view(struct.pack(f'<{len(flat)}f', *flat), target), 'componentType': 5126,
                 'count': len(rows), 'type': kind,
                 'min': [min(r[i] for r in rows) for i in range(width)], 'max': [max(r[i] for r in rows) for i in range(width)]}
            accessors.append(a); return len(accessors) - 1

        nodes = []; triangles = 0
        for nd in self.nodes:
            j = {'name': nd['name']}
            if tuple(nd['t']) != (0, 0, 0): j['translation'] = list(nd['t'])
            if tuple(nd['r']) != (0, 0, 0, 1): j['rotation'] = list(nd['r'])
            if tuple(nd['s']) != (1, 1, 1): j['scale'] = list(nd['s'])
            mesh = nd['mesh']
            if mesh and mesh.groups:
                prims = []
                for key, points in mesh.groups.items():
                    if key not in matidx:
                        matidx[key] = len(materials)
                        materials.append({'name': key, 'pbrMetallicRoughness': {'baseColorFactor': [*COLORS[key], 1], 'metallicFactor': 0, 'roughnessFactor': 1}})
                    prims.append({'attributes': {'POSITION': accessor(points, 'VEC3', 34962)}, 'material': matidx[key], 'mode': 4})
                    triangles += len(points) // 3
                meshes.append({'name': nd['name'], 'primitives': prims}); j['mesh'] = len(meshes) - 1
            nodes.append(j)
        for i, nd in enumerate(self.nodes):
            if nd['parent'] is not None: nodes[nd['parent']].setdefault('children', []).append(i)
        animations = []
        for name, chs in self.anims.items():
            samplers = []; channels = []
            for node, path, times, values in chs:
                samplers.append({'input': accessor(times, 'SCALAR'), 'output': accessor(values, 'VEC4' if path == 'rotation' else 'VEC3'),
                                 'interpolation': 'LINEAR'})
                channels.append({'sampler': len(samplers) - 1, 'target': {'node': node, 'path': path}})
            animations.append({'name': name, 'samplers': samplers, 'channels': channels})
        doc = {'asset': {'version': '2.0', 'generator': 'Sillage / create_assets.py'}, 'scene': 0,
               'scenes': [{'nodes': [i for i, nd in enumerate(self.nodes) if nd['parent'] is None]}],
               'nodes': nodes, 'meshes': meshes, 'materials': materials,
               'buffers': [{'byteLength': len(binary)}], 'bufferViews': views, 'accessors': accessors}
        if animations: doc['animations'] = animations
        text = json.dumps(doc, separators=(',', ':')).encode(); text += b' ' * ((-len(text)) % 4)
        binary.extend(b'\0' * ((-len(binary)) % 4))
        size = 12 + 8 + len(text) + 8 + len(binary)
        glb = struct.pack('<III', 0x46546C67, 2, size) + struct.pack('<II', len(text), 0x4E4F534A) + text + struct.pack('<II', len(binary), 0x004E4942) + binary
        (OUT / filename).write_bytes(glb)
        print(f'{filename}: {len(glb)} octets, {triangles} triangles, {len(self.nodes)} nœuds, animations {list(self.anims)}')


def single(mesh, filename):
    a = Asset(); a.node(filename.removesuffix('.glb'), mesh); a.save(filename)


# ---------------------------------------------------------------- terrain

land = Mesh(); n = 64
vertices = [(0, 1.5, 0)]
rings = [(2.5, 1.50), (5.5, 1.46), (8, 1.35), (10, 1.1), (11.6, .48), (12.5, -.18)]
for r, y in rings:
    for i in range(n):
        a = TAU * i / n; radius = r * (1 + .055 * math.sin(3 * a) + .045 * math.cos(5 * a))
        x = radius * math.cos(a); z = radius * .83 * math.sin(a)
        hill = 1.65 * math.exp(-((x - 3.8) ** 2 + (z + 3) ** 2) / 10) if r < 10 else 0
        vertices.append((x, y + hill, z))
for i in range(n): land.tri(vertices[0], vertices[1 + (i + 1) % n], vertices[1 + i], 'herbe')
for k in range(len(rings) - 1):
    a = 1 + k * n; b = a + n; mat = 'herbe' if k < 2 else 'sable'
    for i in range(n):
        j = (i + 1) % n
        land.tri(vertices[a + i], vertices[b + j], vertices[b + i], mat)
        land.tri(vertices[a + i], vertices[a + j], vertices[b + j], mat)
GROUND = [t for t in land.triangles()]
for i in range(14): land.box(-1.8, .91, 7.6 + i * .36, 2.1, .18, .32, 'bois')
single(land, 'terrain.glb')


def ground(x, z):
    """Hauteur du sol (sans le ponton) en (x, z), comme Triangle.hauteur du moteur."""
    best = None
    for a, b, c in GROUND:
        den = (b[2] - c[2]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[2] - c[2])
        if abs(den) < 1e-9: continue
        u = ((b[2] - c[2]) * (x - c[0]) + (c[0] - b[0]) * (z - c[2])) / den
        v = ((c[2] - a[2]) * (x - c[0]) + (a[0] - c[0]) * (z - c[2])) / den
        if u < -1e-4 or v < -1e-4 or u + v > 1.0001: continue
        h = u * a[1] + v * b[1] + (1 - u - v) * c[1]
        best = h if best is None else max(best, h)
    return best if best is not None else -1.0


def on_grass(x, z):
    """Vrai sur l'herbe, loin des bâtiments et des chemins."""
    if ground(x, z) < 1.34: return False
    if -4.6 < x < -1.4 and -3.4 < z < .4: return False          # maison et banc
    if (x - 4) ** 2 + (z + 3) ** 2 < 1.3 ** 2: return False      # phare
    return True


# ---------------------------------------------------------------- village (décor fixe)

v = Mesh()
for i in range(16):  # mêmes tirages qu'avant : les rochers ne bougent pas
    a = random.uniform(0, TAU); r = random.uniform(9.5, 11)
    v.sphere(math.cos(a) * r, .7, math.sin(a) * r * .83, .55, .65, .4, 'roche', 7, 4)

# Maison : façade avant vers +Z (z = -0.8), porte au milieu.
v.box(-3, 1.45, -2, 2.7, 1.7, 2.4, 'creme')
v.box(-3, 1.40, -2, 2.8, .12, 2.5, 'pierre')                                 # soubassement
p = [(-4.7, 3.1, -3.5), (-1.3, 3.1, -3.5), (-4.7, 3.1, -.5), (-1.3, 3.1, -.5), (-3, 4.2, -3.5), (-3, 4.2, -.5)]
v.tri(p[0], p[1], p[4], 'toit'); v.tri(p[2], p[5], p[3], 'toit')
v.quad(p[0], p[4], p[5], p[2], 'toit'); v.quad(p[1], p[3], p[5], p[4], 'toit')
v.tube((-3, 4.22, -3.55), (-3, 4.22, -.45), .07, .07, 'bois', 6)              # faîtage
v.box(-3, 3.12, -2, 2.72, .08, 2.42, 'bois')                                # sablière sous le toit
v.box(-2.0, 3.2, -2.6, .42, 1.4, .42, 'pierre'); v.box(-2.0, 4.6, -2.6, .5, .1, .5, 'pierre')  # cheminée
v.box(-3, 1.45, -.80, .86, 1.36, .05, 'blanc')                              # encadrement de porte
v.box(-3, 1.45, -.78, .7, 1.2, .035, 'bleu')
v.box(-2.78, 2.0, -.755, .06, .06, .04, 'or')                               # poignée
v.box(-3, 1.40, -.55, .9, .07, .4, 'pierre')                                # marche
for x in [-3.91, -2.09]:
    v.box(x, 2.24, -.80, .58, .64, .04, 'blanc')                             # cadre
    v.box(x, 2.3, -.78, .42, .52, .04, 'vitre')
    v.box(x, 2.3, -.765, .04, .52, .03, 'blanc'); v.box(x, 2.54, -.765, .42, .04, .03, 'blanc')  # croisillons
    for side in (-1, 1): v.box(x + side * .34, 2.24, -.77, .18, .64, .04, 'bleu')                  # volets
    v.box(x, 2.08, -.66, .56, .16, .22, 'bois_clair')                        # jardinière
    for k in range(5):
        v.sphere(x - .2 + k * .1, 2.29, -.66 + (.04 if k % 2 else -.04), .055, .05, .055, ['corail', 'jaune', 'rose'][k % 3], 6, 3)
# Tonneaux et caisses à droite de la maison, banc devant.
for bx, bz in [(-1.25, -1.5), (-1.2, -2.15)]:
    y0 = ground(bx, bz)
    v.cone(bx, y0, bz, .26, .26, .72, 'bois_clair', 10)
    for hy in (.12, .58): v.cone(bx, y0 + hy, bz, .275, .275, .05, 'fer', 10)
    v.disk(bx, y0 + .725, bz, .24, .24, 'bois', 10)
for cx, cz, cy, sz in [(-1.3, -2.9, 0, .55), (-1.3, -2.9, .55, .4), (-.75, -3.0, 0, .4)]:
    y0 = ground(cx, cz) + cy
    v.box(cx, y0, cz, sz, sz, sz, 'bois_clair')
    v.box(cx, y0 + sz * .42, cz, sz + .02, sz * .16, sz + .02, 'bois')
y0 = ground(-4.0, -.35)
v.box(-4.0, y0 + .38, -.35, 1.0, .07, .34, 'bois_clair')
v.box(-4.0, y0 + .62, -.52, 1.0, .22, .05, 'bois_clair')
for lx in (-4.42, -3.58): v.box(lx, y0, -.35, .07, .4, .3, 'bois')

# Phare : porte, fenêtres, galerie, salle de la lampe (la lampe tournante est dans port.glb).
v.cone(4, 2.85, -3, .68, .49, 3.3, 'creme', 12)
v.cone(4, 4.89, -3, .58, .55, .38, 'corail', 12)
gy = ground(4, -2.3)
v.box(4, gy - .05, -2.33, .42, .85, .08, 'bleu'); v.box(4, gy + .80, -2.34, .5, .08, .08, 'blanc')
for wy, wr in [(4.1, .62), (5.6, .55)]: v.box(4, wy, -3 + wr - .01, .2, .32, .06, 'vitre')
v.cone(4, 6.08, -3, .88, .88, .08, 'blanc', 16)                            # plancher de galerie
for i in range(12):
    a = i * TAU / 12; b = (i + 1) * TAU / 12
    pa = (4 + math.cos(a) * .82, 6.16, -3 + math.sin(a) * .82); pb = (4 + math.cos(b) * .82, 6.16, -3 + math.sin(b) * .82)
    v.box(pa[0], 6.16, pa[2], .04, .34, .04, 'noir')
    v.tube(add(pa, (0, .32, 0)), add(pb, (0, .32, 0)), .02, .02, 'noir', 4)
for i in range(6):
    a = i * TAU / 6
    v.box(4 + math.cos(a) * .5, 6.16, -3 + math.sin(a) * .5, .05, .5, .05, 'noir')
v.cone(4, 6.62, -3, .7, 0, .55, 'toit', 12)
v.sphere(4, 7.2, -3, .08, .08, .08, 'noir', 6, 3)

# Ponton : pieux, bittes d'amarrage, lanterne.
for i in range(0, 14, 3):
    for px in (-2.75, -.85): v.box(px, -.7, 7.6 + i * .36, .14, 1.62, .14, 'bois')
for bx in (-2.6, -1.0): v.cone(bx, 1.09, 12.25, .09, .08, .22, 'noir', 8)
v.box(-.85, 1.09, 12.35, .1, 1.5, .1, 'bois'); v.box(-.85, 2.59, 12.35, .22, .26, .22, 'fer')
v.box(-.85, 2.62, 12.35, .16, .2, .24, 'lampe'); v.box(-.85, 2.62, 12.35, .24, .2, .16, 'lampe')

# Chemin de pierres de la porte au ponton, puis vers le phare.
def path(a, b, step=.62):
    d = math.dist(a, b); k = int(d / step)
    for i in range(k + 1):
        t = i / max(k, 1); x = a[0] + (b[0] - a[0]) * t; z = a[1] + (b[1] - a[1]) * t
        off = .18 if i % 2 else -.18
        nx, nz = -(b[1] - a[1]) / d, (b[0] - a[0]) / d
        x += nx * off; z += nz * off
        v.disk(x, ground(x, z) + .03, z, .22 + random.uniform(-.04, .04), .17, 'pierre', 7)
path((-3, -.1), (-1.85, 7.3))
path((-2.4, .1), (3.6, -2.0))

# Fleurs, touffes d'herbe et buissons, seulement sur l'herbe libre.
for _ in range(26):
    cx = random.uniform(-8, 8); cz = random.uniform(-6.5, 6.5)
    if not on_grass(cx, cz): continue
    color = random.choice(['jaune', 'rose', 'blanc', 'violet', 'corail'])
    for _ in range(random.randint(3, 6)):
        x = cx + random.uniform(-.45, .45); z = cz + random.uniform(-.45, .45)
        if not on_grass(x, z): continue
        y = ground(x, z); h = random.uniform(.14, .26)
        v.box(x, y, z, .02, h, .02, 'palme')
        for k in range(5):
            a = k * TAU / 5
            v.box(x + math.cos(a) * .045, y + h, z + math.sin(a) * .045, .05, .025, .05, color)
        v.box(x, y + h + .01, z, .035, .03, .035, 'or')
for _ in range(110):
    x = random.uniform(-9, 9); z = random.uniform(-7.5, 7.5)
    if not on_grass(x, z): continue
    y = ground(x, z) - .01
    for k in range(3):
        a = random.uniform(0, TAU); lean = random.uniform(.03, .08)
        tip = (x + math.cos(a) * lean, y + random.uniform(.16, .28), z + math.sin(a) * lean)
        side = (math.cos(a + 1.6) * .035, 0, math.sin(a + 1.6) * .035)
        v.face([add((x, y, z), side), add((x, y, z), mul(side, -1)), tip], 'herbe_fonce', (math.cos(a), .3, math.sin(a)))
BUSHES = [(-2.0, -4.25), (5.5, -4.6), (2.6, 1.3), (-0.3, -3.7), (-6.4, -1.8), (6.6, .1)]
for bx, bz in BUSHES:
    y0 = ground(bx, bz)
    for k in range(4):
        a = k * TAU / 4 + .5
        v.sphere(bx + math.cos(a) * .28, y0 + .3, bz + math.sin(a) * .22, .38, .34, .36, 'buisson' if k % 2 else 'herbe_fonce', 8, 4)
    for k in range(3): v.sphere(bx + random.uniform(-.3, .3), y0 + .55, bz - .3, .05, .05, .05, 'corail', 5, 3)

# Plage : galets, étoiles de mer, bois flotté, château de sable.
for _ in range(36):
    a = random.uniform(0, TAU); r = random.uniform(9.9, 11.4)
    x = math.cos(a) * r; z = math.sin(a) * r * .83
    if -3.2 < x < -.4 and z > 6: continue
    v.sphere(x, ground(x, z) + .02, z, random.uniform(.06, .13), .05, random.uniform(.05, .1), 'pierre', 6, 3)
for a in [.6, 2.3, 3.9, 5.2]:
    x = math.cos(a) * 10.7; z = math.sin(a) * 10.7 * .83
    v.star(x, ground(x, z) + .02, z, .2, 'orange', a)
d0 = (6.8, 0, 7.4); d1 = (8.3, 0, 6.2)
v.tube((d0[0], ground(d0[0], d0[2]) + .1, d0[2]), (d1[0], ground(d1[0], d1[2]) + .1, d1[2]), .13, .09, 'roche', 7)
sx, sz = 4.6, 8.4; sy = ground(sx, sz) - .05
v.box(sx, sy, sz, .9, .32, .9, 'sable_mouille')
for dx, dz in [(-.45, -.45), (.45, -.45), (-.45, .45), (.45, .45)]:
    v.cone(sx + dx, sy, sz + dz, .14, .12, .5, 'sable_mouille', 8); v.cone(sx + dx, sy + .5, sz + dz, .12, 0, .14, 'sable', 8)
v.cone(sx, sy + .32, sz, .22, .18, .34, 'sable_mouille', 8); v.cone(sx, sy + .66, sz, .2, 0, .2, 'sable', 8)
v.box(sx, sy + .86, sz, .015, .3, .015, 'bois'); v.face([(sx, sy + 1.16, sz), (sx, sy + 1.04, sz), (sx + .16, sy + 1.1, sz)], 'corail', (0, 0, 1))
single(v, 'village.glb')


# ---------------------------------------------------------------- marin

hero = Asset()
root = hero.node('marin')

m = Mesh()                                                   # bassin : ceinture et haut du pantalon
m.box(0, -.12, 0, .44, .14, .28, 'marine')
m.box(0, -.03, 0, .465, .08, .305, 'cuir'); m.box(0, -.02, -.16, .09, .06, .02, 'or')
hips = hero.node('bassin', m, root, t=(0, .56, 0))

m = Mesh()                                                   # torse : marinière, col, foulard
m.box(0, 0, 0, .44, .56, .28, 'blanc')
for k in range(4): m.box(0, .06 + k * .11, 0, .45, .04, .29, 'marine')
m.box(0, .38, .146, .36, .18, .02, 'marine')                  # col marin dans le dos
for side in (-1, 1):
    m.face([(side * .2, .56, -.142), (side * .2, .5, -.142), (side * .02, .36, -.142), (side * .05, .36, -.142)], 'marine', (0, 0, -1))
m.box(0, .44, -.155, .1, .08, .04, 'corail')
m.face([(-.07, .44, -.162), (.07, .44, -.162), (0, .3, -.162)], 'corail', (0, 0, -1))
m.box(0, .56, 0, .13, .07, .13, 'peau')                       # cou
torso = hero.node('torse', m, hips, t=(0, .02, 0))

m = Mesh()                                                   # tête : visage, cheveux, chapeau de paille
m.sphere(0, .17, 0, .26, .27, .23, 'peau', 12, 6)
m.sphere(0, .21, .06, .25, .22, .2, 'brun', 10, 5)
for side in (-1, 1):
    m.box(side * .09, .17, -.214, .085, .08, .02, 'blanc')
    m.box(side * .09, .165, -.226, .045, .055, .012, 'noir')
    m.box(side * .09, .245, -.218, .09, .022, .02, 'brun')
    m.box(side * .15, .085, -.188, .065, .035, .02, 'joue')
    m.sphere(side * .25, .15, 0, .045, .065, .045, 'peau', 6, 3)
    m.box(side * .245, .1, -.03, .04, .14, .1, 'brun')
m.box(0, .1, -.24, .055, .07, .05, 'peau')
m.box(0, .045, -.214, .09, .02, .02, 'bouche')
m.cone(0, .35, 0, .44, .44, .045, 'paille', 14)
m.cone(0, .395, 0, .25, .22, .2, 'paille', 12)
m.cone(0, .40, 0, .27, .258, .07, 'corail', 12)
head = hero.node('tete', m, torso, t=(0, .62, 0))

def limb(name, parent, t, upper, lower, joint):
    up = hero.node(name, upper, parent, t=t)
    return up, hero.node(name + '_bas', lower, up, t=(0, -joint, 0))

arms = {}
for side, tag in [(1, 'd'), (-1, 'g')]:
    u = Mesh(); u.box(0, -.25, 0, .15, .28, .17, 'blanc')
    for k in range(2): u.box(0, -.2 + k * .1, 0, .16, .035, .18, 'marine')
    u.sphere(0, -.01, 0, .09, .07, .09, 'blanc', 8, 4)
    l = Mesh(); l.box(0, -.04, 0, .145, .06, .165, 'blanc'); l.box(0, -.2, 0, .12, .18, .14, 'peau')
    l.box(0, -.3, -.01, .13, .11, .15, 'peau')
    arms[tag] = limb('bras_' + tag, torso, (side * .31, .5, 0), u, l, .25)
legs = {}
for side, tag in [(1, 'd'), (-1, 'g')]:
    u = Mesh(); u.box(0, -.27, 0, .17, .29, .21, 'marine')
    l = Mesh(); l.box(0, -.18, 0, .15, .2, .18, 'marine'); l.box(0, -.12, 0, .16, .04, .19, 'blanc')
    l.box(0, -.265, -.03, .17, .13, .26, 'cuir'); l.box(0, -.28, -.03, .18, .03, .275, 'semelle')
    legs[tag] = limb('jambe_' + tag, hips, (side * .12, -.02, 0), u, l, .27)

def anim(name, duration, count, poses):
    """poses(t, phase) → {nœud: {'translation': v, 'rotation': q}} ; une clé par instant."""
    times = keys(duration, count); tracks = {}
    for t in times:
        for nd, props in poses(t, TAU * t / duration).items():
            for path, val in props.items(): tracks.setdefault((nd, path), []).append(val)
    for (nd, path), vals in tracks.items(): hero.channel(name, nd, path, times, vals)

def idle(t, ph):
    return {hips: {'translation': (0, .56 + .006 * math.sin(ph), 0)},
            torso: {'rotation': qx(.025 * math.sin(ph))},
            head: {'rotation': qeuler(x=.03 * math.sin(ph + 1), y=.10 * math.sin(ph))},
            arms['d'][0]: {'rotation': qz(.07 + .02 * math.sin(ph))}, arms['g'][0]: {'rotation': qz(-.07 - .02 * math.sin(ph))},
            arms['d'][1]: {'rotation': qx(.12)}, arms['g'][1]: {'rotation': qx(.12)},
            legs['d'][0]: {'rotation': qx(0)}, legs['g'][0]: {'rotation': qx(0)},
            legs['d'][1]: {'rotation': qx(0)}, legs['g'][1]: {'rotation': qx(0)}}
anim('idle', 2.4, 25, idle)

def knee(ph): return -(.15 + 1.0 * max(0.0, -math.sin(ph - math.pi / 4)))
def run(t, ph):
    return {hips: {'translation': (0, .54 - .03 * math.cos(2 * ph), 0), 'rotation': qy(.12 * math.sin(ph))},
            torso: {'rotation': qeuler(x=-.14, y=-.2 * math.sin(ph))},
            head: {'rotation': qeuler(x=.1, y=.08 * math.sin(ph))},
            legs['d'][0]: {'rotation': qx(.75 * math.sin(ph))}, legs['g'][0]: {'rotation': qx(.75 * math.sin(ph + math.pi))},
            legs['d'][1]: {'rotation': qx(knee(ph))}, legs['g'][1]: {'rotation': qx(knee(ph + math.pi))},
            arms['d'][0]: {'rotation': qeuler(x=-.7 * math.sin(ph), z=.1)}, arms['g'][0]: {'rotation': qeuler(x=.7 * math.sin(ph), z=-.1)},
            arms['d'][1]: {'rotation': qx(1.15 + .2 * math.sin(ph))}, arms['g'][1]: {'rotation': qx(1.15 - .2 * math.sin(ph))}}
anim('run', .6, 17, run)

def pose(values):
    return lambda t, ph: values
anim('jump', .2, 2, pose({hips: {'translation': (0, .56, 0), 'rotation': qy(0)}, torso: {'rotation': qx(-.1)}, head: {'rotation': qx(-.15)},
                          legs['d'][0]: {'rotation': qx(.95)}, legs['d'][1]: {'rotation': qx(-1.35)},
                          legs['g'][0]: {'rotation': qx(-.3)}, legs['g'][1]: {'rotation': qx(-.45)},
                          arms['d'][0]: {'rotation': qeuler(x=2.0, z=.8)}, arms['g'][0]: {'rotation': qeuler(x=2.0, z=-.8)},
                          arms['d'][1]: {'rotation': qx(.4)}, arms['g'][1]: {'rotation': qx(.4)}}))
anim('fall', .3, 2, pose({hips: {'translation': (0, .56, 0), 'rotation': qy(0)}, torso: {'rotation': qx(.06)}, head: {'rotation': qx(.12)},
                          legs['d'][0]: {'rotation': qx(.35)}, legs['d'][1]: {'rotation': qx(-.6)},
                          legs['g'][0]: {'rotation': qx(-.15)}, legs['g'][1]: {'rotation': qx(-.25)},
                          arms['d'][0]: {'rotation': qeuler(x=.3, z=1.3)}, arms['g'][0]: {'rotation': qeuler(x=.3, z=-1.3)},
                          arms['d'][1]: {'rotation': qx(.2)}, arms['g'][1]: {'rotation': qx(.2)}}))
hero.save('marin.glb')


# ---------------------------------------------------------------- palmiers (vent)

palms = Asset(); proot = palms.node('palmiers')
for i, (x, z) in enumerate([(-5, -1), (-6, 3), (6, 1), (1, -5), (-3, -4)]):
    random.seed(100 + i)
    lean_a = random.uniform(0, TAU); lean = random.uniform(.25, .5)
    trunk = Mesh(); pts = []
    for k in range(6):
        t = k / 5
        pts.append((math.cos(lean_a) * lean * t * t, 2.95 * t, math.sin(lean_a) * lean * t * t))
    for k in range(5):
        trunk.tube(pts[k], pts[k + 1], .2 - .02 * k, .18 - .02 * k, 'bois', 8)
        trunk.tube(add(pts[k + 1], (0, -.06, 0)), add(pts[k + 1], (0, .02, 0)), .2 - .02 * k, .19 - .02 * k, 'roche', 8)
    top = pts[-1]
    for j in range(3): trunk.sphere(top[0] + .2 * math.cos(j * 2.1), top[1] - .2, top[2] + .2 * math.sin(j * 2.1), .13, .15, .13, 'bois', 6, 3)
    tree = palms.node(f'palmier{i}', trunk, proot, t=(x, 1.5, z))
    crown = Mesh()
    for j in range(8):
        a = j * TAU / 8 + random.uniform(-.2, .2)
        dirv = (math.cos(a), 0, math.sin(a)); side = (-math.sin(a), 0, math.cos(a))
        prof = [(0, .05, .02), (.6, .38, .26), (1.3, .45, .34), (1.9, .2, .28), (2.4, -.45, .04)]
        mat = 'palme' if j % 2 else 'palme_claire'
        for k in range(len(prof) - 1):
            r0, h0, w0 = prof[k]; r1, h1, w1 = prof[k + 1]
            c0 = (dirv[0] * r0, h0, dirv[2] * r0); c1 = (dirv[0] * r1, h1, dirv[2] * r1)
            for s in (-1, 1):
                e0 = add(c0, add(mul(side, s * w0), (0, -.1 * w0, 0))); e1 = add(c1, add(mul(side, s * w1), (0, -.1 * w1, 0)))
                crown.face([c0, c1, e1, e0], mat, (0, 1, 0))
    palms.node(f'palmes{i}', crown, tree, t=top)
    period = [3, 4, 6, 4, 3][i]; phase = random.uniform(0, TAU)
    times = keys(12, 49)
    palms.channel('vent', len(palms.nodes) - 1, 'rotation', times,
                  [qeuler(x=.06 * math.sin(TAU * t / period + phase), z=.05 * math.sin(TAU * t / period + phase + 1.3)) for t in times])
palms.save('palmiers.glb')
random.seed(23)


# ---------------------------------------------------------------- ciel (nuages, mouettes)

sky = Asset()
clouds = sky.node('nuages')
for k, (x, z, y) in enumerate([(-18, -18, 12), (16, -23, 10), (1, -29, 13), (-24, 12, 11), (21, 14, 12)]):
    c = Mesh()
    for dx, h, s in [(-1.6, 0, 1.0), (0, .5, 1.25), (1.6, 0, 1.0), (.8, -.2, .8), (-.7, .35, .9)]:
        c.sphere(dx * s, h, 0, 2 * s, .8 * s, 1.1 * s, 'nuage', 10, 4)
    sky.node(f'nuage{k}', c, clouds, t=(x, y, z), r=qy(random.uniform(0, TAU)))
times, vals = turn_keys(60, 1, 1, 8)
sky.channel('vol', clouds, 'rotation', times, vals)
for k, (radius, height, period, sign) in enumerate([(7, 8.5, 12, 1), (9.5, 10, 15, -1), (12, 7.5, 20, 1)]):
    pivot = sky.node(f'vol{k}', None, None, t=(0, height, 0))
    body = Mesh()
    body.sphere(0, 0, 0, .09, .08, .22, 'blanc', 8, 4); body.sphere(0, .04, -.2, .07, .07, .07, 'blanc', 6, 3)
    body.box(0, .01, -.31, .03, .025, .08, 'or')
    body.face([(-.06, 0, .18), (.06, 0, .18), (0, .01, .34)], 'blanc', (0, 1, 0))
    gull = sky.node(f'mouette{k}', body, pivot, t=(radius, 0, 0), r=qmul(qy(0 if sign > 0 else math.pi), qz(.25 * sign)))
    wings = []
    for side in (1, -1):
        w = Mesh()
        w.face([(0, 0, -.08), (side * .32, .02, -.06), (side * .3, .02, .08), (0, 0, .07)], 'blanc', (0, 1, 0))
        w.face([(side * .32, .02, -.06), (side * .55, .0, .02), (side * .3, .02, .08)], 'noir', (0, 1, 0))
        wings.append((sky.node(f'aile{k}{"d" if side > 0 else "g"}', w, gull, t=(side * .06, .03, 0)), side))
    times, vals = turn_keys(60, 60 // period, sign, 8)
    sky.channel('vol', pivot, 'rotation', times, vals)
    flap = keys(60, 641)
    for node, side in wings:
        sky.channel('vol', node, 'rotation', flap,
                    [qz(side * (.15 + .45 * (.55 + .45 * math.sin(TAU * t / 6 + k))) * math.sin(TAU * t / .75)) for t in flap])
sky.save('ciel.glb')


# ---------------------------------------------------------------- port (bateau, bouées, lampe, fumée)

port = Asset()
boat = Mesh()
boat.sphere(0, 0, 0, .85, .52, 1.8, 'toit', 12, 6)
boat.face([(math.cos(i * TAU / 16) * .78, .21, math.sin(i * TAU / 16) * 1.66) for i in range(16)], 'bois_clair', (0, 1, 0))
for zz in (-.6, .5): boat.box(0, .21, zz, 1.3, .06, .25, 'bois')
boat.cone(0, .05, .2, .05, .04, 3.6, 'bois', 8)
boat.face([(0, 3.5, .2), (0, .8, .2), (0, 1.0, -1.5)], 'lin', (1, 0, 0))
boat.tube((0, .95, .2), (0, .95, -1.55), .03, .03, 'bois', 5)
boat.box(0, -.25, 1.72, .05, .5, .3, 'bois')
boat.cone(0, .45, .2, .08, .08, .04, 'blanc', 8)
bnode = port.node('bateau', boat, None, t=(-4, .16, 10.7))
flag = Mesh(); flag.face([(0, 0, 0), (0, -.25, 0), (.45, -.12, 0)], 'corail', (0, 0, 1))
fnode = port.node('drapeau', flag, bnode, t=(0, 3.62, .2))
times = keys(12, 97)
port.channel('vie', bnode, 'translation', times, [(-4, .16 + .05 * math.sin(TAU * t / 4), 10.7) for t in times])
port.channel('vie', bnode, 'rotation', times, [qeuler(x=.03 * math.sin(TAU * t / 4 + 1), z=.05 * math.sin(TAU * t / 6)) for t in times])
port.channel('vie', fnode, 'rotation', times, [qy(.35 * math.sin(TAU * t / 1.5) + .2 * math.sin(TAU * t / 3)) for t in times])
for k, (x, z, period) in enumerate([(-8.5, 11, 3), (7.5, 11.5, 4), (13.2, -5, 6)]):
    b = Mesh()
    b.sphere(0, 0, 0, .28, .3, .28, 'corail', 10, 5); b.cone(0, .12, 0, .3, .3, .1, 'blanc', 10)
    b.cone(0, .25, 0, .05, .03, .45, 'fer', 6); b.sphere(0, .72, 0, .07, .07, .07, 'jaune', 6, 3)
    node = port.node(f'bouee{k}', b, None, t=(x, -.3, z))
    port.channel('vie', node, 'translation', times, [(x, -.3 + .07 * math.sin(TAU * t / period + k), z) for t in times])
    port.channel('vie', node, 'rotation', times, [qeuler(x=.1 * math.sin(TAU * t / period + k + 1), z=.08 * math.cos(TAU * t / period + k)) for t in times])
lamp = Mesh()
lamp.cone(0, 0, 0, .34, .34, .46, 'lampe', 12)
for s in (-1, 1): lamp.box(s * .3, .03, 0, .12, .4, .5, 'noir')
lnode = port.node('lampe', lamp, None, t=(4, 6.16, -3))
times2, vals2 = turn_keys(12, 2, 1, 8)
port.channel('vie', lnode, 'rotation', times2, vals2)
puff = Mesh(); puff.sphere(0, 0, 0, 1, 1, 1, 'fumee', 8, 4)
smoke_times = keys(12, 97)
for k in range(3):
    node = port.node(f'fumee{k}', puff, None, t=(-2.0, 4.7, -2.6), s=(0, 0, 0))
    def at(t, k=k):
        u = ((t / 3) + k / 3) % 1.0
        return (-2.0 + .5 * u, 4.75 + 1.6 * u, -2.6 + .2 * u), .42 * math.sin(math.pi * min(u / .9, 1.0)) ** .7
    port.channel('vie', node, 'translation', smoke_times, [at(t)[0] for t in smoke_times])
    port.channel('vie', node, 'scale', smoke_times, [(at(t)[1],) * 3 for t in smoke_times])
port.save('port.glb')


# ---------------------------------------------------------------- objets physiques (origine au centre)

crate = Mesh()                                    # caisse d'un mètre, à mettre à l'échelle
crate.box(0, -.48, 0, .96, .96, .96, 'bois_clair')
for a in (-1, 1):
    for b in (-1, 1):
        crate.box(0, a * .45 - .05, b * .45, 1.0, .1, .1, 'bois')      # arêtes le long de X
        crate.box(a * .45, -.5, b * .45, .1, 1.0, .1, 'bois')         # arêtes verticales
        crate.box(a * .45, b * .45 - .05, 0, .1, .1, 1.0, 'bois')     # arêtes le long de Z
for side in (-1, 1):                                # planches diagonales sur deux faces
    for k in range(-1, 2):
        crate.box(k * .25, -.42, side * .485, .2, .84, .02, 'bois_clair')
single(crate, 'caisse.glb')

ball = Mesh()                                     # ballon de plage de rayon 1, six fuseaux colorés
segments = ['corail', 'blanc', 'jaune', 'bleu', 'blanc', 'palme_claire']
n, rings = 24, 12
def bp(i, j): a = math.pi * i / rings; b = TAU * j / n; return (math.sin(a) * math.cos(b), math.cos(a), math.sin(a) * math.sin(b))
for i in range(rings):
    for j in range(n):
        mat = 'blanc' if i == 0 or i == rings - 1 else segments[j * 6 // n]
        ball.quad(bp(i, j), bp(i + 1, j), bp(i + 1, j + 1), bp(i, j + 1), mat)
single(ball, 'ballon.glb')
