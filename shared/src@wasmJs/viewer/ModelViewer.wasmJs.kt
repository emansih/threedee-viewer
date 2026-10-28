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
import com.hisname.productrender.glb.Ray
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * SceneView's Compose viewer has no web variant, so the web draws with SceneView's
 * sceneview.js (Filament.js), loaded by index.html, on a canvas laid over this composable.
 */
@Composable
actual fun ModelViewer(glb: ByteArray, capture: ViewerCapture, modifier: Modifier, onError: (String) -> Unit, onTap: (Ray) -> Unit) {
    val currentOnError by rememberUpdatedState(onError)
    val currentOnTap by rememberUpdatedState(onTap)
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
        created?.let { sceneView ->
            listenForTaps(canvas, sceneView) { ox, oy, oz, dx, dy, dz ->
                currentOnTap(Ray(listOf(ox, oy, oz).map { it.toFloat() }, listOf(dx, dy, dz).map { it.toFloat() }))
            }
        }
        onDispose {
            stopTaps(canvas)
            created?.dispose()
        }
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

// A click that didn't drag (the drag orbits) gives the camera's ray through it, in world space. sceneview.js has
// no picking, so this reads its Filament camera: the projection's scale (and shift) turns the click into a view
// direction, which the camera's own axes take to world space.
@JsFun("""(c, sv, onTap) => {
    let down = null;
    const onDown = e => { down = { x: e.clientX, y: e.clientY }; };
    const onUp = e => {
        const start = down;
        down = null;
        const camera = sv._camera;
        if (!start || !camera || Math.hypot(e.clientX - start.x, e.clientY - start.y) > 5) return;
        const r = c.getBoundingClientRect();
        const x = 2 * (e.clientX - r.left) / r.width - 1, y = 1 - 2 * (e.clientY - r.top) / r.height;
        const p = camera.getProjectionMatrix();
        const vx = (x + p[8]) / p[0], vy = (y + p[9]) / p[5];
        // Despite its name, getLeftVector() is the camera's +X axis: screen right
        const eye = camera.getPosition(), f = camera.getForwardVector(), u = camera.getUpVector(), right = camera.getLeftVector();
        onTap(eye[0], eye[1], eye[2],
            f[0] + u[0] * vy + right[0] * vx, f[1] + u[1] * vy + right[1] * vx, f[2] + u[2] * vy + right[2] * vx);
    };
    c.addEventListener('pointerdown', onDown);
    c.addEventListener('pointerup', onUp);
    c._taps = [onDown, onUp];
}""")
private external fun listenForTaps(canvas: Canvas, sceneView: SceneViewJs, onTap: (Double, Double, Double, Double, Double, Double) -> Unit)

@JsFun("(c) => { if (c._taps) { c.removeEventListener('pointerdown', c._taps[0]); c.removeEventListener('pointerup', c._taps[1]); c._taps = null; } }")
private external fun stopTaps(canvas: Canvas)

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
