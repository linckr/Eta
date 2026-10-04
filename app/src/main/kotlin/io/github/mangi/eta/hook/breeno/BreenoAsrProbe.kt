package io.github.mangi.eta.hook.breeno

import android.os.Bundle
import android.os.SystemClock
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Debug APK diagnostics plus an explicitly armed, one-shot transcription experiment. */
internal object BreenoAsrProbe {
    private val sequence = AtomicLong()
    private val salt = UUID.randomUUID().toString()
    private val gate = BreenoAsrProbeGate()

    fun suppressRuntime(hooksLogger: io.github.mangi.eta.core.ModuleLogger, room: String, record: String, original: String, source: String): Boolean {
        if (!gate.matches(room, listOf(record, original), SystemClock.elapsedRealtime())) return false
        hooksLogger.info("ASR_CONTROL runtime_suppressed source=$source room=${token(room)} record=${token(record)}")
        return true
    }

    fun install(hooks: HookRegistrar, classLoader: ClassLoader, cacheDirectory: File) {
        installOneShotControl(hooks, classLoader, cacheDirectory)
        for (name in listOf("RecognizeResultOperation", "StreamRecognizeResultOperation")) {
            val id = if (name.startsWith("Stream")) "breeno.asr-probe.stream" else "breeno.asr-probe.normal"
            val type = HookSupport.findClassOrNull(
                classLoader, "com.heytap.speechassist.core.engine.skill.$name",
            )
            val method = type?.let { HookSupport.findMethod(it, "process") }
            if (method == null) {
                hooks.missing(id, name, "ASR probe target missing: $name")
                continue
            }
            hooks.intercept(id, method, "ASR probe $name") { chain ->
                // Diagnostics must never replace the native invocation, even when reflection fails.
                runCatching {
                    val operation = chain.thisObject ?: return@runCatching
                    val directive = HookSupport.invokeNoArgs(operation, "getDirective") ?: return@runCatching
                    val payload = HookSupport.invokeNoArgs(directive, "getPayload") ?: return@runCatching
                    val info = HookSupport.invokeNoArgs(operation, "getConversationInfo")
                    val record = info?.let { HookSupport.invokeNoArgs(it, "getRecordId") as? String }
                    val current = info?.let { HookSupport.invokeNoArgs(it, "getCurrentRecordId") as? String }
                    val roomId = info?.let { HookSupport.invokeNoArgs(it, "getRoomId") as? String }
                    gate.observe(roomId, listOf(record, current,
                        HookSupport.invokeNoArgs(payload, "getAssociatedOriginalRecordId") as? String),
                        SystemClock.elapsedRealtime())
                    emit(hooks, name,
                        HookSupport.invokeNoArgs(payload, "getContent") as? String,
                        HookSupport.invokeNoArgs(payload, "getType") == "FINAL",
                        info?.let { HookSupport.invokeNoArgs(it, "getRecordId") as? String },
                        info?.let { HookSupport.invokeNoArgs(it, "getCurrentRecordId") as? String },
                        info?.let { HookSupport.invokeNoArgs(it, "getRoomId") as? String },
                        "round=${HookSupport.invokeNoArgs(payload, "getOneshotRound") as? Int}" +
                            " seq=${HookSupport.invokeNoArgs(payload, "getSeqNo") as? Int}")
                }.onFailure { failure ->
                    hooks.logger.warnThrottled("asr_probe_operation") {
                        "ASR_PROBE read failed: ${failure.javaClass.simpleName}"
                    }
                }
                chain.proceed()
            }
        }

        // Version-specific second observation point for XiaoBu 13.3.2, not a production fallback.
        val callback = HookSupport.findClassOrNull(classLoader, "com.heytap.speechassist.core.engine.a0")
        val method = callback?.let {
            HookSupport.findMethod(it, "w", String::class.java, Boolean::class.javaPrimitiveType!!, Bundle::class.java)
        }
        if (method == null) {
            hooks.missing("breeno.asr-probe.callback", "ASR shared callback", "ASR probe callback missing")
        } else {
            hooks.intercept("breeno.asr-probe.callback", method, "ASR probe shared callback") { chain ->
                runCatching {
                    val bundle = chain.args.getOrNull(2) as? Bundle
                    emit(hooks, "callback", chain.args.getOrNull(0) as? String,
                        chain.args.getOrNull(1) == true, bundle?.getString("recordId"),
                        bundle?.getString("currentRecordId"), bundle?.getString("roomId"),
                        "round=${bundle?.getInt("oneshotRound", 0)} wakeup=${bundle?.getBoolean("wakeupWord", false)}")
                }.onFailure { failure ->
                    hooks.logger.warnThrottled("asr_probe_callback") {
                        "ASR_PROBE read failed: ${failure.javaClass.simpleName}"
                    }
                }
                chain.proceed()
            }
        }
    }

    private fun installOneShotControl(hooks: HookRegistrar, loader: ClassLoader, cache: File) {
        val processor = HookSupport.findClassOrNull(loader, "com.heytap.speech.engine.nodes.g")
        val parameter = HookSupport.findClassOrNull(loader, "com.heytap.speech.engine.nodes.DmParameter")
        val start = processor?.let { HookSupport.findMethod(it, "j", String::class.java, Array<String>::class.java) }
        val asr = processor?.let { HookSupport.findMethod(it, "i", String::class.java, String::class.java) }
        val request = if (processor != null && parameter != null) HookSupport.findMethod(processor, "p0", parameter) else null
        val setRoom = parameter?.let { HookSupport.findMethod(it, "setRoomId", String::class.java) }
        if (start == null || asr == null || request == null || setRoom == null) {
            hooks.missing("breeno.asr-probe.control", "ASR one-shot control", "ASR one-shot control target missing")
            return
        }
        val room = ThreadLocal<String?>()
        val observeUntil = AtomicLong()
        val marker = File(cache, "eta-asr-only-probe")
        hooks.intercept("breeno.asr-probe.room", request, "ASR probe room correlation") { chain ->
            room.get()?.let { setRoom.invoke(chain.args[0], it) }
            chain.proceed()
        }
        hooks.intercept("breeno.asr-probe.control", start, "ASR probe one-shot start") { chain ->
            if (chain.args.getOrNull(0) != "start" || !marker.isFile) return@intercept chain.proceed()
            val age = System.currentTimeMillis() - marker.lastModified()
            if (age !in 0..600_000L) {
                marker.delete()
                hooks.logger.info("ASR_CONTROL skipped=expired ageMs=$age")
                return@intercept chain.proceed()
            }
            val args = chain.args.getOrNull(1) as? Array<*>
            val original = runCatching { JSONObject(args?.firstOrNull() as? String ?: "{}") }.getOrNull()
            val roomId = original?.optString("roomId").orEmpty()
            if (roomId.isBlank() || !marker.delete()) {
                hooks.logger.info("ASR_CONTROL skipped=unclaimed roomAvailable=${roomId.isNotBlank()}")
                return@intercept chain.proceed()
            }
            val options = JSONObject().put("startVAD", true).put("oneshot", false)
                .put("enable_wakeup_cloud_check", false).put("word", "")
            room.set(roomId)
            gate.arm(roomId, SystemClock.elapsedRealtime() + 70_000L)
            observeUntil.set(SystemClock.elapsedRealtime() + 70_000L)
            hooks.logger.info("ASR_CONTROL one-shot start room=${token(roomId)}")
            try {
                // Do not fall through to a second microphone/model request if this invocation fails.
                asr.invoke(chain.thisObject, "start", options.toString())
                null
            } finally {
                room.remove()
            }
        }
        val messageProcessor = HookSupport.findClassOrNull(loader, "com.heytap.speech.engine.connect.core.manager.i")
        val inbound = messageProcessor?.let { HookSupport.findMethod(it, "C", String::class.java, String::class.java) }
        if (inbound != null) {
            hooks.intercept("breeno.asr-probe.directives", inbound, "ASR probe directive types") { chain ->
                if (SystemClock.elapsedRealtime() < observeUntil.get()) runCatching {
                    val message = JSONObject(chain.args.getOrNull(1) as? String ?: "{}")
                    val directives = message.optJSONArray("directives")
                    val kinds = (0 until (directives?.length() ?: 0)).mapNotNull { index ->
                        val header = directives?.optJSONObject(index)?.optJSONObject("header") ?: return@mapNotNull null
                        // Names only: no content, payload, identifiers or server messages.
                        val namespace = header.optString("namespace")
                        val name = header.optString("name")
                        if (namespace.matches(Regex("[A-Za-z0-9_]{1,64}")) && name.matches(Regex("[A-Za-z0-9_]{1,64}"))) "$namespace.$name" else "other"
                    }
                    hooks.logger.info("ASR_CONTROL directives=${kinds.joinToString(",")}")
                }
                chain.proceed()
            }
        }
    }

    private fun emit(
        hooks: HookRegistrar, layer: String, text: String?, final: Boolean,
        record: String?, current: String?, room: String?, extra: String,
    ) {
        // Salted identifiers correlate only within this process. Never log text or its hash.
        hooks.logger.info("ASR_PROBE n=${sequence.incrementAndGet()} t=${SystemClock.elapsedRealtime()} " +
            "layer=$layer final=$final chars=${text?.length ?: 0} record=${token(record)} " +
            "current=${token(current)} room=${token(room)} $extra")
    }

    private fun token(value: String?): String {
        if (value.isNullOrBlank()) return "-"
        return MessageDigest.getInstance("SHA-256").digest((salt + value).toByteArray())
            .take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
