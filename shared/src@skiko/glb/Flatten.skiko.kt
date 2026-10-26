package com.hisname.productrender.glb

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface

internal actual fun flattenPng(png: ByteArray, argb: Int): ByteArray {
    val image = Image.makeFromEncoded(png)
    val surface = Surface.makeRasterN32Premul(image.width, image.height)
    surface.canvas.clear(argb)
    surface.canvas.drawImage(image, 0f, 0f)
    return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
}
