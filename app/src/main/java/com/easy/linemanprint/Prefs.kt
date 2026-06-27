package com.easy.linemanprint

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    private const val FILE = "easy_print_prefs"
    private const val KEY_MAC = "printer_mac"
    private const val KEY_NAME = "printer_name"
    private const val KEY_DAY = "printed_day"
    private const val KEY_SET = "printed_set"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setPrinter(c: Context, mac: String, name: String) {
        sp(c).edit().putString(KEY_MAC, mac).putString(KEY_NAME, name).apply()
    }
    fun getPrinterMac(c: Context): String? = sp(c).getString(KEY_MAC, null)
    fun getPrinterName(c: Context): String? = sp(c).getString(KEY_NAME, null)

    private fun today(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    // กันพิมพ์ซ้ำ: เก็บรหัสที่พิมพ์แล้วของ "วันนี้" ล้างทุกวันใหม่
    @Synchronized
    fun isPrinted(c: Context, code: String): Boolean {
        val s = sp(c)
        if (s.getString(KEY_DAY, "") != today()) return false
        val set = s.getStringSet(KEY_SET, emptySet()) ?: emptySet()
        return set.contains(code)
    }

    @Synchronized
    fun markPrinted(c: Context, code: String) {
        val s = sp(c)
        val set = if (s.getString(KEY_DAY, "") == today())
            HashSet(s.getStringSet(KEY_SET, emptySet()) ?: emptySet())
        else HashSet()
        set.add(code)
        s.edit().putString(KEY_DAY, today()).putStringSet(KEY_SET, set).apply()
    }
}
