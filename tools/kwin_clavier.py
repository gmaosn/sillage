#!/usr/bin/env python3
"""Essai fenêtré de l'interface dans un KWin isolé : un client Wayland minimal (libwayland-client par ctypes)
qui se sert du protocole de KWin org_kde_kwin_fake_input pour taper de vraies touches (codes evdev, passés
par la disposition du compositeur : AZERTY, touches mortes comprises) et pour bouger et cliquer le pointeur.

Lancé par tools/kwin_essai_interface.sh dans un kwin_wayland --virtual dont la disposition est « fr » et dont
les contrôles de permission sont coupés (KWIN_WAYLAND_NO_PERMISSION_CHECKS=1). Le scénario lit le journal du
jeu (sillage.qa.FenetreKt) pour savoir quand agir et où est le bouton à cliquer.

Usage : kwin_clavier.py journal
"""
import ctypes, re, sys, time
from ctypes import c_char_p, c_int, c_uint32, c_void_p, POINTER, Structure

wl = ctypes.CDLL('libwayland-client.so.0')


class Message(Structure):
    _fields_ = [('name', c_char_p), ('signature', c_char_p), ('types', POINTER(c_void_p))]


class Interface(Structure):
    _fields_ = [('name', c_char_p), ('version', c_int), ('method_count', c_int), ('methods', POINTER(Message)),
                ('event_count', c_int), ('events', POINTER(Message))]


NULLS = (c_void_p * 8)()
# org_kde_kwin_fake_input (plasma-wayland-protocols, fake-input.xml), ses requêtes dans l'ordre.
REQUESTS = [('authenticate', 'ss'), ('pointer_motion', 'ff'), ('button', 'uu'), ('axis', 'uf'), ('touch_down', '2uff'),
            ('touch_motion', '2uff'), ('touch_up', '2u'), ('touch_cancel', '2'), ('touch_frame', '2'),
            ('pointer_motion_absolute', '3ff'), ('keyboard_key', '4uu'), ('destroy', '5')]
methods = (Message * len(REQUESTS))(*[Message(n.encode(), s.encode(), ctypes.cast(NULLS, POINTER(c_void_p))) for n, s in REQUESTS])
FAKE = Interface(b'org_kde_kwin_fake_input', 4, len(REQUESTS), methods, 0, None)

wl.wl_display_connect.restype = c_void_p; wl.wl_display_connect.argtypes = [c_char_p]
wl.wl_display_roundtrip.argtypes = [c_void_p]; wl.wl_display_flush.argtypes = [c_void_p]
wl.wl_proxy_marshal_array_flags.restype = c_void_p
wl.wl_proxy_marshal_array_flags.argtypes = [c_void_p, c_uint32, c_void_p, c_uint32, c_uint32, c_void_p]
wl.wl_proxy_add_listener.argtypes = [c_void_p, c_void_p, c_void_p]
wl.wl_proxy_get_version.argtypes = [c_void_p]; wl.wl_proxy_get_version.restype = c_uint32
registry_interface = c_void_p.in_dll(wl, 'wl_registry_interface')

display = wl.wl_display_connect(None)
if not display: raise SystemExit('pas de compositeur Wayland')


def args(*values):
    """Un tableau de wl_argument (8 octets chacun) : entiers, chaînes (gardées en vie), None."""
    a = (ctypes.c_uint64 * max(1, len(values)))()
    keep = []
    for i, v in enumerate(values):
        if v is None: a[i] = 0
        elif isinstance(v, bytes): s = ctypes.create_string_buffer(v); keep.append(s); a[i] = ctypes.addressof(s)
        else: a[i] = v & 0xFFFFFFFF
    return a, keep


def request(proxy, opcode, interface, version, *values):
    a, keep = args(*values)
    return wl.wl_proxy_marshal_array_flags(proxy, opcode, interface, version, 0, a)


registry = request(display, 1, ctypes.addressof(registry_interface), wl.wl_proxy_get_version(display), None)
found = {}
GLOBAL = ctypes.CFUNCTYPE(None, c_void_p, c_void_p, c_uint32, c_char_p, c_uint32)
REMOVE = ctypes.CFUNCTYPE(None, c_void_p, c_void_p, c_uint32)
def on_global(data, reg, name, interface, version): found[interface.decode()] = (name, version)
on_global_c = GLOBAL(on_global); on_remove_c = REMOVE(lambda *a: None)          # gardés en vie
listener = (c_void_p * 2)(ctypes.cast(on_global_c, c_void_p), ctypes.cast(on_remove_c, c_void_p))
wl.wl_proxy_add_listener(registry, listener, None)
wl.wl_display_roundtrip(display)
if 'org_kde_kwin_fake_input' not in found: raise SystemExit('KWin n\'offre pas org_kde_kwin_fake_input')
name, version = found['org_kde_kwin_fake_input']
version = min(version, 4)
fake = request(registry, 0, ctypes.addressof(FAKE), version, name, b'org_kde_kwin_fake_input', version, None)
request(fake, 0, None, version, b'Sillage', b'essai de l\'interface')
wl.wl_display_roundtrip(display)


def key(code, down):
    request(fake, 10, None, version, code, 1 if down else 0); wl.wl_display_flush(display); time.sleep(.03)


def tap(*codes):
    """Des touches enfoncées ensemble (modificateurs d'abord), relâchées dans l'ordre inverse."""
    for c in codes: key(c, True)
    for c in reversed(codes): key(c, False)


def pointer(x, y):
    request(fake, 9, None, version, int(x * 256), int(y * 256)); wl.wl_display_flush(display); time.sleep(.05)


def click():
    for s in (1, 0): request(fake, 2, None, version, 0x110, s); wl.wl_display_flush(display); time.sleep(.06)


journal = sys.argv[1]
def wait(pattern, timeout=120):
    end = time.time() + timeout
    while time.time() < end:
        text = open(journal, encoding='utf-8', errors='replace').read()
        m = list(re.finditer(pattern, text))
        if m: return m[-1]
        time.sleep(.1)
    raise SystemExit('attendu en vain : ' + pattern)


def latest_pointer():
    m = wait(r'ETAT .*pointeur=([-\d.]+),([-\d.]+)')
    return float(m.group(1)), float(m.group(2))

SHIFT, ALTGR, ESC = 42, 100, 1
wait('CHAMP_PRET')
time.sleep(.5)
# « Île à Noël, ça ! Été » sur un clavier AZERTY : ^ puis Maj+i, ¨ (Maj+^) puis e, AltGr+, (accent aigu mort) puis Maj+e.
for t in [(26,), (SHIFT, 23), (38,), (18,), (57,), (11,), (57,), (SHIFT, 49), (24,), (SHIFT, 26), (18,), (38,), (50,), (57,),
          (10,), (16,), (57,), (53,), (57,), (ALTGR, 50), (SHIFT, 18), (20,), (3,)]:
    tap(*t)
time.sleep(.5)
print('TAPÉ', flush=True)
tap(ESC); time.sleep(.4); tap(ESC)                     # quitter le champ, puis la pause
time.sleep(1.0)
# Le pointeur, libéré par la pause : deux places pour trouver l'échelle et le décalage, puis le bouton visé.
pointer(560, 360); time.sleep(.6); a = latest_pointer()
pointer(720, 460); time.sleep(.6); b = latest_pointer()
sx = 160 / (b[0] - a[0]); sy = 100 / (b[1] - a[1])
def target(name):
    m = wait(r'BOUTON %s=([-\d.]+),([-\d.]+)' % name)
    return 560 + (float(m.group(1)) - a[0]) * sx, 360 + (float(m.group(2)) - a[1]) * sy
tx, ty = target('REPRENDRE')
print('POINTEUR échelle %.3f × %.3f, REPRENDRE à (%.0f, %.0f)' % (sx, sy, tx, ty), flush=True)
pointer(tx, ty); time.sleep(.6)
click(); time.sleep(1.0)
print('CLIQUÉ', flush=True)
tap(ESC); time.sleep(1.0)                              # la pause de nouveau, le pointeur sur RECOMMENCER pour la capture
pointer(*target('RECOMMENCER')); time.sleep(.5)
print('FIN DU SCÉNARIO', flush=True)
