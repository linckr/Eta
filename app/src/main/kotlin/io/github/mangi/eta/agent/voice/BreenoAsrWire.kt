package io.github.mangi.eta.agent.voice

internal object BreenoAsrWire {
    const val ACTION = "io.github.mangi.eta.BREENO_ASR_CONTROL"
    const val PERMISSION = "io.github.mangi.eta.permission.BREENO_ASR"
    const val PACKAGE = "com.heytap.speechassist"
    const val START = "start"
    const val FINISH = "finish"
    const val CANCEL = "cancel"
    const val ACK = 731
    const val LISTENING = 1
    const val PARTIAL = 2
    const val FINAL = 3
    const val ERROR = 4
    const val ERROR_REASON = "errorReason"
    const val ENGINE_NOT_READY = "engine_not_ready"
}
