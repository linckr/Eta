package io.github.mangi.eta.agent.model

/** Fixed codes only: never include model text, fact IDs or source values in diagnostics. */
internal class AgentSummaryValidation(
    val reason: String,
    val scope: String? = null,
    val missing: Set<String> = emptySet(),
    val extraCount: Int = 0,
    val factCount: Int? = null,
) : IllegalArgumentException("Invalid summary: $reason") {
    fun fieldsDescription(): String = buildString {
        append(when (scope) { "bound_patch" -> "有序增量对象"; "bound_delta" -> "区间增量"; "bound_fact" -> "区间增量事实"; "patch" -> "增量对象"; "fact" -> "增量事实"; "leaf" -> "区间对象"; "leaf_fact" -> "区间事实"; else -> "对象" })
        if (missing.isNotEmpty()) append("缺少字段：").append(missing.sorted().joinToString("、"))
        if (extraCount > 0) append("；多出字段 ").append(extraCount).append(" 个")
    }
}

internal fun summaryRequire(valid: Boolean, reason: String) {
    if (!valid) throw AgentSummaryValidation(reason)
}

internal fun summaryReason(error: Exception): String = when (error) {
    is AgentSummaryValidation -> error.reason
    is org.json.JSONException -> "field_type"
    else -> "schema"
}
