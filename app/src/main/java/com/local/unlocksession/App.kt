package com.local.unlocksession

import android.app.Application
import com.local.unlocksession.core.AndroidEnv
import com.local.unlocksession.core.SessionController
import com.local.unlocksession.data.SessionRepository
import com.local.unlocksession.diag.Diagnostics
import com.local.unlocksession.session.SessionPanelBridge

class App : Application() {

    lateinit var diagnostics: Diagnostics
        private set
    lateinit var controller: SessionController
        private set

    override fun onCreate() {
        super.onCreate()
        diagnostics = Diagnostics.get(this)
        val repo = SessionRepository(this)
        val env = AndroidEnv(this, repo, diagnostics)
        val controller = SessionController(repo, diagnostics, env)
        // 桥需要控制器、悬浮层需要桥：控制器构造后注入，打破循环
        env.setPanelBridge(SessionPanelBridge(controller))
        this.controller = controller
        controller.startIfNeeded()
        diagnostics.log("INIT", "App onCreate（进程启动）")
    }
}
