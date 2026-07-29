package io.atlasarc.parity.fixtures.jvm.kotlin.right

import io.atlasarc.parity.fixtures.jvm.kotlin.left.KotlinLeft

class KotlinRight {
    fun call(left: KotlinLeft): Int = left.touch()
    fun touch(label: String = "default"): Int = label.length
}
