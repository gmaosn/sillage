package sillage

import sillage.io.Json
import kotlin.math.abs
import kotlin.math.sqrt

// What a glTF material gives beyond its base colour, as the engine draws it: a normal map, its own light
// (emission, by a factor and a map) and blending. Read by Glb, laid on the meshes by Model (Mesh.surface).

/** Per material of a model: its normal map and how strongly it bends the normal, its emission, blending. */
internal class MaterialLooks(
    val normalMap: List<Texture?>,
    val normalScale: FloatArray,
    /** emissiveFactor × KHR_materials_emissive_strength (linear light); null: none. */
    val emission: List<Color?>,
    val emissionMap: List<Texture?>,
    /** alphaMode BLEND: drawn blended, sorted with the particles; [opacity] its base colour's alpha. */
    val blended: BooleanArray,
    val opacity: FloatArray,
) {
    fun maps(m: Int) = m in normalMap.indices && (normalMap[m] != null || emissionMap[m] != null)
    fun any(m: Int) = m in normalMap.indices && (maps(m) || emission[m] != null || blended[m])
}

/** The looks of [materials] (glTF's array); [texture] turns a textureInfo into the engine's texture. */
internal fun materialLooks(materials: Json.Arr, texture: (Json.Obj) -> Texture): MaterialLooks {
    val n = materials.size
    val normal = arrayOfNulls<Texture>(n); val scale = FloatArray(n) { 1f }
    val emission = arrayOfNulls<Color>(n); val emissionMap = arrayOfNulls<Texture>(n)
    val blended = BooleanArray(n); val opacity = FloatArray(n) { 1f }
    for (m in 0 until n) {
        val mat = materials.obj(m)
        mat.obj("normalTexture")?.let { t -> normal[m] = texture(t); scale[m] = t.float("scale") ?: 1f }
        val strength = mat.obj("extensions")?.obj("KHR_materials_emissive_strength")?.float("emissiveStrength") ?: 1f
        val f = mat.arr("emissiveFactor")?.floats()
        if (f != null && f.size >= 3 && (f[0] > 0f || f[1] > 0f || f[2] > 0f)) {
            emission[m] = Color(f[0] * strength, f[1] * strength, f[2] * strength)
            mat.obj("emissiveTexture")?.let { emissionMap[m] = texture(it) }
        }
        blended[m] = mat.string("alphaMode") == "BLEND"
        opacity[m] = mat.obj("pbrMetallicRoughness")?.arr("baseColorFactor")?.floats()?.let { if (it.size >= 4) it[3] else 1f } ?: 1f
    }
    return MaterialLooks(normal.toList(), scale, emission.toList(), emissionMap.toList(), blended, opacity)
}

/**
 * Tangents of a glTF primitive when the file gives none, per vertex of [positions] (x, y, z, then the handedness ±1):
 * each triangle's direction of growing u, summed over the triangles sharing a vertex ([ids], three per triangle),
 * made square to the vertex's normal (the file's, else the faces'); the handedness says on which side of the normal
 * the image's top lies (glTF: the bitangent is normal × tangent × w). Close to MikkTSpace on ordinary meshes.
 */
internal fun tangentsOf(positions: List<FloatArray>, normals: List<FloatArray>?, uvs: List<FloatArray>, ids: IntArray): Array<FloatArray> {
    val count = positions.size
    val t = FloatArray(3 * count); val b = FloatArray(3 * count); val fn = FloatArray(3 * count)
    for (i in ids.indices step 3) {
        val a = ids[i]; val c1 = ids[i + 1]; val c2 = ids[i + 2]
        if (a !in 0 until count || c1 !in 0 until count || c2 !in 0 until count) continue
        val p0 = positions[a]; val p1 = positions[c1]; val p2 = positions[c2]
        val e1x = p1[0] - p0[0]; val e1y = p1[1] - p0[1]; val e1z = p1[2] - p0[2]
        val e2x = p2[0] - p0[0]; val e2y = p2[1] - p0[1]; val e2z = p2[2] - p0[2]
        val du1 = uvs[c1][0] - uvs[a][0]; val dv1 = uvs[c1][1] - uvs[a][1]
        val du2 = uvs[c2][0] - uvs[a][0]; val dv2 = uvs[c2][1] - uvs[a][1]
        val det = du1 * dv2 - du2 * dv1
        val nx = e1y * e2z - e1z * e2y; val ny = e1z * e2x - e1x * e2z; val nz = e1x * e2y - e1y * e2x
        for (v in intArrayOf(a, c1, c2)) { fn[3 * v] += nx; fn[3 * v + 1] += ny; fn[3 * v + 2] += nz }
        if (abs(det) < 1e-12f) continue
        val k = 1f / det
        // dP/du and dP/dv of the triangle, weighted by its area through the cross products' lengths kept as they are.
        val tx = (e1x * dv2 - e2x * dv1) * k; val ty = (e1y * dv2 - e2y * dv1) * k; val tz = (e1z * dv2 - e2z * dv1) * k
        val bx = (e2x * du1 - e1x * du2) * k; val by = (e2y * du1 - e1y * du2) * k; val bz = (e2z * du1 - e1z * du2) * k
        val area = sqrt(nx * nx + ny * ny + nz * nz)
        val tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-20f); val bl = sqrt(bx * bx + by * by + bz * bz).coerceAtLeast(1e-20f)
        for (v in intArrayOf(a, c1, c2)) {
            t[3 * v] += tx / tl * area; t[3 * v + 1] += ty / tl * area; t[3 * v + 2] += tz / tl * area
            b[3 * v] += bx / bl * area; b[3 * v + 1] += by / bl * area; b[3 * v + 2] += bz / bl * area
        }
    }
    return Array(count) { v ->
        var nx = fn[3 * v]; var ny = fn[3 * v + 1]; var nz = fn[3 * v + 2]
        val given = normals?.getOrNull(v)
        if (given != null && given.size >= 3) { nx = given[0]; ny = given[1]; nz = given[2] }
        val nl = sqrt(nx * nx + ny * ny + nz * nz)
        if (nl < 1e-20f) return@Array floatArrayOf(1f, 0f, 0f, 1f)
        nx /= nl; ny /= nl; nz /= nl
        // Square to the normal (Gram–Schmidt); a tangent lost in it takes any direction across the normal.
        var tx = t[3 * v]; var ty = t[3 * v + 1]; var tz = t[3 * v + 2]
        val d = tx * nx + ty * ny + tz * nz
        tx -= nx * d; ty -= ny * d; tz -= nz * d
        var tl = sqrt(tx * tx + ty * ty + tz * tz)
        if (tl < 1e-12f) {
            if (abs(nx) < .9f) { tx = 0f; ty = -nz; tz = ny } else { tx = nz; ty = 0f; tz = -nx }
            tl = sqrt(tx * tx + ty * ty + tz * tz)
        }
        tx /= tl; ty /= tl; tz /= tl
        // The image's top is towards −v: the handedness is the side of (normal × tangent) where −dP/dv points.
        val cx = ny * tz - nz * ty; val cy = nz * tx - nx * tz; val cz = nx * ty - ny * tx
        val w = if (cx * -b[3 * v] + cy * -b[3 * v + 1] + cz * -b[3 * v + 2] < 0f) -1f else 1f
        floatArrayOf(tx, ty, tz, w)
    }
}
