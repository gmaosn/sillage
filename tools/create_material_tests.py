#!/usr/bin/env python3
"""Les GLB d'essai des matières complètes (contrôle qualité `rendu`, tests du moteur), sans bibliothèque tierce :

  relief.glb            un panneau de 2 × 2 m tourné vers +Z, une carte de relief de seize bosses, sans tangentes
                        (le moteur les calcule) ;
  relief_tangentes.glb  le même avec ses tangentes dans le fichier (TANGENT) : les deux doivent se ressembler ;
  emission.glb          un panneau sombre dont une image d'émission fait luire des bandes (emissiveFactor × image) ;
  verre.glb             une vitre bleutée (alphaMode BLEND, opacité 0,35) devant un pilier rouge opaque.

    python3 tools/create_material_tests.py      → qa/resources/essais/
"""
import math
import pathlib

import glbkit

glbkit.OUT = pathlib.Path(__file__).resolve().parent.parent / 'qa' / 'resources' / 'essais'
glbkit.OUT.mkdir(parents=True, exist_ok=True)

SIDE = 64


def bump(x, y):
    """Seize bosses rondes : la hauteur (0 à 1) au point x, y de l'image (en texels, y vers le bas)."""
    u = (x + .5) / SIDE * 4; v = (y + .5) / SIDE * 4
    du = u - math.floor(u) - .5; dv = v - math.floor(v) - .5
    r2 = (du * du + dv * dv) / .16
    return math.sqrt(1 - r2) if r2 < 1 else 0.0


def normal_texel(x, y):
    # En espace tangent : X vers la droite de l'image, Y vers son haut (glTF), Z hors de la surface.
    e = .5
    dx = (bump(x + e, y) - bump(x - e, y)) / (2 * e) * 6
    dy = (bump(x, y + e) - bump(x, y - e)) / (2 * e) * 6       # y de l'image vers le bas : le haut est −y
    n = (-dx, dy, 1.0); l = math.sqrt(sum(c * c for c in n))
    return tuple(int(round((c / l * .5 + .5) * 255)) for c in n) + (255,)


def panel(material, tangents=False, z=0.0):
    """Deux triangles de (−1, 0) à (1, 2), tournés vers +Z ; u de gauche à droite, v du haut (0) vers le bas (1)."""
    a, b, c, d = (-1, 0, z), (1, 0, z), (1, 2, z), (-1, 2, z)
    uv = {a: (0, 1), b: (1, 1), c: (1, 0), d: (0, 0)}
    pts = [a, b, c, a, c, d]
    p = {'POSITION': pts, 'NORMAL': [(0, 0, 1)] * 6, 'TEXCOORD_0': [uv[q] for q in pts], 'material': material}
    if tangents: p['TANGENT'] = [(1, 0, 0, 1)] * 6
    return p


relief = glbkit.png(SIDE, SIDE, normal_texel)
stone = {'name': 'relief', 'pbrMetallicRoughness': {'baseColorFactor': [.8, .78, .74, 1], 'metallicFactor': 0, 'roughnessFactor': 1},
         'normalTexture': {'index': 0, 'scale': 1.0}}
glbkit.textured('relief.glb', [panel(0)], [stone], [relief])
glbkit.textured('relief_tangentes.glb', [panel(0, tangents=True)], [stone], [relief])


def stripes(x, y):
    on = (x // 8) % 2 == 0
    return (255, 255, 255, 255) if on else (0, 0, 0, 255)


glowing = {'name': 'enseigne_lumineuse', 'pbrMetallicRoughness': {'baseColorFactor': [.05, .05, .06, 1], 'metallicFactor': 0},
           'emissiveFactor': [1.0, .55, .2], 'emissiveTexture': {'index': 0},
           'extensions': {'KHR_materials_emissive_strength': {'emissiveStrength': 2.0}}}
glbkit.textured('emission.glb', [panel(0)], [glowing], [glbkit.png(SIDE, SIDE, stripes)], ['KHR_materials_emissive_strength'])


def box(x0, y0, z0, x1, y1, z1, material):
    """Un pavé, ses faces tournées vers l'extérieur (normales par face)."""
    p = {'POSITION': [], 'NORMAL': [], 'TEXCOORD_0': [], 'material': material}
    faces = [((x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1), (1, 0, 0)), ((x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (x0, y0, z0), (-1, 0, 0)),
             ((x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (0, 1, 0)), ((x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (0, -1, 0)),
             ((x1, y0, z1), (x1, y1, z1), (x0, y1, z1), (x0, y0, z1), (0, 0, 1)), ((x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (x1, y0, z0), (0, 0, -1))]
    for a, b, c, d, n in faces:
        for q in (a, b, c, a, c, d):
            p['POSITION'].append(q); p['NORMAL'].append(n); p['TEXCOORD_0'].append((0, 0))
    return p


glass = {'name': 'verre', 'pbrMetallicRoughness': {'baseColorFactor': [.35, .65, 1.0, .35], 'metallicFactor': 0}, 'alphaMode': 'BLEND'}
red = {'name': 'pilier', 'pbrMetallicRoughness': {'baseColorFactor': [.8, .08, .05, 1], 'metallicFactor': 0}}
glbkit.textured('verre.glb', [panel(0, z=.6), box(-.25, 0, -.6, .25, 1.8, -.1, 1)], [glass, red], [])
