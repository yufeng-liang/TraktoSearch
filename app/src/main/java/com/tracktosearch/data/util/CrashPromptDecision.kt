package com.tracktosearch.data.util

/**
 * 崩溃上报启动决策（纯函数，便于单元测试）。
 * MainActivity 的崩溃上报对话框据此决定展示内容。
 */
object CrashPromptDecision {

    sealed interface Action {
        /** 弹授权询问对话框（用户同意后开启开关并上传） */
        data object Authorize : Action
        /** 已拒绝过上报：清理本地日志，不打扰用户 */
        data object ClearLogs : Action
        /** 已授权：启动自动上传已由 TraktSearchApp 触发，失败时弹重试 */
        data object AutoUpload : Action
        /** 无崩溃：无动作 */
        data object None : Action
    }

    fun decide(crashCount: Int, enabled: Boolean, prompted: Boolean): Action = when {
        crashCount < 1 -> Action.None
        !enabled && !prompted -> Action.Authorize
        !enabled -> Action.ClearLogs
        else -> Action.AutoUpload
    }
}
