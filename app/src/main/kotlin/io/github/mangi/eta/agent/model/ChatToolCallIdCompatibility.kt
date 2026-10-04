package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.core.AndroidAgentLogger

/** Only touches IDs in the detached Chat request; stored history and provider payloads stay intact. */
internal object ChatToolCallIdCompatibility {
    fun repairCopiedMessages(messages: JSONArray) {
        val reservedIds = mutableSetOf<String>()
        for (messageIndex in 0 until messages.length()) {
            val message = messages.optJSONObject(messageIndex) ?: continue
            message.optString("tool_call_id").takeIf { it.isNotBlank() }?.let(reservedIds::add)
            val calls = message.optJSONArray("tool_calls") ?: continue
            for (callIndex in 0 until calls.length()) {
                calls.optJSONObject(callIndex)?.optString("id")?.takeIf { it.isNotBlank() }?.let(reservedIds::add)
            }
        }
        val encounteredIds = mutableSetOf<String>()
        var renamedCalls = 0
        var renamedResults = 0
        for (messageIndex in 0 until messages.length()) {
            val message = messages.optJSONObject(messageIndex) ?: continue
            if (message.optString("role") != "assistant") continue
            val calls = message.optJSONArray("tool_calls") ?: continue
            val callCounts = mutableMapOf<String, Int>()
            for (callIndex in 0 until calls.length()) {
                calls.optJSONObject(callIndex)?.optString("id")?.takeIf { it.isNotBlank() }?.let {
                    callCounts[it] = (callCounts[it] ?: 0) + 1
                }
            }
            val results = mutableMapOf<String, MutableList<org.json.JSONObject>>()
            for (resultIndex in messageIndex + 1 until messages.length()) {
                val result = messages.optJSONObject(resultIndex) ?: break
                if (result.optString("role") != "tool") break
                val id = result.optString("tool_call_id")
                results.getOrPut(id) { mutableListOf() }.add(result)
            }
            for (callIndex in 0 until calls.length()) {
                val call = calls.optJSONObject(callIndex) ?: continue
                val original = call.optString("id").takeIf { it.isNotBlank() } ?: continue
                val repeated = !encounteredIds.add(original)
                // Missing, crossing, or ambiguous pairs stay unchanged. Never invent a match.
                val pairedResult = results[original]?.singleOrNull()
                if (!repeated || callCounts[original] != 1 || pairedResult == null) continue
                val base = "eta_call_${messageIndex}_$callIndex"
                var replacement = base
                var suffix = 1
                while (!reservedIds.add(replacement)) replacement = "${base}_${suffix++}"
                call.put("id", replacement)
                pairedResult.put("tool_call_id", replacement)
                renamedCalls += 1
                renamedResults += 1
            }
        }
        runCatching { AndroidAgentLogger.debug {
            "chat_tool_id_mapping renamed_calls=$renamedCalls renamed_results=$renamedResults"
        } }
    }
}
