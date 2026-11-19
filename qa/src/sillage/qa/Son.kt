package sillage.qa

import sillage.CameraMode
import sillage.Scene
import sillage.audio.Audio
import sillage.audio.Reverb
import sillage.demo.island
import sillage.math.Vec3
import kotlin.math.sqrt

private const val DT = 1f / 60f

/** Le son de l'île, rendu hors ligne (rien n'est joué à haute voix) : guitare du feu, réverbération, Doppler. */
fun Qa.son() {
    /**
     * L'île à [heure], le héros à 2,5 m du feu, en première personne (les oreilles sont à la caméra) : le niveau
     * du son pendant 3 s, après 2 s d'installation. Le ressac (huit sources autour du rivage) est partout ; la
     * guitare s'y ajoute près du feu.
     */
    fun niveauPresDuFeu(heure: Float): Pair<Double, Scene> {
        val m = Scene().apply(island)
        val audio = Audio.offline(); m.audio = audio
        m.lighting.time = heure
        val joueur = m.player!!; joueur.controls = false
        joueur.position = Vec3(6.2f, m.groundHeight(6.2f, 4.2f) + .05f, 4.2f)
        m.camera.mode = CameraMode.FIRST_PERSON
        val bloc = FloatArray(2 * 800); var somme = 0.0; var n = 0
        repeat(300) { i ->
            m.step(DT); audio.mixer.render(bloc, 800)
            if (i >= 120) { for (x in bloc) somme += x * x; n += bloc.size }
        }
        return sqrt(somme / n) to m
    }
    val (nuit, scene) = niveauPresDuFeu(22f)
    val (jour, _) = niveauPresDuFeu(13f)
    // Le niveau propre de la guitare : ce que la nuit ajoute au jour (des sources sans rapport s'ajoutent en quadrature).
    val guitare = sqrt(maxOf(0.0, nuit * nuit - jour * jour))
    controle("la guitare du feu de camp (Ogg Vorbis) s'entend la nuit, pas le jour", "à 2,5 m du feu, la nuit, ≥ 0,6 × le reste (le ressac)") {
        "guitare ${f3(guitare)}, reste ${f3(jour)} (× ${f2(guitare / jour)})" to (guitare >= .6 * jour)
    }
    controle("réverbération de plein air sur l'île", "Reverb.OUTDOOR au mixeur") {
        val r = scene.audio!!.mixer.reverb
        (if (r === Reverb.OUTDOOR) "plein air" else "réglage : $r") to (r === Reverb.OUTDOOR)
    }
}
