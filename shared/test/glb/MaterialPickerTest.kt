package com.hisname.productrender.glb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MaterialPickerTest {
    /**
     * Two triangles facing +Z, each its own primitive and material: "front" at z = 0 covering
     * x, y in 0..1, and "back" at z = -1 covering x, y in 0..2. The node moves both 10 along x.
     */
    private fun glb(): ByteArray {
        val front = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val back = floatArrayOf(0f, 0f, -1f, 2f, 0f, -1f, 0f, 2f, -1f)
        val bin = ByteArray(72)
        (front + back).forEachIndexed { i, f ->
            val bits = f.toRawBits()
            for (b in 0 until 4) bin[i * 4 + b] = (bits ushr (8 * b)).toByte()
        }
        val json = Json.parseToJsonElement(
            """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
               "nodes":[{"mesh":0,"translation":[10,0,0]}],
               "meshes":[{"primitives":[
                 {"attributes":{"POSITION":0},"material":0},
                 {"attributes":{"POSITION":1},"material":1}]}],
               "accessors":[
                 {"bufferView":0,"count":3,"type":"VEC3","componentType":5126},
                 {"bufferView":0,"byteOffset":36,"count":3,"type":"VEC3","componentType":5126}],
               "bufferViews":[{"buffer":0,"byteLength":72}],
               "materials":[{"name":"front"},{"name":"back"}],
               "buffers":[{"byteLength":72}]}""",
        ).jsonObject
        return Glb(json, bin).toBytes()
    }

    private fun towardsMinusZ(x: Float, y: Float) = Ray(listOf(x, y, 5f), listOf(0f, 0f, -1f))

    @Test
    fun `picks the nearest surface's material`() {
        assertEquals(0, MaterialPicker(glb()).materialAt(towardsMinusZ(10.2f, 0.2f)))
    }

    @Test
    fun `picks what is behind when the nearer surface is missed`() {
        assertEquals(1, MaterialPicker(glb()).materialAt(towardsMinusZ(11.2f, 0.5f)))
    }

    @Test
    fun `nothing is picked off the model or behind the ray`() {
        val picker = MaterialPicker(glb())
        assertNull(picker.materialAt(towardsMinusZ(0.2f, 0.2f)))
        assertNull(picker.materialAt(Ray(listOf(10.2f, 0.2f, 5f), listOf(0f, 0f, 1f))))
    }
}
