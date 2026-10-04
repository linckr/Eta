package io.github.mangi.eta.hook.breeno

/** Keep a bounded tail after completion/cancellation so delayed native queries cannot start a run. */
internal class BreenoAsrClaims {
    private data class Claim(val room: String, val ids: MutableSet<String>, val expiry: Long)
    private val claims = LinkedHashMap<String, Claim>()

    @Synchronized fun open(id: String, room: String, expiry: Long) {
        claims[id] = Claim(room, linkedSetOf(), expiry)
        while (claims.size > 8) claims.remove(claims.keys.first())
    }
    @Synchronized fun bind(id: String, room: String?, ids: List<String?>, now: Long): Boolean {
        prune(now)
        val claim = claims[id] ?: return false
        if (claim.room != room) return false
        val incoming = ids.filterNotNull().filter { it.isNotBlank() }.toSet()
        if (incoming.isEmpty()) return false
        if (claim.ids.isNotEmpty() && incoming.none { it in claim.ids }) return false
        claim.ids.addAll(incoming.take(8 - claim.ids.size))
        return true
    }
    @Synchronized fun matches(room: String, ids: List<String>, now: Long): Boolean {
        prune(now)
        return room.isNotBlank() && claims.values.any { claim ->
            claim.room == room && ids.any { it.isNotBlank() && it in claim.ids }
        }
    }
    private fun prune(now: Long) { claims.entries.removeAll { now >= it.value.expiry } }
}
