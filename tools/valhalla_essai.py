#!/usr/bin/env python3
"""Essai Valhalla : rend Vec3, Quat et Mat4 classes valeur (JEP 401) dans un jar compilé par Kotlin.

Kotlin ne sait pas encore écrire de classe valeur à plusieurs champs pour la JVM. Cet outil réécrit
le bytecode, pour mesure seulement :
  - Vec3, Quat, Mat4 : sans ACC_IDENTITY, champs d'instance ACC_STRICT_INIT, et dans chaque constructeur
    qui commence par super() puis remplit ses champs, super() déplacé à la fin (ce qu'exige la JEP 401) ;
  - toutes les classes sillage/** : version 71.65535 (JDK 27, aperçu) et attribut LoadableDescriptors
    listant les classes valeur qu'elles nomment, pour que la JVM les connaisse d'avance.
Sans bibliothèque. Usage : valhalla_essai.py entrée.jar sortie.jar
Puis : java --enable-preview ... -jar sortie.jar  (avec une build Valhalla, jdk.java.net/valhalla).
"""
import struct, sys, zipfile

VALUE = {'sillage/math/Vec3', 'sillage/math/Quat', 'sillage/math/Mat4'}
DESCRIPTORS = ['L' + c + ';' for c in sorted(VALUE)]
MAJOR, MINOR = 71, 0xFFFF
ACC_IDENTITY, ACC_STATIC, ACC_STRICT_INIT = 0x0020, 0x0008, 0x0800

# Longueur des instructions (sans les instructions à longueur variable, refusées ici).
LEN = {op: 1 for op in range(256)}
for op in (0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc): LEN[op] = 2
for op in (0x11, 0x13, 0x14, 0x84, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xbb, 0xbd, 0xc0, 0xc1): LEN[op] = 3
for op in (0xb9, 0xba): LEN[op] = 5
LEN[0xc5] = 4
BRANCHES = set(range(0x99, 0xa9)) | {0xc6, 0xc7, 0xc8, 0xc9, 0xaa, 0xab, 0xc4, 0xa9}


class ClassFile:
    def __init__(self, data):
        self.d = data; p = 8
        self.minor, self.major = struct.unpack_from('>HH', data, 4)
        n = struct.unpack_from('>H', data, p)[0]; p += 2
        self.cp = [None]; i = 1
        while i < n:
            tag = data[p]
            if tag == 1:
                l = struct.unpack_from('>H', data, p + 1)[0]; self.cp.append((1, data[p + 3:p + 3 + l])); p += 3 + l
            elif tag in (5, 6): self.cp.append((tag, data[p + 1:p + 9])); self.cp.append(None); p += 9; i += 1
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18): self.cp.append((tag, data[p + 1:p + 5])); p += 5
            elif tag in (7, 8, 16, 19, 20): self.cp.append((tag, data[p + 1:p + 3])); p += 3
            elif tag == 15: self.cp.append((tag, data[p + 1:p + 4])); p += 4
            else: raise ValueError('constant tag %d' % tag)
            i += 1
        self.flags, self.this, self.super = struct.unpack_from('>HHH', data, p); p += 6
        k = struct.unpack_from('>H', data, p)[0]; self.interfaces = data[p:p + 2 + 2 * k]; p += 2 + 2 * k
        self.fields, p = self._members(p)
        self.methods, p = self._members(p)
        self.attributes, p = self._attributes(p)

    def _attributes(self, p):
        n = struct.unpack_from('>H', self.d, p)[0]; p += 2; out = []
        for _ in range(n):
            name, l = struct.unpack_from('>HI', self.d, p); out.append([name, self.d[p + 6:p + 6 + l]]); p += 6 + l
        return out, p

    def _members(self, p):
        n = struct.unpack_from('>H', self.d, p)[0]; p += 2; out = []
        for _ in range(n):
            flags, name, desc = struct.unpack_from('>HHH', self.d, p); attrs, p = self._attributes(p + 6)
            out.append([flags, name, desc, attrs])
        return out, p

    def utf8(self, i): return self.cp[i][1].decode('utf-8', 'surrogatepass')
    def class_name(self, i): return self.utf8(struct.unpack('>H', self.cp[i][1])[0])
    def add_utf8(self, s):
        b = s.encode('utf-8')
        for i, e in enumerate(self.cp):
            if e and e[0] == 1 and e[1] == b: return i
        self.cp.append((1, b)); return len(self.cp) - 1

    def is_object_init(self, i):
        tag, b = self.cp[i]
        if tag != 10: return False
        cls, nat = struct.unpack('>HH', b)
        name, desc = struct.unpack('>HH', self.cp[nat][1])
        return self.class_name(cls) == 'java/lang/Object' and self.utf8(name) == '<init>' and self.utf8(desc) == '()V'

    def write(self):
        out = bytearray(b'\xca\xfe\xba\xbe') + struct.pack('>HHH', self.minor, self.major, len(self.cp))
        for e in self.cp[1:]:
            if e is None: continue
            tag, b = e
            out += bytes([tag]) + (struct.pack('>H', len(b)) if tag == 1 else b'') + b
        out += struct.pack('>HHH', self.flags, self.this, self.super) + self.interfaces
        for members in (self.fields, self.methods):
            out += struct.pack('>H', len(members))
            for flags, name, desc, attrs in members:
                out += struct.pack('>HHH', flags, name, desc) + attributes(attrs)
        return bytes(out + attributes(self.attributes))


def attributes(attrs):
    out = bytearray(struct.pack('>H', len(attrs)))
    for name, b in attrs: out += struct.pack('>HI', name, len(b)) + b
    return out


def late_super(cf, code):
    """Le Code d'un constructeur « super() puis champs » avec super() juste avant le return ; None sinon."""
    max_stack, max_locals, n = struct.unpack_from('>HHI', code, 0)
    body = code[8:8 + n]; rest = code[8 + n:]
    if len(body) < 5 or body[0] != 0x2a or body[1] != 0xb7 or not cf.is_object_init(struct.unpack_from('>H', body, 2)[0]):
        return None
    p = 4; returns = 0
    while p < len(body):
        op = body[p]
        if op in BRANCHES or op in (0xaa, 0xab): raise ValueError('constructeur avec branchements')
        if op == 0xb1: returns += 1
        p += LEN[op]
    if returns != 1 or body[-1] != 0xb1: raise ValueError('constructeur sans return unique final')
    exceptions = struct.unpack_from('>H', rest, 0)[0]
    if exceptions: raise ValueError('constructeur avec exceptions')
    new = body[4:-1] + body[0:4] + b'\xb1'
    # Attributs du Code (tables de lignes et de variables) retirés : ils nomment des positions qui ont bougé.
    return struct.pack('>HHI', max_stack, max_locals, len(new)) + new + struct.pack('>HH', 0, 0)


def rewrite(data):
    cf = ClassFile(data)
    name = cf.class_name(cf.this)
    if name in VALUE:
        cf.flags &= ~ACC_IDENTITY
        for f in cf.fields:
            if not f[0] & ACC_STATIC: f[0] |= ACC_STRICT_INIT
        for m in cf.methods:
            if cf.utf8(m[1]) != '<init>': continue
            for a in m[3]:
                if cf.utf8(a[0]) == 'Code':
                    changed = late_super(cf, a[1])
                    if changed is not None: a[1] = changed
    used = [d for d in DESCRIPTORS if any(e and e[0] == 1 and d.encode() in e[1] for e in cf.cp)]
    if used:
        attr = cf.add_utf8('LoadableDescriptors')
        idx = [cf.add_utf8(d) for d in used]
        cf.attributes.append([attr, struct.pack('>H', len(idx)) + b''.join(struct.pack('>H', i) for i in idx)])
    cf.major, cf.minor = MAJOR, MINOR
    return cf.write(), name in VALUE


def main(src, dst):
    count = values = 0
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename.startswith('sillage/') and item.filename.endswith('.class'):
                data, v = rewrite(data); count += 1; values += v
            zout.writestr(item, data)
    print('%d classes réécrites, %d classes valeur' % (count, values))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
