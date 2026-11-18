package sillage.platform

import sillage.platform.Native.fn
import sillage.vk.NativeStack
import sillage.vk.VK_KHR_SURFACE_EXTENSION_NAME
import sillage.vk.VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME
import sillage.vk.VkInstanceApi
import sillage.vk.VkWaylandSurfaceCreateInfoKHR
import sillage.vk.struct
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

private val NULL: MemorySegment = MemorySegment.NULL

/**
 * libwayland-client through FFM. Requests go through wl_proxy_marshal_array_flags (arguments in an
 * array, no varargs) and every event of every object reaches one upcall installed with
 * wl_proxy_add_dispatcher, which routes it by object and event name.
 */
internal object Wl {
    private val lib = Native.library("libwayland-client.so.0", "libwayland-client.so")
    val connect = lib.fn("wl_display_connect")
    val disconnect = lib.fn("wl_display_disconnect")
    val roundtrip = lib.fn("wl_display_roundtrip")
    val dispatchPending = lib.fn("wl_display_dispatch_pending")
    val flush = lib.fn("wl_display_flush")
    val prepareRead = lib.fn("wl_display_prepare_read")
    val readEvents = lib.fn("wl_display_read_events")
    val cancelRead = lib.fn("wl_display_cancel_read")
    val getFd = lib.fn("wl_display_get_fd")
    val getError = lib.fn("wl_display_get_error")
    val marshalArrayFlags = lib.fn("wl_proxy_marshal_array_flags")
    val addDispatcher = lib.fn("wl_proxy_add_dispatcher")
    val getVersion = lib.fn("wl_proxy_get_version")
    val getInterface = lib.fn("wl_proxy_get_interface")

    /** Core interfaces exported by the library. */
    val display = lib.fn("wl_display_interface")
    val registry = lib.fn("wl_registry_interface")
    val compositor = lib.fn("wl_compositor_interface")
    val surface = lib.fn("wl_surface_interface")
    val seat = lib.fn("wl_seat_interface")
    val pointer = lib.fn("wl_pointer_interface")
    val keyboard = lib.fn("wl_keyboard_interface")
    val region = lib.fn("wl_region_interface")
    val output = lib.fn("wl_output_interface")

    const val MARSHAL_FLAG_DESTROY = 1

    private val opcodes = HashMap<Pair<Long, String>, Int>()

    /** Request opcode of [name] in the interface at [itf], read from the interface table itself. */
    fun opcode(itf: MemorySegment, name: String): Int = opcodes.getOrPut(itf.address() to name) {
        val i = itf.reinterpret(40)
        val count = i.get(JAVA_INT, 12)
        val methods = i.get(ADDRESS, 16).reinterpret(24L * count)
        (0 until count).firstOrNull { Native.string(methods.get(ADDRESS, 24L * it)) == name }
            ?: throw IllegalArgumentException("No Wayland request $name in ${Native.string(i.get(ADDRESS, 0))}")
    }
}

/**
 * Interfaces that libwayland-client does not ship, written as wayland-scanner would from the protocol
 * XML (xdg-shell, xdg-decoration, relative-pointer, pointer-constraints, cursor-shape).
 * Message types name other interfaces; unknown names (never used here) stay NULL.
 */
internal object WlProtocols {
    private class Msg(val name: String, val signature: String, val types: List<String?>)
    private class Itf(val name: String, val version: Int, val requests: List<Msg>, val events: List<Msg>)
    private fun m(name: String, sig: String, vararg types: String?) = Msg(name, sig, types.toList())

    private val defs = listOf(
        Itf("xdg_wm_base", 5,
            listOf(m("destroy", ""), m("create_positioner", "n", "xdg_positioner"), m("get_xdg_surface", "no", "xdg_surface", "wl_surface"),
                m("pong", "u", null)),
            listOf(m("ping", "u", null))),
        Itf("xdg_surface", 5,
            listOf(m("destroy", ""), m("get_toplevel", "n", "xdg_toplevel"), m("get_popup", "n?oo", "xdg_popup", "xdg_surface", "xdg_positioner"),
                m("set_window_geometry", "iiii", null, null, null, null), m("ack_configure", "u", null)),
            listOf(m("configure", "u", null))),
        Itf("xdg_toplevel", 5,
            listOf(m("destroy", ""), m("set_parent", "?o", "xdg_toplevel"), m("set_title", "s", null), m("set_app_id", "s", null),
                m("show_window_menu", "ouii", "wl_seat", null, null, null), m("move", "ou", "wl_seat", null),
                m("resize", "ouu", "wl_seat", null, null), m("set_max_size", "ii", null, null), m("set_min_size", "ii", null, null),
                m("set_maximized", ""), m("unset_maximized", ""), m("set_fullscreen", "?o", "wl_output"), m("unset_fullscreen", ""),
                m("set_minimized", "")),
            listOf(m("configure", "iia", null, null, null), m("close", ""), m("configure_bounds", "4ii", null, null),
                m("wm_capabilities", "5a", null))),
        Itf("zxdg_decoration_manager_v1", 1,
            listOf(m("destroy", ""), m("get_toplevel_decoration", "no", "zxdg_toplevel_decoration_v1", "xdg_toplevel")), listOf()),
        Itf("zxdg_toplevel_decoration_v1", 1,
            listOf(m("destroy", ""), m("set_mode", "u", null), m("unset_mode", "")), listOf(m("configure", "u", null))),
        Itf("zwp_relative_pointer_manager_v1", 1,
            listOf(m("destroy", ""), m("get_relative_pointer", "no", "zwp_relative_pointer_v1", "wl_pointer")), listOf()),
        Itf("zwp_relative_pointer_v1", 1,
            listOf(m("destroy", "")), listOf(m("relative_motion", "uuffff", null, null, null, null, null, null))),
        Itf("zwp_pointer_constraints_v1", 1,
            listOf(m("destroy", ""), m("lock_pointer", "noo?ou", "zwp_locked_pointer_v1", "wl_surface", "wl_pointer", "wl_region", null),
                m("confine_pointer", "noo?ou", "zwp_confined_pointer_v1", "wl_surface", "wl_pointer", "wl_region", null)), listOf()),
        Itf("zwp_locked_pointer_v1", 1,
            listOf(m("destroy", ""), m("set_cursor_position_hint", "ff", null, null), m("set_region", "?o", "wl_region")),
            listOf(m("locked", ""), m("unlocked", ""))),
        Itf("wp_cursor_shape_manager_v1", 1,
            listOf(m("destroy", ""), m("get_pointer", "no", "wp_cursor_shape_device_v1", "wl_pointer"),
                m("get_tablet_tool_v2", "no", "wp_cursor_shape_device_v1", "zwp_tablet_tool_v2")), listOf()),
        Itf("wp_cursor_shape_device_v1", 1, listOf(m("destroy", ""), m("set_shape", "uu", null, null)), listOf()),
    )

    private val arena = Arena.global()
    private val tables: Map<String, MemorySegment>

    init {
        val structs = defs.associate { it.name to arena.allocate(40, 8) }
        val core = mapOf("wl_surface" to Wl.surface, "wl_pointer" to Wl.pointer, "wl_seat" to Wl.seat,
            "wl_region" to Wl.region, "wl_output" to Wl.output)
        fun messages(list: List<Msg>): MemorySegment {
            if (list.isEmpty()) return NULL
            val seg = arena.allocate(24L * list.size, 8)
            list.forEachIndexed { i, msg ->
                val types = arena.allocate(8L * maxOf(msg.types.size, 1), 8)
                msg.types.forEachIndexed { j, t -> types.set(ADDRESS, 8L * j, t?.let { structs[it] ?: core[it] } ?: NULL) }
                seg.set(ADDRESS, 24L * i, arena.allocateFrom(msg.name))
                seg.set(ADDRESS, 24L * i + 8, arena.allocateFrom(msg.signature))
                seg.set(ADDRESS, 24L * i + 16, types)
            }
            return seg
        }
        for (d in defs) {
            val s = structs.getValue(d.name)
            s.set(ADDRESS, 0, arena.allocateFrom(d.name))
            s.set(JAVA_INT, 8, d.version)
            s.set(JAVA_INT, 12, d.requests.size); s.set(ADDRESS, 16, messages(d.requests))
            s.set(JAVA_INT, 24, d.events.size); s.set(ADDRESS, 32, messages(d.events))
        }
        tables = structs
    }

    operator fun get(name: String): MemorySegment = tables.getValue(name)
    fun version(name: String): Int = defs.first { it.name == name }.version
}

/** A Wayland toplevel window: xdg-shell, server-side decorations, pointer lock and relative motion. */
internal class WaylandWindow(title: String, private var width: Int, private var height: Int) : Window {
    private val arena = Arena.ofConfined()
    private val stack = NativeStack(64 * 1024, arena)
    private val pollScratch = arena.allocate(8, 4)
    private val display: MemorySegment
    private val handlers = HashMap<Long, (String, MemorySegment) -> Unit>()
    private val eventNames = HashMap<Long, String>()
    private var failure: Throwable? = null
    private val dispatcher: MemorySegment

    private var registry = NULL; private var compositor = NULL; private var wmBase = NULL; private var seat = NULL
    private var decorationManager = NULL; private var relativeManager = NULL; private var constraints = NULL
    private var cursorShapeManager = NULL
    private var surface = NULL; private var xdgSurface = NULL; private var toplevel = NULL; private var decoration = NULL
    private var pointer = NULL; private var keyboard = NULL; private var relativePointer = NULL
    private var lockedPointer = NULL; private var cursorShape = NULL

    private var configured = false
    private var pendingWidth = 0; private var pendingHeight = 0
    private var scale = 1
    private var resized = false
    private var pointerSerial = -1
    private var pointerInside = false
    private var lastX = Double.NaN; private var lastY = Double.NaN
    private var mouseDx = 0f; private var mouseDy = 0f
    private var scroll = 0f
    private var discreteThisFrame = false
    private val keys = BooleanArray(KEY_COUNT)

    override var shouldClose = false; private set
    // Une fenêtre Wayland n'apparaît qu'après sa première image, et n'a le focus qu'une fois apparue :
    // elle est donc réputée active jusqu'au premier « leave », sinon le jeu attendrait sans jamais dessiner.
    override var focused = true; private set

    override var title: String = title
        set(v) { field = v; if (toplevel != NULL) { request(toplevel, "set_title", null, 0, v); flush() } }

    override var mouseCaptured = false
        set(v) { if (field != v) { field = v; applyCapture() } }

    init {
        display = PSig.P_P.invokeExact(Wl.connect, NULL) as MemorySegment
        check(display != NULL) { "Cannot connect to the Wayland compositor (WAYLAND_DISPLAY=${System.getenv("WAYLAND_DISPLAY")})" }
        try {
            val target = MethodHandles.lookup().findVirtual(WaylandWindow::class.java, "dispatch", MethodType.methodType(
                Int::class.javaPrimitiveType, MemorySegment::class.java, MemorySegment::class.java, Int::class.javaPrimitiveType,
                MemorySegment::class.java, MemorySegment::class.java)).bindTo(this)
            dispatcher = PSig.linker.upcallStub(target, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS), arena)

            registry = request(display, "get_registry", Wl.registry, 0, null)
            listen(registry) { event, a -> if (event == "global") global(a.u(0), a.s(1), a.u(2)) }
            roundtrip()
            check(compositor != NULL && wmBase != NULL) { "The compositor offers no wl_compositor and xdg_wm_base" }
            roundtrip() // capacités du siège, pointeur et clavier

            surface = request(compositor, "create_surface", Wl.surface, 0, null)
            listen(surface) { event, a ->
                if (event == "preferred_buffer_scale") {
                    val s = a.i(0).coerceIn(1, 4)
                    if (s != scale) { scale = s; request(surface, "set_buffer_scale", null, 0, s); resized = true }
                }
            }
            xdgSurface = request(wmBase, "get_xdg_surface", WlProtocols["xdg_surface"], 0, null, surface)
            listen(xdgSurface) { event, a ->
                if (event == "configure") {
                    request(xdgSurface, "ack_configure", null, 0, a.u(0))
                    if (pendingWidth > 0 && pendingHeight > 0 && (pendingWidth != width || pendingHeight != height)) {
                        width = pendingWidth; height = pendingHeight; resized = true
                    }
                    configured = true
                }
            }
            toplevel = request(xdgSurface, "get_toplevel", WlProtocols["xdg_toplevel"], 0, null)
            listen(toplevel) { event, a ->
                when (event) {
                    "configure" -> { pendingWidth = a.i(0); pendingHeight = a.i(1) }
                    "close" -> shouldClose = true
                }
            }
            request(toplevel, "set_title", null, 0, title)
            request(toplevel, "set_app_id", null, 0, "sillage")
            request(toplevel, "set_min_size", null, 0, 320, 200)
            if (decorationManager != NULL) {
                decoration = request(decorationManager, "get_toplevel_decoration", WlProtocols["zxdg_toplevel_decoration_v1"], 0, null, toplevel)
                listen(decoration) { _, _ -> }
                request(decoration, "set_mode", null, 0, 2 /* server_side */)
            }
            request(surface, "commit", null, 0)
            while (!configured) { roundtrip(); check(!shouldClose) { "Window closed before it was configured" } }
        } catch (t: Throwable) { close(); throw t }
    }

    // ------------------------------------------------------------ globals and input objects

    private fun global(name: Int, itf: String, version: Int) {
        fun bind(table: MemorySegment, max: Int): MemorySegment = stack.frame { s ->
            val v = minOf(version, max)
            request(registry, "bind", table, 0, name, s.allocateFrom(itf), v, null, version = v)
        }
        when (itf) {
            "wl_compositor" -> compositor = bind(Wl.compositor, 6)
            "xdg_wm_base" -> { wmBase = bind(WlProtocols["xdg_wm_base"], WlProtocols.version("xdg_wm_base"))
                listen(wmBase) { event, a -> if (event == "ping") request(wmBase, "pong", null, 0, a.u(0)) } }
            "wl_seat" -> if (seat == NULL) { seat = bind(Wl.seat, 7); listen(seat) { event, a -> if (event == "capabilities") capabilities(a.u(0)) } }
            "zxdg_decoration_manager_v1" -> decorationManager = bind(WlProtocols[itf], 1)
            "zwp_relative_pointer_manager_v1" -> relativeManager = bind(WlProtocols[itf], 1)
            "zwp_pointer_constraints_v1" -> constraints = bind(WlProtocols[itf], 1)
            "wp_cursor_shape_manager_v1" -> cursorShapeManager = bind(WlProtocols[itf], 1)
        }
    }

    private fun capabilities(caps: Int) {
        if (caps and 1 != 0 && pointer == NULL) {
            pointer = request(seat, "get_pointer", Wl.pointer, 0, null)
            listen(pointer) { event, a -> pointerEvent(event, a) }
            if (relativeManager != NULL) {
                relativePointer = request(relativeManager, "get_relative_pointer", WlProtocols["zwp_relative_pointer_v1"], 0, null, pointer)
                listen(relativePointer) { event, a ->
                    if (event == "relative_motion" && mouseCaptured) { mouseDx += a.fixed(4).toFloat(); mouseDy += a.fixed(5).toFloat() }
                }
            }
            if (cursorShapeManager != NULL) {
                cursorShape = request(cursorShapeManager, "get_pointer", WlProtocols["wp_cursor_shape_device_v1"], 0, null, pointer)
                listen(cursorShape) { _, _ -> }
            }
            if (mouseCaptured) applyCapture()
        }
        if (caps and 2 != 0 && keyboard == NULL) {
            keyboard = request(seat, "get_keyboard", Wl.keyboard, 0, null)
            listen(keyboard) { event, a -> keyboardEvent(event, a) }
        }
    }

    private fun pointerEvent(event: String, a: MemorySegment) {
        when (event) {
            "enter" -> { pointerSerial = a.u(0); pointerInside = true; lastX = a.fixed(2); lastY = a.fixed(3); applyCursor() }
            "leave" -> { pointerInside = false; lastX = Double.NaN }
            "motion" -> {
                val x = a.fixed(1); val y = a.fixed(2)
                if (mouseCaptured && relativePointer == NULL && !lastX.isNaN()) { mouseDx += (x - lastX).toFloat(); mouseDy += (y - lastY).toFloat() }
                lastX = x; lastY = y
            }
            "button" -> if (a.u(3) == 1 && a.u(2) == 0x110 && !mouseCaptured) mouseCaptured = true   // clic gauche : capturer
            "axis_discrete" -> if (a.u(0) == 0) { scroll -= a.i(1); discreteThisFrame = true }
            "axis" -> if (a.u(1) == 0 && !discreteThisFrame) scroll -= (a.fixed(2) / 10.0).toFloat()
            "frame" -> discreteThisFrame = false
        }
    }

    private fun keyboardEvent(event: String, a: MemorySegment) {
        when (event) {
            "keymap" -> Native.close(a.i(1))         // les touches sont lues par position : pas besoin de la table
            "enter" -> {
                focused = true
                val array = a.get(ADDRESS, 16).reinterpret(24)
                val size = array.get(JAVA_LONG, 0)
                val data = array.get(ADDRESS, 16).reinterpret(size)
                for (k in 0 until (size / 4)) setKey(data.get(JAVA_INT, 4 * k), true)
            }
            "leave" -> { focused = false; keys.fill(false) }
            "key" -> setKey(a.u(2), a.u(3) != 0)
        }
    }

    private fun setKey(code: Int, down: Boolean) { if (code in 0 until KEY_COUNT) keys[code] = down }

    private fun applyCapture() {
        if (pointer == NULL) return
        if (mouseCaptured) {
            if (lockedPointer == NULL && constraints != NULL) {
                lockedPointer = request(constraints, "lock_pointer", WlProtocols["zwp_locked_pointer_v1"], 0, null, surface, pointer, NULL, 2 /* persistent */)
                listen(lockedPointer) { _, _ -> }
            }
        } else if (lockedPointer != NULL) {
            destroy(lockedPointer); lockedPointer = NULL
        }
        mouseDx = 0f; mouseDy = 0f
        applyCursor()
        flush()
    }

    private fun applyCursor() {
        if (pointer == NULL || pointerSerial < 0) return
        if (mouseCaptured || cursorShape == NULL) request(pointer, "set_cursor", null, 0, pointerSerial, NULL, 0, 0)
        if (!mouseCaptured && cursorShape != NULL) request(cursorShape, "set_shape", null, 0, pointerSerial, 1 /* default */)
    }

    // ------------------------------------------------------------ Window

    override val instanceExtensions = listOf(VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME)

    override fun createSurface(vk: VkInstanceApi, stack: NativeStack): Long = stack.frame { s ->
        val out = s.allocate(JAVA_LONG)
        vk.createWaylandSurfaceKHR(s.struct(VkWaylandSurfaceCreateInfoKHR, "display" to display, "surface" to surface), out)
        out.get(JAVA_LONG, 0)
    }

    override fun framebufferSize() = intArrayOf(width * scale, height * scale)

    override fun poll() = pump(0)

    override fun waitEvents(seconds: Double) = pump((seconds * 1000).toInt().coerceAtLeast(0))

    private fun pump(timeoutMs: Int) {
        (PSig.I_P.invokeExact(Wl.dispatchPending, display) as Int)
        while ((PSig.I_P.invokeExact(Wl.prepareRead, display) as Int) != 0) (PSig.I_P.invokeExact(Wl.dispatchPending, display) as Int)
        flush()
        val fd = PSig.I_P.invokeExact(Wl.getFd, display) as Int
        if (Native.pollIn(fd, timeoutMs, pollScratch) and 1 != 0) (PSig.I_P.invokeExact(Wl.readEvents, display) as Int)
        else PSig.V_P.invokeExact(Wl.cancelRead, display)
        (PSig.I_P.invokeExact(Wl.dispatchPending, display) as Int)
        checkHealth()
    }

    override fun isDown(key: Int) = key in 0 until KEY_COUNT && keys[key]

    override fun consumeMouseDelta(): FloatArray { val d = floatArrayOf(mouseDx, mouseDy); mouseDx = 0f; mouseDy = 0f; return d }
    override fun consumeScroll(): Float { val s = scroll; scroll = 0f; return s }
    override fun consumeResize(): Boolean { val r = resized; resized = false; return r }

    override fun close() {
        for (p in listOf(lockedPointer, cursorShape, relativePointer, decoration, toplevel, xdgSurface))
            if (p != NULL) runCatching { destroy(p) }
        if (surface != NULL) runCatching { destroy(surface) }
        if (display != NULL) {
            runCatching { flush() }
            PSig.V_P.invokeExact(Wl.disconnect, display)
        }
        arena.close()
    }

    // ------------------------------------------------------------ protocol plumbing

    /**
     * Sends request [name] on [proxy]. [newItf] is the interface of the object it creates, if any.
     * Arguments: Int (int, uint, fd), Double (fixed), String, MemorySegment (object), null (new id or null object).
     */
    private fun request(proxy: MemorySegment, name: String, newItf: MemorySegment?, flags: Int, vararg args: Any?, version: Int = -1): MemorySegment = stack.frame { s ->
        val itf = PSig.P_P.invokeExact(Wl.getInterface, proxy) as MemorySegment
        val opcode = Wl.opcode(itf, name)
        val array = s.allocate(8L * maxOf(args.size, 1), 8)
        args.forEachIndexed { i, v ->
            when (v) {
                null -> array.set(ADDRESS, 8L * i, NULL)
                is Int -> array.set(JAVA_INT, 8L * i, v)
                is Double -> array.set(JAVA_INT, 8L * i, (v * 256).toInt())
                is String -> array.set(ADDRESS, 8L * i, s.allocateFrom(v))
                is MemorySegment -> array.set(ADDRESS, 8L * i, v)
                else -> throw IllegalArgumentException("Unsupported Wayland argument: $v")
            }
        }
        // Un objet créé prend la version de son parent, sauf avec wl_registry.bind qui la choisit.
        val v = if (version >= 0) version else PSig.I_P.invokeExact(Wl.getVersion, proxy) as Int
        PSig.P_PIPIIP.invokeExact(Wl.marshalArrayFlags, proxy, opcode, newItf ?: NULL, v, flags, array) as MemorySegment
    }

    private fun destroy(proxy: MemorySegment) {
        handlers.remove(proxy.address())
        request(proxy, "destroy", null, Wl.MARSHAL_FLAG_DESTROY)
    }

    private fun listen(proxy: MemorySegment, handler: (String, MemorySegment) -> Unit) {
        check(proxy != NULL) { "Wayland object not created" }
        handlers[proxy.address()] = handler
        check((PSig.I_PPPP.invokeExact(Wl.addDispatcher, proxy, dispatcher, NULL, NULL) as Int) == 0) { "wl_proxy_add_dispatcher failed" }
    }

    /** The single event entry point, called from libwayland during dispatch. Never throws. */
    @Suppress("unused")
    private fun dispatch(impl: MemorySegment, target: MemorySegment, opcode: Int, message: MemorySegment, args: MemorySegment): Int {
        try {
            val name = eventNames.getOrPut(message.address()) { Native.string(message.reinterpret(24).get(ADDRESS, 0)) }
            handlers[target.address()]?.invoke(name, args.reinterpret(8L * 16))
        } catch (t: Throwable) {
            if (failure == null) failure = t
        }
        return 0
    }

    private fun roundtrip() {
        check((PSig.I_P.invokeExact(Wl.roundtrip, display) as Int) >= 0) { "Connexion Wayland perdue" }
        checkHealth()
    }

    private fun flush() { (PSig.I_P.invokeExact(Wl.flush, display) as Int) }

    private fun checkHealth() {
        failure?.let { failure = null; throw IllegalStateException("Error in a Wayland event", it) }
        val error = PSig.I_P.invokeExact(Wl.getError, display) as Int
        check(error == 0) { "Wayland protocol error (errno $error)" }
    }

    private fun MemorySegment.u(i: Int): Int = get(JAVA_INT, 8L * i)
    private fun MemorySegment.i(i: Int): Int = get(JAVA_INT, 8L * i)
    private fun MemorySegment.fixed(i: Int): Double = get(JAVA_INT, 8L * i) / 256.0
    private fun MemorySegment.s(i: Int): String = Native.string(get(ADDRESS, 8L * i))
}
