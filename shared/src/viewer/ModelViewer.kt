package com.hisname.productrender.viewer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hisname.productrender.glb.Ray

/**
 * Renders a GLB with an orbit camera; [onError] gets a message when it cannot be shown.
 * [capture] can take a PNG of it while it is on screen. A click or tap on the model (not a
 * drag) gives [onTap] the ray from the camera through that point, in world space.
 */
@Composable
expect fun ModelViewer(
    glb: ByteArray,
    capture: ViewerCapture,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {},
    onTap: (Ray) -> Unit = {},
)
