"""Blender -> trois GLB statiques, sans textures ni dépendances externes.
blender --background --python tools/create_island.py
"""
import bpy, math, random
from pathlib import Path
from mathutils import Vector
random.seed(17)
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'demo/resources/models'
OUT.mkdir(parents=True, exist_ok=True)
bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete(use_global=False)

def mat(name, rgb):
    m = bpy.data.materials.new(name)
    m.diffuse_color = (*rgb, 1)
    m.use_nodes = True
    m.node_tree.nodes['Principled BSDF'].inputs['Base Color'].default_value = (*rgb, 1)
    m.node_tree.nodes['Principled BSDF'].inputs['Roughness'].default_value = 1
    return m
sand = mat('Sable miel', (.86,.66,.32))
grass = mat('Herbe citron vert', (.30,.60,.16))
cliff = mat('Roche ocre', (.50,.36,.19))
trunk = mat('Bois chaud', (.32,.16,.07))
leaf = mat('Palmes', (.12,.39,.14))
plaster = mat('Creme', (.94,.83,.57))
roof = mat('Toit terre cuite', (.66,.19,.09))
blue = mat('Bleu marin', (.06,.24,.37))
white = mat('Lin', (.96,.91,.70))
skin = mat('Peau', (.77,.48,.27))
red = mat('Foulard corail', (.90,.16,.07))

def mesh(name, vertices, faces, materials, face_mats=None):
    data = bpy.data.meshes.new(name)
    data.from_pydata(vertices, [], faces)
    data.update()
    o = bpy.data.objects.new(name, data)
    bpy.context.collection.objects.link(o)
    for m in materials: data.materials.append(m)
    if face_mats:
        for p,i in zip(data.polygons,face_mats): p.material_index = i
    return o

def cube(name, loc, scale, material):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc)
    o = bpy.context.object; o.name = name; o.scale = scale
    o.data.materials.append(material)
    return o

def cone(name, loc, r1, r2, depth, material, segments=9):
    bpy.ops.mesh.primitive_cone_add(vertices=segments, radius1=r1, radius2=r2, depth=depth, location=loc)
    o=bpy.context.object; o.name=name; o.data.materials.append(material)
    return o

def ico(name, loc, scale, material, subdivisions=1):
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=subdivisions, radius=1, location=loc)
    o=bpy.context.object; o.name=name; o.scale=scale; o.data.materials.append(material)
    return o

def export(name):
    bpy.ops.object.select_all(action='SELECT')
    bpy.ops.export_scene.gltf(filepath=str(OUT / name), export_format='GLB', use_selection=True,
        export_animations=False, export_materials='EXPORT', export_yup=True)
    bpy.ops.object.delete(use_global=False)

# Anneaux triangulés : plage douce, plateau herbeux, petite colline.
N=64
vertices=[(0,0,1.50)]
rings=[(2.5,1.50),(5.5,1.46),(8.0,1.35),(10.0,1.10),(11.6,.48),(12.5,-.18)]
for r,z in rings:
    for i in range(N):
        a=2*math.pi*i/N
        radius=r*(1+.055*math.sin(3*a)+.045*math.cos(5*a))
        x=radius*math.cos(a); y=radius*.83*math.sin(a)
        hill=1.65*math.exp(-((x-3.8)**2+(y-3.0)**2)/10) if r < 10 else 0
        vertices.append((x,y,z+hill))
faces=[]; ids=[]
for i in range(N): faces.append((0,1+i,1+(i+1)%N)); ids.append(0)
for ring in range(len(rings)-1):
    a=1+ring*N; b=a+N
    for i in range(N):
        j=(i+1)%N
        faces.extend([(a+i,b+i,b+j),(a+i,b+j,a+j)])
        ids.extend([0 if ring<2 else 1]*2)
mesh('Ile des Alizes',vertices,faces,[grass,sand],ids)
# Ponton, praticable et inclus dans la géométrie de collision.
for i in range(14): cube('Planche',(-1.8,-7.6-i*.36,1.00), (2.1,.32,.18),trunk)
export('terrain.glb')

# Décor : plusieurs palmiers, maison, phare et voilier.
for tx,ty in [(-5,1),(-6,-3),(6,-1),(1,5),(-3,4)]:
    z=1.50
    cone('Tronc',(tx,ty,z+1.45),.20,.11,2.9,trunk)
    for j in range(7):
        a=j*2*math.pi/7
        def p(r,h,w=0): return (tx+math.cos(a)*r+math.sin(a)*w,ty+math.sin(a)*r-math.cos(a)*w,z+3+h)
        mesh('Palme',[p(0,0),p(1,.35,.38),p(2.5,-.65),p(1,.35,-.38)],[(0,1,2),(0,2,3)],[leaf])
    for j in range(3): ico('Noix de coco',(tx+.22*math.cos(j*2),ty+.22*math.sin(j*2),z+2.8),(.15,.15,.17),trunk)
# maison x=-3,z=-2 (coordonnées moteur); Blender y = -z
cube('Maison',(-3,2,2.3),(2.7,2.4,1.7),plaster)
mesh('Toit',[(-4.7,.5,3.1),(-1.3,.5,3.1),(-4.7,3.5,3.1),(-1.3,3.5,3.1),(-3,.5,4.2),(-3,3.5,4.2)],
    [(0,1,4),(2,5,3),(0,4,5,2),(1,3,5,4)],[roof])
cube('Porte',(-3,.785,2.02),(.70,.035,1.20),blue)
for x in [-3.91,-2.09]: cube('Volet',(x,.78,2.57),(.42,.04,.52),blue)
# phare sur la colline (x=4,z=-3)
cone('Phare',(4,3,4.5),.68,.49,3.3,plaster,12)
cone('Bande rouge',(4,3,5.08),.58,.55,.38,red,12)
cone('Lanterne',(4,3,6.38),.62,.62,.46,blue,12)
cone('Chapeau',(4,3,6.85),.85,0,.55,roof,12)
for i in range(16):
    a=random.uniform(0,math.tau); r=random.uniform(9.5,11.0)
    ico('Rocher',(math.cos(a)*r,math.sin(a)*r*.83,.7),(.55,.4,.65),cliff)
# bateau près du ponton
ico('Coque',(-4,-10.7,.18),(.85,1.8,.52),roof,2)
cone('Mat',(-4,-10.7,1.8),.05,.04,3.6,trunk)
mesh('Voile',[(-4,-10.7,3.5),(-4,-10.7,.8),(-4,-9.0,1.0)],[(0,1,2)],[white])
# Nuages stylisés au large, pas de collisions.
for x,y,z in [(-18,18,12),(16,23,10),(1,29,13)]:
    for dx,h in [(-1.6,0),(0,.5),(1.6,0)]: ico('Nuage',(x+dx,y,z+h),(2,1,.8),white,2)
export('village.glb')

# Petit marin original, pied à z=0. Animation rigide gérée en Kotlin.
cube('Corps',(0,0,.82),(.46,.30,.56),blue)
for x in [-.13,.13]:
    cube('Jambe',(x,0,.30),(.16,.20,.45),white)
    cube('Botte',(x,-.065,.10),(.19,.32,.17),trunk)
for x in [-.33,.33]:
    cube('Bras',(x,0,.83),(.16,.20,.43),skin)
ico('Tete',(0,0,1.34),(.27,.24,.29),skin,2)
cone('Bord du chapeau',(0,0,1.57),.42,.42,.07,sand,12)
cone('Chapeau',(0,0,1.69),.25,.22,.21,sand,12)
cube('Foulard',(0,-.17,1.05),(.39,.06,.12),red)
for x in [-.10,.10]: cube('Oeil',(x,-.225,1.36),(.045,.025,.055),trunk)
export('marin.glb')
print('SILLAGE_ASSETS_OK', OUT)
