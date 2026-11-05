package com.example.remotecontrol

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

object TrustedDevices {
    private const val PREFS = "device_identity"
    private const val KEY_SELF = "device_id"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun selfId(): String {
        val p = prefs ?: return Random.nextInt(1000, 9999).toString()
        val existing = p.getString(KEY_SELF, null)
        if (existing != null) return existing
        val fresh = Random.nextInt(1000, 9999).toString()
        p.edit().putString(KEY_SELF, fresh).apply()
        return fresh
    }
}
