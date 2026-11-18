#!/usr/bin/env python3
"""GLB 2.0 autonomes pour la démo, générés sans Blender ni bibliothèque tierce.
Repère glTF : Y vers le haut, mètres, marin vers -Z.
"""
from pathlib import Path
import json, math, struct, random
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'demo/resources/models'
OUT.mkdir(parents=True,exist_ok=True)
random.seed(17)
COLORS={
    'herbe':(.24,.52,.10), 'sable':(.82,.60,.27), 'roche':(.40,.29,.16),
    'bois':(.29,.13,.045), 'palme':(.075,.32,.07), 'creme':(.87,.76,.49),
    'toit':(.64,.13,.045), 'bleu':(.035,.15,.25), 'lin':(.90,.83,.62),
    'peau':(.67,.36,.16), 'corail':(.83,.075,.025), 'noir':(.025,.022,.02)
}
class Scene:
    def __init__(self): self.groups={}
    def tri(self,a,b,c,mat): self.groups.setdefault(mat,[]).extend([a,b,c])
    def quad(self,a,b,c,d,mat): self.tri(a,b,c,mat); self.tri(a,c,d,mat)
    def box(self,x,y,z,w,h,d,mat):
        p=[(x+(-1 if i&1==0 else 1)*w/2,y+(0 if i&2==0 else h),z+(-1 if i&4==0 else 1)*d/2) for i in range(8)]
        for f in [(0,4,6,2),(1,3,7,5),(0,1,5,4),(2,6,7,3),(0,2,3,1),(4,5,7,6)]: self.quad(*(p[i] for i in f),mat)
    def cone(self,x,y,z,r1,r2,h,mat,n=10):
        for i in range(n):
            a=i*math.tau/n; b=(i+1)*math.tau/n
            p=(x+math.cos(a)*r1,y,z+math.sin(a)*r1)
            q=(x+math.cos(b)*r1,y,z+math.sin(b)*r1)
            u=(x+math.cos(a)*r2,y+h,z+math.sin(a)*r2)
            v=(x+math.cos(b)*r2,y+h,z+math.sin(b)*r2)
            self.quad(p,u,v,q,mat)
            self.tri((x,y,z),p,q,mat)
            if r2>0:self.tri((x,y+h,z),v,u,mat)
    def sphere(self,x,y,z,sx,sy,sz,mat,n=10,rings=5):
        def p(i,j):
            a=math.pi*i/rings; b=math.tau*j/n
            return (x+math.sin(a)*math.cos(b)*sx,y+math.cos(a)*sy,z+math.sin(a)*math.sin(b)*sz)
        for i in range(rings):
            for j in range(n): self.quad(p(i,j),p(i+1,j),p(i+1,j+1),p(i,j+1),mat)
    def save(self,name):
        binary=bytearray(); views=[]; accessors=[]; primitives=[]; materials=[]
        for key,points in self.groups.items():
            start=len(binary)
            for p in points: binary.extend(struct.pack('<3f',*p))
            views.append({'buffer':0,'byteOffset':start,'byteLength':len(binary)-start,'target':34962})
            accessors.append({'bufferView':len(views)-1,'componentType':5126,'count':len(points),'type':'VEC3',
                'min':[min(p[i] for p in points) for i in range(3)],'max':[max(p[i] for p in points) for i in range(3)]})
            materials.append({'name':key,'pbrMetallicRoughness':{'baseColorFactor':[*COLORS[key],1], 'metallicFactor':0,'roughnessFactor':1}})
            primitives.append({'attributes':{'POSITION':len(accessors)-1},'material':len(materials)-1,'mode':4})
        doc={'asset':{'version':'2.0','generator':'Sillage / create_assets.py'},'scene':0,'scenes':[{'nodes':[0]}],
            'nodes':[{'mesh':0,'name':name}],'meshes':[{'primitives':primitives}], 'materials':materials,
            'buffers':[{'byteLength':len(binary)}],'bufferViews':views,'accessors':accessors}
        text=json.dumps(doc,separators=(',',':')).encode(); text+=b' '*((-len(text))%4)
        binary+=b'\0'*((-len(binary))%4)
        size=12+8+len(text)+8+len(binary)
        glb=struct.pack('<III',0x46546C67,2,size)+struct.pack('<II',len(text),0x4E4F534A)+text+struct.pack('<II',len(binary),0x004E4942)+binary
        (OUT/name).write_bytes(glb)
        print(name, len(glb),'octets',sum(len(x)//3 for x in self.groups.values()),'triangles')

land=Scene(); n=64
vertices=[(0,1.5,0)]
rings=[(2.5,1.50),(5.5,1.46),(8,1.35),(10,1.1),(11.6,.48),(12.5,-.18)]
for r,y in rings:
    for i in range(n):
        a=math.tau*i/n; radius=r*(1+.055*math.sin(3*a)+.045*math.cos(5*a))
        x=radius*math.cos(a); z=radius*.83*math.sin(a)
        hill=1.65*math.exp(-((x-3.8)**2+(z+3)**2)/10) if r<10 else 0
        vertices.append((x,y+hill,z))
for i in range(n):land.tri(vertices[0],vertices[1+(i+1)%n],vertices[1+i],'herbe')
for k in range(len(rings)-1):
    a=1+k*n; b=a+n; mat='herbe' if k<2 else 'sable'
    for i in range(n):
        j=(i+1)%n
        land.tri(vertices[a+i],vertices[b+j],vertices[b+i],mat)
        land.tri(vertices[a+i],vertices[a+j],vertices[b+j],mat)
for i in range(14):land.box(-1.8,.91,7.6+i*.36,2.1,.18,.32,'bois')
land.save('terrain.glb')

v=Scene()
for x,z in [(-5,-1),(-6,3),(6,1),(1,-5),(-3,-4)]:
    v.cone(x,1.5,z,.20,.11,2.9,'bois')
    for j in range(7):
        a=j*math.tau/7
        def p(r,h,w=0):return (x+math.cos(a)*r+math.sin(a)*w,4.5+h,z+math.sin(a)*r-math.cos(a)*w)
        v.quad(p(0,0),p(1,.35,.38),p(2.5,-.65),p(1,.35,-.38),'palme')
    for j in range(3):v.sphere(x+.22*math.cos(j*2),4.3,z+.22*math.sin(j*2),.14,.17,.14,'bois',6,3)
v.box(-3,1.45,-2,2.7,1.7,2.4,'creme')
p=[(-4.7,3.1,-3.5),(-1.3,3.1,-3.5),(-4.7,3.1,-.5),(-1.3,3.1,-.5),(-3,4.2,-3.5),(-3,4.2,-.5)]
v.tri(p[0],p[1],p[4],'toit');v.tri(p[2],p[5],p[3],'toit')
v.quad(p[0],p[4],p[5],p[2],'toit');v.quad(p[1],p[3],p[5],p[4],'toit')
v.box(-3,1.45,-.78,.7,1.2,.035,'bleu')
for x in [-3.91,-2.09]:v.box(x,2.3,-.78,.42,.52,.04,'bleu')
v.cone(4,2.85,-3,.68,.49,3.3,'creme',12)
v.cone(4,4.89,-3,.58,.55,.38,'corail',12)
v.cone(4,6.15,-3,.62,.62,.46,'bleu',12)
v.cone(4,6.57,-3,.85,0,.55,'toit',12)
for i in range(16):
    a=random.uniform(0,math.tau); r=random.uniform(9.5,11)
    v.sphere(math.cos(a)*r,.7,math.sin(a)*r*.83,.55,.65,.4,'roche',7,4)
# Voilier, amarré au ponton.
v.sphere(-4,.16,10.7,.85,.52,1.8,'toit',10,5)
v.cone(-4,.2,10.7,.05,.04,3.6,'bois')
v.tri((-4,3.7,10.7),(-4,1,10.7),(-4,1.2,9),'lin')
for x,z,y in [(-18,-18,12),(16,-23,10),(1,-29,13)]:
    for dx,h in [(-1.6,0),(0,.5),(1.6,0)]: v.sphere(x+dx,y+h,z,2,.8,1,'lin',10,4)
v.save('village.glb')

m=Scene();m.box(0,.54,0,.46,.56,.30,'bleu')
for x in [-.13,.13]:
    m.box(x,.08,0,.16,.45,.2,'lin');m.box(x,.01,-.065,.19,.17,.32,'bois')
for x in [-.33,.33]:m.box(x,.62,0,.16,.43,.2,'peau')
m.sphere(0,1.34,0,.27,.29,.24,'peau',10,5)
m.cone(0,1.54,0,.42,.42,.07,'sable',12);m.cone(0,1.61,0,.25,.22,.21,'sable',12)
m.box(0,1.00,-.17,.39,.12,.06,'corail')
for x in [-.1,.1]:m.box(x,1.33,-.225,.045,.055,.025,'noir')
m.save('marin.glb')
