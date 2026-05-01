package com.agon.app

import android.app.Application
import com.arthenica.ffmpegkit.FFmpegKitConfig

class AgonApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Reduce FFmpegKit log verbosity in production
        FFmpegKitConfig.setLogLevel(com.arthenica.ffmpegkit.Level.AV_LOG_WARNING)
    }
}
