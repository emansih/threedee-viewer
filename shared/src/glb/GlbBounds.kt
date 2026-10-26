package com.hisname.productrender.glb

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** An axis-aligned box in scene units (metres for a Blender export). */
data class Bounds(val min: List<Float>, val max: List<Float>) {
    val center get() = List(3) { (min[it] + max[it]) / 2 }
    val radius get() = sqrt((0..2).sumOf { ((max[it] - min[it]) / 2.0).let { d -> d * d } }).toFloat()
}

/** The world-space bounds of the default scene's meshes, from their POSITION min/max. */
fun glbBounds(glb: ByteArray): Bounds? {
    val json = Glb.parse(glb).json
    val nodes = json["nodes"]?.jsonArray ?: return null
    val sceneIndex = json["scene"]?.jsonPrimitive?.int ?: 0
    val roots = json["scenes"]?.jsonArray?.getOrNull(sceneIndex)?.jsonObject?.get("nodes")?.jsonArray
        ?.map { it.jsonPrimitive.int } ?: nodes.indices.toList()
    val lo = FloatArray(3) { Float.MAX_VALUE }
    val hi = FloatArray(3) { -Float.MAX_VALUE }

    fun visit(index: Int, parent: Matrix) {
        val node = nodes[index].jsonObject
        val world = parent * localMatrix(node)
        node["mesh"]?.jsonPrimitive?.int?.let { mesh ->
            for (primitive in json["meshes"]!!.jsonArray[mesh].jsonObject["primitives"]!!.jsonArray) {
                val accessor = json["accessors"]!!.jsonArray[
                    primitive.jsonObject["attributes"]!!.jsonObject["POSITION"]!!.jsonPrimitive.int,
                ].jsonObject
                val aMin = accessor["min"]?.jsonArray?.map { it.jsonPrimitive.double } ?: continue
                val aMax = accessor["max"]?.jsonArray?.map { it.jsonPrimitive.double } ?: continue
                for (corner in 0 until 8) {
                    val p = world.transform(List(3) { if (corner shr it and 1 == 0) aMin[it] else aMax[it] })
                    for (i in 0..2) {
                        lo[i] = min(lo[i], p[i].toFloat())
                        hi[i] = max(hi[i], p[i].toFloat())
                    }
                }
            }
        }
        node["children"]?.jsonArray?.forEach { visit(it.jsonPrimitive.int, world) }
    }
    roots.forEach { visit(it, Matrix.IDENTITY) }
    return if (lo[0] <= hi[0]) Bounds(lo.toList(), hi.toList()) else null
}

/** Column-major 4×4, as glTF stores it. */
private class Matrix(val m: DoubleArray) {
    operator fun times(o: Matrix) = Matrix(DoubleArray(16) { i ->
        val col = i / 4
        val row = i % 4
        (0..3).sumOf { k -> m[k * 4 + row] * o.m[col * 4 + k] }
    })

    fun transform(p: List<Double>) = List(3) { row -> m[row] * p[0] + m[4 + row] * p[1] + m[8 + row] * p[2] + m[12 + row] }

    companion object {
        val IDENTITY = Matrix(DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 })
    }
}

private fun localMatrix(node: JsonObject): Matrix {
    node["matrix"]?.jsonArray?.let { a -> return Matrix(DoubleArray(16) { a[it].jsonPrimitive.double }) }
    fun vector(key: String, default: List<Double>) = node[key]?.jsonArray?.map { it.jsonPrimitive.double } ?: default
    val (tx, ty, tz) = vector("translation", listOf(0.0, 0.0, 0.0))
    val (x, y, z, w) = vector("rotation", listOf(0.0, 0.0, 0.0, 1.0))
    val (sx, sy, sz) = vector("scale", listOf(1.0, 1.0, 1.0))
    return Matrix(doubleArrayOf(
        (1 - 2 * (y * y + z * z)) * sx, (2 * (x * y + z * w)) * sx, (2 * (x * z - y * w)) * sx, 0.0,
        (2 * (x * y - z * w)) * sy, (1 - 2 * (x * x + z * z)) * sy, (2 * (y * z + x * w)) * sy, 0.0,
        (2 * (x * z + y * w)) * sz, (2 * (y * z - x * w)) * sz, (1 - 2 * (x * x + y * y)) * sz, 0.0,
        tx, ty, tz, 1.0,
    ))
}
