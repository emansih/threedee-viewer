package com.hisname.productrender.viewer

/** Takes a picture of what [ModelViewer] shows; the viewer connects itself while it is on screen. */
class ViewerCapture {
    internal var source: (suspend () -> ByteArray)? = null

    /** The viewer as it is drawn right now, as PNG bytes; null when no viewer is on screen. */
    suspend fun png(): ByteArray? = source?.invoke()
}
