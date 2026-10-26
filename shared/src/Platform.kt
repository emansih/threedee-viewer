package com.hisname.productrender

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
