#!/usr/bin/env python3
"""Référence des polices du moteur : FreeType (libfreetype du système, par ctypes) trame des glyphes sans
hinting en niveaux de gris, et donne les numéros de glyphes, les avances, l'en-tête de la police et le
crénage de la table « kern » ; HarfBuzz (libharfbuzz, par ctypes) donne le crénage GPOS en composant des
paires de lettres. Rien de tout cela ne sert au moteur : seulement aux essais (engine/test/sillage/ui).

Usage : python3 tools/freetype_reference.py   (depuis la racine du projet)
Écrit engine/test@jvm/fixtures/fonts/<nom>.txt.gz ; une police absente est passée (l'essai la passera aussi).
"""
import ctypes, gzip, os, struct, sys

ft = ctypes.CDLL('libfreetype.so.6')
hb = ctypes.CDLL('libharfbuzz.so.0')

FT_Long = ctypes.c_long; FT_Pos = ctypes.c_long; FT_Fixed = ctypes.c_long

class FT_Generic(ctypes.Structure):
    _fields_ = [('data', ctypes.c_void_p), ('finalizer', ctypes.c_void_p)]
class FT_BBox(ctypes.Structure):
    _fields_ = [('xMin', FT_Pos), ('yMin', FT_Pos), ('xMax', FT_Pos), ('yMax', FT_Pos)]
class FT_Vector(ctypes.Structure):
    _fields_ = [('x', FT_Pos), ('y', FT_Pos)]
class FT_Glyph_Metrics(ctypes.Structure):
    _fields_ = [(n, FT_Pos) for n in ('width', 'height', 'horiBearingX', 'horiBearingY', 'horiAdvance', 'vertBearingX', 'vertBearingY', 'vertAdvance')]
class FT_Bitmap(ctypes.Structure):
    _fields_ = [('rows', ctypes.c_uint), ('width', ctypes.c_uint), ('pitch', ctypes.c_int), ('buffer', ctypes.POINTER(ctypes.c_ubyte)),
                ('num_grays', ctypes.c_ushort), ('pixel_mode', ctypes.c_ubyte), ('palette_mode', ctypes.c_ubyte), ('palette', ctypes.c_void_p)]
class FT_GlyphSlotRec(ctypes.Structure):
    _fields_ = [('library', ctypes.c_void_p), ('face', ctypes.c_void_p), ('next', ctypes.c_void_p), ('glyph_index', ctypes.c_uint),
                ('generic', FT_Generic), ('metrics', FT_Glyph_Metrics), ('linearHoriAdvance', FT_Fixed), ('linearVertAdvance', FT_Fixed),
                ('advance', FT_Vector), ('format', ctypes.c_int), ('bitmap', FT_Bitmap), ('bitmap_left', ctypes.c_int), ('bitmap_top', ctypes.c_int)]
class FT_FaceRec(ctypes.Structure):
    _fields_ = [('num_faces', FT_Long), ('face_index', FT_Long), ('face_flags', FT_Long), ('style_flags', FT_Long), ('num_glyphs', FT_Long),
                ('family_name', ctypes.c_char_p), ('style_name', ctypes.c_char_p), ('num_fixed_sizes', ctypes.c_int), ('available_sizes', ctypes.c_void_p),
                ('num_charmaps', ctypes.c_int), ('charmaps', ctypes.c_void_p), ('generic', FT_Generic), ('bbox', FT_BBox),
                ('units_per_EM', ctypes.c_ushort), ('ascender', ctypes.c_short), ('descender', ctypes.c_short), ('height', ctypes.c_short),
                ('max_advance_width', ctypes.c_short), ('max_advance_height', ctypes.c_short), ('underline_position', ctypes.c_short),
                ('underline_thickness', ctypes.c_short), ('glyph', ctypes.POINTER(FT_GlyphSlotRec))]

ft.FT_Init_FreeType.argtypes = [ctypes.POINTER(ctypes.c_void_p)]
ft.FT_New_Face.argtypes = [ctypes.c_void_p, ctypes.c_char_p, FT_Long, ctypes.POINTER(ctypes.POINTER(FT_FaceRec))]
ft.FT_Set_Char_Size.argtypes = [ctypes.POINTER(FT_FaceRec), FT_Long, FT_Long, ctypes.c_uint, ctypes.c_uint]
ft.FT_Get_Char_Index.argtypes = [ctypes.POINTER(FT_FaceRec), ctypes.c_ulong]; ft.FT_Get_Char_Index.restype = ctypes.c_uint
ft.FT_Load_Glyph.argtypes = [ctypes.POINTER(FT_FaceRec), ctypes.c_uint, ctypes.c_int32]
ft.FT_Render_Glyph.argtypes = [ctypes.POINTER(FT_GlyphSlotRec), ctypes.c_int]
ft.FT_Get_Advance.argtypes = [ctypes.POINTER(FT_FaceRec), ctypes.c_uint, ctypes.c_int32, ctypes.POINTER(FT_Fixed)]
ft.FT_Get_Kerning.argtypes = [ctypes.POINTER(FT_FaceRec), ctypes.c_uint, ctypes.c_uint, ctypes.c_uint, ctypes.POINTER(FT_Vector)]
ft.FT_Done_Face.argtypes = [ctypes.POINTER(FT_FaceRec)]

FT_LOAD_NO_SCALE = 1; FT_LOAD_NO_HINTING = 2; FT_LOAD_NO_BITMAP = 8; FT_KERNING_UNSCALED = 2; FT_RENDER_MODE_NORMAL = 0

hb.hb_blob_create_from_file.argtypes = [ctypes.c_char_p]; hb.hb_blob_create_from_file.restype = ctypes.c_void_p
hb.hb_face_create.argtypes = [ctypes.c_void_p, ctypes.c_uint]; hb.hb_face_create.restype = ctypes.c_void_p
hb.hb_font_create.argtypes = [ctypes.c_void_p]; hb.hb_font_create.restype = ctypes.c_void_p
hb.hb_buffer_create.restype = ctypes.c_void_p
hb.hb_buffer_add_utf8.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_uint, ctypes.c_int]
hb.hb_buffer_guess_segment_properties.argtypes = [ctypes.c_void_p]
hb.hb_buffer_clear_contents.argtypes = [ctypes.c_void_p]
hb.hb_feature_from_string.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_void_p]
hb.hb_shape.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint]
hb.hb_buffer_get_glyph_infos.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint)]; hb.hb_buffer_get_glyph_infos.restype = ctypes.POINTER(ctypes.c_uint32 * 5)
hb.hb_buffer_get_glyph_positions.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint)]; hb.hb_buffer_get_glyph_positions.restype = ctypes.POINTER(ctypes.c_int32 * 5)

LATIN = 'AaBbCcegHIiJjMmOoQRSsWwxyz0123456789&@%?§éèêçàùôœÉ«»—’'
PAIRS = ['AV', 'AT', 'To', 'Ty', 'Wa', 'LT', 'Yo', 'Fa', 'P.', 'r,', 'VA', 'av', 'Té', 'll', 'oo']

# (fichier, face, nom, caractères, tailles en pixels, paires de crénage)
FONTS = [
    ('/usr/share/fonts/noto/NotoSans-Regular.ttf', 0, 'noto-sans', LATIN, (11, 16, 40), PAIRS),
    ('/usr/share/fonts/TTF/DejaVuSans.ttf', 0, 'dejavu-sans', LATIN + 'ŒÆ', (13, 32), PAIRS),
    ('/usr/share/fonts/liberation/LiberationSerif-Regular.ttf', 0, 'liberation-serif', 'AaQgéçW&', (15, 36), PAIRS),
    ('/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc', 0, 'noto-cjk-0', 'Aagé永字中あア', (14, 36), ['AV', 'To']),
    ('/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc', 3, 'noto-cjk-3', 'Rg永国', (20,), []),
    ('/usr/share/fonts/OTF/PowerlineSymbols.otf', 0, 'powerline', '', (16, 30), []),
]


def checksum(path, index):
    d = open(path, 'rb').read()
    o = struct.unpack('>I', d[12 + 4 * index:16 + 4 * index])[0] if d[:4] == b'ttcf' else 0
    for i in range(struct.unpack('>H', d[o + 4:o + 6])[0]):
        r = d[o + 12 + 16 * i:o + 28 + 16 * i]
        if r[:4] == b'head': return struct.unpack('>I', d[struct.unpack('>I', r[8:12])[0] + 8:][:4])[0], len(d)
    raise SystemExit('pas de table head : ' + path)


def gpos_kerning(path, index, pairs, gids):
    """Le crénage de chaque paire d'après HarfBuzz : avance composée du premier glyphe moins son avance seule."""
    blob = hb.hb_blob_create_from_file(path.encode()); face = hb.hb_face_create(blob, index); font = hb.hb_font_create(face)
    buf = hb.hb_buffer_create(); out = {}
    for p in pairs:
        hb.hb_buffer_clear_contents(buf)
        data = p.encode('utf-8')
        hb.hb_buffer_add_utf8(buf, data, len(data), 0, len(data)); hb.hb_buffer_guess_segment_properties(buf)
        hb.hb_shape(font, buf, None, 0)
        n = ctypes.c_uint(); info = hb.hb_buffer_get_glyph_infos(buf, ctypes.byref(n)); pos = hb.hb_buffer_get_glyph_positions(buf, ctypes.byref(n))
        if n.value != 2 or info[0][0] != gids[p[0]] or info[1][0] != gids[p[1]]: continue      # ligature ou substitution : pas une paire simple
        out[p] = pos[0][0] - adv_units[(path, index, gids[p[0]])]          # avance composée du premier glyphe − son avance
    return out


adv_units = {}
lib = ctypes.c_void_p(); assert ft.FT_Init_FreeType(ctypes.byref(lib)) == 0
os.makedirs('engine/test@jvm/fixtures/fonts', exist_ok=True)
for path, index, name, chars, sizes, pairs in FONTS:
    if not os.path.exists(path): print('absente :', path); continue
    face = ctypes.POINTER(FT_FaceRec)()
    assert ft.FT_New_Face(lib, path.encode(), index, ctypes.byref(face)) == 0, path
    f = face.contents
    check, size = checksum(path, index)
    lines = ['font %s %d %d %08x' % (path, index, size, check),
             'metrics %d %d %d %d %d' % (f.units_per_EM, f.ascender, f.descender, f.height, f.num_glyphs)]
    gids = {}
    for c in chars:
        g = ft.FT_Get_Char_Index(face, ord(c)); gids[c] = g
        a = FT_Fixed(); ft.FT_Get_Advance(face, g, FT_LOAD_NO_SCALE, ctypes.byref(a)); adv_units[(path, index, g)] = a.value
        lines.append('char %d %d %d' % (ord(c), g, a.value))
    # Crénage de la table « kern » (FreeType) et GPOS (HarfBuzz), en unités de la police.
    for p in pairs:
        for c in p:
            if c not in gids:
                g = ft.FT_Get_Char_Index(face, ord(c)); gids[c] = g
                a = FT_Fixed(); ft.FT_Get_Advance(face, g, FT_LOAD_NO_SCALE, ctypes.byref(a)); adv_units[(path, index, g)] = a.value
        v = FT_Vector(); ft.FT_Get_Kerning(face, gids[p[0]], gids[p[1]], FT_KERNING_UNSCALED, ctypes.byref(v))
        lines.append('kern %d %d %d' % (gids[p[0]], gids[p[1]], v.x))
    for p, k in gpos_kerning(path, index, pairs, gids).items():
        lines.append('gpos %d %d %d' % (gids[p[0]], gids[p[1]], k))
    for px in sizes:
        assert ft.FT_Set_Char_Size(face, 0, px * 64, 72, 72) == 0
        for c in chars:
            g = gids[c]
            if g == 0: continue
            assert ft.FT_Load_Glyph(face, g, FT_LOAD_NO_HINTING | FT_LOAD_NO_BITMAP) == 0
            slot = f.glyph
            assert ft.FT_Render_Glyph(slot, FT_RENDER_MODE_NORMAL) == 0
            s = slot.contents; b = s.bitmap
            rows = bytearray()
            for y in range(b.rows):
                rows += bytes(b.buffer[y * b.pitch + x] for x in range(b.width))
            lines.append('glyph %d %d %d %d %d %d %s' % (px, g, s.bitmap_left, s.bitmap_top, b.width, b.rows, rows.hex()))
    ft.FT_Done_Face(face)
    out = 'engine/test@jvm/fixtures/fonts/%s.txt.gz' % name
    with gzip.open(out, 'wt', encoding='utf-8') as o: o.write('\n'.join(lines) + '\n')
    print('%-55s face %d → %s (%d lignes)' % (path, index, out, len(lines)))
