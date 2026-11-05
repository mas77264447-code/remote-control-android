package com.example.remotecontrol

import android.content.Context
import android.content.SharedPreferences

// يحفظ اسم المستخدم الذي سجّل الدخول على هذا الهاتف
// الحساب والأذونات محفوظة على الخادم، وهذا الملف يحفظ الجلسة فقط
object Session {
    private const val PREFS = "session"
    private const val KEY_USER = "username"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun username(): String? = prefs?.getString(KEY_USER, null)

    fun save(username: String) {
        prefs?.edit()?.putString(KEY_USER, username)?.apply()
    }

    fun clear() {
        prefs?.edit()?.remove(KEY_USER)?.apply()
    }
}
