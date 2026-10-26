package com.hisname.productrender.glb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.ByteArrayOutputStream

internal actual fun flattenPng(png: ByteArray, argb: Int): ByteArray {
    val image = BitmapFactory.decodeByteArray(png, 0, png.size)
    val flat = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
    Canvas(flat).apply {
        drawColor(argb)
        drawBitmap(image, 0f, 0f, null)
    }
    return ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
