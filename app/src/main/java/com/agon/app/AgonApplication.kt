package com.agon.app

import android.app.Application
import com.agon.app.debug.AppLogger
import com.agon.app.debug.CrashHandler

class AgonApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this, defaultHandler))
        AppLogger.i("App", "MariAsh Stream started — session ${AppLogger.sessionId}")
    }
}
