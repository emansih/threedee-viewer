package com.hisname.productrender.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import com.hisname.productrender.glb.glbBounds
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.compose.EnvironmentSource
import io.github.sceneview.compose.ModelSource
import io.github.sceneview.compose.SceneViewer
import io.github.sceneview.compose.rememberUnsavedCameraState

/** Camera distance in bounding radii: fits the model in a 45° field of view, with a margin. */
private const val FRAMING = 3f

/** The light from all around the model: a neutral studio, so no side of it is left black. */
internal expect val viewerEnvironment: EnvironmentSource

/** Reads back the pixels the screen shows for a viewer's area, as PNG. */
internal fun interface ViewerGrabber {
    suspend fun grab(area: LayoutCoordinates): ByteArray
}

@Composable
internal expect fun rememberViewerGrabber(): ViewerGrabber

@Composable
actual fun ModelViewer(glb: ByteArray, capture: ViewerCapture, modifier: Modifier, onError: (String) -> Unit) {
    val grabber = rememberViewerGrabber()
    var area by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(capture, grabber) {
        capture.source = { grabber.grab(checkNotNull(area?.takeIf { it.isAttached }) { "The viewer isn't on screen" }) }
        onDispose { capture.source = null }
    }
    val camera = rememberUnsavedCameraState()
    val bounds = remember(glb) { runCatching { glbBounds(glb) }.getOrNull() }
    // A new print leaves the bounds unchanged, so the camera stays where the user left it
    LaunchedEffect(bounds) {
        val (x, y, z) = bounds?.center ?: return@LaunchedEffect
        camera.target = Float3(x, y, z)
        camera.distance = bounds.radius * FRAMING
    }
    SceneViewer(
        model = remember(glb) { ModelSource.Bytes(glb) },
        modifier = modifier.onGloballyPositioned { area = it },
        camera = camera,
        environment = viewerEnvironment,
        onError = { onError(it.message) },
    )
}
