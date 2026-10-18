package io.github.mangi.eta.hook.breeno

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import io.github.mangi.eta.agent.runtime.AgentAppContext
import io.github.mangi.eta.agent.voice.BreenoAsrWire
import io.github.mangi.eta.core.DexKitTargets
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

internal object BreenoAsrHooks {
    private val handler = Handler(Looper.getMainLooper())
    private val claims = BreenoAsrClaims()
    @Volatile private var active: Session? = null
    @Volatile private var processor = WeakReference<Any>(null)
    private var registered = false
    private var startingRoom: String? = null
    private val recorderSession = ThreadLocal<Session?>()
    @Volatile private var reuseUntil = 0L
    private data class Session(val id: String, val room: String, val receiver: ResultReceiver, val processor: Any) {
        var requestStarted = false
        @Volatile var finishRequested = false
        @Volatile var record: String? = null
        @Volatile var recorder: Any? = null
        val frames = AtomicLong()
    }

    fun suppressRuntime(logger: ModuleLogger, room: String, record: String, original: String): Boolean {
        val matches = claims.matches(room, listOf(record, original), SystemClock.elapsedRealtime())
        if (matches) logger.info("Breeno ASR consumed native query")
        return matches
    }

    fun install(hooks: HookRegistrar, loader: ClassLoader, dex: DexKitTargets, currentRoom: () -> String) {
        val holder = HookSupport.findClassOrNull(loader, "com.heytap.speechassist.core.EngineHolder")
        val lazyType = HookSupport.findClassOrNull(loader, "com.heytap.speechassist.utils.j4")
        val releaseHolder = lazyType?.let { uniqueBreenoInstanceMethod(it, "void") }
        releaseHolder?.let { method ->
            hooks.intercept("breeno.asr-engine-holder-release", method, "Breeno ASR engine ownership") { chain ->
                val nativeHolder = holder?.declaredFields?.any { field ->
                    java.lang.reflect.Modifier.isStatic(field.modifiers) && field.type == lazyType &&
                        runCatching { field.isAccessible = true; field.get(null) === chain.thisObject }.getOrDefault(false)
                } == true
                if (nativeHolder && (active != null || SystemClock.elapsedRealtime() < reuseUntil)) {
                    hooks.logger.info("Breeno ASR deferred native engine holder release")
                    Unit
                } else chain.proceed()
            }
        }
        fun find(key: String, literal: String, params: List<String>): Method? = dex.findMethod(
            key, validate = { it.returnType == Void.TYPE && it.parameterTypes.map { p -> p.canonicalName ?: p.name } == params },
        ) {
            findMethod {
                searchPackages("com.heytap.speech.engine.nodes")
                matcher { returnType = "void"; paramTypes(*params.toTypedArray()); usingStrings(literal) }
            }.toList()
        }
        val control = find("breeno.asr-control.v1", "start asr only ", listOf("java.lang.String", "java.lang.String"))
        val finish = find("breeno.asr-finish.v1", "stopAsrOnly, enableVad is ", emptyList())
        val ordinary = find("breeno.asr-native-start.v1", "force dialog start ", listOf("java.lang.String", "java.lang.String[]"))
        val requestType = HookSupport.findClassOrNull(loader, "com.heytap.speech.engine.nodes.DmParameter")
        val request = if (control != null && requestType != null) {
            uniqueBreenoInstanceMethod(control.declaringClass, "void", requestType.name)
        } else null
        val setRoom = requestType?.let { HookSupport.findMethod(it, "setRoomId", String::class.java) }
        val getRecord = requestType?.let { HookSupport.findMethod(it, "getRecordId") }
        val nativeCancel = dex.findMethod("breeno.asr-native-cancel.v1", validate = {
            it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes.toList() == listOf(Bundle::class.java, Any::class.java, Boolean::class.javaPrimitiveType)
        }) {
            findMethod {
                searchPackages("com.heytap.speechassist.core.engine")
                matcher { returnType = "boolean"; paramTypes("android.os.Bundle", "java.lang.Object", "boolean")
                    usingStrings("cancelLastVoiceRequest recordId = ") }
            }.toList()
        }
        nativeCancel?.let { method ->
            hooks.intercept("breeno.asr-native-cancel", method, "Breeno ASR native request cancellation") { chain ->
                val session = active
                val room = chain.args.getOrNull(1) as? String ?: currentRoom()
                // Finishing recording still needs the backend FINAL; Eta cancellation clears active first.
                if (session != null && session.room == room) {
                    hooks.logger.info("Breeno ASR isolated native voice cancellation")
                    true
                } else chain.proceed()
            }
        }
        val inbound = dex.findMethod("breeno.inbound-message.v1", validate = {
            it.returnType == Void.TYPE && it.parameterTypes.toList() == listOf(String::class.java, String::class.java)
        }) {
            findMethod {
                searchPackages("com.heytap.speech.engine.connect.core.manager")
                matcher { returnType = "void"; paramTypes("java.lang.String", "java.lang.String")
                    usingStrings("messageContent", "processMessage , onDirectiveFilter , return.") }
            }.toList()
        }
        inbound?.let { method ->
            hooks.intercept("breeno.asr-protocol-status", method, "Breeno ASR protocol status") { chain ->
                if (active != null) runCatching {
                    val message = JSONObject(chain.args[1] as String)
                    val directives = message.optJSONArray("directives")
                    val kinds = (0 until (directives?.length() ?: 0)).mapNotNull { index ->
                        val header = directives?.optJSONObject(index)?.optJSONObject("header") ?: return@mapNotNull null
                        val namespace = header.optString("namespace")
                        val name = header.optString("name")
                        if (namespace == "SpeechRecognizer" && name in listOf("RecognizeResult", "StreamRecognizeResult")) {
                            val payload = directives?.optJSONObject(index)?.optJSONObject("payload")
                            val session = active
                            val owned = session != null && claims.bind(session.id, message.optString("roomId"),
                                listOf(message.optString("recordId"), message.optString("originalRecordId"),
                                    payload?.optString("associatedOriginalRecordId")), SystemClock.elapsedRealtime())
                            hooks.logger.info("Breeno ASR inbound result: final=${payload?.optString("type") == "FINAL"} correlated=$owned chars=${payload?.optString("content")?.length ?: 0}")
                        }
                        if (namespace.matches(Regex("[A-Za-z0-9_]{1,64}")) && name.matches(Regex("[A-Za-z0-9_]{1,64}"))) "$namespace.$name" else "other"
                    }
                    hooks.logger.info("Breeno ASR inbound directives=${kinds.joinToString(",")}")
                }
                chain.proceed()
            }
            // This callback receives native-merged stream text before UI Operation dispatch.
            val infoType = HookSupport.findClassOrNull(loader, "com.heytap.speech.engine.net.remote.ConversationInfo")
            val normalized = infoType?.let {
                uniqueBreenoInstanceMethod(method.declaringClass, "void", "java.lang.String", "java.lang.Integer", "java.lang.String", it.name)
            }
            normalized?.let { target ->
                hooks.intercept("breeno.asr-normalized-result", target, "Breeno ASR normalized result") { chain ->
                    val session = active
                    val info = chain.args[3]
                    val belongs = session != null && info != null && claims.bind(session.id,
                        HookSupport.invokeNoArgs(info, "getRoomId") as? String,
                        listOf(HookSupport.invokeNoArgs(info, "getRecordId") as? String,
                            HookSupport.invokeNoArgs(info, "getCurrentRecordId") as? String,
                            HookSupport.invokeNoArgs(info, "getAssociatedOriginalRecordId") as? String), SystemClock.elapsedRealtime())
                    val final = chain.args[0] == "FINAL"
                    val text = chain.args[2] as? String
                    if (session != null) hooks.logger.info("Breeno ASR normalized result: final=$final correlated=$belongs chars=${text?.length ?: 0}")
                    val result = chain.proceed()
                    if (session != null && belongs) handler.post {
                        if (active === session) {
                            if (final) {
                                active = null
                                reply(session, BreenoAsrWire.FINAL, text)
                            } else reply(session, BreenoAsrWire.PARTIAL, text)
                        }
                    }
                    result
                }
            }
            // Observe native verification outcomes without relaxing identity or sequence checks.
            method.declaringClass.declaredMethods.filter {
                it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes.map { type -> type.name } in listOf(
                        listOf("org.json.JSONObject", "java.util.HashMap", infoType?.name),
                        listOf("org.json.JSONObject", "java.lang.String", "java.util.HashMap", infoType?.name))
            }.forEachIndexed { index, target ->
                hooks.intercept("breeno.asr-verification.$index", target, "Breeno ASR native verification") { chain ->
                    val result = chain.proceed()
                    if (active != null) hooks.logger.info("Breeno ASR native verification: method=${target.name} accepted=$result")
                    // The native UI can retain another original record while Eta owns this ASR request.
                    // Verify against the exact locally generated record, never a room-only claim.
                    val session = active
                    val message = chain.args.firstOrNull() as? JSONObject
                    val directives = message?.optJSONArray("directives")
                    val ownedAsr = target.parameterCount == 4 && result == false && session != null &&
                        BreenoAsrResultIdentity.accepts(session.room, session.record, message?.optString("roomId"),
                            message?.optString("recordId"), (0 until (directives?.length() ?: 0)).map { position ->
                            val header = directives?.optJSONObject(position)?.optJSONObject("header")
                            header?.optString("namespace").orEmpty() to header?.optString("name").orEmpty()
                        })
                    if (ownedAsr) {
                        hooks.logger.info("Breeno ASR verified owned result independently of native UI record")
                        true
                    } else result
                }
            }
            HookSupport.findClassOrNull(loader, "com.heytap.speech.engine.HeytapSpeechEngine")?.declaredMethods
                ?.filter { it.name.startsWith("onDirectiveFilter") && it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes.toList() == listOf(String::class.java, String::class.java, String::class.java) }
                ?.forEachIndexed { index, target ->
                    hooks.intercept("breeno.asr-directive-filter.$index", target, "Breeno ASR native directive filter") { chain ->
                        val result = chain.proceed()
                        if (active != null) hooks.logger.info("Breeno ASR native directive filter: intercepted=$result")
                        result
                    }
                }
        }
        val recorderIdle = dex.findMethod("breeno.asr-recorder-idle.v1", validate = {
            it.returnType == Boolean::class.javaPrimitiveType && it.parameterCount == 0
        }) {
            findMethod {
                searchPackages("com.heytap.speechassist.core.engine.audiorecorder")
                matcher { returnType = "boolean"; paramTypes(); usingStrings("getConversationState , conversationState = ") }
            }.toList()
        }
        val recorderStart = dex.findMethod("breeno.asr-recorder-start.v1", validate = {
            it.parameterTypes.toList() == listOf(Bundle::class.java)
        }) {
            findMethod {
                searchPackages("com.heytap.speechassist.core.engine.audiorecorder")
                matcher { paramTypes("android.os.Bundle"); usingStrings("startRecord failed , current state is idle") }
            }.toList()
        }
        val fireAudio = dex.findMethod("breeno.asr-recorder-fire.v1", validate = {
            it.returnType == Void.TYPE && it.parameterTypes.toList() == listOf(String::class.java, String::class.java)
        }) {
            findMethod {
                searchPackages("com.heytap.speechassist.core.engine.audiorecorder.recorder")
                matcher { returnType = "void"; paramTypes("java.lang.String", "java.lang.String"); usingStrings("updateFireDataStatus stage = ") }
            }.toList()
        }
        val recorderCleanup = dex.findMethod("breeno.asr-recorder-cleanup.v1", validate = {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterCount == 2 && it.parameterTypes.last() == String::class.java
        }) {
            findMethod {
                searchPackages("com.heytap.speechassist.core.engine.audiorecorder")
                matcher { returnType = "void"; usingStrings("stopRecord , audioRecorder = ") }
            }.toList()
        }
        val feed = HookSupport.findClassOrNull(loader, "com.heytap.speech.engine.nodes.recorder.RecorderNode")
            ?.let { HookSupport.findMethod(it, "feedPcm", ByteArray::class.java) }
        val operations = listOf("RecognizeResultOperation", "StreamRecognizeResultOperation").mapNotNull { name ->
            HookSupport.findClassOrNull(loader, "com.heytap.speechassist.core.engine.skill.$name")
                ?.let { HookSupport.findMethod(it, "process") }
        }
        if (control == null || finish == null || ordinary == null || request == null || setRoom == null || getRecord == null || recorderIdle == null || recorderStart == null || fireAudio == null || recorderCleanup == null || feed == null || operations.size != 2) {
            hooks.missing("breeno.asr-provider", "Breeno ASR provider", "小布 ASR 接口不完整，保留原生处理")
            return
        }
        hooks.intercept("breeno.asr-recorder-cleanup", recorderCleanup, "Breeno ASR recording ownership") { chain ->
            val session = active
            val ownsRecorder = session?.recorder?.let { recorder ->
                chain.args[0]?.javaClass?.declaredFields?.any { field ->
                    if (field.type != java.util.concurrent.atomic.AtomicReference::class.java) false else {
                        field.isAccessible = true
                        (field.get(chain.args[0]) as? java.util.concurrent.atomic.AtomicReference<*>)?.get() === recorder
                    }
                } == true
            } == true
            if (session != null && ownsRecorder && !session.finishRequested) {
                // The native cleanup Runnable looks up the manager's current recorder when it runs,
                // so cleanup from an earlier UI conversation can otherwise stop a fresh Eta input.
                val reason = (chain.args.getOrNull(1) as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,64}")) } ?: "other"
                hooks.logger.info("Breeno ASR isolated native recorder cleanup: reason=$reason")
                Unit
            } else chain.proceed()
        }
        fun connectAudio(session: Session) {
            val recorder = session.recorder ?: return
            val record = session.record?.takeIf { it.isNotBlank() } ?: return
            runCatching { fireAudio.invoke(recorder, record, "Eta transcription") }
                .onFailure { handler.post { end(session, control, "小布音频关联失败，请重试", true) } }
        }
        hooks.intercept("breeno.asr-audio-feed", feed, "Breeno ASR audio flow") { chain ->
            active?.frames?.incrementAndGet()
            chain.proceed()
        }
        val recordThread = HookSupport.findClassOrNull(loader,
            "com.heytap.speechassist.core.engine.audiorecorder.thread.BaseAudioRecordThread")
        recordThread?.let { type ->
            HookSupport.findMethod(type, "interrupt")?.let { method ->
                hooks.intercept("breeno.asr-record-interruption", method, "Breeno ASR recording interruption") { chain ->
                    if (active != null) {
                        val callers = Throwable().stackTrace.filter {
                            it.className.startsWith("com.heytap.speechassist") || it.className.startsWith("com.heytap.speech.engine")
                        }.take(8).joinToString(" <- ") { "${it.className}.${it.methodName}" }
                        hooks.logger.info("Breeno ASR recording interrupted: callers=$callers")
                    }
                    chain.proceed()
                }
            }
            // Observe the typed error callback structurally; never log its free-form message.
            type.declaredMethods.singleOrNull {
                it.returnType == Void.TYPE && it.parameterTypes.toList() == listOf(Int::class.javaPrimitiveType, String::class.java)
            }?.let { method ->
                hooks.intercept("breeno.asr-record-error", method, "Breeno ASR recording error") { chain ->
                    active?.let { session ->
                        hooks.logger.info("Breeno ASR recording error: code=${chain.args[0]}")
                        handler.post { end(session, control, "小布录音发生错误，请重试", true) }
                    }
                    chain.proceed()
                }
            }
        }
        val recordStop = dex.findMethod("breeno.asr-recorder-stop.v1", validate = {
            it.returnType == Void.TYPE && it.parameterTypes.toList() == listOf(String::class.java)
        }) {
            findMethod {
                searchPackages("com.heytap.speechassist.core.engine")
                matcher { returnType = "void"; paramTypes("java.lang.String"); usingStrings("stopRecord , recorder =  ") }
            }.toList()
        }
        recordStop?.let { method ->
            hooks.intercept("breeno.asr-recorder-stop", method, "Breeno ASR recording stop reason") { chain ->
                if (active != null) {
                    val reason = (chain.args[0] as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,64}")) } ?: "other"
                    hooks.logger.info("Breeno ASR recording stopped: reason=$reason")
                    val session = active
                    if (session != null && !session.finishRequested && session.frames.get() == 0L) {
                        handler.post { end(session, control, "小布引擎在收到语音前结束了录音，正在排查兼容性", true) }
                    }
                }
                chain.proceed()
            }
        }
        val bus = HookSupport.findClassOrNull(loader, "fe.e")
        val lifetimeName = dex.findMethod("breeno.asr-native-lifetime-name.v1", validate = {
            it.returnType == String::class.java && it.parameterCount == 0
        }) {
            findMethod {
                searchPackages("com.heytap.speechassist.core")
                matcher { returnType = "java.lang.String"; paramTypes(); usingStrings("SpeechService.foreground") }
            }.toList()
        }
        lifetimeName?.declaringClass?.let { type ->
                HookSupport.findMethod(type, "interceptKillSelf")?.takeIf {
                    it.returnType == Boolean::class.javaPrimitiveType && it.parameterCount == 0
                }?.let { method ->
                    hooks.intercept("breeno.asr-native-lifetime", method, "Breeno ASR native lifetime") { chain ->
                        val native = chain.proceed()
                        if (active != null) {
                            hooks.logger.info("Breeno ASR retained during native idle cleanup")
                            true
                        } else native
                    }
                }
            }
        bus?.declaredMethods?.filter { method ->
            method.name == "t" && method.parameterTypes.firstOrNull() == String::class.java
        }?.forEachIndexed { index, method ->
            hooks.intercept("breeno.asr-bus-stop.$index", method, "Breeno ASR stop command source") { chain ->
                if (active != null && chain.args.firstOrNull() == "recorder.ctrl") {
                    val first = when (val parts = chain.args.getOrNull(1)) {
                        is String -> parts
                        is Array<*> -> when (val part = parts.firstOrNull()) {
                            is String -> part
                            is ByteArray -> if (part.size <= 16) part.toString(Charsets.UTF_8) else null
                            else -> null
                        }
                        else -> null
                    }
                    if (first == "stop" || first == "stopall") {
                        val callers = Throwable().stackTrace.filter {
                            it.className.startsWith("com.heytap.speechassist") || it.className.startsWith("com.heytap.speech.engine")
                        }.take(10).joinToString(" <- ") { "${it.className}.${it.methodName}" }
                        hooks.logger.info("Breeno ASR stop command: callers=$callers")
                    }
                }
                chain.proceed()
            }
        }
        hooks.intercept("breeno.asr-recorder-idle", recorderIdle, "Breeno ASR recorder eligibility") { chain ->
            val idle = chain.proceed()
            // This guard belongs to the native conversation UI, not microphone permissions.
            // Only the synchronous start of an explicit Eta transcription may outlive that UI.
            if (recorderSession.get()?.let { active === it } == true) {
                hooks.logger.info("Breeno ASR recorder eligibility: nativeIdle=$idle")
                false
            } else idle
        }
        hooks.intercept("breeno.asr-recorder-start", recorderStart, "Breeno ASR recorder startup") { chain ->
            val session = active
            recorderSession.set(session)
            try {
                val result = chain.proceed()
                if (session != null) {
                    hooks.logger.info("Breeno ASR recorder startup: available=${result != null}")
                    if (result == null) handler.post { end(session, control, "小布未能启动录音，请重新唤醒后重试", true) }
                    else { session.recorder = result; connectAudio(session) }
                }
                result
            } finally {
                recorderSession.remove()
            }
        }
        for ((index, constructor) in control.declaringClass.declaredConstructors.withIndex()) {
            hooks.intercept("breeno.asr-constructor.$index", constructor, "Breeno ASR processor") { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { processor = WeakReference(it) }
                result
            }
        }
        hooks.intercept("breeno.asr-room", request, "Breeno ASR room") { chain ->
            val session = active?.takeIf { startingRoom == it.room }
            session?.let { setRoom.invoke(chain.args[0], it.room) }
            val result = chain.proceed()
            session?.let {
                // The native switcher must mint the ID and update its own current-record state.
                // Pre-setting only DmParameter.recordId skips that state update on 13.3.2.
                val record = getRecord.invoke(chain.args[0]) as? String
                it.record = record
                it.requestStarted = claims.bind(it.id, it.room, listOf(record), SystemClock.elapsedRealtime())
                connectAudio(it)
                hooks.logger.info("Breeno ASR native request: correlated=${it.requestStarted}")
            }
            result
        }
        hooks.intercept("breeno.asr-external-start", ordinary, "Breeno ASR external interruption") { chain ->
            val session = active
            if (session != null && chain.thisObject === session.processor && chain.args.getOrNull(0) == "close"
                && !session.finishRequested && !(startingRoom != null && Looper.myLooper() == Looper.getMainLooper())) {
                // The native UI has its own lifecycle. Its close command must not close Eta's explicit ASR input.
                hooks.logger.info("Breeno ASR isolated native dialog close")
                return@intercept Unit
            }
            chain.thisObject?.let { processor = WeakReference(it) }
            if (chain.args.getOrNull(0) == "start") active?.let {
                end(it, control, "小布开始了另一段语音输入", cancel = true)
            }
            chain.proceed()
        }
        for ((index, operation) in operations.withIndex()) {
            hooks.intercept("breeno.asr-result.$index", operation, "Breeno ASR result") { chain ->
                val session = active
                var text: String? = null
                var final = false
                var belongs = false
                runCatching {
                    if (session != null) {
                        val target = chain.thisObject ?: return@runCatching
                        val info = HookSupport.invokeNoArgs(target, "getConversationInfo") ?: return@runCatching
                        val directive = HookSupport.invokeNoArgs(target, "getDirective") ?: return@runCatching
                        val payload = HookSupport.invokeNoArgs(directive, "getPayload") ?: return@runCatching
                        belongs = claims.bind(session.id, HookSupport.invokeNoArgs(info, "getRoomId") as? String,
                            listOf(HookSupport.invokeNoArgs(info, "getRecordId") as? String,
                                HookSupport.invokeNoArgs(info, "getCurrentRecordId") as? String,
                                HookSupport.invokeNoArgs(payload, "getAssociatedOriginalRecordId") as? String), SystemClock.elapsedRealtime())
                        text = HookSupport.invokeNoArgs(payload, "getContent") as? String
                        final = HookSupport.invokeNoArgs(payload, "getType") == "FINAL"
                        if (final) hooks.logger.info("Breeno ASR native final observed: correlated=$belongs chars=${text?.length ?: 0}")
                    }
                }.onFailure { hooks.logger.warnThrottled("breeno_asr_read") { "小布 ASR 读取失败: ${it.javaClass.simpleName}" } }
                val result = chain.proceed()
                if (session != null && belongs) handler.post {
                    if (active === session) {
                        if (final) {
                            active = null
                            reply(session, BreenoAsrWire.FINAL, text)
                            hooks.logger.info("Breeno ASR delivered final: chars=${text?.length ?: 0}")
                        } else reply(session, BreenoAsrWire.PARTIAL, text)
                    }
                }
                result
            }
        }
        fun register(attempt: Int) {
            if (registered) return
            val context = AgentAppContext.resolve()
            if (context == null) {
                if (attempt < 20) handler.postDelayed({ register(attempt + 1) }, 500)
                return
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    resultCode = BreenoAsrWire.ACK
                    val id = intent.getStringExtra("id") ?: return
                    val command = intent.getStringExtra("command")
                    when (command) {
                        BreenoAsrWire.START -> {
                            val callback = intent.getParcelableExtra("receiver", ResultReceiver::class.java) ?: return
                            val target = processor.get()
                            val room = currentRoom()
                            if (target == null || room.isBlank() || active != null) {
                                val busy = active != null
                                hooks.logger.info("Breeno ASR startup unavailable: processor=${target != null} room=${room.isNotBlank()} busy=$busy")
                                callback.send(BreenoAsrWire.ERROR, Bundle().apply {
                                    putString("id", id); putString("error", "请先唤醒一次小布完成初始化，或结束当前语音输入后重试")
                                    if (!busy) putString(BreenoAsrWire.ERROR_REASON, BreenoAsrWire.ENGINE_NOT_READY)
                                })
                                return
                            }
                            val session = Session(id, room, callback, target)
                            reuseUntil = SystemClock.elapsedRealtime() + 60_000
                            active = session
                            claims.open(id, room, SystemClock.elapsedRealtime() + 120_000)
                            try {
                                startingRoom = room
                                control.invoke(target, "start", JSONObject().put("startVAD", true)
                                    .put("oneshot", false).put("enable_wakeup_cloud_check", false).put("word", "").toString())
                                if (!session.requestStarted) {
                                    hooks.logger.info("Breeno ASR startup uncorrelated: frames=${session.frames.get()}")
                                    end(session, control, "小布识别引擎尚未就绪，请重新唤醒小布后重试", true)
                                    return
                                }
                                reply(session, BreenoAsrWire.LISTENING)
                                hooks.logger.info("Breeno ASR started")
                                handler.postDelayed({ if (active === session) hooks.logger.info("Breeno ASR audio flow: frames=${session.frames.get()}") }, 5_000)
                                handler.postDelayed({ if (active === session) end(session, control, "小布识别超时，请重试", true) }, 65_000)
                            } catch (failure: Exception) {
                                end(session, control, "小布识别启动失败，请重新唤醒小布后重试", true)
                            } finally { startingRoom = null }
                        }
                        BreenoAsrWire.FINISH -> active?.takeIf { it.id == id }?.let { session ->
                            session.finishRequested = true
                            runCatching { finish.invoke(session.processor) }.onFailure {
                                end(session, control, "小布识别结束失败，请重试", true)
                            }
                            handler.postDelayed({ if (active === session) end(session, control, "小布识别超时，请重试", true) }, 12_000)
                        }
                        BreenoAsrWire.CANCEL -> active?.takeIf { it.id == id }?.let { end(it, control, null, true) }
                    }
                }
            }
            context.registerReceiver(receiver, IntentFilter(BreenoAsrWire.ACTION), BreenoAsrWire.PERMISSION,
                handler, Context.RECEIVER_EXPORTED)
            registered = true
            hooks.logger.info("Breeno ASR bridge ready")
        }
        handler.post { register(0) }
    }

    private fun reply(session: Session, code: Int, text: String? = null, error: String? = null) {
        runCatching { session.receiver.send(code, Bundle().apply {
            putString("id", session.id); text?.let { putString("text", it) }; error?.let { putString("error", it) }
        }) }
    }

    private fun end(session: Session, control: Method, error: String?, cancel: Boolean) {
        if (active !== session) return
        active = null
        if (cancel) runCatching { control.invoke(session.processor, "cancel", "{}") }
        if (error != null) reply(session, BreenoAsrWire.ERROR, error = error)
    }
}
