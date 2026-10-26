package com.hisname.productrender.glb

/** Decodes [png], draws it over an opaque [argb] background and encodes the result as PNG. */
internal expect fun flattenPng(png: ByteArray, argb: Int): ByteArray
