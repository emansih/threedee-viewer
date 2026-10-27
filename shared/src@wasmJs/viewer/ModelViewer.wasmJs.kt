package com.hisname.productrender.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * SceneView's Compose viewer has no web variant, so the web draws with SceneView's
 * sceneview.js (Filament.js), loaded by index.html, on a canvas laid over this composable.
 */
@Composable
actual fun ModelViewer(glb: ByteArray, capture: ViewerCapture, modifier: Modifier, onError: (String) -> Unit) {
    val currentOnError by rememberUpdatedState(onError)
    val canvas = remember { createOverlayCanvas() }
    DisposableEffect(canvas, capture) {
        capture.source = { canvasToPng(canvas).await<Uint8Array>().toByteArray() }
        onDispose {
            capture.source = null
            removeOverlayCanvas(canvas)
        }
    }
    val viewer by produceState<SceneViewJs?>(null, canvas) {
        value = runCatching { createSceneView(canvas).await<SceneViewJs>() }
            .onFailure { currentOnError("Could not start the 3D viewer: ${it.message}") }
            .getOrNull()
    }
    val created = viewer
    DisposableEffect(created) {
        onDispose { created?.dispose() }
    }
    LaunchedEffect(viewer, glb) {
        val sceneView = viewer ?: return@LaunchedEffect
        val url = createBlobUrl(glb.toUint8Array())
        try {
            sceneView.loadModel(url).await<JsAny?>()
        } catch (e: Throwable) {
            // A newer loadModel(), or disposing the viewer, rejects this one with an AbortError: not a failure
            if (e.message?.contains("AbortError") != true) currentOnError("Could not show the model: ${e.message}")
        } finally {
            revokeBlobUrl(url)
        }
    }
    val density = LocalDensity.current.density
    Box(modifier.onGloballyPositioned {
        val position = it.positionInWindow()
        placeOverlayCanvas(
            canvas,
            left = position.x / density,
            top = position.y / density,
            width = it.size.width / density,
            height = it.size.height / density,
        )
    })
}

private external interface SceneViewJs : JsAny {
    fun loadModel(url: String): Promise<JsAny?>
    fun dispose()
}

private external interface Canvas : JsAny

private external interface Uint8Array : JsAny

// The drawing buffer is kept after each frame (by adding the flag to the context Filament asks for), so the canvas can be read back later
@JsFun("""() => {
    const c = document.createElement('canvas');
    c.style.position = 'fixed';
    c.style.display = 'block';
    const getContext = c.getContext.bind(c);
    c.getContext = (type, attributes) => getContext(type, { ...attributes, preserveDrawingBuffer: true });
    document.body.appendChild(c);
    return c;
}""")
private external fun createOverlayCanvas(): Canvas

@JsFun("(c) => c.remove()")
private external fun removeOverlayCanvas(canvas: Canvas)

@JsFun("(c, left, top, width, height) => { c.style.left = left + 'px'; c.style.top = top + 'px'; c.style.width = width + 'px'; c.style.height = height + 'px'; }")
private external fun placeOverlayCanvas(canvas: Canvas, left: Float, top: Float, width: Float, height: Float)

// The default lighting comes from SceneView's CDN, like sceneview.js itself; its default path is relative to this site
@JsFun("(c) => SceneView.create(c, { iblUrl: 'https://sceneview.github.io/environments/neutral_ibl.ktx' })")
private external fun createSceneView(canvas: Canvas): Promise<SceneViewJs>

@JsFun("(bytes) => URL.createObjectURL(new Blob([bytes], { type: 'model/gltf-binary' }))")
private external fun createBlobUrl(bytes: Uint8Array): String

@JsFun("(url) => URL.revokeObjectURL(url)")
private external fun revokeBlobUrl(url: String)

@JsFun("(c) => new Promise((resolve, reject) => requestAnimationFrame(() => c.toBlob(b => b ? b.arrayBuffer().then(a => resolve(new Uint8Array(a))) : reject(new Error('The canvas is empty')), 'image/png')))")
private external fun canvasToPng(canvas: Canvas): Promise<Uint8Array>

@JsFun("(bytes, name) => { const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([bytes], { type: 'image/png' })); a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 1000); }")
private external fun download(bytes: Uint8Array, name: String)

actual suspend fun savePng(png: ByteArray, baseName: String) = download(png.toUint8Array(), "$baseName.png")

@JsFun("(size) => new Uint8Array(size)")
private external fun newUint8Array(size: Int): Uint8Array

@JsFun("(array, index, value) => { array[index] = value; }")
private external fun setByte(array: Uint8Array, index: Int, value: Byte)

private fun ByteArray.toUint8Array(): Uint8Array {
    val array = newUint8Array(size)
    for (i in indices) setByte(array, i, this[i])
    return array
}

@JsFun("(array) => array.length")
private external fun length(array: Uint8Array): Int

@JsFun("(array, index) => array[index]")
private external fun getByte(array: Uint8Array, index: Int): Byte

private fun Uint8Array.toByteArray(): ByteArray = ByteArray(length(this)) { getByte(this, it) }
