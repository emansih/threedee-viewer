package com.hisname.productrender.glb

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Puts images on a model's materials, each stretched over its material's own UVs whatever
 * the image's aspect ratio. On the bottle's `hero_print` that is once around, centred on the
 * front, and from the base edge to the cap joint.
 */
object MaterialPrint {
    private const val LINEAR = 9729
    private const val LINEAR_MIPMAP_LINEAR = 9987
    private const val CLAMP_TO_EDGE = 33071

    /** The material's name, or its number when it has none. */
    fun materialName(glb: ByteArray, materialIndex: Int): String =
        Glb.parse(glb).json.array("materials").getOrNull(materialIndex)?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            ?: "Material ${materialIndex + 1}"

    /** The index of the first material called [name], or null. */
    fun materialIndex(glb: ByteArray, name: String): Int? =
        Glb.parse(glb).json.array("materials").indexOfFirst { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == name }
            .takeIf { it >= 0 }

    /** True when some primitive drawn with the material has the UVs an image is mapped onto. */
    fun canPrint(glb: ByteArray, materialIndex: Int): Boolean {
        var found = false
        forEachMeshPrimitive(Glb.parse(glb).json) { primitive, _ ->
            if (primitive["material"]?.jsonPrimitive?.intOrNull == materialIndex &&
                primitive["attributes"]?.jsonObject?.containsKey("TEXCOORD_0") == true
            ) {
                found = true
            }
        }
        return found
    }

    /** Returns [glb] with each image of [prints] on the material it is keyed by. */
    fun apply(glb: ByteArray, prints: Map<Int, PrintImage>): ByteArray =
        prints.entries.fold(glb) { model, (materialIndex, print) -> apply(model, materialIndex, print) }

    /** Returns [glb] with [print] on material [materialIndex]; the material colour shows through transparent pixels. */
    fun apply(glb: ByteArray, materialIndex: Int, print: PrintImage): ByteArray {
        val parsed = Glb.parse(glb)
        val json = parsed.json
        val material = json.array("materials").getOrNull(materialIndex)?.jsonObject
            ?: throw GlbFormatException("The model has no material ${materialIndex + 1}")
        val pbr = material["pbrMetallicRoughness"]?.jsonObject ?: JsonObject(emptyMap())

        // glTF multiplies the texture by the material colour rather than layering it, so the
        // colour goes under the image here and the material colour becomes white
        val image = if (print.mayHaveAlpha) {
            PrintImage(flattenPng(print.bytes, baseColorArgb(pbr)), ImageFormat.Png, print.width, print.height)
        } else {
            print
        }

        val bin = parsed.bin ?: ByteArray(0)
        val imageOffset = (bin.size + 3) / 4 * 4
        val newBin = bin.copyOf(imageOffset + image.bytes.size)
        image.bytes.copyInto(newBin, imageOffset)

        val buffers = json.array("buffers")
        if (buffers.firstOrNull()?.jsonObject?.containsKey("uri") == true) {
            throw GlbFormatException("The GLB's first buffer is external")
        }
        val bufferView = buildJsonObject {
            put("buffer", 0)
            put("byteOffset", imageOffset)
            put("byteLength", image.bytes.size)
        }
        val bufferViewIndex = json.array("bufferViews").size
        val imageIndex = json.array("images").size
        val samplerIndex = json.array("samplers").size
        val textureIndex = json.array("textures").size

        val textureInfo = buildJsonObject { put("index", textureIndex) }
        val materials = json.array("materials").toMutableList()
        materials[materialIndex] = material.with(
            "pbrMetallicRoughness",
            pbr.with("baseColorTexture", textureInfo)
                .with("baseColorFactor", JsonArray(List(4) { JsonPrimitive(1.0) })),
        )
        val patched = json
            .with("buffers", JsonArray(listOf(
                (buffers.firstOrNull()?.jsonObject ?: JsonObject(emptyMap())).with("byteLength", JsonPrimitive(newBin.size)),
            ) + buffers.drop(1)))
            .with("bufferViews", JsonArray(json.array("bufferViews") + bufferView))
            .with("images", JsonArray(json.array("images") + buildJsonObject {
                put("bufferView", bufferViewIndex)
                put("mimeType", image.format.mimeType)
            }))
            .with("samplers", JsonArray(json.array("samplers") + buildJsonObject {
                put("magFilter", LINEAR)
                put("minFilter", LINEAR_MIPMAP_LINEAR)
                // the bottle's UVs reach just past 1 at the back seam: clamp rather than repeat a sliver
                put("wrapS", CLAMP_TO_EDGE)
                put("wrapT", CLAMP_TO_EDGE)
            }))
            .with("textures", JsonArray(json.array("textures") + buildJsonObject {
                put("sampler", samplerIndex)
                put("source", imageIndex)
            }))
            .with("materials", JsonArray(materials))
        return Glb(patched, newBin).toBytes()
    }

    /** The material's linear base colour as an opaque sRGB ARGB int (glTF's default is white). */
    private fun baseColorArgb(pbr: JsonObject): Int {
        val factor = pbr["baseColorFactor"]?.jsonArray?.map { it.jsonPrimitive.double } ?: listOf(1.0, 1.0, 1.0)
        return (0..2).fold(0xFF) { argb, i -> (argb shl 8) or (linearToSrgb(factor[i]) * 255).roundToInt() }
    }

    private fun linearToSrgb(c: Double): Double = when {
        c <= 0.0031308 -> 12.92 * c
        else -> 1.055 * c.pow(1 / 2.4) - 0.055
    }.coerceIn(0.0, 1.0)
}

private fun JsonObject.array(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())

private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
