package com.hisname.productrender.glb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BottlePrintTest {
    private fun glb(materialName: String = "hero_print", bin: ByteArray = byteArrayOf(1, 2, 3)): ByteArray {
        val json = Json.parseToJsonElement(
            """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
               "nodes":[{"name":"shell","mesh":0,"translation":[0,1,0]}],
               "meshes":[{"primitives":[{"attributes":{"POSITION":0},"material":0}]}],
               "accessors":[{"count":2,"type":"VEC3","componentType":5126,"min":[-1,0,-1],"max":[1,2,1]}],
               "materials":[{"name":"$materialName","pbrMetallicRoughness":{"baseColorFactor":[0,0,1,1]}}],
               "buffers":[{"byteLength":${bin.size}}]}""",
        ).jsonObject
        return Glb(json, bin).toBytes()
    }

    /** Header-only images: enough for [PrintImage.read], which never decodes pixels. */
    private fun png(width: Int, height: Int, colorType: Int, chunks: List<String> = emptyList()): ByteArray {
        val out = mutableListOf<Byte>()
        fun int(v: Int) = (3 downTo 0).forEach { out += (v ushr (8 * it)).toByte() }
        fun chunk(type: String, data: List<Byte>) {
            int(data.size); out += type.encodeToByteArray().toList(); out += data; int(0)
        }
        out += byteArrayOf(-119, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).toList()
        val ihdr = mutableListOf<Byte>().also { d ->
            listOf(width, height).forEach { v -> (3 downTo 0).forEach { d += (v ushr (8 * it)).toByte() } }
            d += listOf(8, colorType, 0, 0, 0).map { it.toByte() }
        }
        chunk("IHDR", ihdr)
        chunks.forEach { chunk(it, listOf(0)) }
        chunk("IDAT", listOf(0))
        return out.toByteArray()
    }

    private fun jpeg(width: Int, height: Int) = listOf(
        0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x04, 0x4A, 0x46, // SOI, APP0 with 2 bytes of data
        0xFF, 0xC2, 0x00, 0x0B, 0x08, height shr 8, height and 0xFF, width shr 8, width and 0xFF, 0x01, 0, 0, 0,
        0xFF, 0xD9,
    ).map { it.toByte() }.toByteArray()

    @Test
    fun `reads PNG size and transparency from the header`() {
        val opaque = PrintImage.read(png(2360, 1110, colorType = 2))
        assertEquals(ImageFormat.Png, opaque.format)
        assertEquals(2360 to 1110, opaque.width to opaque.height)
        assertFalse(opaque.mayHaveAlpha)
        assertTrue(PrintImage.read(png(4, 4, colorType = 6)).mayHaveAlpha)
        assertTrue(PrintImage.read(png(4, 4, colorType = 3, chunks = listOf("PLTE", "tRNS"))).mayHaveAlpha)
    }

    @Test
    fun `reads JPEG size from its frame header`() {
        val image = PrintImage.read(jpeg(width = 640, height = 480))
        assertEquals(ImageFormat.Jpeg, image.format)
        assertEquals(640 to 480, image.width to image.height)
    }

    @Test
    fun `rejects other image formats`() {
        assertFailsWith<UnsupportedImageException> { PrintImage.read("GIF89a-----------".encodeToByteArray()) }
    }

    @Test
    fun `puts the image on the print material, embedded in the BIN chunk`() {
        val image = PrintImage.read(jpeg(width = 2000, height = 1000))
        val patched = Glb.parse(BottlePrint.apply(glb(), image))
        val json = patched.json

        val material = json.at("materials", 0)
        val textureInfo = material.at("pbrMetallicRoughness", "baseColorTexture")
        val texture = json.at("textures", textureInfo.int("index"))
        val imageJson = json.at("images", texture.int("source"))
        val view = json.at("bufferViews", imageJson.int("bufferView"))
        val offset = view.int("byteOffset")

        assertEquals("image/jpeg", imageJson["mimeType"]!!.jsonPrimitive.content)
        assertEquals(0, offset % 4)
        assertContentEquals(image.bytes, patched.bin!!.copyOfRange(offset, offset + view.int("byteLength")))
        // the BIN chunk is padded to 4 bytes; the buffer keeps its exact length
        assertEquals(offset + image.bytes.size, json.at("buffers", 0).int("byteLength"))
        assertEquals(0, patched.bin.size % 4)
        assertEquals(33071, json.at("samplers", texture.int("sampler")).int("wrapS"))
        assertEquals(List(4) { 1.0 }, material.at("pbrMetallicRoughness")["baseColorFactor"]!!.jsonArray.map { it.jsonPrimitive.double })

        // stretched over the print area's own UVs, whatever the image's aspect ratio
        assertFalse(textureInfo.containsKey("extensions"))
    }

    @Test
    fun `models without the print material are refused`() {
        assertFalse(BottlePrint.hasPrintArea(glb(materialName = "steel")))
        assertFailsWith<NoPrintAreaException> { BottlePrint.apply(glb(materialName = "steel"), PrintImage.read(jpeg(2, 1))) }
    }

    @Test
    fun `bounds include node transforms`() {
        val bounds = glbBounds(glb())!!
        assertEquals(listOf(-1f, 1f, -1f), bounds.min)
        assertEquals(listOf(1f, 3f, 1f), bounds.max)
    }

    private fun JsonObject.at(vararg path: Any): JsonObject {
        var e: kotlinx.serialization.json.JsonElement = this
        for (p in path) e = if (p is Int) e.jsonArray[p] else e.jsonObject[p as String]!!
        return e.jsonObject
    }

    private fun JsonObject.int(key: String) = this[key]!!.jsonPrimitive.int
}
