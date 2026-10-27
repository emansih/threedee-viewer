package com.hisname.productrender.viewer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Renders a GLB with an orbit camera; [onError] gets a message when it cannot be shown.
 * [capture] can take a PNG of it while it is on screen.
 */
@Composable
expect fun ModelViewer(glb: ByteArray, capture: ViewerCapture, modifier: Modifier = Modifier, onError: (String) -> Unit = {})
