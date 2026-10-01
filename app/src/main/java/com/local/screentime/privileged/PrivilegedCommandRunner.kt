package com.local.screentime.privileged

import com.topjohnwu.superuser.Shell

/**
 * 高权限命令执行抽象（P1-3）。
 *
 * 动机：UsageRepository 之前直接散落 `Shell.cmd(...)`（libsu/root 硬绑定）。抽成接口后，
 * root 语义只在 RootCommandRunner 一处；后续接 Shizuku / DUMP 权限后端（参考
 * mlm-games/BatStats 的 ShizukuBridge / ShellRunner 分层思路，未复制其代码）时，
 * 仓库与解析层零改动。
 *
 * 第一阶段行为约束：RootCommandRunner 与旧直用 libsu 的路径**逐字节等价**——
 * `exec` 返回 stdout、命令失败不抛异常（libsu 语义），root 可用性用
 * `Shell.getShell().isRoot`（同步等待 su 握手完成，v0.16.10 的冷启动修复语义）。
 */
interface PrivilegedCommandRunner {
    /** 同步等待 shell 通道建立后返回 root 可用性；失败（无 su/未授权）返回 false */
    suspend fun isRootAvailable(): Boolean

    /** 执行命令并返回 stdout；不抛异常（libsu：命令级失败体现在输出/退出码，不由这里抛） */
    suspend fun exec(cmd: String): List<String>
}

/** libsu root 后端：当前生产路径的直移封装，行为与旧 Shell.cmd 用法完全一致 */
class RootCommandRunner : PrivilegedCommandRunner {
    override suspend fun isRootAvailable(): Boolean = try {
        Shell.getShell().isRoot
    } catch (e: Exception) {
        false
    }

    override suspend fun exec(cmd: String): List<String> = Shell.cmd(cmd).exec().out
}
