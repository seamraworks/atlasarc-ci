package io.atlasarc.archunit.fixtures.kotlin.left

import io.atlasarc.archunit.fixtures.kotlin.right.KotlinRight

class KotlinLeft {
    fun call(right: KotlinRight) = right.touch()
    fun touch() = Unit
}
