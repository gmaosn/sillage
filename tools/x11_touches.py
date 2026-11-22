#!/usr/bin/env python3
"""Presse des touches sur un écran X (Xvfb) par l'extension XTEST, sans outil à installer : pour essayer le
clavier d'un jeu dans une fenêtre que personne ne voit.  x11_touches.py :9 "25 down" "sleep 3" "25 up" "65 tap"
Les codes sont ceux de X : le code de touche du moteur (evdev) plus 8 (W = 17 + 8 = 25, Espace = 57 + 8 = 65)."""
import ctypes, sys, time
x = ctypes.CDLL('libX11.so.6'); t = ctypes.CDLL('libXtst.so.6')
x.XOpenDisplay.restype = ctypes.c_void_p
d = x.XOpenDisplay(sys.argv[1].encode())
assert d, 'écran X introuvable'
def key(code, down):
    t.XTestFakeKeyEvent(ctypes.c_void_p(d), code, 1 if down else 0, 0); x.XFlush(ctypes.c_void_p(d))
for step in sys.argv[2:]:
    a, b = step.split()
    if a == 'sleep': time.sleep(float(b))
    elif b == 'down': key(int(a), True)
    elif b == 'up': key(int(a), False)
    else: key(int(a), True); time.sleep(.08); key(int(a), False)
