package org.finanzaspersonales

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform