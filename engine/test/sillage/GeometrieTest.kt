package sillage

import sillage.math.Vec3
import kotlin.test.*

class GeometrieTest {
    @Test fun boiteAuxBonnesDimensionsEtNormalesSortantes() {
        val g = Geometrie()
        g.boite(1f, 0f, -2f, 2f, 3f, 4f, Couleur(1f, 0f, 0f))
        val m = g.construire()
        assertEquals(12, m.triangles.size)
        val centre = Vec3(1f, 1.5f, -2f)
        for (i in m.sommets.indices step 9) {
            val x = m.sommets[i]; val y = m.sommets[i + 1]; val z = m.sommets[i + 2]
            assertTrue(x == 0f || x == 2f, "x=$x"); assertTrue(y == 0f || y == 3f, "y=$y"); assertTrue(z == -4f || z == 0f, "z=$z")
        }
        for (i in m.sommets.indices step 27) {
            var milieu = Vec3.ZERO
            for (k in 0..2) milieu = milieu.plus(m.sommets[i + k * 9], m.sommets[i + k * 9 + 1], m.sommets[i + k * 9 + 2])
            milieu = milieu / 3f
            val n = Vec3(m.sommets[i + 3], m.sommets[i + 4], m.sommets[i + 5])
            assertTrue(n dot (milieu - centre) > 0f, "normale rentrante pour le triangle ${i / 27}")
        }
    }

    @Test fun hauteurInterpoleeDansUnTriangle() {
        val t = Triangle(Vec3(0f, 0f, 0f), Vec3(2f, 2f, 0f), Vec3(0f, 4f, 2f))
        assertEquals(0f, t.hauteur(0f, 0f)!!, 1e-5f)
        assertEquals(1f, t.hauteur(1f, 0f)!!, 1e-5f)
        assertEquals(2f, t.hauteur(0f, 1f)!!, 1e-5f)
        assertNull(t.hauteur(3f, 3f))
    }

    @Test fun triangleDegenereIgnore() {
        val g = Geometrie()
        g.triangle(Vec3.ZERO, Vec3(1f, 0f, 0f), Vec3(2f, 0f, 0f), Couleur(1f, 1f, 1f))
        assertEquals(0, g.construire().sommets.size)
    }
}
