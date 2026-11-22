package sillage.io

import kotlin.test.*

class JsonTest {
    private fun obj(s: String) = Json.parse(s) as Json.Obj

    @Test fun documentImbrique() {
        val o = obj(""" { "a": [1, -2.5e3, true, false, null], "b": {"c": "d"}, "e": 0 } """)
        val a = o.arr("a")!!
        assertEquals(5, a.size)
        assertEquals(1, (a[0] as Json.Num).toInt())
        assertEquals(-2500.0, (a[1] as Json.Num).toDouble())
        assertSame(Json.Bool.TRUE, a[2]); assertSame(Json.Null, a[4])
        assertEquals("d", o.obj("b")!!.string("c"))
        assertEquals(0, o.int("e"))
        assertNull(o.int("absent"))
    }

    @Test fun flottantsArrondisUneSeuleFois() {
        // Le texte décimal va directement en Float, sans passer par un Double arrondi.
        for (t in listOf("0.1", "1.0000001", "3.4028235e38", "1e-45", "0.30000001192092896", "16777217"))
            assertEquals(t.toFloat(), Json.Num(t).toFloat(), t)
        assertEquals(listOf(.5f, .25f, 1f), (Json.parse("[0.5,0.25,1]") as Json.Arr).floats().toList())
    }

    @Test fun entiers() {
        assertEquals(3, Json.Num("3.0").toInt())
        assertEquals(300, Json.Num("3e2").toInt())
        assertEquals(5125, Json.Num("5125").toInt())
        assertFailsWith<IllegalArgumentException> { Json.Num("3.5").toInt() }
        assertFailsWith<IllegalArgumentException> { Json.Num("4294967296").toInt() }
    }

    @Test fun echappements() {
        val s = (Json.parse(""""a\"b\\c\/d\n\té\u00e9\ud83d\ude00"""") as Json.Str).value
        assertEquals("a\"b\\c/d\n\téé😀", s)
        assertEquals(""""a\"b\\\n"""", Json.Str("a\"b\\\n").toString())
    }

    @Test fun typesVerifies() {
        val o = obj("""{"n": "pas un nombre"}""")
        assertFailsWith<IllegalArgumentException> { o.int("n") }
        assertFailsWith<IllegalArgumentException> { o.arr("n") }
    }

    @Test fun textesInvalidesRefuses() {
        for (bad in listOf("", "{", "[1,]", "{\"a\":1,}", "01", "1.", "-", ".5", "1e", "+1", "\"abc", "\"a\u0001\"",
                "tru", "nul", "{a:1}", "[1 2]", "{\"a\" 1}", "\"\\x\"", "\"\\u12\"", "[1] 2", "NaN", "[Infinity]"))
            assertFailsWith<IllegalArgumentException>("« $bad » accepté") { Json.parse(bad) }
    }

    @Test fun profondeurBornee() {
        val ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH)
        assertIs<Json.Arr>(Json.parse(ok))
        val hostile = "[".repeat(100_000) + "]".repeat(100_000)
        val e = assertFailsWith<IllegalArgumentException> { Json.parse(hostile) }
        assertContains(e.message!!, "nesting")
    }

    @Test fun allerRetour() {
        val text = """{"a":[1,2.50,"x\n"],"b":{"c":null,"d":false}}"""
        assertEquals(text, Json.parse(text).toString())
    }
}
