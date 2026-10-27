package com.hisname.productrender.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

// Filament draws on the GPU, outside what Compose can copy, so the pixels are read from the screen
@Composable
internal actual fun rememberViewerGrabber(): ViewerGrabber {
    val density = LocalDensity.current.density
    return remember(density) {
        ViewerGrabber { area ->
            val position = area.positionOnScreen()
            val rect = Rectangle(
                Math.round(position.x / density),
                Math.round(position.y / density),
                Math.round(area.size.width / density),
                Math.round(area.size.height / density),
            )
            // Let a frame land after the last click, so the capture is of the settled view
            delay(50)
            withContext(Dispatchers.IO) {
                val shots = Robot().createMultiResolutionScreenCapture(rect)
                val sharpest = shots.resolutionVariants.filterIsInstance<BufferedImage>().maxByOrNull { it.width }
                    ?: error("Couldn't read the screen")
                ByteArrayOutputStream().also { ImageIO.write(sharpest, "png", it) }.toByteArray()
            }
        }
    }
}
