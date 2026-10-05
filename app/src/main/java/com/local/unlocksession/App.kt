package com.local.unlocksession

import android.app.Application
import com.local.unlocksession.core.SessionController
import com.local.unlocksession.data.SessionRepository
import com.local.unlocksession.diag.Diagnostics

class App : Application() {

    lateinit var diagnostics: Diagnostics
        private set
    lateinit var controller: SessionController
        private set

    override fun onCreate() {
        super.onCreate()
        diagnostics = Diagnostics.get(this)
        val repo = SessionRepository(this)
        controller = SessionController(this, repo, diagnostics)
        controller.startIfNeeded()
        diagnostics.log("INIT", "App onCreate（进程启动）")
    }
}
