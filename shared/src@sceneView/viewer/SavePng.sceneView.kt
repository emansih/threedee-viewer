package com.hisname.productrender.viewer

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.write

actual suspend fun savePng(png: ByteArray, baseName: String) {
    FileKit.openFileSaver(baseName, "png")?.write(png)
}
