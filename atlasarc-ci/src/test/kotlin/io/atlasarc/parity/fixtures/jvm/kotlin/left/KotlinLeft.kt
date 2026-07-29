package io.atlasarc.parity.fixtures.jvm.kotlin.left

import io.atlasarc.parity.fixtures.jvm.kotlin.right.KotlinRight

class KotlinLeft {
    fun call(right: KotlinRight): Int = right.touch("left")
    fun touch(): Int = 1
}
