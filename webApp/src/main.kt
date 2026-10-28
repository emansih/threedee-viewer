package com.hisname.productrender

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.hisname.productrender.glb.MaterialPrint
import com.hisname.productrender.glb.PrintImage
import kotlinx.coroutines.await
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        App(shared = ::loadSharedLink, shareLink = ::copyShareLink)
    }
}

/** Largest link the app makes; Chrome refuses URLs over 2 MB. */
private const val MAX_LINK_CHARS = 1_900_000

/** Links made before images could go on any material held one image, for this one. */
private const val LEGACY_PRINT_MATERIAL = "hero_print"

/** Puts a link holding [glb] and [prints] on the clipboard, shrinking the images to fit; returns its size in KB. */
@OptIn(ExperimentalEncodingApi::class)
private suspend fun copyShareLink(glb: ByteArray, prints: Map<Int, PrintImage>): Int {
    val url = makeLink(
        Base64.encode(glb),
        prints.keys.joinToString(","),
        prints.values.joinToString(",") { Base64.encode(it.bytes) },
        MAX_LINK_CHARS,
    ).await<JsString>().toString()
    if (url.isEmpty()) error("It is too big for a link; use a smaller model")
    copyText(url).await<JsAny?>()
    return url.length / 1024
}

/**
 * The link's fragment (`#=<base64url>`) holds everything: gzipped, the GLB's length (4 bytes, big endian),
 * the GLB, then for each image its material index and its length (4 bytes each, big endian) and the image.
 * A browser never sends the fragment, so no server or host (Vercel's CDN stops at 14 KB) limits the link's size.
 * Older links put it in the query (`?=`), and the oldest have one bare PNG or JPEG after the GLB, for
 * [LEGACY_PRINT_MATERIAL]. The path is free, so `/v#=...` works.
 */
@OptIn(ExperimentalEncodingApi::class)
private suspend fun loadSharedLink(): SharedView? {
    // A URL-safe alphabet; '+' arrives as a space, which a query string can't hold
    val encoded = locationHash().removePrefix("#").ifEmpty { locationQuery().removePrefix("?") }.removePrefix("=")
        .replace('+', '-').replace(' ', '-').replace('/', '_').trimEnd('=')
    if (encoded.isEmpty()) return null
    val standard = encoded.replace('-', '+').replace('_', '/')
    val payload = Base64.decode(gunzipToBase64(standard + "=".repeat((4 - standard.length % 4) % 4)).await<JsString>().toString())
    val glbSize = payload.intAt(0)
    if (glbSize < 0 || 4 + glbSize > payload.size) error("The link is damaged")
    val glb = payload.copyOfRange(4, 4 + glbSize)
    val rest = payload.copyOfRange(4 + glbSize, payload.size)
    val legacy = runCatching { PrintImage.read(rest) }.getOrNull()
    if (legacy != null) {
        val material = MaterialPrint.materialIndex(glb, LEGACY_PRINT_MATERIAL)
        return SharedView(glb, if (material == null) emptyMap() else mapOf(material to legacy))
    }
    val prints = mutableMapOf<Int, PrintImage>()
    var at = 0
    while (at < rest.size) {
        val material = rest.intAt(at)
        val size = rest.intAt(at + 4)
        if (size < 0 || at + 8 + size > rest.size) error("The link is damaged")
        prints[material] = PrintImage.read(rest.copyOfRange(at + 8, at + 8 + size))
        at += 8 + size
    }
    return SharedView(glb, prints)
}

/** The big-endian int at [at]; a link too short to hold it is damaged. */
private fun ByteArray.intAt(at: Int): Int {
    if (at < 0 || at + 4 > size) error("The link is damaged")
    return ((this[at].toInt() and 0xFF) shl 24) or ((this[at + 1].toInt() and 0xFF) shl 16) or
        ((this[at + 2].toInt() and 0xFF) shl 8) or (this[at + 3].toInt() and 0xFF)
}

@JsFun("() => location.search")
private external fun locationQuery(): String

@JsFun("() => location.hash")
private external fun locationHash(): String

// Gunzips base64 into base64, with the browser's own DecompressionStream
@JsFun("""(b64) => {
    const bytes = Uint8Array.from(atob(b64), c => c.charCodeAt(0));
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
    return new Response(stream).blob().then(b => new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result.substring(reader.result.indexOf(',') + 1));
        reader.onerror = () => reject(reader.error);
        reader.readAsDataURL(b);
    }));
}""")
private external fun gunzipToBase64(base64: String): Promise<JsString>

@JsFun("(text) => navigator.clipboard.writeText(text)")
private external fun copyText(text: String): Promise<JsAny?>

// Builds <origin>/v#=<base64url of gzip(GLB length + GLB + per image: material index, length, image)>, ints being
// 4 bytes big endian. [materials] and [imagesB64] are comma-separated, in the same order. The images are shrunk
// together until the link fits (JPEG, or PNG when one has transparency); "" when even without them it is too long.
@JsFun("""async (glbB64, materials, imagesB64, max) => {
    const bytesOf = b64 => Uint8Array.from(atob(b64), c => c.charCodeAt(0));
    const gzip = async bytes => new Uint8Array(await new Response(new Blob([bytes]).stream().pipeThrough(new CompressionStream('gzip'))).arrayBuffer());
    const b64url = bytes => {
        let s = '';
        for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
        return btoa(s).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
    };
    const glb = bytesOf(glbB64);
    const indices = materials ? materials.split(',').map(Number) : [];
    const linkOf = async images => {
        const size = images.reduce((n, image) => n + 8 + image.length, 4 + glb.length);
        const out = new Uint8Array(size), data = new DataView(out.buffer);
        data.setUint32(0, glb.length);
        out.set(glb, 4);
        let at = 4 + glb.length;
        images.forEach((image, i) => {
            data.setUint32(at, indices[i]);
            data.setUint32(at + 4, image.length);
            out.set(image, at + 8);
            at += 8 + image.length;
        });
        return location.origin + '/v#=' + b64url(await gzip(out));
    };
    let url = await linkOf([]);
    if (url.length > max) return '';
    if (!imagesB64) return url;
    const bitmaps = await Promise.all(imagesB64.split(',').map(b64 => createImageBitmap(new Blob([bytesOf(b64)]))));
    const alphas = bitmaps.map(() => null);
    let scale = 1;
    for (let step = 0; step < 16; step++) {
        const images = [];
        for (let i = 0; i < bitmaps.length; i++) {
            const bitmap = bitmaps[i], s = scale * Math.min(1, 2048 / bitmap.width);
            const w = Math.max(1, Math.round(bitmap.width * s)), h = Math.max(1, Math.round(bitmap.height * s));
            const canvas = new OffscreenCanvas(w, h), g = canvas.getContext('2d');
            g.drawImage(bitmap, 0, 0, w, h);
            if (alphas[i] === null) {
                const d = g.getImageData(0, 0, w, h).data;
                alphas[i] = false;
                for (let j = 3; j < d.length; j += 4) if (d[j] < 255) { alphas[i] = true; break; }
            }
            const blob = await canvas.convertToBlob(alphas[i] ? { type: 'image/png' } : { type: 'image/jpeg', quality: 0.85 });
            images.push(new Uint8Array(await blob.arrayBuffer()));
        }
        const candidate = await linkOf(images);
        if (candidate.length <= max) return candidate;
        scale *= 0.8;
    }
    return url;
}""")
private external fun makeLink(glbBase64: String, materials: String, imagesBase64: String, maxChars: Int): Promise<JsString>
