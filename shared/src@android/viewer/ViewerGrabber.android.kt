package com.hisname.productrender.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Filament draws on a SurfaceView, which only the window compositor can copy
@Composable
internal actual fun rememberViewerGrabber(): ViewerGrabber {
    val view = LocalView.current
    return remember(view) {
        ViewerGrabber { area ->
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { "Saving an image needs Android 8 or newer" }
            val window = checkNotNull(view.context.findActivity()?.window) { "The viewer isn't in a window" }
            val bounds = area.boundsInWindow()
            val rect = Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
            val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
            suspendCancellableCoroutine { continuation ->
                PixelCopy.request(window, rect, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(IllegalStateException("Couldn't read the screen (error $result)"))
                    }
                }, Handler(Looper.getMainLooper()))
            }
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
