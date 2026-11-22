package sillage.vk

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * The SPIR-V files the engine ships are the ones their sources give. A shader left out of a recompile
 * keeps the old layout of the frame block and reads its neighbours' data without any error: it happened
 * to particle.frag when the shadow cascades came in (smoke was lit by the wrong lamps).
 * Needs glslc; without it the test says so and passes.
 */
class ShadersTest {
    private fun root(): Path {
        var d = Path.of("").toAbsolutePath()
        while (!Files.exists(d.resolve("project.yaml"))) d = d.parent ?: error("project.yaml not found")
        return d
    }

    @Test fun lesBinairesSontCeuxDesSources() {
        val glslc = listOf("/usr/bin/glslc", "/usr/local/bin/glslc").firstOrNull { Files.isExecutable(Path.of(it)) }
        if (glslc == null) { println("ShadersTest: glslc not installed, nothing checked"); return }
        val dir = root().resolve("engine/resources/shaders")
        val sources = Files.list(dir).use { l -> l.filter { it.toString().endsWith(".vert") || it.toString().endsWith(".frag") }.sorted().toList() }
        assertTrue(sources.size >= 19, "shaders found: ${sources.size}")
        val stale = ArrayList<String>()
        val tmp = Files.createTempFile("sillage-shader", ".spv")
        try {
            for (src in sources) {
                val p = ProcessBuilder(glslc, "--target-env=vulkan1.0", src.toString(), "-o", tmp.toString()).redirectErrorStream(true).start()
                val log = p.inputStream.readAllBytes().decodeToString()
                assertEquals(0, p.waitFor(), "${src.fileName} does not compile:\n$log")
                val shipped = dir.resolve("${src.fileName}.spv")
                if (!Files.exists(shipped) || !Files.readAllBytes(shipped).contentEquals(Files.readAllBytes(tmp))) stale.add(src.fileName.toString())
            }
        } finally { Files.deleteIfExists(tmp) }
        assertTrue(stale.isEmpty(), "run tools/shaders.sh: these .spv are not what their sources give: $stale")
    }
}
