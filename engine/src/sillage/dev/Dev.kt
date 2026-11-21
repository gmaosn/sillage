package sillage.dev

import sillage.io.Assets
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * `./sillage dev GAME`: plays the game and reloads it each time one of its files is saved. The script
 * starts this with the engine on the class path and the game's own classes outside it: they are loaded
 * by a class loader of their own, which a new one replaces at each change of the code.
 *
 * - The game's Kotlin files: compiled again by the Kotlin compiler the toolchain downloaded, kept warm
 *   in this JVM (about a second), then the game's `main` runs again in a new class loader and its scene
 *   replaces the one on screen at the end of a frame.
 * - Its resources (models, images, sounds): read again from the game's resources folder, the scene is
 *   built again.
 * - The engine's own files: the program stops with code 75 and the script builds and starts it again.
 *
 * The player, the camera and the hour stay where they were, unless the code changed them. Errors
 * (compiling, building the scene, an update) show over the game until the next save fixes them.
 */
fun main(args: Array<String>) { exitProcess(DevHost(args).run()) }

internal class DevHost(all: Array<String>) {
    private val split = all.indexOf("--").let { if (it < 0) all.size else it }
    private val options = all.copyOfRange(0, split)
    private val gameArgs = if (split < all.size) all.copyOfRange(split + 1, all.size) else emptyArray()

    private fun option(name: String): String =
        options.indexOf(name).takeIf { it >= 0 && it + 1 < options.size }?.let { options[it + 1] } ?: error("sillage dev: missing $name")

    private val game = option("--dev-game")
    private val mainClass = option("--dev-main")
    private val sources = Path.of(option("--dev-sources"))
    private val resources = Path.of(option("--dev-resources"))
    private val firstClasses = Path.of(option("--dev-classes"))
    private val engineSources = option("--dev-engine-sources").split(':').filter { it.isNotBlank() }.map { Path.of(it) }.filter { Files.isDirectory(it) }
    private val compilerJars = option("--dev-compiler").split(':').filter { it.isNotBlank() }.map { Path.of(it) }
    private val jdk = Path.of(option("--dev-jdk"))
    private val work = Path.of(option("--dev-work"))

    @Volatile private var loader: URLClassLoader = URLClassLoader(arrayOf(firstClasses.toUri().toURL()), javaClass.classLoader)
    private var generation = 0
    private val retry = Semaphore(0)
    private val compiler by lazy { KotlinCompiler(compilerJars) }

    fun run(): Int {
        DevLink.enabled = true
        if (Files.isDirectory(resources)) Assets.roots = listOf(resources)
        Files.createDirectories(work)
        Files.list(work).use { s -> s.filter { it.fileName.toString().startsWith("classes-") }.forEach { delete(it) } }
        Thread({ watch() }, "sillage-dev-watch").apply { isDaemon = true; start() }
        println("SILLAGE_DEV watching ${relative(sources)}" + (if (Files.isDirectory(resources)) " and ${relative(resources)}" else "") +
            "; save a file to reload, change the engine to restart")
        // The first build runs on this thread; a failure waits for a save that fixes it.
        while (true) {
            try {
                callMain(loader)
                return DevLink.quitCode ?: 0
            } catch (e: Throwable) {
                if (DevLink.looping) throw e
                val cause = rootCause(e)
                System.err.println("SILLAGE_DEV_ERROR the game did not start: " + describe(cause))
                cause.printStackTrace()
                System.err.println("SILLAGE_DEV waiting for a save that fixes it")
                retry.acquire()
                if (DevLink.quitCode != null) return DevLink.quitCode!!
            }
        }
    }

    private enum class Kind { CODE, RESOURCE, ENGINE }

    /** Watches the folders; a burst of events (an editor saving, Blender writing a file) counts once. */
    private fun watch() {
        val ws = FileSystems.getDefault().newWatchService()
        val keys = HashMap<WatchKey, Pair<Path, Kind>>()
        fun register(dir: Path, kind: Kind) {
            if (!Files.isDirectory(dir)) return
            Files.walk(dir).use { s -> s.filter { Files.isDirectory(it) }.forEach { keys[it.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)] = it to kind } }
        }
        register(sources, Kind.CODE)
        register(resources, Kind.RESOURCE)
        for (e in engineSources) register(e, Kind.ENGINE)
        // The compiler's first run takes a few seconds: done now, the first reload takes about one.
        try { compile(work.resolve("warm-up")); delete(work.resolve("warm-up")) } catch (e: Throwable) { System.err.println("SILLAGE_DEV compiler warm-up failed: " + describe(e)) }
        while (true) {
            var key = ws.take()
            val kinds = HashSet<Kind>()
            var since = 0L
            while (key != null) {
                val (dir, kind) = keys[key] ?: (null to null)
                for (event in key.pollEvents()) {
                    if (dir == null || kind == null) continue
                    if (event.kind() == OVERFLOW) { kinds += kind; continue }
                    val name = (event.context() as? Path) ?: continue
                    val path = dir.resolve(name)
                    if (event.kind() == ENTRY_CREATE && Files.isDirectory(path)) register(path, kind)
                    if (!counts(name.toString(), kind)) continue
                    kinds += kind
                }
                if (!key.reset()) keys.remove(key)
                if (kinds.isNotEmpty() && since == 0L) since = System.nanoTime()
                // A little more time for the rest of the burst.
                key = ws.poll(150, TimeUnit.MILLISECONDS)
            }
            if (kinds.isEmpty()) continue
            DevLink.changedAt = since
            when {
                Kind.ENGINE in kinds -> {
                    println("SILLAGE_DEV the engine changed: restarting")
                    DevLink.quitCode = DevLink.RESTART
                    retry.release()
                    return
                }
                Kind.CODE in kinds -> {
                    val out = work.resolve("classes-" + ++generation)
                    if (compile(out)) reload(URLClassLoader(arrayOf(out.toUri().toURL()), javaClass.classLoader), "code")
                    // The loaders before the last two may still be finishing a frame: only older folders go.
                    delete(work.resolve("classes-" + (generation - 3)))
                }
                else -> reload(loader, "resources")
            }
        }
    }

    /** Files that count: Kotlin sources for the code; for resources, anything but an editor's temporary files. */
    private fun counts(name: String, kind: Kind): Boolean {
        if (name.startsWith(".") || name.startsWith("#") || name.endsWith("~") || name.endsWith(".swp") || name.endsWith(".tmp") || name.endsWith(".part")) return false
        return when (kind) {
            Kind.CODE, Kind.ENGINE -> name.endsWith(".kt") || (kind == Kind.ENGINE && !name.contains('.'))
            Kind.RESOURCE -> true
        }
    }

    private fun compile(out: Path): Boolean {
        val started = System.nanoTime()
        val (ok, messages) = compiler.compile(listOf(sources.toString(), "-d", out.toString(),
            "-classpath", System.getProperty("java.class.path"), "-no-stdlib", "-no-reflect",
            "-jvm-target", Runtime.version().feature().toString(), "-jdk-home", jdk.toString(), "-module-name", game))
        val ms = (System.nanoTime() - started) / 1_000_000
        val errors = messages.lines().filter { ": error: " in it || it.startsWith("error: ") }
        if (messages.isNotBlank()) System.err.print(messages.lines().filter { it.isNotBlank() }.joinToString("\n", postfix = "\n"))
        if (out.fileName.toString() == "warm-up") return ok
        if (ok) {
            println("SILLAGE_DEV compiled in $ms ms")
            return true
        }
        DevLink.error = "COMPILATION FAILED\n" + errors.take(6).joinToString("\n") { shorten(it) }.ifEmpty { messages.lines().firstOrNull { it.isNotBlank() } ?: "(no message)" }
        println("SILLAGE_DEV_ERROR compilation failed (${errors.size} error" + (if (errors.size == 1) "" else "s") + ")")
        return false
    }

    /** Runs the game's `main` again in [next]: its call to `game()` hands the new scene to the loop. */
    private fun reload(next: URLClassLoader, what: String) {
        loader = next
        if (!DevLink.looping) { retry.release(); return }       // the first start failed: try it again
        DevLink.reloadWhat = what
        val t = Thread({
            try { callMain(next) } catch (e: Throwable) {
                val cause = rootCause(e)
                DevLink.error = "THE GAME FAILED TO START\n" + describe(cause)
                System.err.println("SILLAGE_DEV_ERROR " + describe(cause))
                cause.printStackTrace()
            }
        }, "sillage-dev-reload")
        t.isDaemon = true
        t.contextClassLoader = next
        t.start()
    }

    private fun callMain(l: ClassLoader) {
        Thread.currentThread().contextClassLoader = l
        val main = Class.forName(mainClass, true, l).getMethod("main", Array<String>::class.java)
        main.invoke(null, gameArgs)
    }

    private fun relative(p: Path): String = runCatching { Path.of("").toAbsolutePath().relativize(p).toString() }.getOrDefault(p.toString())

    /** A compiler message without the folders before the game's own files. */
    private fun shorten(line: String): String = line.replace(sources.toAbsolutePath().toString() + "/", "").let { if (it.length > 110) it.take(107) + "..." else it }

    private fun delete(p: Path) {
        if (!Files.exists(p)) return
        Files.walk(p).use { s -> s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } } }
    }

    companion object {
        fun rootCause(e: Throwable): Throwable {
            var c = e
            while ((c is java.lang.reflect.InvocationTargetException || c is ExceptionInInitializerError) && c.cause != null) c = c.cause!!
            return c
        }

        /** An exception in one line, with the first place in the game's code (not the engine's) it came from. */
        fun describe(e: Throwable): String {
            val where = e.stackTrace.firstOrNull { !it.className.startsWith("sillage.") && !it.className.startsWith("java.") && !it.className.startsWith("jdk.") && !it.className.startsWith("kotlin.") }
                ?: e.stackTrace.firstOrNull()
            return e.javaClass.simpleName + ": " + (e.message ?: "") + (where?.let { " (" + it.fileName + ":" + it.lineNumber + ")" } ?: "")
        }
    }
}

/** The Kotlin compiler the toolchain downloaded, in a class loader of its own, called by reflection. */
private class KotlinCompiler(jars: List<Path>) {
    private val loader = URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader())
    private val type = loader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    private val exec = type.getMethod("exec", PrintStream::class.java, Array<String>::class.java)

    fun compile(args: List<String>): Pair<Boolean, String> {
        val out = ByteArrayOutputStream()
        val stream = PrintStream(out, true, Charsets.UTF_8)
        val result = exec.invoke(type.getConstructor().newInstance(), stream, args.toTypedArray())
        return (result.toString() == "OK") to out.toString(Charsets.UTF_8)
    }
}
