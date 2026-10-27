package com.hisname.productrender.viewer

/** Lets the user save [png] as "[baseName].png": a save dialog, or a download on the web. */
expect suspend fun savePng(png: ByteArray, baseName: String)
