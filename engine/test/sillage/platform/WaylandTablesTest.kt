package sillage.platform

import java.lang.foreign.SymbolLookup
import java.lang.foreign.Arena
import kotlin.test.*

/** Reads request opcodes back from the native wl_interface tables (ours and libwayland's). */
class WaylandTablesTest {
    private val available = runCatching { SymbolLookup.libraryLookup("libwayland-client.so.0", Arena.global()) }.isSuccess

    @Test fun nosTablesDeProtocole() {
        if (!available) return
        assertEquals(2, Wl.opcode(WlProtocols["xdg_wm_base"], "get_xdg_surface"))
        assertEquals(3, Wl.opcode(WlProtocols["xdg_wm_base"], "pong"))
        assertEquals(4, Wl.opcode(WlProtocols["xdg_surface"], "ack_configure"))
        assertEquals(2, Wl.opcode(WlProtocols["xdg_toplevel"], "set_title"))
        assertEquals(8, Wl.opcode(WlProtocols["xdg_toplevel"], "set_min_size"))
        assertEquals(1, Wl.opcode(WlProtocols["zwp_pointer_constraints_v1"], "lock_pointer"))
        assertEquals(1, Wl.opcode(WlProtocols["wp_cursor_shape_device_v1"], "set_shape"))
        assertFailsWith<IllegalArgumentException> { Wl.opcode(WlProtocols["xdg_toplevel"], "voler") }
    }

    @Test fun tablesDeLaBibliotheque() {
        if (!available) return
        assertEquals(1, Wl.opcode(Wl.display, "get_registry"))
        assertEquals(0, Wl.opcode(Wl.registry, "bind"))
        assertEquals(6, Wl.opcode(Wl.surface, "commit"))
        assertEquals(8, Wl.opcode(Wl.surface, "set_buffer_scale"))
        assertEquals(0, Wl.opcode(Wl.pointer, "set_cursor"))
    }

    @Test fun touchesParPosition() {
        // Codes evdev : la touche Z d'un AZERTY est la touche W d'un QWERTY.
        assertEquals(17, Key.W); assertEquals(30, Key.A); assertEquals(31, Key.S); assertEquals(32, Key.D)
        assertEquals(57, Key.SPACE); assertEquals(1, Key.ESCAPE)
    }
}
