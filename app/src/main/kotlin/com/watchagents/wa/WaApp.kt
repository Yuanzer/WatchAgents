package com.watchagents.wa

import android.app.Application
import com.watchagents.wa.agent.skill.SkillRuntime
import com.watchagents.wa.config.Prefs
import com.watchagents.wa.core.AndroidAgentLogger
import com.watchagents.wa.core.safeLogType
import com.watchagents.wa.data.datastore.SettingsDataStore
import com.watchagents.wa.data.repository.AgentMemoryRepository
import com.watchagents.wa.data.repository.ProviderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Watch4 精简版 Application（无 Xposed/LSPosed/root 依赖）。
 *
 * 与手机版差异：不注册 XposedServiceHelper（无 root 环境不会有 framework 推送 binder），
 * 不做跨进程 RemotePreferences 同步，不调用 predictive-back 隐藏 API hack；
 * 只初始化本地能力（本地配置、记忆、Provider、技能索引预热）。
 */
class WaApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Prefs.initLocal(this)
        // 进程名走 API 分支（Application.getProcessName() 在 API 26/27 不存在，直接调会启动即崩）
        if (!AppProcessPolicy.shouldInitializeFullRuntime(AppProcessPolicy.currentProcessName(), packageName)) {
            return
        }
        SettingsDataStore.init(this)
        AgentMemoryRepository.init(this)
        ProviderRepository.init(this)
        applicationScope.launch {
            runCatching {
                SkillRuntime.createIndexService(this@WaApp).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}"
                )
            }
        }
    }
}
