package com.hisname.productrender.glb

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/** A half-line in world space (scene units), from a camera through a point on screen. */
data class Ray(val origin: List<Float>, val direction: List<Float>)

/**
 * Finds the material under a [Ray] by testing it against the GLB's own triangles, so every
 * renderer picks the same surface. Meshes it can't read (Draco, external buffers) can't be picked.
 */
class MaterialPicker(glb: ByteArray) {
    private val primitives: List<Triangles> by lazy { readTriangles(Glb.parse(glb)) }

    /** The index of the material the ray meets first; null when it meets nothing or a primitive without one. */
    fun materialAt(ray: Ray): Int? {
        val o = DoubleArray(3) { ray.origin[it].toDouble() }
        val d = DoubleArray(3) { ray.direction[it].toDouble() }
        var nearest = Double.MAX_VALUE
        var material: Int? = null
        for (primitive in primitives) {
            val p = primitive.positions
            for (at in p.indices step 9) {
                val t = intersect(o, d, p, at)
                if (t != null && t < nearest) {
                    nearest = t
                    material = primitive.material
                }
            }
        }
        return material
    }

    /** World-space triangles, 9 coordinates each, and the material they are drawn with. */
    private class Triangles(val positions: DoubleArray, val material: Int?)

    private companion object {
        const val TRIANGLES = 4
        const val TRIANGLE_STRIP = 5
        const val TRIANGLE_FAN = 6
        const val EPSILON = 1e-12

        fun readTriangles(glb: Glb): List<Triangles> {
            val json = glb.json
            val bin = glb.bin ?: return emptyList()
            val out = mutableListOf<Triangles>()
            forEachMeshPrimitive(json) { primitive, world ->
                val mode = primitive["mode"]?.jsonPrimitive?.int ?: TRIANGLES
                if (mode !in TRIANGLES..TRIANGLE_FAN) return@forEachMeshPrimitive
                val positionAccessor = primitive["attributes"]?.jsonObject?.get("POSITION")?.jsonPrimitive?.int
                    ?: return@forEachMeshPrimitive
                val positions = readAccessor(json, bin, positionAccessor) ?: return@forEachMeshPrimitive
                val vertexCount = positions.size / 3
                val indices = primitive["indices"]?.jsonPrimitive?.int
                    ?.let { readAccessor(json, bin, it) ?: return@forEachMeshPrimitive }
                    ?.map { it.toInt() }
                    ?: List(vertexCount) { it }
                val corners = when (mode) {
                    TRIANGLE_STRIP -> (0 until indices.size - 2).flatMap { i ->
                        // every other triangle of a strip is wound the other way; picking ignores winding
                        listOf(indices[i], indices[i + 1], indices[i + 2])
                    }
                    TRIANGLE_FAN -> (1 until indices.size - 1).flatMap { i -> listOf(indices[0], indices[i], indices[i + 1]) }
                    else -> indices.take(indices.size / 3 * 3)
                }
                val world9 = DoubleArray(corners.size * 3)
                corners.forEachIndexed { n, vertex ->
                    if (vertex !in 0 until vertexCount) return@forEachMeshPrimitive
                    val p = world.transform(List(3) { positions[vertex * 3 + it].toDouble() })
                    for (i in 0..2) world9[n * 3 + i] = p[i]
                }
                out += Triangles(world9, primitive["material"]?.jsonPrimitive?.int)
            }
            return out
        }

        /** An accessor's components as floats (normalized integers scaled to 0..1 or -1..1); null if unreadable. */
        fun readAccessor(json: JsonObject, bin: ByteArray, index: Int): FloatArray? {
            val accessor = json["accessors"]?.jsonArray?.getOrNull(index)?.jsonObject ?: return null
            val viewIndex = accessor["bufferView"]?.jsonPrimitive?.int ?: return null
            val view = json["bufferViews"]?.jsonArray?.getOrNull(viewIndex)?.jsonObject ?: return null
            if ((view["buffer"]?.jsonPrimitive?.int ?: 0) != 0) return null
            val count = accessor["count"]?.jsonPrimitive?.int ?: return null
            val components = when (accessor["type"]?.jsonPrimitive?.content) {
                "SCALAR" -> 1
                "VEC3" -> 3
                else -> return null
            }
            val componentType = accessor["componentType"]?.jsonPrimitive?.int ?: return null
            val size = componentSize(componentType) ?: return null
            val normalized = accessor["normalized"]?.jsonPrimitive?.boolean ?: false
            val stride = view["byteStride"]?.jsonPrimitive?.int ?: (size * components)
            val start = (view["byteOffset"]?.jsonPrimitive?.int ?: 0) + (accessor["byteOffset"]?.jsonPrimitive?.int ?: 0)
            if (count > 0 && start + (count - 1) * stride + size * components > bin.size) return null
            return FloatArray(count * components) { i ->
                readComponent(bin, start + (i / components) * stride + (i % components) * size, componentType, normalized)
            }
        }

        fun componentSize(componentType: Int): Int? = when (componentType) {
            5120, 5121 -> 1
            5122, 5123 -> 2
            5125, 5126 -> 4
            else -> null
        }

        fun readComponent(bin: ByteArray, at: Int, componentType: Int, normalized: Boolean): Float {
            val raw = when (componentType) {
                5120 -> bin[at].toInt()
                5121 -> bin[at].toInt() and 0xFF
                5122 -> ((bin[at].toInt() and 0xFF) or (bin[at + 1].toInt() shl 8)).toShort().toInt()
                5123 -> (bin[at].toInt() and 0xFF) or ((bin[at + 1].toInt() and 0xFF) shl 8)
                5126 -> return Float.fromBits(bin.getInt(at))
                else -> return bin.getInt(at).toUInt().toFloat() // 5125: unsigned int, only used for indices
            }
            if (!normalized) return raw.toFloat()
            return when (componentType) {
                5120 -> maxOf(raw / 127f, -1f)
                5121 -> raw / 255f
                5122 -> maxOf(raw / 32767f, -1f)
                else -> raw / 65535f
            }
        }

        /** Möller–Trumbore: the distance along [d] (in units of its length) to the triangle at [at], or null. */
        fun intersect(o: DoubleArray, d: DoubleArray, p: DoubleArray, at: Int): Double? {
            val e1x = p[at + 3] - p[at]; val e1y = p[at + 4] - p[at + 1]; val e1z = p[at + 5] - p[at + 2]
            val e2x = p[at + 6] - p[at]; val e2y = p[at + 7] - p[at + 1]; val e2z = p[at + 8] - p[at + 2]
            val hx = d[1] * e2z - d[2] * e2y; val hy = d[2] * e2x - d[0] * e2z; val hz = d[0] * e2y - d[1] * e2x
            val det = e1x * hx + e1y * hy + e1z * hz
            if (abs(det) < EPSILON) return null // parallel to the triangle, or a degenerate one
            val f = 1 / det
            val sx = o[0] - p[at]; val sy = o[1] - p[at + 1]; val sz = o[2] - p[at + 2]
            val u = f * (sx * hx + sy * hy + sz * hz)
            if (u < 0 || u > 1) return null
            val qx = sy * e1z - sz * e1y; val qy = sz * e1x - sx * e1z; val qz = sx * e1y - sy * e1x
            val v = f * (d[0] * qx + d[1] * qy + d[2] * qz)
            if (v < 0 || u + v > 1) return null
            val t = f * (e2x * qx + e2y * qy + e2z * qz)
            return t.takeIf { it > 0 }
        }
    }
}
