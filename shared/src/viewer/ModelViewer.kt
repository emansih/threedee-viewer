package com.hisname.productrender.viewer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Renders a GLB with an orbit camera; [onError] gets a message when it cannot be shown. */
@Composable
expect fun ModelViewer(glb: ByteArray, modifier: Modifier = Modifier, onError: (String) -> Unit = {})
