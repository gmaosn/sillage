package sillage

import kotlin.test.*

class FormatTest {
    @Test fun commeStringFormat() {
        assertEquals("16 h 05", "%d h %02d".fmt(16, 5))
        assertEquals("3.14", "%.2f".fmt(3.14159)); assertEquals("  3.142", "%7.3f".fmt(3.14159f)); assertEquals("-0.50", "%.2f".fmt(-.5))
        assertEquals("2", "%.0f".fmt(1.5)); assertEquals("3", "%.0f".fmt(2.5)); assertEquals("0.125000", "%f".fmt(.125))
        assertEquals("ff 00FF", "%x %04X".fmt(255, 255)); assertEquals("ab |cd   |  ef", "%s |%-5s|%4s".fmt("ab", "cd", "ef"))
        assertEquals("100% +7 1,234,567", "%d%% %+d %,d".fmt(100, 7, 1234567)); assertEquals("-007", "%04d".fmt(-7))
        assertEquals("1.500e+03", "%.3e".fmt(1500.0)); assertEquals("true null", "%b %s".fmt(1, null))
        assertFailsWith<IllegalArgumentException> { "%d %d".fmt(1) }
    }
}
