package io.atlasarc.evaluator

import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    exitProcess(EvaluatorApplication().run(arguments))
}
