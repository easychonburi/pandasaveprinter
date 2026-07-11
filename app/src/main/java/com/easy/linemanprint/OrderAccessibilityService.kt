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

    // Grab มักส่ง Accessibility event ก่อนที่ส่วนรายการอาหารจะวาดเสร็จ
    // จึงต้องรอและอ่าน root ใหม่ แทนการใช้ node ชุดแรกทันที
    private val grabReadDelayMs = 1_200L
    private val grabRetryDelayMs = 800L
    private val grabMaxAttempts = 3
    private var pendingGrabCode = ""
    private var grabReadScheduled = false

    private val lmfMarker = Regex("LMF-\\d{6}")
    private val grabMarker = Regex("GF-\\d{3,}")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: ""
        if (pkg == packageName) return   // ข้ามแอพตัวเอง

        val nodes = ArrayList<NodeText>()
        try { collect(root, nodes) } catch (_: Exception) { return }

        val joined = nodes.joinToString("|") { it.text }
        // เลือกตัวอ่านตามแอปที่เจอบนหน้าจอ + เช็คสวิตช์ว่าเปิดแอปนั้นไว้ไหม
        when {
            // LINE MAN (WMA) — ทำงานเฉพาะเมื่อเปิดสวิตช์ไว้
            Prefs.isLinemanOn(this) && (
                joined.contains("รหัสใบสั่งซื้อ") ||
                joined.contains("รายการสั่งซื้อ") ||
                lmfMarker.containsMatchIn(joined)
            ) -> processOrder(OrderParser.parse(nodes))

            // Grab: อย่า parse ทันที เพราะตอน event แรกมักยังไม่มีรายการอาหาร
            Prefs.isGrabOn(this) &&
            grabMarker.containsMatchIn(joined) &&
            joined.contains("รวมทั้งหมด") -> {
                val grabCode = grabMarker.find(joined)?.value ?: return
                scheduleGrabRead(grabCode)
            }

            else -> return // ไม่ใช่หน้าออเดอร์ที่เรารู้จัก / หรือปิดสวิตช์ไว้
        }
    }

    // จองการอ่าน Grab ครั้งเดียวต่อหนึ่งออเดอร์ เพื่อไม่ให้ content-change event
    // ที่ยิงถี่ ๆ ทำให้เลื่อนเวลารอออกไปเรื่อย ๆ
    private fun scheduleGrabRead(grabCode: String) {
        if (grabCode == pendingGrabCode && grabReadScheduled) return

        pendingGrabCode = grabCode
        grabReadScheduled = true
        main.postDelayed({ readGrabWhenReady(grabCode, 1) }, grabReadDelayMs)
    }

    private fun readGrabWhenReady(expectedCode: String, attempt: Int) {
        // ผู้ใช้อาจออกจากหน้าออเดอร์ไปแล้วระหว่างที่รอ
        if (expectedCode != pendingGrabCode) return

        val root = rootInActiveWindow
        if (root == null) {
            retryGrabRead(expectedCode, attempt)
            return
        }

        val nodes = ArrayList<NodeText>()
        try { collect(root, nodes) } catch (_: Exception) {
            retryGrabRead(expectedCode, attempt)
            return
        }

        val joined = nodes.joinToString("|") { it.text }
        if (!joined.contains(expectedCode)) {
            // หน้าเปลี่ยนแล้ว จบการรอโดยไม่พิมพ์และไม่ mark ว่าออเดอร์นี้เสร็จ
            pendingGrabCode = ""
            grabReadScheduled = false
            return
        }

        if (!hasReadableGrabItem(nodes) && attempt < grabMaxAttempts) {
            retryGrabRead(expectedCode, attempt)
            return
        }

        pendingGrabCode = ""
        grabReadScheduled = false
        toast("Grab: อ่านรอบ $attempt พบ ${nodes.size} nodes")
        processOrder(GrabOrderParser.parse(nodes))
    }

    private fun retryGrabRead(grabCode: String, attempt: Int) {
        if (grabCode != pendingGrabCode) return
        main.postDelayed({ readGrabWhenReady(grabCode, attempt + 1) }, grabRetryDelayMs)
    }

    // รองรับทั้ง node ปกติและ node ที่ collector ใส่ T:/D: ไว้ด้านหน้า
    private fun hasReadableGrabItem(nodes: List<NodeText>): Boolean = nodes.any { node ->
        val text = node.text.removePrefix("T:").removePrefix("D:").trim()
        Regex("^\\d+\\s*[xX×]\\s*.+").containsMatchIn(text)
    }

    private fun processOrder(order: Order) {
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
