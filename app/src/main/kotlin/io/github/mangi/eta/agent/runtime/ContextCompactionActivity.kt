package io.github.mangi.eta.agent.runtime

import org.json.JSONObject

/** Whitelisted numbers and stages only; never persist provider deltas or history. */
internal data class ContextCompactionActivity(
    val stage: String,
    val currentSlice: Int = 0,
    val sliceElapsedMs: Long = 0,
    val contentChars: Long = 0,
    val thinkingChars: Long = 0,
    val refinement: Int = 0,
    val operation: String = "",
) {
    val displayMessage: String get() = buildString {
        if (currentSlice > 0) append("第 ").append(currentSlice).append(" 段 · ")
        if (operation == "merge") append("合并区间 · ")
        if (refinement > 0) append("精简 ").append(refinement).append("/2 · ")
        append(labels[stage] ?: "准备摘要")
        if (stage == "thinking" || stage == "output") {
            append(" · 正文 ").append(contentChars).append(" 字符 · 思考 ").append(thinkingChars).append(" 字符")
        }
        if (currentSlice > 0) append(" · 本段 ").append(sliceElapsedMs / 1000).append(" 秒")
    }

    fun encode(): JSONObject = JSONObject().put("stage", stage).put("current_slice", currentSlice)
        .put("slice_elapsed_ms", sliceElapsedMs).put("content_chars", contentChars)
        .put("thinking_chars", thinkingChars).put("refinement", refinement).put("operation", operation)

    companion object {
        private val labels = mapOf("preparing" to "准备历史", "slicing" to "准备片段",
            "resuming" to "恢复已验证断点", "connecting" to "连接模型服务",
            "uploading" to "上传摘要请求", "waiting" to "等待模型响应",
            "thinking" to "模型正在思考", "output" to "模型正在输出",
            "refining" to "精简超长摘要", "saving" to "校验完成，保存进度",
            "retrying" to "准备单次恢复请求")

        fun decode(json: JSONObject?): ContextCompactionActivity? {
            json ?: return null
            val stage = json.optString("stage").takeIf { it in labels } ?: return null
            fun number(key: String, max: Long = Long.MAX_VALUE): Long = (json.opt(key) as? Number)?.let {
                val n = it.toLong()
                n.takeIf { value -> value in 0..max && it.toDouble() == value.toDouble() }
            } ?: 0
            return ContextCompactionActivity(stage, number("current_slice", Int.MAX_VALUE.toLong()).toInt(),
                number("slice_elapsed_ms"), number("content_chars"), number("thinking_chars"), number("refinement", 2).toInt(),
                json.optString("operation").takeIf { it == "merge" }.orEmpty())
        }
    }
}
