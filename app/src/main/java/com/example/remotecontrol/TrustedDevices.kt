package com.example.remotecontrol

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

// قائمة الأذونات المحلية القديمة، ولا يستخدمها الكود الجديد
// يبقى الملف موجوداً فقط لتجنب أخطاء الاستيراد
object TrustedDevices {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("trusted_devices", Context.MODE_PRIVATE)
        }
    }

    fun selfId(): String = Random.nextInt(100000, 999999).toString()
}
