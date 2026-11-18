package sillage.qa

import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.Body
import sillage.physics.BodyType
import sillage.physics.Box
import sillage.physics.PhysicsWorld
import sillage.physics.Sphere
import sillage.physics.TriangleMesh
import sillage.physics.raycast
import java.util.Random
import kotlin.math.*

private const val DT = 1f / 60f

/** Un sol : une grande boîte statique de 1 m d'épaisseur dont le dessus est à y = 0. */
private fun PhysicsWorld.solBoite() = add(Box(60f, .5f, 60f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC)

/** Un terrain bosselé maillé (vagues de ±40 cm). */
private fun terrainBossele(rnd: Random, demi: Float = 12f, n: Int = 24): FloatArray {
    val a = rnd.f(.2f, .6f); val b = rnd.f(.2f, .6f); val ph = rnd.f(0f, 6f)
    fun h(x: Float, z: Float) = .4f * sin(a * x + ph) * cos(b * z)
    val out = ArrayList<Float>(); val pas = 2 * demi / n
    for (i in 0 until n) for (j in 0 until n) {
        val x0 = -demi + i * pas; val z0 = -demi + j * pas; val x1 = x0 + pas; val z1 = z0 + pas
        for (p in listOf(Vec3(x0, h(x0, z0), z0), Vec3(x0, h(x0, z1), z1), Vec3(x1, h(x1, z1), z1),
                Vec3(x0, h(x0, z0), z0), Vec3(x1, h(x1, z1), z1), Vec3(x1, h(x1, z0), z0))) { out.add(p.x); out.add(p.y); out.add(p.z) }
    }
    return out.toFloatArray()
}

fun Qa.physique() {
    val rnd = Random(graine)

    controle("valeurs finies (scènes au hasard, 10 s)", "aucune valeur non finie") {
        val scenes = cas(3, 10); var corps = 0; var mauvais = 0
        repeat(scenes) { k ->
            val w = PhysicsWorld()
            if (k % 2 == 0) w.solBoite() else w.addMesh(TriangleMesh(terrainBossele(rnd)))
            val bs = ajouterCorps(w, rnd, cas(40, 70), 5f, 1f, 9f, vitesse = 8f)
            corps += bs.size
            repeat(600) { w.step(DT) }
            mauvais += bs.count { !it.fini() }
        }
        "$scenes scènes, $corps corps, $mauvais non finis" to (mauvais == 0)
    }

    controle("pas de traversée du sol (chutes de 5 à 30 m/s)", "aucun corps sous le sol, ≤ 2 cm") {
        var pire = 0f; var dessous = 0; var n = 0
        for (maillage in listOf(false, true)) repeat(cas(2, 5)) {
            val w = PhysicsWorld()
            if (maillage) w.addMesh(TriangleMesh(solMaille())) else w.solBoite()
            val bs = ajouterCorps(w, rnd, 30, 8f, 1f, 6f)
            for (b in bs) b.velocity = Vec3(rnd.f(-2f, 2f), -rnd.f(5f, 30f), rnd.f(-2f, 2f))
            repeat(180) { w.step(DT) }
            for (b in bs) { val bas = b.plusBas(); pire = min(pire, bas); if (bas < -.1f) dessous++; n++ }
        }
        "$n corps, point le plus bas ${cm(pire)}, $dessous passés dessous" to (dessous == 0 && pire > -.02f)
    }

    info("vitesse limite avant traversée (sphère de 10 cm)") {
        fun tient(v: Float, maillage: Boolean): Boolean {
            val w = PhysicsWorld()
            if (maillage) w.addMesh(TriangleMesh(solMaille())) else w.add(Box(5f, .05f, 5f), Vec3(0f, -.05f, 0f), type = BodyType.STATIC)
            val b = w.add(Sphere(.05f), Vec3(0f, 1f, 0f))
            b.velocity = Vec3(0f, -v, 0f)
            repeat(120) { w.step(DT) }
            return b.position.y > -.05f
        }
        fun limite(maillage: Boolean): String {
            val vitesses = listOf(5f, 10f, 20f, 40f, 80f, 160f, 320f)
            val v = vitesses.lastOrNull { s -> vitesses.filter { it <= s }.all { tient(it, maillage) } }
            return if (v == null) "moins de 5 m/s" else if (v == vitesses.last()) "plus de ${v.toInt()} m/s" else "${v.toInt()} m/s (traverse à ${vitesses[vitesses.indexOf(v) + 1].toInt()})"
        }
        "dalle de 10 cm : ${limite(false)} ; sol maillé : ${limite(true)} (pas de collision continue)"
    }

    controle("énergie : jamais créée (chocs en apesanteur, 10 s)", "≤ +1 %") {
        var pire = 0.0; val scenes = cas(2, 5)
        repeat(scenes) {
            val w = PhysicsWorld(gravity = Vec3.ZERO)
            // Une pièce fermée de 8 m.
            for (axe in 0..2) for (signe in floatArrayOf(-1f, 1f)) {
                val h = floatArrayOf(4.5f, 4.5f, 4.5f); h[axe] = .5f
                val c = FloatArray(3); c[axe] = signe * 4.5f
                w.add(Box(h[0], h[1], h[2]), Vec3(c[0], c[1], c[2]), type = BodyType.STATIC)
            }
            val bs = ajouterCorps(w, rnd, 40, 3f, -3f, 3f, vitesse = 6f, rebond = { rnd.f(0f, 1f) })
            val e0 = energie(bs, w.gravity)
            repeat(600) { w.step(DT); pire = max(pire, energie(bs, w.gravity) / e0 - 1) }
        }
        "$scenes pièces, 40 corps : au plus ${pct(pire)}" to (pire <= .01)
    }

    controle("quantité de mouvement conservée (apesanteur, 5 s)", "dérive ≤ 0,01 %") {
        var pire = 0.0
        repeat(cas(2, 5)) {
            val w = PhysicsWorld(gravity = Vec3.ZERO)
            val bs = ajouterCorps(w, rnd, 40, 3f, -3f, 3f)
            for (b in bs) { b.velocity = -b.position * rnd.f(.5f, 2f) + rnd.dir(); b.angularVelocity = rnd.dir() * rnd.f(0f, 3f) }
            val p0 = impulsion(bs); val echelle = bs.sumOf { it.mass * it.velocity.length().toDouble() }
            repeat(300) { w.step(DT); pire = max(pire, ecart(impulsion(bs), p0) / echelle) }
        }
        "dérive au plus ${"%.4f".format(pire * 100)} % de Σ m|v|" to (pire <= 1e-4)
    }

    controle("moment cinétique conservé (apesanteur, 5 s)", "dérive ≤ 3 %") {
        var pire = 0.0
        repeat(cas(2, 5)) {
            val w = PhysicsWorld(gravity = Vec3.ZERO)
            val bs = ajouterCorps(w, rnd, 40, 3f, -3f, 3f)
            for (b in bs) { b.velocity = -b.position * rnd.f(.5f, 2f) + rnd.dir(); b.angularVelocity = rnd.dir() * rnd.f(0f, 3f) }
            val l0 = momentCinetique(bs)
            val echelle = bs.sumOf { (it.position cross (it.velocity * it.mass)).length().toDouble() } + norme(l0)
            repeat(300) { w.step(DT); pire = max(pire, ecart(momentCinetique(bs), l0) / echelle) }
        }
        "dérive au plus ${pct(pire)}" to (pire <= .03)
    }

    // Tas au repos dans une fosse (une boule qui roule sur un sol sans fin ne s'arrête pas : c'est juste).
    var pireEnfoncement = 0f; var dormants = 0; var total = 0; var agitation = 0f; var tas = 0
    repeat(cas(2, 6)) {
        val w = PhysicsWorld(); w.solBoite()
        for ((x, z) in listOf(4f to 0f, -4f to 0f, 0f to 4f, 0f to -4f))
            w.add(Box(if (x == 0f) 4.5f else .5f, 1.5f, if (z == 0f) 4.5f else .5f), Vec3(x, 1.5f, z), type = BodyType.STATIC)
        val bs = ajouterCorps(w, rnd, cas(50, 80), 3f, .5f, 10f)
        repeat(900) { w.step(DT) }
        for (c in w.contacts()) pireEnfoncement = max(pireEnfoncement, -c.separation)
        dormants += bs.count { it.isSleeping }; total += bs.size
        if (System.getenv("QA_DETAIL") != null) for (b in bs.filter { !it.isSleeping }.sortedByDescending { it.velocity.length() + it.angularVelocity.length() }.take(4))
            println("DETAIL éveillé ${b.shape::class.simpleName} v=${b.velocity.length()} w=${b.angularVelocity.length()} p=${b.position}")
        agitation = max(agitation, bs.filter { !it.isSleeping }.maxOfOrNull { it.velocity.length() } ?: 0f)
        tas++
    }
    controle("enfoncement au repos (tas au hasard dans une fosse, 15 s)", "≤ 1 cm, toutes masses (densités de 100 à 3000)") { "$tas tas, $total corps : ${mm(pireEnfoncement)} au plus" to (pireEnfoncement <= .01f) }
    controle("sommeil des tas au repos (15 s)", "≥ 95 % endormis") { "${dormants}/$total endormis (${pct(dormants.toDouble() / total)})" to (dormants >= .95 * total) }
    controle("agitation des corps éveillés au repos", "≤ 5 cm/s") { "vitesse max ${cm(agitation)}/s" to (agitation <= .05f) }

    controle("pyramide de 55 caisses (20 s)", "dérive ≤ 2 cm, toutes endormies") {
        val w = PhysicsWorld(); w.solBoite()
        val bs = ArrayList<Body>()
        for (rang in 0 until 10) for (i in 0 until 10 - rang)
            bs.add(w.add(Box(.5f, .5f, .5f), Vec3(i * 1.0f - (9 - rang) * .5f, .5f + rang * 1.0f, 0f)))
        val depart = bs.map { it.position }
        repeat(1200) { w.step(DT) }
        val derive = bs.indices.maxOf { (bs[it].position - depart[it]).length() }
        val dorment = bs.count { it.isSleeping }
        "dérive ${cm(derive)}, $dorment/55 endormies" to (derive <= .02f && dorment == 55)
    }

    controle("déterminisme (même scène, deux fois)", "bit pour bit") {
        fun etat(): List<Int> {
            val r = Random(graine * 31 + 7)
            val w = PhysicsWorld(); w.solBoite()
            val bs = ajouterCorps(w, r, 60, 3f, .5f, 8f, vitesse = 5f)
            repeat(300) { w.step(DT) }
            return bs.flatMap { b -> listOf(b.position.x, b.position.y, b.position.z, b.rotation.x, b.rotation.w, b.velocity.x, b.angularVelocity.y).map { it.toRawBits() } }
        }
        val a = etat(); val b = etat()
        val diff = a.indices.count { a[it] != b[it] }
        "${a.size} valeurs, $diff différentes" to (diff == 0)
    }

    controle("rapports de masses : un lourd posé sur un léger", "1:10, 1:100, 1:1000 : enfoncement ≤ 3 mm, dérive ≤ 5 mm") {
        val lignes = ArrayList<String>(); var ok = true
        for (r in listOf(10f, 100f, 1000f)) {
            val w = PhysicsWorld(); w.solBoite()
            w.add(Box(.25f, .25f, .25f), Vec3(0f, .25f, 0f), mass = 1f)
            val haut = w.add(Box(.5f, .5f, .5f), Vec3(0f, 1.01f, 0f), mass = r)
            repeat(300) { w.step(DT) }
            val e = 1f - haut.position.y; val d = sqrt(haut.position.x * haut.position.x + haut.position.z * haut.position.z)
            lignes.add("1:${r.toInt()} ${mm(e)}/${mm(d)}"); if (e > .003f || d > .005f || !haut.isSleeping) ok = false
        }
        lignes.joinToString(", ") to ok
    }

    controle("un lourd sur un léger, sur un sol glissant", "250 N : < 5 cm ; 400 N : l'ensemble glisse") {
        fun pousser(force: Float): Pair<Float, Float> {
            val w = PhysicsWorld(); w.add(Box(30f, .5f, 30f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC, friction = .2f)
            val planche = w.add(Box(.6f, .05f, .6f), Vec3(0f, .05f, 0f), mass = 1f, friction = .5f)
            val caisse = w.add(Box(.4f, .4f, .4f), Vec3(0f, .5f, 0f), mass = 100f, friction = .5f)
            repeat(60) { w.step(DT) }
            val p0 = planche.position.x; val c0 = caisse.position.x
            repeat(60) { w.applyForce(caisse, Vec3(force, 0f, 0f)); w.step(DT) }
            return (planche.position.x - p0) to (caisse.position.x - c0)
        }
        val (p250, c250) = pousser(250f); val (p400, c400) = pousser(400f)
        "250 N : planche ${mm(p250)}, caisse ${mm(c250)} ; 400 N : planche ${mm(p400)}, caisse ${mm(c400)} (théorie 430 mm ensemble)" to
            (abs(p250) < .05f && abs(c250) < .06f && p400 > .25f && p400 < .6f && abs(c400 - p400) < .15f)
    }

    controle("retirer des corps d'un tas", "voisins réveillés, plus de contact ni de rayon, places réutilisées") {
        val w = PhysicsWorld(); w.solBoite()
        val bs = ajouterCorps(w, rnd, 60, 2f, .5f, 8f)
        repeat(600) { w.step(DT) }
        val retires = bs.shuffled(rnd).take(20)
        for (b in retires) w.remove(b)
        var touches = 0; var nonFinis = 0
        repeat(300) {
            w.step(DT)
            touches += w.contacts().count { c -> c.b.isRemoved || c.a?.isRemoved == true }
        }
        val restants = bs.filter { !it.isRemoved }
        nonFinis = restants.count { !it.fini() }
        val rayons = retires.count { b -> w.raycast(b.position.plus(0f, 5f, 0f), Vec3(0f, -1f, 0f), 5f)?.body == b }
        val nouveaux = (0 until 20).map { w.add(Box(.2f, .2f, .2f), Vec3(rnd.f(-2f, 2f), 6f + .5f * it, rnd.f(-2f, 2f))) }
        val reutilisees = nouveaux.count { n -> retires.any { it.id == n.id } }
        repeat(600) { w.step(DT) }
        // Le tas, réveillé par les retraits et les nouvelles caisses, doit se reposer.
        val dorment = restants.count { it.isSleeping }
        "20 retirés : $touches contacts après, $rayons rayons qui les touchent, $reutilisees/20 places reprises, $nonFinis non finis, ${dorment}/${restants.size} du tas rendormis 10 s après" to
            (touches == 0 && rayons == 0 && reutilisees == 20 && nonFinis == 0 && dorment >= restants.size * 9 / 10)
    }

    controle("chute libre (1 s) contre ½gt²", "écart ≤ 0,5 %") {
        val w = PhysicsWorld(); val b = w.add(Sphere(.1f), Vec3(0f, 100f, 0f))
        repeat(60) { w.step(DT) }
        val e = abs((100f - b.position.y) / (.5f * 9.81f) - 1f)
        "chute ${f3(100f - b.position.y)} m, écart ${pct(e)}" to (e <= .005f)
    }

    controle("glissade freinée contre v²/(2μg)", "écart ≤ 2 %") {
        val w = PhysicsWorld(); w.add(Box(60f, .5f, 60f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC, friction = .4f)
        val b = w.add(Box(.3f, .3f, .3f), Vec3(0f, .3f, 0f), friction = .4f)
        repeat(30) { w.step(DT) }
        val x0 = b.position.x; b.velocity = Vec3(5f, 0f, 0f)
        repeat(300) { w.step(DT) }
        val attendu = 25f / (2 * .4f * 9.81f); val e = abs((b.position.x - x0) / attendu - 1f)
        "${f2(b.position.x - x0)} m pour ${f2(attendu)} m, écart ${pct(e)}" to (e <= .02f)
    }

    controle("plan incliné de 20° : adhérence et glissement", "immobile à μ = 0,6 ; accélération à 3 %") {
        val angle = Math.toRadians(20.0).toFloat()
        val pente = Quat.axisAngle(Vec3.Z, angle)
        fun essai(mu: Float, secondes: Float): Pair<Float, Float> {
            val w = PhysicsWorld()
            w.add(Box(20f, .5f, 3f), Vec3.ZERO, pente, type = BodyType.STATIC, friction = mu)
            val n = pente.rotate(Vec3.Y)
            val b = w.add(Box(.25f, .25f, .25f), n * .76f, pente, friction = mu)
            repeat(20) { w.step(DT) }
            val p0 = b.position; val v0 = b.velocity.length()
            repeat((secondes / DT).toInt()) { w.step(DT) }
            return (b.position - p0).length() to (b.velocity.length() - v0) / secondes
        }
        val (derive, _) = essai(.6f, 5f)
        val (_, acc) = essai(.2f, 1f)
        val attendu = 9.81f * (sin(angle) - .2f * cos(angle))
        val e = abs(acc / attendu - 1f)
        "μ = 0,6 : dérive ${mm(derive)} ; μ = 0,2 : ${f2(acc)} m/s² pour ${f2(attendu)} (${pct(e)})" to (derive < .01f && e <= .03f)
    }
}
