package io.github.mangi.eta.hook.breeno

internal object BreenoAsrResultIdentity {
    fun accepts(room: String, record: String?, incomingRoom: String?, incomingRecord: String?, directives: List<Pair<String, String>>): Boolean =
        room.isNotBlank() && !record.isNullOrBlank() && room == incomingRoom && record == incomingRecord &&
            directives.any { (namespace, name) -> namespace == "SpeechRecognizer" && name in setOf("RecognizeResult", "StreamRecognizeResult") } &&
            directives.all { (namespace, name) -> namespace == "SpeechRecognizer" && name in setOf("RecognizeResult", "StreamRecognizeResult", "VadControl") }
}
