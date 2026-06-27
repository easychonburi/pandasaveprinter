package com.easy.linemanprint

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

class OrderAccessibilityService : AccessibilityService() {

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var lastSig = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val nodes = ArrayList<NodeText>()
        try {
            collect(root, nodes)
        } catch (_: Exception) { return }

        val joined = nodes.joinToString("|") { it.text }
        // อ่านเฉพาะ "หน้ารายละเอียดออเดอร์" เท่านั้น
        if (!joined.contains("รหัสใบสั่งซื้อ") && !joined.contains("รายการสั่งซื้อ")) return

        val order = OrderParser.parse(nodes)
        val sig = order.lmfCode.ifEmpty { order.orderNo }
        if (sig.isEmpty()) return
        if (sig == lastSig) return                     // กันยิงซ้ำรัวๆ
        if (Prefs.isPrinted(this, sig)) { lastSig = sig; return }
        lastSig = sig
        LastOrder.value = order

        worker.execute {
            try {
                val bmp = ReceiptRenderer.render(applicationContext, order)
                BluetoothPrinter.printBitmap(applicationContext, bmp)
                Prefs.markPrinted(applicationContext, sig)
                beep(order.parsedOk)
            } catch (e: Exception) {
                lastSig = ""        // พิมพ์ไม่ออก -> ปลดล็อกให้ลองใหม่ได้
                beep(false)         // เตือนยาว = มีปัญหา
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
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out)
        }
    }

    private fun beep(success: Boolean) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            if (success) {
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            } else {
                // เตือนยาว 3 ครั้ง = อ่านไม่ครบ/พิมพ์ไม่ออก
                tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
            }
            worker.execute { Thread.sleep(1200); tg.release() }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}
}
