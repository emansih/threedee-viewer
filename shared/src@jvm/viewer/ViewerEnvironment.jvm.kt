package com.hisname.productrender.viewer

import io.github.sceneview.compose.EnvironmentSource

// SceneView's desktop default has no indirect light, so whatever faces away from its sun renders black
internal actual val viewerEnvironment: EnvironmentSource =
    EnvironmentSource.Hdr("environments/studio.hdr", showSkybox = false)
