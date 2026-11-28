#!/usr/bin/env python3
"""GLB d'essai du chargeur (cibles de morphose, courbes CUBICSPLINE, KHR_texture_transform), sans bibliothèque tierce.

    python3 tools/create_glb_essais.py      # écrit engine/test@jvm/fixtures/glb/*.glb

Chaque fichier est petit et ses valeurs sont choisies pour que les tests (GlbTest) puissent les vérifier à la main.
"""
import json
import math
import os
import struct
import zlib

ICI = os.path.dirname(os.path.abspath(__file__))
SORTIE = os.path.join(ICI, "..", "engine", "test@jvm", "fixtures", "glb")


class Glb:
    """Un glTF binaire en construction : un seul tampon, des vues et des accesseurs ajoutés au fil."""

    def __init__(self):
        self.bin = bytearray()
        self.doc = {"asset": {"version": "2.0", "generator": "tools/create_glb_essais.py"}, "buffers": [{}],
                    "bufferViews": [], "accessors": []}

    def view(self, data):
        while len(self.bin) % 4:
            self.bin.append(0)
        self.doc["bufferViews"].append({"buffer": 0, "byteOffset": len(self.bin), "byteLength": len(data)})
        self.bin += data
        return len(self.doc["bufferViews"]) - 1

    def floats(self, values, kind, size):
        flat = [x for v in values for x in (v if isinstance(v, (list, tuple)) else [v])]
        v = self.view(struct.pack("<%df" % len(flat), *flat))
        acc = {"bufferView": v, "componentType": 5126, "count": len(flat) // size, "type": kind}
        if kind == "VEC3":
            acc["min"] = [min(flat[i::3]) for i in range(3)]
            acc["max"] = [max(flat[i::3]) for i in range(3)]
        if kind == "SCALAR" and len(values) and not isinstance(values[0], (list, tuple)):
            acc["min"] = [min(flat)]; acc["max"] = [max(flat)]
        self.doc["accessors"].append(acc)
        return len(self.doc["accessors"]) - 1

    def indices(self, ids):
        v = self.view(struct.pack("<%dH" % len(ids), *ids))
        self.doc["accessors"].append({"bufferView": v, "componentType": 5123, "count": len(ids), "type": "SCALAR"})
        return len(self.doc["accessors"]) - 1

    def save(self, nom):
        self.doc["buffers"][0]["byteLength"] = len(self.bin)
        j = json.dumps(self.doc, separators=(",", ":")).encode()
        j += b" " * ((4 - len(j) % 4) % 4)
        b = bytes(self.bin) + b"\0" * ((4 - len(self.bin) % 4) % 4)
        total = 12 + 8 + len(j) + 8 + len(b)
        out = struct.pack("<III", 0x46546C67, 2, total) + struct.pack("<II", len(j), 0x4E4F534A) + j + struct.pack("<II", len(b), 0x004E4942) + b
        os.makedirs(SORTIE, exist_ok=True)
        with open(os.path.join(SORTIE, nom), "wb") as f:
            f.write(out)
        print("écrit", nom, len(out), "octets")


def carre(g):
    """Un carré de 1 m dans le plan z = 0, face vers +z : 4 sommets, 2 triangles, normales."""
    pos = g.floats([(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)], "VEC3", 3)
    nrm = g.floats([(0, 0, 1)] * 4, "VEC3", 3)
    uv = g.floats([(0, 0), (1, 0), (1, 1), (0, 1)], "VEC2", 2)
    ids = g.indices([0, 1, 2, 0, 2, 3])
    return pos, nrm, uv, ids


def morphose():
    """Deux cibles sur un carré : « lever » monte le haut de 1 m, « pencher » avance la droite de 0,5 m et tourne ses normales.
    Poids du maillage au repos (0,25 ; 0) ; un second nœud prend les siens (0 ; 1). Clips : linéaire, cubique, palier."""
    g = Glb()
    pos, nrm, uv, ids = carre(g)
    lever = g.floats([(0, 0, 0), (0, 0, 0), (0, 1, 0), (0, 1, 0)], "VEC3", 3)
    lever_n = g.floats([(0, 0, 0)] * 4, "VEC3", 3)
    pencher = g.floats([(0, 0, 0), (0, 0, .5), (0, 0, .5), (0, 0, 0)], "VEC3", 3)
    pencher_n = g.floats([(.6, 0, -.2)] * 4, "VEC3", 3)
    g.doc["meshes"] = [{"name": "carre", "weights": [.25, 0], "extras": {"targetNames": ["lever", "pencher"]},
                        "primitives": [{"attributes": {"POSITION": pos, "NORMAL": nrm}, "indices": ids,
                                        "targets": [{"POSITION": lever, "NORMAL": lever_n}, {"POSITION": pencher, "NORMAL": pencher_n}]}]}]
    g.doc["nodes"] = [{"name": "visage", "mesh": 0}, {"name": "masque", "mesh": 0, "translation": [3, 0, 0], "weights": [0, 1]}]
    g.doc["scenes"] = [{"nodes": [0, 1]}]; g.doc["scene"] = 0
    t3 = g.floats([0.0, 1.0, 2.0], "SCALAR", 1)
    w3 = g.floats([0.0, 0.0, 1.0, .5, 0.0, 0.0], "SCALAR", 1)            # par clé, deux poids
    t2 = g.floats([0.0, 1.0], "SCALAR", 1)
    # Cubique, par clé : tangentes d'entrée (2), valeurs (2), tangentes de sortie (2).
    wc = g.floats([0, 0, 0, 0, 2, 0,    0, 0, 1, 0, 0, 0], "SCALAR", 1)
    ws = g.floats([0.0, 1.0, 1.0, 0.0], "SCALAR", 1)
    g.doc["animations"] = [
        {"name": "respirer", "samplers": [{"input": t3, "output": w3, "interpolation": "LINEAR"}],
         "channels": [{"sampler": 0, "target": {"node": 0, "path": "weights"}}]},
        {"name": "cligner", "samplers": [{"input": t2, "output": wc, "interpolation": "CUBICSPLINE"}],
         "channels": [{"sampler": 0, "target": {"node": 0, "path": "weights"}}]},
        {"name": "sauter", "samplers": [{"input": t2, "output": ws, "interpolation": "STEP"}],
         "channels": [{"sampler": 0, "target": {"node": 1, "path": "weights"}}]},
    ]
    g.save("morphose.glb")


def cubique():
    """Une bouée qui monte d'un mètre en 2 s sur une courbe d'Hermite (tangentes 1 au départ, 0 à l'arrivée) et tourne
    d'un quart de tour autour de Y, tangentes nulles (en douceur)."""
    g = Glb()
    pos, nrm, uv, ids = carre(g)
    g.doc["meshes"] = [{"primitives": [{"attributes": {"POSITION": pos, "NORMAL": nrm}, "indices": ids}]}]
    g.doc["nodes"] = [{"name": "bouee", "mesh": 0}]
    g.doc["scenes"] = [{"nodes": [0]}]; g.doc["scene"] = 0
    t = g.floats([0.0, 2.0], "SCALAR", 1)
    # Par clé : tangente d'entrée, valeur, tangente de sortie (m/s).
    tr = g.floats([(0, 0, 0), (0, 0, 0), (0, 1, 0),    (0, 0, 0), (0, 1, 0), (0, 0, 0)], "VEC3", 3)
    s = math.sqrt(.5)
    ro = g.floats([(0, 0, 0, 0), (0, 0, 0, 1), (0, 0, 0, 0),    (0, 0, 0, 0), (0, s, 0, s), (0, 0, 0, 0)], "VEC4", 4)
    g.doc["animations"] = [{"name": "flotter",
                            "samplers": [{"input": t, "output": tr, "interpolation": "CUBICSPLINE"},
                                         {"input": t, "output": ro, "interpolation": "CUBICSPLINE"}],
                            "channels": [{"sampler": 0, "target": {"node": 0, "path": "translation"}},
                                         {"sampler": 1, "target": {"node": 0, "path": "rotation"}}]}]
    g.save("cubique.glb")


def png(largeur, hauteur, pixels):
    """Un PNG RVBA 8 bits sans filtre (zlib de la bibliothèque standard)."""
    brut = b"".join(b"\0" + bytes(pixels[y * largeur * 4:(y + 1) * largeur * 4]) for y in range(hauteur))
    def bloc(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + bloc(b"IHDR", struct.pack(">IIBBBBB", largeur, hauteur, 8, 6, 0, 0, 0)) + \
        bloc(b"IDAT", zlib.compress(brut)) + bloc(b"IEND", b"")


def transformee():
    """Un carré texturé dont la texture est décalée (0,5 ; 0), tournée d'un quart de tour, étirée deux fois
    (KHR_texture_transform, requise) ; un second matériau sans transformée."""
    g = Glb()
    pos, nrm, uv, ids = carre(g)
    image = g.view(png(2, 2, [255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255, 255, 255, 255]))
    g.doc["images"] = [{"bufferView": image, "mimeType": "image/png", "name": "damier"}]
    g.doc["textures"] = [{"source": 0}]
    g.doc["materials"] = [
        {"name": "tourne", "pbrMetallicRoughness": {"baseColorTexture": {"index": 0, "extensions": {
            "KHR_texture_transform": {"offset": [.5, 0], "rotation": math.pi / 2, "scale": [2, 2]}}}}},
        {"name": "droit", "pbrMetallicRoughness": {"baseColorTexture": {"index": 0}}}]
    g.doc["extensionsUsed"] = ["KHR_texture_transform"]; g.doc["extensionsRequired"] = ["KHR_texture_transform"]
    g.doc["meshes"] = [{"primitives": [{"attributes": {"POSITION": pos, "NORMAL": nrm, "TEXCOORD_0": uv}, "indices": ids, "material": 0}]},
                       {"primitives": [{"attributes": {"POSITION": pos, "NORMAL": nrm, "TEXCOORD_0": uv}, "indices": ids, "material": 1}]}]
    g.doc["nodes"] = [{"name": "tourne", "mesh": 0}, {"name": "droit", "mesh": 1, "translation": [2, 0, 0]}]
    g.doc["scenes"] = [{"nodes": [0, 1]}]; g.doc["scene"] = 0
    g.save("transformee.glb")


if __name__ == "__main__":
    morphose()
    cubique()
    transformee()
