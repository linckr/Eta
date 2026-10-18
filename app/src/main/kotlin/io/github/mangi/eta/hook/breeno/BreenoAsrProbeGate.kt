package io.github.mangi.eta.hook.breeno

/** A test may consume only its first ASR request; another request in the same room stays normal. */
internal class BreenoAsrProbeGate {
    private var room = ""
    private var expiresAt = 0L
    private var records: Set<String> = emptySet()

    @Synchronized fun arm(roomId: String, expiry: Long) {
        room = roomId
        expiresAt = expiry
        records = emptySet()
    }

    @Synchronized fun observe(roomId: String?, ids: List<String?>, now: Long) {
        if (now >= expiresAt || roomId != room || room.isBlank()) return
        val present = ids.filterNotNull().filter { it.isNotBlank() }.toSet()
        if (records.isEmpty()) records = present
        else if (present.any { it in records }) records = (records + present).take(8).toSet()
    }

    @Synchronized fun matches(roomId: String, ids: List<String>, now: Long): Boolean =
        now < expiresAt && room.isNotBlank() && roomId == room && ids.any { it.isNotBlank() && it in records }
}
