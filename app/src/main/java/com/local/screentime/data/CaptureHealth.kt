package com.local.screentime.data

/**
 * 采样健康状态（P1-1）：纯函数状态机，供 UsageRepository 在 sync_state 中持久化。
 *
 * 用途：nominal 15 分钟的周期采样实际受 Doze 与系统调度影响可能明显偏长（不可保证
 * 「每 15 分钟准时」），记录实际 attempt/success/最大间隔，让 diagnosticJson 能回答
 * 「采样链路最近是否健康、丢失窗口实际有多大」，而不是靠绝对化表述。
 */
object CaptureHealth {
    data class State(
        val lastAttemptTs: Long? = null,
        val lastSuccessTs: Long? = null,
        val lastFailureCode: Int = FAIL_NONE,
        val lastFailureTs: Long? = null,
        val maxObservedCaptureGapMs: Long? = null,
    )

    const val FAIL_NONE = 0
    const val FAIL_NO_SOURCE = 1   // root 与 DUMP 权限都不可用（需用户侧动作）
    const val FAIL_SHELL = 2       // shell 执行异常（临时错误，可 retry）
    const val FAIL_TX = 3          // 差分事务异常（已整体回滚，可 retry）
    const val FAIL_NO_DATA = 4     // dump 解析为空（系统状态，等下一周期）
    const val FAIL_PARSE = 5       // parser 完整性异常（P1-2：禁止静默推进部分快照，可 retry）

    fun failCodeOf(outcome: BatteryCaptureOutcome): Int = when (outcome) {
        is BatteryCaptureOutcome.SourceUnavailable -> FAIL_NO_SOURCE
        is BatteryCaptureOutcome.Failed -> when (outcome.stage) {
            "tx" -> FAIL_TX
            "parser" -> FAIL_PARSE
            else -> FAIL_SHELL
        }
        is BatteryCaptureOutcome.NoData -> FAIL_NO_DATA
        is BatteryCaptureOutcome.Success -> FAIL_NONE
    }

    fun failLabel(code: Int): String = when (code) {
        FAIL_NO_SOURCE -> "root/DUMP 均不可用"
        FAIL_SHELL -> "shell 执行失败"
        FAIL_TX -> "差分事务失败（已回滚）"
        FAIL_NO_DATA -> "dump 解析为空"
        FAIL_PARSE -> "parser 完整性异常（本帧未提交）"
        else -> "无"
    }

    fun onAttempt(prev: State, now: Long): State = prev.copy(lastAttemptTs = now)

    /** 成功：记录 success 时刻；与上次成功之间的间隔若超过历史最大值则更新
     *  （即实际观测到的最大丢失窗口）。 */
    fun onSuccess(prev: State, now: Long): State {
        val gap = prev.lastSuccessTs?.let { now - it }
        return prev.copy(
            lastSuccessTs = now,
            lastFailureCode = FAIL_NONE,
            maxObservedCaptureGapMs = listOfNotNull(prev.maxObservedCaptureGapMs, gap).maxOrNull(),
        )
    }

    fun onFailure(prev: State, now: Long, code: Int): State = prev.copy(
        lastFailureCode = code,
        lastFailureTs = now,
    )
}
