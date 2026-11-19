#!/usr/bin/env python3
"""Décode des fichiers Ogg Vorbis avec libvorbisfile (le décodeur de référence de Xiph, par ctypes) et écrit
les échantillons en flottants 32 bits entrelacés (fichier .f32 à côté du .ogg). Sert aux essais du décodeur
du moteur. Usage : vorbis_reference.py fichier.ogg..."""
import ctypes, struct, sys

lib = ctypes.CDLL('libvorbisfile.so.3')
lib.ov_fopen.argtypes = [ctypes.c_char_p, ctypes.c_void_p]
lib.ov_read_float.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.POINTER(ctypes.POINTER(ctypes.c_float))), ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
lib.ov_read_float.restype = ctypes.c_long
lib.ov_info.argtypes = [ctypes.c_void_p, ctypes.c_int]
lib.ov_info.restype = ctypes.POINTER(ctypes.c_int * 3)          # version, channels, rate (début de vorbis_info)
lib.ov_clear.argtypes = [ctypes.c_void_p]

for path in sys.argv[1:]:
    vf = ctypes.create_string_buffer(16384)                         # OggVorbis_File, largement
    if lib.ov_fopen(path.encode(), vf) != 0: raise SystemExit('ov_fopen a échoué : ' + path)
    channels = lib.ov_info(vf, -1).contents[1]
    pcm = ctypes.POINTER(ctypes.POINTER(ctypes.c_float))()
    section = ctypes.c_int()
    out = bytearray()
    while True:
        n = lib.ov_read_float(vf, ctypes.byref(pcm), 4096, ctypes.byref(section))
        if n < 0: raise SystemExit('ov_read_float : erreur %d' % n)
        if n == 0: break
        for i in range(n):
            for c in range(channels): out += struct.pack('<f', pcm[c][i])
    lib.ov_clear(vf)
    with open(path[:-4] + '.f32', 'wb') as f: f.write(out)
    print('%s : %d canaux, %d échantillons' % (path, channels, len(out) // 4 // channels))
