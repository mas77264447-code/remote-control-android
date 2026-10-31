package com.example.remotecontrol

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

// الإذن المحفوظ = معرّف الجهاز + المصدر المسموح، مثل: 578558|front
object TrustedDevices {
    private const val PREFS = "trusted_devices"
    private const val KEY = "grants"
    private const val KEY_SELF = "self_id"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun selfId(): String {
        val p = prefs ?: return Random.nextInt(100000, 999999).toString()
        val existing = p.getString(KEY_SELF, null)
        if (existing != null) return existing
        val fresh = Random.nextInt(100000, 999999).toString()
        p.edit().putString(KEY_SELF, fresh).apply()
        return fresh
    }

    private fun grants(): MutableSet<String> =
        (prefs?.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()

    private fun save(set: Set<String>) {
        prefs?.edit()?.putStringSet(KEY, set)?.apply()
    }

    // هل يملك هذا الجهاز إذناً محفوظاً لهذا المصدر؟
    fun allows(id: String, src: String): Boolean = grants().contains("$id|$src")

    fun grant(id: String, src: String) {
        val g = grants()
        g.add("$id|$src")
        save(g)
    }

    fun revoke(id: String, src: String) {
        val g = grants()
        g.remove("$id|$src")
        save(g)
    }

    fun revokeAll() {
        save(emptySet())
    }

    // قائمة مرتبة للعرض: كل زوج معرّف ومصدر
    fun list(): List<Pair<String, String>> =
        grants().map { entry ->
            val parts = entry.split("|")
            parts[0] to parts[1]
        }.sortedWith(compareBy({ it.first }, { it.second }))
}
