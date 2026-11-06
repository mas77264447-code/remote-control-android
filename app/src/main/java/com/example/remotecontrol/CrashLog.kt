package com.example.remotecontrol

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashLog {
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val file = File(context.applicationContext.filesDir, "last_crash.txt")

        // عرض الخطأ السابق إن وُجد
        if (file.exists()) {
            ErrorBus.last = file.readText().lineSequence().take(4).joinToString("\n")
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file.writeText(sw.toString().take(4000))
            } catch (_: Exception) {
            }
            previous?.uncaughtException(t, e)
        }
    }
}
