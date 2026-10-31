package com.example.remotecontrol

import android.content.Context
import android.content.SharedPreferences

object TrustedDevices {
    private const val PREFS = "trusted_devices"
    private const val KEY = "ids"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun all(): Set<String> = prefs?.getStringSet(KEY, emptySet()) ?: emptySet()

    fun contains(id: String): Boolean = all().contains(id)

    fun add(id: String) {
        val updated = all().toMutableSet()
        updated.add(id)
        prefs?.edit()?.putStringSet(KEY, updated)?.apply()
    }

    fun remove(id: String) {
        val updated = all().toMutableSet()
        updated.remove(id)
        prefs?.edit()?.putStringSet(KEY, updated)?.apply()
    }
}
