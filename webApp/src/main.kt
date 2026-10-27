package com.hisname.productrender

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
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

/** Puts a link holding [glb] and [print] on the clipboard, shrinking the image to fit; returns its size in KB. */
@OptIn(ExperimentalEncodingApi::class)
private suspend fun copyShareLink(glb: ByteArray, print: PrintImage?): Int {
    val url = makeLink(Base64.encode(glb), print?.let { Base64.encode(it.bytes) } ?: "", MAX_LINK_CHARS).await<JsString>().toString()
    if (url.isEmpty()) error("It is too big for a link; use a smaller model")
    copyText(url).await<JsAny?>()
    return url.length / 1024
}

/**
 * The link's query (`?=<base64url>`) holds everything: gzipped, the GLB's length (4 bytes, big endian),
 * the GLB, then the print image if there is one. The path is free, so `/v?=...` works.
 */
@OptIn(ExperimentalEncodingApi::class)
private suspend fun loadSharedLink(): SharedView? {
    // A URL-safe alphabet; '+' arrives as a space, which a query string can't hold
    val encoded = locationQuery().removePrefix("?").removePrefix("=")
        .replace('+', '-').replace(' ', '-').replace('/', '_').trimEnd('=')
    if (encoded.isEmpty()) return null
    val standard = encoded.replace('-', '+').replace('_', '/')
    val payload = Base64.decode(gunzipToBase64(standard + "=".repeat((4 - standard.length % 4) % 4)).await<JsString>().toString())
    val glbSize = payload.size.takeIf { it >= 4 }?.let {
        ((payload[0].toInt() and 0xFF) shl 24) or ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
    } ?: error("The link is damaged")
    if (glbSize < 0 || 4 + glbSize > payload.size) error("The link is damaged")
    val image = payload.copyOfRange(4 + glbSize, payload.size)
    return SharedView(payload.copyOfRange(4, 4 + glbSize), if (image.isEmpty()) null else PrintImage.read(image))
}

@JsFun("() => location.search")
private external fun locationQuery(): String

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

// Builds <origin>/v?=<base64url of gzip(GLB length (4 bytes, big endian) + GLB + image)>. The image is shrunk
// until the link fits (JPEG, or PNG when it has transparency); "" when even without it the link is too long.
@JsFun("""async (glbB64, imageB64, max) => {
    const bytesOf = b64 => Uint8Array.from(atob(b64), c => c.charCodeAt(0));
    const gzip = async bytes => new Uint8Array(await new Response(new Blob([bytes]).stream().pipeThrough(new CompressionStream('gzip'))).arrayBuffer());
    const b64url = bytes => {
        let s = '';
        for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
        return btoa(s).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
    };
    const glb = bytesOf(glbB64);
    const linkOf = async image => {
        const out = new Uint8Array(4 + glb.length + image.length);
        new DataView(out.buffer).setUint32(0, glb.length);
        out.set(glb, 4);
        out.set(image, 4 + glb.length);
        return location.origin + '/v?=' + b64url(await gzip(out));
    };
    let url = await linkOf(new Uint8Array(0));
    if (url.length > max) return '';
    if (!imageB64) return url;
    const original = bytesOf(imageB64);
    const bitmap = await createImageBitmap(new Blob([original]));
    let scale = Math.min(1, 2048 / bitmap.width), alpha = null;
    for (let step = 0; step < 16; step++) {
        const w = Math.max(1, Math.round(bitmap.width * scale)), h = Math.max(1, Math.round(bitmap.height * scale));
        const canvas = new OffscreenCanvas(w, h), g = canvas.getContext('2d');
        g.drawImage(bitmap, 0, 0, w, h);
        if (alpha === null) {
            const d = g.getImageData(0, 0, w, h).data;
            alpha = false;
            for (let i = 3; i < d.length; i += 4) if (d[i] < 255) { alpha = true; break; }
        }
        const blob = await canvas.convertToBlob(alpha ? { type: 'image/png' } : { type: 'image/jpeg', quality: 0.85 });
        const candidate = await linkOf(new Uint8Array(await blob.arrayBuffer()));
        if (candidate.length <= max) return candidate;
        scale *= 0.8;
    }
    return url;
}""")
private external fun makeLink(glbBase64: String, imageBase64: String, maxChars: Int): Promise<JsString>
