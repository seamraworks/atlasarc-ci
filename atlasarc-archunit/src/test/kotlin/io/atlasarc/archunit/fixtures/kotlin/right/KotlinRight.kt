package io.atlasarc.archunit.fixtures.kotlin.right

import io.atlasarc.archunit.fixtures.kotlin.left.KotlinLeft

class KotlinRight {
    fun call(left: KotlinLeft) = left.touch()
    fun touch(label: String = "default") = label.length
}
