package com.easy.linemanprint

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.concurrent.Executors

class OrderAccessibilityService : AccessibilityService() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastSig = ""

    private val lmfMarker = Regex("LMF-\\d{6}")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: ""
        if (pkg == packageName) return   // ข้ามแอพตัวเอง

        val nodes = ArrayList<NodeText>()
        try { collect(root, nodes) } catch (_: Exception) { return }

        val joined = nodes.joinToString("|") { it.text }
        // เป็นหน้ารายละเอียดออเดอร์หรือไม่
        val isDetail = joined.contains("รหัสใบสั่งซื้อ") ||
                joined.contains("รายการสั่งซื้อ") ||
                lmfMarker.containsMatchIn(joined)
        if (!isDetail) return

        val order = OrderParser.parse(nodes)
        val sig = order.lmfCode.ifEmpty { order.orderNo }
        if (sig.isEmpty()) return
        if (sig == lastSig) return                       // กันยิงซ้ำรัวๆ
        if (Prefs.isPrinted(this, sig)) { lastSig = sig; return }
        lastSig = sig
        LastOrder.value = order

        toast("พบออเดอร์ #${order.orderNo} กำลังพิมพ์...")  // ตัวบอกสถานะ

        worker.execute {
            try {
                val bmp = ReceiptRenderer.render(applicationContext, order)
                BluetoothPrinter.printBitmap(applicationContext, bmp)
                Prefs.markPrinted(applicationContext, sig)
                beep(order.parsedOk)
            } catch (e: Exception) {
                lastSig = ""                              // พิมพ์ไม่ออก -> ลองใหม่ได้
                beep(false)
                toast("พิมพ์ไม่ออก: ${e.message}")
            }
        }
    }

    private fun collect(node: AccessibilityNodeInfo?, out: ArrayList<NodeText>) {
        if (node == null) return
        val txt = (node.text ?: node.contentDescription)?.toString()?.trim()
        if (!txt.isNullOrEmpty()) {
            val r = Rect()
            node.getBoundsInScreen(r)
            out.add(NodeText(txt, r.left, r.top))
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out)
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun beep(success: Boolean) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            if (success) tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            else tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
            worker.execute { Thread.sleep(1200); tg.release() }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}
}
