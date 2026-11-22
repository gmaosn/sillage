package sillage.gl

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * The OpenGL ES shaders the engine ships (GlShaders.kt) are the ones tools/shaders_gl.py makes from the
 * Vulkan sources: a shader changed without running the tool would leave the two renderers drawing with
 * different code. Needs python3; without it the test says so and passes.
 */
class GlShadersTest {
    private fun root(): Path {
        var d = Path.of("").toAbsolutePath()
        while (!Files.exists(d.resolve("project.yaml"))) d = d.parent ?: error("project.yaml not found")
        return d
    }

    @Test fun leFichierEstCeluiQueDonneLOutil() {
        val python = listOf("/usr/bin/python3", "/usr/local/bin/python3").firstOrNull { Files.isExecutable(Path.of(it)) }
        if (python == null) { println("GlShadersTest: python3 not installed, nothing checked"); return }
        val p = ProcessBuilder(python, root().resolve("tools/shaders_gl.py").toString(), "--stdout").redirectErrorStream(true).start()
        val made = p.inputStream.readAllBytes().decodeToString()
        assertEquals(0, p.waitFor(), "tools/shaders_gl.py failed:\n$made")
        val shipped = Files.readString(root().resolve("engine/src/sillage/gl/GlShaders.kt"))
        assertTrue(made == shipped, "run tools/shaders_gl.py: engine/src/sillage/gl/GlShaders.kt is not what the shaders' sources give")
    }

    @Test fun chaqueShaderEstLa() {
        val dir = root().resolve("engine/resources/shaders")
        val names = Files.list(dir).use { l -> l.map { it.fileName.toString() }.filter { it.endsWith(".vert") || it.endsWith(".frag") }.sorted().toList() }
        assertTrue(names.size >= 19, "shaders found: ${names.size}")
        for (n in names) {
            val s = GlShaders.source(n)
            assertTrue(s.startsWith("#version 300 es\n"), n)
            for (word in listOf("push_constant", "binding", "gl_VertexIndex", "gl_InstanceIndex", "#include")) assertFalse(word in s, "$n still has $word")
            if (n.endsWith(".vert")) assertTrue("gl_Position.z = 2.0 * gl_Position.z - gl_Position.w;" in s, "$n: OpenGL's depth")
        }
    }
}
