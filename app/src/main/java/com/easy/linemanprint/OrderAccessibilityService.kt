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
    private val grabMarker = Regex("GF-\\d{3,}")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: ""
        if (pkg == packageName) return   // ข้ามแอพตัวเอง

        val nodes = ArrayList<NodeText>()
        try { collect(root, nodes) } catch (_: Exception) { return }

        val joined = nodes.joinToString("|") { it.text }
        // debug v3: log จำนวน node ที่เจอทั้งหมด (ดูใน toast)
        if (Prefs.isGrabOn(this) && grabMarker.containsMatchIn(joined)) {
            toast("Grab: เจอ ${nodes.size} nodes")
        }

        // เลือกตัวอ่านตามแอปที่เจอบนหน้าจอ + เช็คสวิตช์ว่าเปิดแอปนั้นไว้ไหม
        val order = when {
            // LINE MAN (WMA) — ทำงานเฉพาะเมื่อเปิดสวิตช์ไว้
            Prefs.isLinemanOn(this) && (
                joined.contains("รหัสใบสั่งซื้อ") ||
                joined.contains("รายการสั่งซื้อ") ||
                lmfMarker.containsMatchIn(joined)
            ) -> OrderParser.parse(nodes)

            // Grab (GrabFood) — เจอเลข GF-xxx + มียอดรวม และเปิดสวิตช์ไว้
            Prefs.isGrabOn(this) &&
            grabMarker.containsMatchIn(joined) &&
            joined.contains("รวมทั้งหมด") -> GrabOrderParser.parse(nodes)

            else -> return   // ไม่ใช่หน้าออเดอร์ที่เรารู้จัก / หรือปิดสวิตช์ไว้
        }

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
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        val r = Rect()
        node.getBoundsInScreen(r)

        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()

        // ดึงทั้ง text และ contentDescription แยกกัน (เผื่อคนละค่า)
        if (!t.isNullOrEmpty()) out.add(NodeText("T:$t", r.left, r.top, cls))
        if (!d.isNullOrEmpty() && d != t) out.add(NodeText("D:$d", r.left, r.top, cls))

        // มาร์ก scroll container (รายการอาหารมักอยู่ในนี้) แม้ไม่มีตัวหนังสือ
        if (cls.contains("Recycler", true) || cls.contains("ScrollView", true) ||
            cls.contains("ListView", true)) {
            out.add(NodeText("[[${cls} childs=${node.childCount}]]", r.left, r.top, cls))
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
