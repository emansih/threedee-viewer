package com.hisname.productrender.glb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class GlbFormatException(message: String) : Exception(message)

/** A binary glTF split into its JSON document and its optional BIN chunk. */
class Glb(val json: JsonObject, val bin: ByteArray?) {

    fun toBytes(): ByteArray {
        val jsonBytes = pad(Json.encodeToString(JsonObject.serializer(), json).encodeToByteArray(), SPACE)
        val binBytes = bin?.let { pad(it, 0) }
        val length = HEADER_SIZE + CHUNK_HEADER_SIZE + jsonBytes.size +
            (binBytes?.let { CHUNK_HEADER_SIZE + it.size } ?: 0)
        val out = ByteArray(length)
        var at = 0
        at = out.putInt(at, MAGIC)
        at = out.putInt(at, 2)
        at = out.putInt(at, length)
        at = out.putInt(at, jsonBytes.size)
        at = out.putInt(at, CHUNK_JSON)
        jsonBytes.copyInto(out, at)
        at += jsonBytes.size
        if (binBytes != null) {
            at = out.putInt(at, binBytes.size)
            at = out.putInt(at, CHUNK_BIN)
            binBytes.copyInto(out, at)
        }
        return out
    }

    companion object {
        private const val MAGIC = 0x46546C67 // "glTF"
        private const val CHUNK_JSON = 0x4E4F534A
        private const val CHUNK_BIN = 0x004E4942
        private const val HEADER_SIZE = 12
        private const val CHUNK_HEADER_SIZE = 8
        private const val SPACE = 0x20.toByte()

        fun isGlb(bytes: ByteArray) = bytes.size >= HEADER_SIZE && bytes.getInt(0) == MAGIC

        fun parse(bytes: ByteArray): Glb {
            if (!isGlb(bytes)) throw GlbFormatException("Not a GLB file")
            if (bytes.getInt(4) != 2) throw GlbFormatException("Only glTF 2.0 GLB files are supported")
            val length = bytes.getInt(8)
            if (length > bytes.size) throw GlbFormatException("GLB file is truncated")
            var json: JsonObject? = null
            var bin: ByteArray? = null
            var at = HEADER_SIZE
            while (at + CHUNK_HEADER_SIZE <= length) {
                val size = bytes.getInt(at)
                val type = bytes.getInt(at + 4)
                val start = at + CHUNK_HEADER_SIZE
                if (size < 0 || start + size > length) throw GlbFormatException("GLB chunk is truncated")
                when (type) {
                    CHUNK_JSON -> json = Json.parseToJsonElement(bytes.decodeToString(start, start + size)).jsonObject
                    CHUNK_BIN -> if (bin == null) bin = bytes.copyOfRange(start, start + size)
                }
                at = start + size
            }
            return Glb(json ?: throw GlbFormatException("GLB has no JSON chunk"), bin)
        }

        private fun pad(bytes: ByteArray, with: Byte): ByteArray {
            val padding = (4 - bytes.size % 4) % 4
            return if (padding == 0) bytes else bytes.copyOf(bytes.size + padding).also { it.fill(with, bytes.size) }
        }
    }
}

internal fun ByteArray.getInt(at: Int): Int =
    (this[at].toInt() and 0xFF) or
        ((this[at + 1].toInt() and 0xFF) shl 8) or
        ((this[at + 2].toInt() and 0xFF) shl 16) or
        ((this[at + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.putInt(at: Int, value: Int): Int {
    for (i in 0 until 4) this[at + i] = (value ushr (8 * i)).toByte()
    return at + 4
}
