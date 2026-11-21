package sillage

import sillage.math.Vec3
import sillage.physics.Box
import sillage.physics.BodyType
import sillage.physics.Sphere
import sillage.physics.raycast
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.*

/**
 * Blender as the editor: a GLB made as Blender exports one, with helper objects named by the
 * conventions (col_…, spawn, zone_…, pickup_…, a custom property `sillage = dynamic`) and a light.
 */
class BlenderTest {
    /** Blender's default cube: side 2, centred, 12 triangles, counter-clockwise seen from outside. */
    private fun cube(): FloatArray {
        val out = ArrayList<Float>()
        fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) { for (p in listOf(a, b, c, a, c, d)) out.addAll(p.toList()) }
        fun v(x: Int, y: Int, z: Int) = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())
        quad(v(-1, -1, 1), v(1, -1, 1), v(1, 1, 1), v(-1, 1, 1))      // +Z
        quad(v(1, -1, -1), v(-1, -1, -1), v(-1, 1, -1), v(1, 1, -1))  // −Z
        quad(v(1, -1, 1), v(1, -1, -1), v(1, 1, -1), v(1, 1, 1))      // +X
        quad(v(-1, -1, -1), v(-1, -1, 1), v(-1, 1, 1), v(-1, 1, -1))  // −X
        quad(v(-1, 1, 1), v(1, 1, 1), v(1, 1, -1), v(-1, 1, -1))      // +Y
        quad(v(-1, -1, -1), v(1, -1, -1), v(1, -1, 1), v(-1, -1, 1))  // −Y
        return out.toFloatArray()
    }

    private fun glb(json: String, bin: ByteArray): ByteArray {
        val j = json.toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
        val b = bin + ByteArray((4 - bin.size % 4) % 4)
        val out = ByteBuffer.allocate(12 + 8 + j.size + 8 + b.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546c67).putInt(2).putInt(out.capacity())
        out.putInt(j.size).putInt(0x4e4f534a).put(j)
        out.putInt(b.size).putInt(0x004e4942).put(b)
        return out.array()
    }

    /** A little village: a house, its collision box, a rock, the start, a lamp, a crate that moves, a door zone, a shell to pick up. */
    private fun village(): String {
        val c = cube()
        val bin = ByteBuffer.allocate(c.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { c.forEach { putFloat(it) } }.array()
        val nodes = listOf(
            """{"name":"house","mesh":0,"translation":[0,1,0]}""",
            """{"name":"col_house","mesh":0,"translation":[0,1,0],"scale":[1.7,0.8,1.5]}""",
            """{"name":"col_sphere_rock","mesh":0,"translation":[3,0.5,0],"scale":[0.5,0.5,0.5]}""",
            """{"name":"spawn","translation":[0,0,5],"rotation":[0,0.7071068,0,0.7071068]}""",
            """{"name":"lamp","translation":[0,3,0],"extensions":{"KHR_lights_punctual":{"light":0}},"extras":{"night":true,"flicker":0.2}}""",
            """{"name":"crate","mesh":0,"translation":[-3,0.5,0],"scale":[0.5,0.5,0.5],"extras":{"sillage":"dynamic","mass":12}}""",
            """{"name":"zone_door","translation":[0,1,-3]}""",
            """{"name":"pickup_shell.001","mesh":0,"translation":[2,0.3,2],"scale":[0.2,0.2,0.2]}""",
            """{"name":"COL_wall.002","translation":[6,1,0],"scale":[0.1,1,2],"extras":{"camera":false,"surface":"wood"}}""")
        val json = """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[${nodes.indices.joinToString(",")}]}],
            "nodes":[${nodes.joinToString(",")}],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0},"material":0}]}],
            "materials":[{"name":"stone","pbrMetallicRoughness":{"baseColorFactor":[0.6,0.6,0.6,1]}}],
            "accessors":[{"bufferView":0,"componentType":5126,"count":36,"type":"VEC3"}],
            "bufferViews":[{"buffer":0,"byteLength":${bin.size}}],"buffers":[{"byteLength":${bin.size}}],
            "extensionsUsed":["KHR_lights_punctual"],"extensionsRequired":["KHR_lights_punctual"],
            "extensions":{"KHR_lights_punctual":{"lights":[{"type":"point","color":[1,0.8,0.5],"intensity":54.3,"range":8}]}}}"""
        val f = Files.createTempFile("village", ".glb")
        Files.write(f, glb(json, bin))
        f.toFile().deleteOnExit()
        return f.toString()
    }

    @Test fun lesRôlesSeLisentAuNomEtAuxPropriétés() {
        val m = Glb.loadModel(village())
        val roles = m.nodes.associate { it.name to it.role }
        assertEquals(Role.DRAWN, roles["house"]); assertEquals(Role.COLLIDER, roles["col_house"]); assertEquals(Role.COLLIDER, roles["COL_wall.002"])
        assertEquals(Role.SPAWN, roles["spawn"]); assertEquals(Role.LAMP, roles["lamp"]); assertEquals(Role.DYNAMIC, roles["crate"])
        assertEquals(Role.ZONE, roles["zone_door"]); assertEquals(Role.PICKUP, roles["pickup_shell.001"])
        assertEquals(12.0, m.nodes.first { it.name == "crate" }.properties["mass"])
        assertEquals(true, m.nodes.first { it.name == "lamp" }.properties["night"])
        assertEquals(12, m.flatten().triangleCount, "only the house is drawn")
        val (tris, _) = m.surfaces()
        assertEquals(12 * 9, tris.size, "only the house is solid with solid()")
    }

    @Test fun leVillageSeMetEnPlace() {
        val s = Scene().apply { ground(); model(village()); player() }
        val p = s.player!!
        assertEquals(5f, p.position.z, 1e-4f); assertEquals(90f, p.yaw, .01f, "the player faces as the spawn does")
        s.step(1f / 60f)
        // The house's collision box (1.7 × 0.8 × 1.5 half sizes, centred 1 m up): its top at 1.8 m.
        val top = s.physics.raycast(Vec3(0f, 10f, 0f), Vec3(0f, -1f, 0f), 20f, meshes = false)!!
        assertEquals(1.8f, top.point.y, 1e-3f)
        assertTrue((top.body!!.shape as Box).let { it.halfX == 1.7f && it.halfZ == 1.5f })
        val rock = s.physics.raycast(Vec3(3f, 10f, 0f), Vec3(0f, -1f, 0f), 20f, meshes = false)!!
        assertEquals(.5f, (rock.body!!.shape as Sphere).radius, 1e-5f); assertEquals(1f, rock.point.y, 1e-3f)
        // An empty named col_… is Blender's cube of side 2 scaled: here a thin wall that does not block the camera.
        val wall = s.physics.raycast(Vec3(10f, 1f, 0f), Vec3(-1f, 0f, 0f), 20f, meshes = false)!!
        assertEquals(6.1f, wall.point.x, 1e-3f)
        assertEquals("wood", s.surfaces[wall.body!!.id])
        // The lamp: Blender's 1000 W point light (54.3 cd) gives about 1.5; night and flicker from its properties.
        val lamp = s.lights.single()
        assertEquals(1.5f, lamp.intensity, .01f); assertEquals(8f, lamp.radius); assertTrue(lamp.onlyAtNight); assertEquals(.2f, lamp.flicker, 1e-6f)
        assertEquals(3f, lamp.position.y, 1e-4f)
        // The crate is a node of its own, named, that falls and rests on the ground.
        val crate = s.find("crate")!!
        assertEquals(BodyType.DYNAMIC, crate.body!!.type); assertEquals(12f, crate.body!!.mass, 1e-3f)
        repeat(120) { s.step(1f / 60f) }
        assertEquals(.5f, crate.position.y, .02f)
        assertEquals(12, crate.mesh.triangleCount)
    }

    @Test fun zonesEtRamassagesParLeurNom() {
        var entered = 0; var picked = 0
        val s = Scene().apply {
            ground(); model(village()); player()
            onEnter("door") { if (it is Player) entered++ }
            onPickup("shell") { picked++ }
        }
        s.step(1f / 60f)
        assertNotNull(s.zone("door"))
        s.player!!.position = vec(0, 0, -3); repeat(5) { s.step(1f / 60f) }
        assertEquals(1, entered)
        s.player!!.position = vec(2, 0, 2); repeat(5) { s.step(1f / 60f) }
        assertEquals(1, picked); assertTrue(s.pickups.single().collected)
    }
}
