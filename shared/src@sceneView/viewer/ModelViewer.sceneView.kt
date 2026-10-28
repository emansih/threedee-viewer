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
import com.hisname.productrender.glb.Ray
import com.hisname.productrender.glb.glbBounds
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.compose.CameraState
import io.github.sceneview.compose.EnvironmentSource
import io.github.sceneview.compose.ModelSource
import io.github.sceneview.compose.SceneViewer
import io.github.sceneview.compose.rememberUnsavedCameraState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
actual fun ModelViewer(glb: ByteArray, capture: ViewerCapture, modifier: Modifier, onError: (String) -> Unit, onTap: (Ray) -> Unit) {
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
        // Android hits the model's bounding box, not its surface: the ray from the eye through
        // the hit lets the picker find the surface on every platform
        onTap = { hit -> hit?.let { onTap(Ray(camera.eye().toList(), (it.position - camera.eye()).toList())) } },
        onError = { onError(it.message) },
    )
}

/** Where SceneViewer puts the camera for this orbit: yaw about +Y, then pitch, around the target. */
private fun CameraState.eye(): Float3 {
    val azimuthRad = azimuth * DEG_TO_RAD
    val elevationRad = elevation * DEG_TO_RAD
    val horizontal = distance * cos(elevationRad)
    return target + Float3(horizontal * sin(azimuthRad), distance * sin(elevationRad), horizontal * cos(azimuthRad))
}

private const val DEG_TO_RAD = (PI / 180).toFloat()

private fun Float3.toList() = listOf(x, y, z)
