package com.watchagents.wa

import android.app.Application
import android.os.Build
import java.io.File

internal object AppProcessPolicy {
    /**
     * 只有主进程才初始化完整 Runtime。
     *
     * 进程名取不到时（见 [currentProcessName] 的兜底分支）按"是主进程"处理：
     * 本 App 没有声明任何 `android:process`，拿不到名字时保守初始化，避免功能整体不可用。
     */
    fun shouldInitializeFullRuntime(processName: String?, packageName: String): Boolean =
        processName.isNullOrBlank() || processName == packageName

    /**
     * 当前进程名。
     *
     * `Application.getProcessName()` 是 **API 28** 才有的静态方法，
     * 在 Android 8.0/8.1（API 26/27，OPPO Watch、小米手表等一大批国产安卓手表）上调用
     * 会抛 `NoSuchMethodError` —— 且调用点在 Application.onCreate，等于一启动就崩。
     * 因此这里按 API 级别分支，26/27 退回读自己的 `/proc/self/cmdline`。
     */
    fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }.getOrNull()
        } else {
            readProcessNameFromProc()
        }

    /** API 26/27 兜底：`/proc/self/cmdline` 是空字符分隔的进程名（读自己的 cmdline 无需权限）。 */
    private fun readProcessNameFromProc(): String? = runCatching {
        File("/proc/self/cmdline")
            .readText()
            .substringBefore('\u0000')
            .trim()
            .ifBlank { null }
    }.getOrNull()
}
