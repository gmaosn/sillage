package sillage

import sillage.audio.Audio
import sillage.audio.Noise
import sillage.audio.Sound
import sillage.audio.Sounds
import sillage.audio.Voice
import sillage.math.Vec3
import sillage.physics.Body
import sillage.physics.BodyType
import kotlin.math.*

/** Ce que fait un objet lié quand il heurte quelque chose. */
enum class Choc { BOIS, BALLON }

/**
 * Les sons du monde, sans réglage : pas du héros selon le sol (calés sur ses foulées), saut,
 * atterrissage, coquillage, chocs des caisses et du ballon, caisse qu'on traîne, vagues sur le rivage
 * et mouettes. Tout est synthétisé par le moteur ([Sounds]) et placé dans l'espace.
 */
internal class SonsDuMonde(private val monde: Monde, private val audio: Audio) {
    private val pas = HashMap<String, List<Sound>>()
    private val saut by lazy { Sounds.jump() }
    private val atterrissage by lazy { Sounds.land() }
    private val coquillage by lazy { Sounds.pickup() }
    private val bois by lazy { List(3) { Sounds.knock(it) } }
    private val ballon by lazy { List(3) { Sounds.bounce(it) } }
    private val frottement by lazy { Sounds.scrape() }
    private val vagues by lazy { List(3) { Sounds.waves(it) } }
    private val mouettes by lazy { List(4) { Sounds.gull(it) } }
    private val rnd = Noise(2026)

    private class Suivi(val corps: Body, val choc: Choc, var vitesse: Vec3, var dernier: Float = -1f)
    private val suivis = ArrayList<Suivi>()
    /** Points du rivage où la mer se fait entendre. */
    val rivage = ArrayList<Vec3>()
    private var ambiance = false
    private var prochaineMouette = 3f
    private var traine: Voice? = null
    private var clip: String? = null
    private var phase = 0f
    private var enLAir = 0f
    private var auSol = true

    fun suivre(corps: Body, choc: Choc) { suivis.add(Suivi(corps, choc, corps.velocity)) }

    /** Un son du héros : placé à ses pieds, entendu pleinement depuis la caméra. */
    private fun duHeros(s: Sound, volume: Float, hauteur: Float = 1f) =
        audio.playAt(s, monde.joueur.position.plus(0f, .3f, 0f), volume, hauteur, referenceDistance = 9f, maxDistance = 60f)

    private fun varier(ecart: Float) = 1f + (rnd.unit() - .5f) * 2f * ecart

    fun coquillageRamasse() { audio.play(coquillage, .7f) }

    fun miseAJour(dt: Float) {
        val cam = monde.camera.position
        val cible = monde.joueur.position.plus(0f, 1.1f, 0f)
        audio.listen(cam, (cible - cam).let { if (it.lengthSquared() < 1e-6f) Vec3(0f, 0f, -1f) else it })
        ambiance()
        foulees()
        sautsEtAtterrissages(dt)
        chocs(dt)
        caisseTrainee()
    }

    private fun ambiance() {
        if (!ambiance) {
            ambiance = true
            // Chaque point du rivage joue une des trois mers, décalée et à peine accordée autrement :
            // les vagues n'arrivent jamais ensemble.
            rivage.forEachIndexed { k, p ->
                val s = vagues[k % vagues.size]
                audio.playAt(s, p, .55f, varier(.04f), loop = true, referenceDistance = 5f, maxDistance = 70f,
                    offset = rnd.unit() * s.duration)
            }
        }
        if (monde.temps >= prochaineMouette && rivage.isNotEmpty()) {
            prochaineMouette = monde.temps + 6f + rnd.unit() * 10f
            val a = rnd.unit() * 2 * PI.toFloat(); val r = 8f + rnd.unit() * 14f
            audio.playAt(mouettes[(rnd.unit() * mouettes.size).toInt().coerceAtMost(mouettes.size - 1)],
                Vec3(cos(a) * r, 9f + rnd.unit() * 6f, sin(a) * r), .5f, varier(.06f), referenceDistance = 8f, maxDistance = 90f)
        }
    }

    /** Un pas à chaque appui du pied : deux fois par cycle de marche, de course ou de poussée. */
    private fun foulees() {
        val a = monde.joueur.objet.animateur ?: return
        val nom = a.animation
        val volume = when (nom) { "walk" -> .38f; "run" -> .55f; "push" -> .45f; else -> { clip = nom; return } }
        val duree = a.modele.clips.getValue(nom).duree
        val p = (a.temps % duree) / duree
        if (nom != clip) { clip = nom; phase = .9f }
        // Le pied se pose un peu avant le point le plus bas des hanches (phases 0 et ½ des clips).
        val appui = if (nom == "run") .46f else .5f
        val pose = franchit(phase, p, appui) || franchit(phase, p, appui - .5f + if (appui < .5f) 1f else 0f)
        phase = p
        if (pose && monde.joueur.auSol) piedSur(sol(), volume)
    }

    /** La phase cyclique est passée de [avant] à [apres] en franchissant [x] (moins d'un cycle par image). */
    private fun franchit(avant: Float, apres: Float, x: Float) =
        if (apres >= avant) x > avant && x <= apres else x > avant || x <= apres

    private fun sol(): String {
        val p = monde.joueur.personnage
        p.groundBody?.let { b -> return monde.surfacesCorps[b] ?: "stone" }
        return p.groundMaterial?.name ?: "grass"
    }

    private fun piedSur(surface: String, volume: Float) {
        val variantes = pas.getOrPut(surface) { List(4) { Sounds.footstep(surface, it) } }
        duHeros(variantes[(rnd.unit() * 4).toInt().coerceAtMost(3)], volume * varier(.12f), varier(.05f))
    }

    private fun sautsEtAtterrissages(dt: Float) {
        val j = monde.joueur
        if (j.auSol) {
            if (!auSol && enLAir > .2f) {
                // Plus la chute est longue, plus le choc est fort ; on entend aussi le sol.
                val force = ((enLAir - .2f) / .6f).coerceIn(.15f, 1f)
                duHeros(atterrissage, .35f + .5f * force, varier(.04f))
                piedSur(sol(), .5f + .3f * force)
            }
            enLAir = 0f
        } else {
            if (auSol && j.vitesseY > 1f) duHeros(saut, .4f, varier(.06f))
            enLAir += dt
        }
        auSol = j.auSol
    }

    /** Une caisse ou le ballon change brusquement de vitesse : il a heurté quelque chose. */
    private fun chocs(dt: Float) {
        val g = monde.corps.gravity
        for (s in suivis) {
            val v = s.corps.velocity
            if (s.corps.isSleeping) { s.vitesse = v; continue }
            // Variation de vitesse hors gravité : la chute libre n'est pas un choc.
            val dv = (v - s.vitesse - g * dt).length()
            s.vitesse = v
            if (dv < 1.1f || monde.temps - s.dernier < .09f) continue
            s.dernier = monde.temps
            val force = ((dv - 1f) / 5f).coerceIn(.08f, 1f)
            val (sons, ecart) = if (s.choc == Choc.BOIS) bois to .06f else ballon to .05f
            audio.playAt(sons[(rnd.unit() * sons.size).toInt().coerceAtMost(sons.size - 1)], s.corps.position, force,
                varier(ecart), referenceDistance = 3f, maxDistance = 45f)
        }
    }

    /** Une caisse poussée frotte le sol tant qu'elle avance. */
    private fun caisseTrainee() {
        val b = monde.joueur.personnage.pushing
        val caisse = b != null && b.type == BodyType.DYNAMIC && suivis.any { it.corps == b && it.choc == Choc.BOIS }
        val v = if (caisse) b.velocity.let { sqrt(it.x * it.x + it.z * it.z) } else 0f
        if (caisse && v > .12f) {
            val volume = (v / 1.5f).coerceIn(.1f, .7f)
            val t = traine
            if (t == null || !t.isPlaying) traine = audio.playAt(frottement, b.position, volume, .9f + .2f * v, loop = true,
                referenceDistance = 4f, maxDistance = 40f)
            else { t.volume(volume); t.position(b.position); t.pitch(.9f + .2f * v.coerceAtMost(1.5f)) }
        } else traine?.let { it.stop(.12f); traine = null }
    }
}

/** Écrit tous les sons du moteur en WAV dans [dossier], pour les écouter ou les retoucher. */
internal fun exporterSons(dossier: java.nio.file.Path) {
    val sons = LinkedHashMap<String, Sound>()
    for (sol in listOf("grass", "sand", "wood", "stone", "road", "ice")) for (v in 0..3) sons["pas-$sol-$v"] = Sounds.footstep(sol, v)
    sons["saut"] = Sounds.jump(); sons["atterrissage"] = Sounds.land(); sons["coquillage"] = Sounds.pickup()
    for (v in 0..2) { sons["caisse-$v"] = Sounds.knock(v); sons["ballon-$v"] = Sounds.bounce(v); sons["vagues-$v"] = Sounds.waves(v) }
    for (v in 0..3) sons["mouette-$v"] = Sounds.gull(v)
    sons["frottement"] = Sounds.scrape()
    for ((nom, s) in sons) s.save(dossier.resolve("$nom.wav"))
    println("SILLAGE_SONS ${sons.size} fichiers dans $dossier")
}
