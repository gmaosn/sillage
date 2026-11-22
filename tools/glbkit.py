"""Ce qu'il faut pour écrire des GLB 2.0 sans Blender ni bibliothèque tierce : maillages par matière,
quaternions, nœuds, animations (TRS, LINEAR), volumes de collision et lampes comme Blender les exporte.
Partagé par les générateurs de modèles des démos (create_assets.py, create_temple.py).

L'appelant règle `glbkit.OUT` (le dossier où écrire) et remplit `glbkit.COLORS` (les matières : nom →
couleur linéaire r, v, b)."""
import json, math, struct

TAU = math.tau
OUT = None
COLORS = {}


# ---------------------------------------------------------------- géométrie

def sub(a, b): return (a[0] - b[0], a[1] - b[1], a[2] - b[2])
def add(a, b): return (a[0] + b[0], a[1] + b[1], a[2] + b[2])
def mul(a, s): return (a[0] * s, a[1] * s, a[2] * s)
def cross(a, b): return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
def dot(a, b): return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
def norm(a):
    l = math.sqrt(dot(a, a)); return mul(a, 1 / l) if l else a


class Mesh:
    def __init__(self):
        self.groups = {}
        # Teintes par sommet (COLOR_0), facultatives : `shade(point, normale de la face, matière)` rend un facteur
        # (r, v, b) qui multiplie la couleur de la matière (salissures, mousse, creux assombris) ; `mul` le
        # multiplie encore (un réglage du moment : le fond d'une cannelure). Sans `shade`, rien n'est écrit.
        self.colors = {}; self.shade = None; self.mul = 1.0

    def tri(self, a, b, c, mat, col=None):
        n = cross(sub(b, a), sub(c, a))
        if dot(n, n) < 1e-16: return              # triangle sans aire (pointe d'un cône, pôle d'une sphère)
        self.groups.setdefault(mat, []).extend([a, b, c])
        if self.shade is not None or col is not None:
            if col is None:
                nn = norm(n); col = [self.shade(p, nn, mat) for p in (a, b, c)]
            if self.mul != 1.0: col = [mul(k, self.mul) for k in col]
            self.colors.setdefault(mat, []).extend(col)
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

    def cone(self, x, y, z, r1, r2, h, mat, n=16):
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

    def tube(self, p0, p1, r0, r1, mat, n=12):
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

    def sphere(self, x, y, z, sx, sy, sz, mat, n=16, rings=8):
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

    def quad_out(self, q, mat, center):
        """Quadrilatère orienté vers l'extérieur (normale par les diagonales, sûre aux pôles où un côté s'écrase)."""
        n = cross(sub(q[2], q[0]), sub(q[3], q[1]))
        mid = mul(add(add(q[0], q[1]), add(q[2], q[3])), .25)
        if dot(n, sub(mid, center)) < 0: q = q[::-1]
        for a, b, c in ((q[0], q[1], q[2]), (q[0], q[2], q[3])):
            if dot(cross(sub(b, a), sub(c, a)), cross(sub(b, a), sub(c, a))) > 1e-14: self.tri(a, b, c, mat)

    def rounded(self, x, y, z, w, h, d, mat, e=.45, n=18, rings=10, band=None):
        """Boîte arrondie (superellipsoïde) : (x, z) centre, y = dessous. e règle l'arrondi : 0,1 presque une
        boîte, 0,5 un galet, 1 un ellipsoïde. band(y) donne la matière selon la hauteur (rayures)."""
        c = (x, y + h / 2, z); a, b, cc = w / 2, h / 2, d / 2
        def sp(v, m): return math.copysign(abs(v) ** m, v)
        def p(i, j):
            th = -math.pi / 2 + math.pi * i / rings; ph = -math.pi + TAU * j / n
            return (c[0] + a * sp(math.cos(th), e) * sp(math.cos(ph), e), c[1] + b * sp(math.sin(th), e),
                    c[2] + cc * sp(math.cos(th), e) * sp(math.sin(ph), e))
        for i in range(rings):
            for j in range(n):
                q = [p(i, j), p(i, j + 1), p(i + 1, j + 1), p(i + 1, j)]
                m = band(sum(v[1] for v in q) / 4) if band else mat
                self.quad_out(q, m, c)

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

    def node(self, name, mesh=None, parent=None, t=(0, 0, 0), r=(0, 0, 0, 1), s=(1, 1, 1), extras=None, light=None):
        """Un nœud ; extras : propriétés personnalisées (comme celles de Blender) ; light : une lampe KHR_lights_punctual."""
        self.nodes.append({'name': name, 'mesh': mesh, 'parent': parent, 't': t, 'r': r, 's': s, 'extras': extras, 'light': light})
        return len(self.nodes) - 1

    def collider(self, name, x, y, z, width, height, depth, parent=None, **extras):
        """Un volume de collision invisible, comme dans Blender : un vide « col_… » (cube de côté 2) mis à l'échelle."""
        return self.node('col_' + name, None, parent, t=(x, y, z), s=(width / 2, height / 2, depth / 2), extras=extras or None)

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

        nodes = []; triangles = 0; lights = []
        for nd in self.nodes:
            j = {'name': nd['name']}
            if nd.get('extras'): j['extras'] = nd['extras']
            if nd.get('light'):
                lights.append(nd['light'])
                j['extensions'] = {'KHR_lights_punctual': {'light': len(lights) - 1}}
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
                    attributes = {'POSITION': accessor(points, 'VEC3', 34962)}
                    tints = mesh.colors.get(key)
                    if tints:
                        assert len(tints) == len(points), (nd['name'], key, 'teintes données à une partie seulement des triangles')
                        attributes['COLOR_0'] = accessor([tuple(min(4.0, max(0.0, v)) for v in t) for t in tints], 'VEC3', 34962)
                    prims.append({'attributes': attributes, 'material': matidx[key], 'mode': 4})
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
        if lights:
            doc['extensionsUsed'] = ['KHR_lights_punctual']
            doc['extensions'] = {'KHR_lights_punctual': {'lights': lights}}
        text = json.dumps(doc, separators=(',', ':')).encode(); text += b' ' * ((-len(text)) % 4)
        binary.extend(b'\0' * ((-len(binary)) % 4))
        size = 12 + 8 + len(text) + 8 + len(binary)
        glb = struct.pack('<III', 0x46546C67, 2, size) + struct.pack('<II', len(text), 0x4E4F534A) + text + struct.pack('<II', len(binary), 0x004E4942) + binary
        (OUT / filename).write_bytes(glb)
        print(f'{filename}: {len(glb)} octets, {triangles} triangles, {len(self.nodes)} nœuds, animations {list(self.anims)}')


def single(mesh, filename):
    a = Asset(); a.node(filename.removesuffix('.glb'), mesh); a.save(filename)

