package com.local.screentime.privileged

import com.topjohnwu.superuser.Shell

/**
 * 高权限命令执行抽象（P1-3/P1-10）。
 *
 * v0.17.1：exec 返回 [CommandResult]（stdout/stderr/exitCode/success），
 * 不再把「权限失败」「命令失败」「合法空输出」混为一类——调用方（耗电采集）据此区分
 * NO_PRIVILEGE / COMMAND_FAILED / VALID_EMPTY_OUTPUT / PARSE_FAILED。
 *
 * 后续接 Shizuku / DUMP 权限后端（参考 mlm-games/BatStats 分层思路，未复制代码）时零改动。
 */
interface PrivilegedCommandRunner {
    /** 同步等待 shell 通道建立后返回 root 可用性；失败（无 su/未授权）返回 false */
    suspend fun isRootAvailable(): Boolean

    /** 执行命令；不抛异常（命令级失败由 exitCode/stderr 表达，环境级失败如 su 不可用由 isRootAvailable 表达） */
    suspend fun exec(cmd: String): CommandResult
}

data class CommandResult(
    val stdout: List<String>,
    val stderr: List<String>,
    val exitCode: Int,
) {
    /** exit 0 = 命令成功（输出可能仍为空 = VALID_EMPTY_OUTPUT，由调用方按语义解释） */
    val success: Boolean get() = exitCode == 0

    /** stderr 首行（诊断用，可为 null） */
    val stderrFirstLine: String? get() = stderr.firstOrNull { it.isNotBlank() }?.trim()
}

/** libsu root 后端：当前生产路径的直移封装，行为与旧 Shell.cmd 用法完全一致 */
class RootCommandRunner : PrivilegedCommandRunner {
    override suspend fun isRootAvailable(): Boolean = try {
        Shell.getShell().isRoot
    } catch (e: Exception) {
        false
    }

    override suspend fun exec(cmd: String): CommandResult {
        // libsu reuses a persistent shell. A script's `exit`, exported locale or
        // helper functions must not terminate/pollute later observations.
        val r = Shell.cmd("(\n$cmd\n)").exec()
        return CommandResult(stdout = r.out, stderr = r.err, exitCode = r.code)
    }
}
