package sillage.platform

import sillage.audio.Sound
import sillage.io.Assets
import sillage.vk.VulkanException
import java.nio.file.Files
import kotlin.test.*

/** Les messages pour débutants : ce qui manque, où on l'a cherché, quoi taper ; jamais une pile d'appels Java. */
class AdviceTest {
    @Test fun unFichierIntrouvableDitOuIlAEteCherche() {
        val e = assertFailsWith<IllegalStateException> { Sound.load("sons/absent-de-partout.ogg") }
        val m = e.message!!
        assertTrue(m.startsWith("This sound file is missing: sons/absent-de-partout.ogg"), m)
        assertTrue(System.getProperty("user.dir") + "/sons/absent-de-partout.ogg" in m, "le chemin cherché, en entier : $m")
        assertTrue("resources" in m, m)
        // En haut, le message seul et la ligne du jeu qui l'a demandé (ici, le premier appel hors du moteur : celui de JUnit).
        val text = Advice.plainText(e)!!
        assertTrue(text.startsWith("Sillage: This sound file is missing"), text)
        assertTrue("(This was asked by your game: " in text, text)
        assertNull(Advice.plainText(IllegalArgumentException("un vrai bogue")), "une autre erreur garde sa pile d'appels")
    }

    @Test fun unNomPresqueJusteEstPropose() {
        val dir = Files.createTempDirectory("sillage-advice")
        try {
            Files.createDirectories(dir.resolve("models"))
            Files.write(dir.resolve("models/Maison.GLB"), ByteArray(4))
            val before = Assets.roots
            Assets.roots = listOf(dir.toString())
            try {
                val m = assertFailsWith<IllegalStateException> { Assets.bytes("models/maison.glb", "GLB") }.message!!
                assertTrue(m.startsWith("This model file is missing: models/maison.glb"), m)
                assertTrue("$dir/models/maison.glb" in m, m)
                assertTrue("nearly that name: " in m && "Maison.GLB" in m, m)
            } finally { Assets.roots = before }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun vulkanAbsentDitQuelPaquetInstaller() {
        val loader = IllegalStateException("No Vulkan loader found (libvulkan.so.1, libvulkan.so): x")
        assertEquals("Vulkan is not installed (libvulkan.so.1 is missing)", Advice.vulkanReason(loader))
        val noDriver = VulkanException("vkCreateInstance", -9)
        assertEquals("there is no Vulkan driver for this graphics card", Advice.vulkanReason(noDriver))
        val fix = Advice.vulkanFix(noDriver)
        if (Advice.packageTool == "pacman") assertTrue(fix!!.startsWith("sudo pacman -S vulkan-"), fix)
        if (Advice.packageTool == "apt") assertEquals("sudo apt install mesa-vulkan-drivers", fix)
        val both = Advice.cannotDraw(noDriver, IllegalStateException("System library not found: libEGL.so.1, libEGL.so"))
        val m = both.message!!
        assertTrue(m.startsWith("This computer cannot draw the game: neither Vulkan nor OpenGL ES works."), m)
        assertTrue("OpenGL ES is not installed" in m && "To mend it, type in a terminal:" in m, m)
    }

    @Test fun lesPaquetsSuiventLeSysteme() {
        // Ce que dit le système, pour les machines d'essai (le portable : pacman ; un Raspberry Pi : apt).
        println("ADVICE système=${Advice.system} outil=${Advice.packageTool} cartes=${Advice.gpus.joinToString { it.vendor + "/" + it.driver }} " +
            "vulkan=${Advice.vulkanDrivers} egl=${Advice.eglPackages} son=${Advice.alsa}")
        assertTrue(Advice.install("a", "b").contains("a b"))
    }
}
