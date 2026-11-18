package sillage

import org.joml.Vector3f
import kotlin.test.*

class GeometrieTest {
    @Test fun boiteAuxBonnesDimensionsEtNormalesSortantes() {
        val g = Geometrie()
        g.boite(1f, 0f, -2f, 2f, 3f, 4f, Couleur(1f, 0f, 0f))
        val m = g.construire()
        assertEquals(12, m.triangles.size)
        val centre = Vector3f(1f, 1.5f, -2f)
        for (i in m.sommets.indices step 9) {
            val x = m.sommets[i]; val y = m.sommets[i + 1]; val z = m.sommets[i + 2]
            assertTrue(x == 0f || x == 2f, "x=$x"); assertTrue(y == 0f || y == 3f, "y=$y"); assertTrue(z == -4f || z == 0f, "z=$z")
        }
        for (i in m.sommets.indices step 27) {
            val milieu = Vector3f()
            for (k in 0..2) milieu.add(m.sommets[i + k * 9], m.sommets[i + k * 9 + 1], m.sommets[i + k * 9 + 2])
            milieu.div(3f)
            val n = Vector3f(m.sommets[i + 3], m.sommets[i + 4], m.sommets[i + 5])
            assertTrue(n.dot(milieu.sub(centre)) > 0f, "normale rentrante pour le triangle ${i / 27}")
        }
    }

    @Test fun hauteurInterpoleeDansUnTriangle() {
        val t = Triangle(Vector3f(0f, 0f, 0f), Vector3f(2f, 2f, 0f), Vector3f(0f, 4f, 2f))
        assertEquals(0f, t.hauteur(0f, 0f)!!, 1e-5f)
        assertEquals(1f, t.hauteur(1f, 0f)!!, 1e-5f)
        assertEquals(2f, t.hauteur(0f, 1f)!!, 1e-5f)
        assertNull(t.hauteur(3f, 3f))
    }

    @Test fun triangleDegenereIgnore() {
        val g = Geometrie()
        g.triangle(Vector3f(), Vector3f(1f, 0f, 0f), Vector3f(2f, 0f, 0f), Couleur(1f, 1f, 1f))
        assertEquals(0, g.construire().sommets.size)
    }
}
