package sillage

import sillage.math.Mat4
import sillage.math.Quat
import sillage.math.Vec3
import sillage.math.slerpInto

/** A node of a GLB model: rest transform, children, and its own triangles in node space. */
class Noeud internal constructor(
    val nom: String?,
    val parent: Int,
    val enfants: IntArray,
    val translation: Vec3,
    val rotation: Quat,
    val echelle: Vec3,
    /** Set when the GLB gives a `matrix` instead of TRS; such a node cannot be animated. */
    val matrice: Mat4?,
    /** Per triangle ([TRI] floats): a, b, c (9 floats), the colour r, g, b, then the material index (−1: none). */
    internal val triangles: FloatArray,
) {
    val local: Mat4 get() = matrice ?: Mat4.trs(translation, rotation, echelle)
}

enum class Chemin(val composantes: Int) { TRANSLATION(3), ROTATION(4), ECHELLE(3) }

/** One animated property of one node: key times and values, linear (slerp for rotations) or step. */
class Canal internal constructor(val noeud: Int, val chemin: Chemin, val temps: FloatArray, val valeurs: FloatArray, val palier: Boolean) {
    init {
        require(temps.isNotEmpty()) { "Canal d'animation sans clé" }
        require(valeurs.size == temps.size * chemin.composantes) { "Canal d'animation : ${valeurs.size} valeurs pour ${temps.size} clés" }
        for (i in 1 until temps.size) require(temps[i] > temps[i - 1]) { "Temps d'animation non croissants" }
    }
}

/** A glTF animation: several channels sharing one timeline. */
class Clip internal constructor(val nom: String, val canaux: List<Canal>) {
    val duree: Float = canaux.maxOfOrNull { it.temps.last() } ?: 0f
}

/** A GLB scene kept as a hierarchy, with its animations. */
internal const val TRI = 13

class Modele internal constructor(val noeuds: List<Noeud>, val racines: IntArray, val clips: Map<String, Clip>,
                                  /** Material names of the GLB, by index (as named in Blender). */
                                  val materiaux: List<String> = emptyList()) {
    /** Node indices with every parent before its children, in scene order. */
    val ordre: IntArray = ArrayList<Int>().also { out ->
        val vus = BooleanArray(noeuds.size)
        fun visiter(i: Int) {
            require(!vus[i]) { "Cycle ou nœud partagé dans la hiérarchie GLB" }
            vus[i] = true; out.add(i)
            for (e in noeuds[i].enfants) visiter(e)
        }
        for (r in racines) visiter(r)
    }.toIntArray()

    /** Each node's triangles as a mesh in node space, shared by every instance of the model. */
    private val maillagesLocaux: Map<Int, Maillage> by lazy {
        ordre.filter { noeuds[it].triangles.isNotEmpty() }.associateWith { i ->
            val g = Geometrie(); val t = noeuds[i].triangles
            for (k in t.indices step TRI)
                g.triangle(Vec3(t[k], t[k + 1], t[k + 2]), Vec3(t[k + 3], t[k + 4], t[k + 5]), Vec3(t[k + 6], t[k + 7], t[k + 8]),
                    Couleur(t[k + 9], t[k + 10], t[k + 11]))
            g.construire()
        }
    }

    /** The whole scene in its rest pose as one world-space mesh (static models, collisions). */
    fun aplatir(): Maillage {
        val g = Geometrie()
        fun visiter(i: Int, parent: Mat4) {
            val n = noeuds[i]
            val monde = parent * n.local
            val miroir = monde.determinant3x3() < 0
            val t = n.triangles
            for (k in t.indices step TRI) {
                val a = monde.transformPoint(Vec3(t[k], t[k + 1], t[k + 2]))
                val b = monde.transformPoint(Vec3(t[k + 3], t[k + 4], t[k + 5]))
                val c = monde.transformPoint(Vec3(t[k + 6], t[k + 7], t[k + 8]))
                val couleur = Couleur(t[k + 9], t[k + 10], t[k + 11])
                // Normales par face recalculées pour les aplats, y compris après une échelle négative.
                if (miroir) g.triangle(a, c, b, couleur) else g.triangle(a, b, c, couleur)
            }
            for (e in n.enfants) visiter(e, monde)
        }
        for (r in racines) visiter(r, Mat4.IDENTITY)
        return g.construire()
    }

    /**
     * The scene in its rest pose as world-space triangles for collision (9 floats each, same winding
     * as [aplatir]) and, for each triangle, its material index in [materiaux] (−1: none).
     */
    fun surfaces(): Pair<FloatArray, IntArray> {
        val pos = ArrayList<Float>(); val mat = ArrayList<Int>()
        fun visiter(i: Int, parent: Mat4) {
            val n = noeuds[i]
            val monde = parent * n.local
            val miroir = monde.determinant3x3() < 0
            val t = n.triangles
            for (k in t.indices step TRI) {
                val pts = (0..2).map { v -> monde.transformPoint(Vec3(t[k + 3 * v], t[k + 3 * v + 1], t[k + 3 * v + 2])) }
                for (p in if (miroir) listOf(pts[0], pts[2], pts[1]) else pts) { pos.add(p.x); pos.add(p.y); pos.add(p.z) }
                mat.add(t[k + 12].toInt())
            }
            for (e in n.enfants) visiter(e, monde)
        }
        for (r in racines) visiter(r, Mat4.IDENTITY)
        return pos.toFloatArray() to mat.toIntArray()
    }

    /** New parts for one instance: one per node that has triangles. */
    fun parties(): List<Partie> = ordre.filter { it in maillagesLocaux }.map { Partie(it, maillagesLocaux.getValue(it)) }
}

/** A drawable piece of an animated object; [pose] places it in the object's space. */
class Partie internal constructor(val noeud: Int, val maillage: Maillage) {
    var pose: Mat4 = Mat4.IDENTITY
        internal set
}

/**
 * Plays the clips of a [Modele] on one instance's parts. Switching clip cross-fades from the pose
 * reached at that moment. The pose lives in flat arrays (translation, rotation, scale per node).
 */
class Animateur(val modele: Modele, private val parties: List<Partie>) {
    private val n = modele.noeuds.size
    private val pt = FloatArray(3 * n); private val pr = FloatArray(4 * n); private val ps = FloatArray(3 * n)
    private val ft = FloatArray(3 * n); private val fr = FloatArray(4 * n); private val fs = FloatArray(3 * n)
    private val monde = arrayOfNulls<Mat4>(n)
    private var clip: Clip? = null
    private var boucle = true
    private var fondu = 0f
    private var fonduTotal = 0f
    /** Time in the current clip, in seconds of clip time. */
    var temps = 0f
        private set
    /** Playback rate: 1 is the authored speed. */
    var vitesse = 1f
    val animation: String? get() = clip?.nom

    init { repos(); poser() }

    /** Starts [nom] unless it is already playing; [fondu] seconds of cross-fade from the current pose. */
    fun jouer(nom: String, boucle: Boolean = true, fondu: Float = .15f, vitesse: Float = this.vitesse) {
        val c = modele.clips[nom] ?: throw IllegalArgumentException(
            "Animation « $nom » absente du modèle (disponibles : ${modele.clips.keys.joinToString()})")
        this.vitesse = vitesse
        this.boucle = boucle
        if (c === clip) return
        pt.copyInto(ft); pr.copyInto(fr); ps.copyInto(fs)
        this.fondu = fondu; fonduTotal = fondu
        clip = c; temps = 0f
    }

    fun avancer(dt: Float) {
        temps += dt * vitesse
        echantillonner()
        if (fondu > 0f) {
            fondu -= dt
            if (fondu > 0f) melanger(1f - fondu / fonduTotal)
        }
        poser()
    }

    private fun repos() {
        for (i in 0 until n) {
            val nd = modele.noeuds[i]
            pt[3 * i] = nd.translation.x; pt[3 * i + 1] = nd.translation.y; pt[3 * i + 2] = nd.translation.z
            pr[4 * i] = nd.rotation.x; pr[4 * i + 1] = nd.rotation.y; pr[4 * i + 2] = nd.rotation.z; pr[4 * i + 3] = nd.rotation.w
            ps[3 * i] = nd.echelle.x; ps[3 * i + 1] = nd.echelle.y; ps[3 * i + 2] = nd.echelle.z
        }
    }

    private fun echantillonner() {
        repos()
        val c = clip ?: return
        val x = when {
            c.duree <= 0f -> 0f
            boucle -> temps.mod(c.duree)
            else -> minOf(temps, c.duree)
        }
        for (canal in c.canaux) echantillon(canal, x)
    }

    private fun echantillon(c: Canal, x: Float) {
        val k = c.chemin.composantes
        val dst = when (c.chemin) { Chemin.TRANSLATION -> pt; Chemin.ROTATION -> pr; Chemin.ECHELLE -> ps }
        val base = c.noeud * k
        val t = c.temps; val v = c.valeurs
        if (t.size == 1 || x <= t[0]) { v.copyInto(dst, base, 0, k); return }
        if (x >= t[t.size - 1]) { v.copyInto(dst, base, (t.size - 1) * k, t.size * k); return }
        var lo = 0; var hi = t.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (t[mid] <= x) lo = mid else hi = mid }
        if (c.palier) { v.copyInto(dst, base, lo * k, lo * k + k); return }
        val u = (x - t[lo]) / (t[hi] - t[lo])
        if (c.chemin == Chemin.ROTATION) slerpInto(v, lo * 4, v, hi * 4, u, dst, base)
        else for (j in 0 until k) dst[base + j] = v[lo * k + j] + (v[hi * k + j] - v[lo * k + j]) * u
    }

    /** Pose = frozen pose → sampled pose at [u] ∈ [0, 1]. */
    private fun melanger(u: Float) {
        for (j in pt.indices) pt[j] = ft[j] + (pt[j] - ft[j]) * u
        for (j in ps.indices) ps[j] = fs[j] + (ps[j] - fs[j]) * u
        for (i in 0 until n) slerpInto(fr, 4 * i, pr, 4 * i, u, pr, 4 * i)
    }

    private fun poser() {
        for (i in modele.ordre) {
            val nd = modele.noeuds[i]
            val local = nd.matrice ?: Mat4.trs(Vec3(pt[3 * i], pt[3 * i + 1], pt[3 * i + 2]),
                Quat(pr[4 * i], pr[4 * i + 1], pr[4 * i + 2], pr[4 * i + 3]), Vec3(ps[3 * i], ps[3 * i + 1], ps[3 * i + 2]))
            monde[i] = if (nd.parent < 0) local else monde[nd.parent]!! * local
        }
        for (p in parties) p.pose = monde[p.noeud]!!
    }
}
