package io.github.mangi.eta.agent.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.IBinder
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.UUID

/** Text goes only to this per-request Binder callback, never to an exported result broadcast. */
internal class BreenoSpeechInput(
    private val context: Context,
    private val onListening: () -> Unit,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (SpeechFailure) -> Unit,
    private val automaticEndpoint: Boolean = false,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var id = UUID.randomUUID().toString()
    private val transcript = BreenoSpeechTranscript()
    private var finishRequested = false
    private var segmentRunning = false
    private var listeningAnnounced = false
    private var startupRetried = false
    private val restart = Runnable {
        if (active && !finishRequested) {
            id = UUID.randomUUID().toString()
            sent = false
            sendStart()
        }
    }
    private var active = false
    private var bound = false
    private var sent = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { sendStart() }
        override fun onNullBinding(name: ComponentName) { sendStart() }
        override fun onServiceDisconnected(name: ComponentName) {
            fail(SpeechFailure(SpeechErrorCode.AUDIO, "小布识别进程已退出，请重新唤醒后重试"))
        }
        override fun onBindingDied(name: ComponentName) {
            fail(SpeechFailure(SpeechErrorCode.AUDIO, "小布识别服务连接已断开，请重试"))
        }
    }
    private val timeout = Runnable {
        fail(SpeechFailure(SpeechErrorCode.TIMEOUT, "小布识别超时，请重试"))
    }
    private val finishTimeout = Runnable {
        if (active && finishRequested) {
            AndroidAgentLogger.info("Breeno ASR client finish fallback: chars=${transcript.text.length}")
            complete(cancelNative = true)
        }
    }
    private val retryStartup = Runnable {
        if (active && !finishRequested) {
            id = UUID.randomUUID().toString()
            sent = false
            bindNativeService()
        }
    }
    private val receiver = object : ResultReceiver(handler) {
        override fun onReceiveResult(code: Int, data: Bundle?) {
            if (!active || data?.getString("id") != id) return
            if (code != BreenoAsrWire.PARTIAL) AndroidAgentLogger.info("Breeno ASR client callback: code=$code")
            when (code) {
                BreenoAsrWire.LISTENING -> if (!listeningAnnounced) {
                    listeningAnnounced = true
                    onListening()
                }
                BreenoAsrWire.PARTIAL -> {
                    if (transcript.partial(id, data.getString("text").orEmpty())) onPartial(transcript.text)
                }
                BreenoAsrWire.FINAL -> {
                    if (!transcript.final(id, data.getString("text").orEmpty())) return
                    segmentRunning = false
                    if (automaticEndpoint || finishRequested) complete()
                    else {
                        onPartial(transcript.text)
                        // Native FINAL ends one utterance. Continue with a fresh ID under the same binding.
                        handler.postDelayed(restart, 200)
                    }
                }
                BreenoAsrWire.ERROR -> {
                    if (!finishRequested && !startupRetried && !listeningAnnounced && transcript.text.isBlank()
                        && data.getString(BreenoAsrWire.ERROR_REASON) == BreenoAsrWire.ENGINE_NOT_READY) {
                        startupRetried = true
                        segmentRunning = false
                        // Retire this attempt immediately; queued callbacks must not fail
                        // or restart the pending replacement request.
                        id = UUID.randomUUID().toString()
                        releaseBinding()
                        AndroidAgentLogger.info("Breeno ASR client retrying engine initialization")
                        handler.postDelayed(retryStartup, 1_500)
                    } else if (finishRequested) complete(cancelNative = true)
                    else fail(SpeechFailure(SpeechErrorCode.CONFIGURATION,
                        data.getString("error") ?: "小布识别不可用，请重新唤醒小布后重试"))
                }
            }
        }
    }

    fun start() {
        active = true
        handler.postDelayed(timeout, 65_000)
        bindNativeService()
    }

    private fun bindNativeService() {
        try {
            // Hold the native service for exactly this input. No ENGINE_API/UI action is sent.
            bound = context.bindService(Intent(BreenoAsrWire.ACTION).setComponent(ComponentName(
                BreenoAsrWire.PACKAGE, "com.heytap.speechassist.core.SpeechService")), connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT or Context.BIND_INCLUDE_CAPABILITIES)
            if (!bound) fail(SpeechFailure(SpeechErrorCode.CONFIGURATION, "无法连接小布识别服务，请先唤醒小布"))
        } catch (error: RuntimeException) {
            fail(SpeechFailure(SpeechErrorCode.CONFIGURATION, "无法连接小布识别服务，请先唤醒小布"))
        }
    }

    private fun sendStart() {
        if (!active || sent) return
        sent = true
        segmentRunning = true
        transcript.beginSegment(id)
        AndroidAgentLogger.info("Breeno ASR client service bound")
        val requestId = id
        val intent = intent(BreenoAsrWire.START).putExtra("receiver", receiver)
        context.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (active && id == requestId && segmentRunning && resultCode != BreenoAsrWire.ACK) {
                    fail(SpeechFailure(SpeechErrorCode.CONFIGURATION,
                        "请先唤醒一次小布以初始化识别服务，并确认 Eta 模块已作用于小布"))
                }
            }
        }, handler, 0, null, null)
    }

    fun finish() {
        if (!active || finishRequested) return
        finishRequested = true
        transcript.finish()
        AndroidAgentLogger.info("Breeno ASR client finishing: segmentRunning=$segmentRunning chars=${transcript.text.length}")
        handler.removeCallbacks(restart)
        handler.removeCallbacks(retryStartup)
        if (!segmentRunning) { complete(); return }
        handler.removeCallbacks(timeout)
        // A freshly restarted silent segment may never emit FINAL. Keep visible text and
        // bound the wait for the last utterance instead of leaving the UI recognizing.
        handler.postDelayed(finishTimeout, 1_500)
        context.sendBroadcast(intent(BreenoAsrWire.FINISH))
    }

    fun cancel() {
        if (active) {
            active = false
            context.sendBroadcast(intent(BreenoAsrWire.CANCEL))
        }
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(finishTimeout)
        handler.removeCallbacks(restart)
        handler.removeCallbacks(retryStartup)
        transcript.close()
        releaseBinding()
    }

    private fun releaseBinding() {
        if (bound) {
            bound = false
            runCatching { context.unbindService(connection) }
        }
    }

    private fun complete(cancelNative: Boolean = false) {
        if (!active) return
        active = false
        if (cancelNative) context.sendBroadcast(intent(BreenoAsrWire.CANCEL))
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(finishTimeout)
        handler.removeCallbacks(restart)
        handler.removeCallbacks(retryStartup)
        transcript.close()
        releaseBinding()
        val text = transcript.text
        if (text.isBlank()) onError(SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到语音，请重试"))
        else onResult(text)
    }

    private fun fail(failure: SpeechFailure) {
        if (!active) return
        cancel()
        onError(failure)
    }

    private fun intent(command: String) = Intent(BreenoAsrWire.ACTION)
        .setPackage(BreenoAsrWire.PACKAGE)
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        .putExtra("command", command).putExtra("id", id)
}

/** Per-input transcript; a segment FINAL replaces its preview rather than duplicating it. */
internal class BreenoSpeechTranscript {
    private val completed = mutableListOf<String>()
    private var segmentId: String? = null
    private var preview = ""
    private var running = false
    private var finishing = false
    private var closed = false
    val text: String get() = (completed + preview.trim().takeIf { it.isNotEmpty() }.orEmpty())
        .filter { it.isNotBlank() }.joinToString("\n")

    fun beginSegment(id: String): Boolean {
        if (closed || finishing) return false
        segmentId = id
        preview = ""
        running = true
        return true
    }

    fun partial(id: String, text: String): Boolean {
        if (closed || !running || segmentId != id) return false
        preview = text
        return true
    }

    fun final(id: String, text: String): Boolean {
        if (closed || !running || segmentId != id) return false
        val result = text.trim().ifBlank { preview.trim() }
        if (result.isNotEmpty()) completed.add(result)
        preview = ""
        running = false
        return true
    }

    fun finish() { finishing = true }
    fun close() { closed = true }
}
