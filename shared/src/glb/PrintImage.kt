package com.hisname.productrender.glb

class UnsupportedImageException(message: String) : Exception(message)

enum class ImageFormat(val mimeType: String) { Png("image/png"), Jpeg("image/jpeg") }

/** An encoded PNG or JPEG and its pixel size, read from its header without decoding it. */
class PrintImage(val bytes: ByteArray, val format: ImageFormat, val width: Int, val height: Int) {

    /** True when some pixels may be transparent: an alpha channel or a tRNS chunk. */
    val mayHaveAlpha: Boolean
        get() = format == ImageFormat.Png && (bytes[25].toInt() in PNG_ALPHA_COLOR_TYPES || hasPngChunk("tRNS"))

    private fun hasPngChunk(type: String): Boolean {
        var at = PNG_SIGNATURE.size
        while (at + 8 <= bytes.size) {
            val name = bytes.decodeToString(at + 4, at + 8)
            if (name == type) return true
            if (name == "IDAT") return false // tRNS must come before the image data
            at += 12 + bytes.getIntBigEndian(at)
        }
        return false
    }

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(-119, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val PNG_ALPHA_COLOR_TYPES = setOf(4, 6) // grey + alpha, RGBA

        fun read(bytes: ByteArray): PrintImage = when {
            bytes.size > 33 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) ->
                // IHDR is always the first chunk: width and height follow its type
                PrintImage(bytes, ImageFormat.Png, bytes.getIntBigEndian(16), bytes.getIntBigEndian(20))
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> readJpeg(bytes)
            else -> throw UnsupportedImageException("Only PNG and JPEG images are supported")
        }.also {
            if (it.width <= 0 || it.height <= 0) throw UnsupportedImageException("The image has no pixels")
        }

        private fun readJpeg(bytes: ByteArray): PrintImage {
            var at = 2
            while (at + 9 < bytes.size) {
                if (bytes[at] != 0xFF.toByte()) throw UnsupportedImageException("The JPEG file is damaged")
                val marker = bytes[at + 1].toInt() and 0xFF
                when {
                    marker == 0xFF -> at++ // fill byte
                    marker == 0x01 || marker in 0xD0..0xD7 -> at += 2 // markers without a length
                    marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC ->
                        // start of frame: length (2), precision (1), height (2), width (2)
                        return PrintImage(bytes, ImageFormat.Jpeg, bytes.getShortBigEndian(at + 7), bytes.getShortBigEndian(at + 5))
                    else -> at += 2 + bytes.getShortBigEndian(at + 2)
                }
            }
            throw UnsupportedImageException("The JPEG file has no image size")
        }
    }
}

private fun ByteArray.getIntBigEndian(at: Int): Int =
    ((this[at].toInt() and 0xFF) shl 24) or
        ((this[at + 1].toInt() and 0xFF) shl 16) or
        ((this[at + 2].toInt() and 0xFF) shl 8) or
        (this[at + 3].toInt() and 0xFF)

private fun ByteArray.getShortBigEndian(at: Int): Int =
    ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)
