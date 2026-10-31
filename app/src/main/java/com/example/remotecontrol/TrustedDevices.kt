package com.example.remotecontrol

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

object TrustedDevices {
    private const val PREFS = "trusted_devices"
    private const val KEY = "ids"
    private const val KEY_SELF = "self_id"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    // معرّف الجهاز ثابت: يُولَّد مرة واحدة ويُحفظ
    fun selfId(): String {
        val p = prefs ?: return Random.nextInt(100000, 999999).toString()
        val existing = p.getString(KEY_SELF, null)
        if (existing != null) return existing
        val fresh = Random.nextInt(100000, 999999).toString()
        p.edit().putString(KEY_SELF, fresh).apply()
        return fresh
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
