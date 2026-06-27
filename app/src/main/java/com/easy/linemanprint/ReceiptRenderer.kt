package com.easy.linemanprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

// วาดใบเสร็จเป็น "รูปภาพ" เพื่อให้ภาษาไทยไม่เพี้ยน (กว้าง 384px = กระดาษ 58 มม.)
object ReceiptRenderer {

    private const val WIDTH = 384
    private const val PAD = 4

    // ===== ข้อความท้ายใบ (แก้ตรงนี้ได้เลยถ้าอยากเปลี่ยนคำ) =====
    private const val CTA_LINE1 = "สแกนเพื่อแอดไลน์"
    private const val CTA_LINE2 = "อัพเดทโปรโมชั่นก่อนใคร"

    private val pNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 27f; typeface = Typeface.DEFAULT
    }
    private val pBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 34f; typeface = Typeface.DEFAULT_BOLD
    }
    private val pHeader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 36f; typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 24f; typeface = Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
    }
    // เลขออเดอร์ตัวใหญ่สุด
    private val pHuge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 110f; typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val pCta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 26f; typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val pSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 21f; typeface = Typeface.DEFAULT
    }
    private val pImg = Paint().apply { isAntiAlias = false; isFilterBitmap = false }

    // องค์ประกอบในใบ: เป็นข้อความ หรือ รูป
    private sealed class El
    private data class TextEl(val text: String, val paint: Paint, val center: Boolean = false) : El()
    private data class ImgEl(val bmp: Bitmap) : El()

    private fun wrap(text: String, paint: Paint, maxW: Int): List<String> {
        if (text.isBlank()) return listOf("")
        val words = text.split(" ")
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (w in words) {
            val test = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(test) <= maxW) cur = StringBuilder(test)
            else {
                if (cur.isNotEmpty()) out.add(cur.toString())
                if (paint.measureText(w) > maxW) {
                    var chunk = StringBuilder()
                    for (ch in w) {
                        if (paint.measureText(chunk.toString() + ch) > maxW) {
                            out.add(chunk.toString()); chunk = StringBuilder()
                        }
                        chunk.append(ch)
                    }
                    cur = chunk
                } else cur = StringBuilder(w)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun divider() = TextEl("--------------------------------", pSmall)

    // ลดขนาดฟอนต์ถ้าข้อความกว้างเกินกระดาษ (ใช้กับเลขออเดอร์ตัวใหญ่)
    private fun fit(text: String, base: Paint, maxW: Int): Paint {
        if (base.measureText(text) <= maxW) return base
        val p = Paint(base)
        while (p.textSize > 30f && p.measureText(text) > maxW) p.textSize -= 4f
        return p
    }

    fun render(context: Context, order: Order): Bitmap =
        if (order.parsedOk) renderFull(context, order) else renderFallback(order)

    private fun loadQr(context: Context): Bitmap? = try {
        BitmapFactory.decodeResource(context.resources, R.drawable.line_qr)
    } catch (_: Exception) { null }

    private fun renderFull(context: Context, o: Order): Bitmap {
        val els = ArrayList<El>()
        // หัวใบ
        els.add(TextEl("EASY หมี่ไก่ฉีก", pHeader, true))
        if (o.branch.isNotEmpty()) els.add(TextEl("สาขา ${o.branch}", pSub, true))
        els.add(TextEl("LINE MAN", pSub, true))
        els.add(divider())
        // เลขออเดอร์ตัวใหญ่สุด
        val tag = "#${o.orderNo}"
        els.add(TextEl(tag, fit(tag, pHuge, WIDTH - 2 * PAD), true))
        els.add(divider())
        // รายละเอียดออเดอร์
        if (o.lmfCode.isNotEmpty()) els.add(TextEl(o.lmfCode, pSmall))
        if (o.dateTime.isNotEmpty()) els.add(TextEl(o.dateTime, pSmall))
        els.add(divider())
        for (it in o.items) {
            for (w in wrap("${it.qty}x ${it.name}", pBold, WIDTH - 2 * PAD)) els.add(TextEl(w, pBold))
            for (op in it.options)
                for (w in wrap("  - $op", pNormal, WIDTH - 2 * PAD)) els.add(TextEl(w, pNormal))
        }
        if (o.note.isNotEmpty())
            for (w in wrap("* ${o.note}", pBold, WIDTH - 2 * PAD)) els.add(TextEl(w, pBold))
        els.add(divider())
        if (o.subtotal.isNotEmpty()) els.add(TextEl("รวม          ${o.subtotal}", pNormal))
        if (o.discount.isNotEmpty()) els.add(TextEl("ส่วนลดร้าน   ${o.discount}", pNormal))
        if (o.net.isNotEmpty()) els.add(TextEl("สุทธิ         ${o.net}", pBold))
        if (o.payment.isNotEmpty()) els.add(TextEl("ชำระ: ${o.payment}", pNormal))
        if (o.customer.isNotEmpty()) {
            val ct = if (o.isNewCustomer) "ลูกค้าใหม่ : ${o.customer}" else o.customer
            els.add(TextEl(ct, pNormal))
        }
        // ท้ายใบ: QR + ชวนแอดไลน์
        els.add(divider())
        loadQr(context)?.let { els.add(ImgEl(it)) }
        els.add(TextEl(CTA_LINE1, pCta, true))
        els.add(TextEl(CTA_LINE2, pCta, true))
        return draw(els)
    }

    private fun renderFallback(o: Order): Bitmap {
        val els = ArrayList<El>()
        els.add(TextEl("EASY หมี่ไก่ฉีก", pHeader, true))
        els.add(TextEl("*** ออเดอร์ใหม่ ***", pBold, true))
        els.add(divider())
        val tag = if (o.orderNo.isNotEmpty()) "#${o.orderNo}" else "#????"
        els.add(TextEl(tag, fit(tag, pHuge, WIDTH - 2 * PAD), true))
        els.add(divider())
        if (o.lmfCode.isNotEmpty()) els.add(TextEl(o.lmfCode, pSmall))
        if (o.branch.isNotEmpty()) els.add(TextEl("สาขา ${o.branch}", pNormal))
        els.add(divider())
        els.add(TextEl("อ่านรายละเอียดไม่ครบ", pBold))
        els.add(TextEl(">> เปิดแอพดูออเดอร์ <<", pBold))
        els.add(divider())
        return draw(els)
    }

    private fun draw(els: List<El>): Bitmap {
        var h = PAD
        for (e in els) {
            h += when (e) {
                is TextEl -> {
                    val fm = e.paint.fontMetrics
                    (fm.descent - fm.ascent + 6).toInt()
                }
                is ImgEl -> e.bmp.height + 10
            }
        }
        h += PAD + 40 // feed ท้ายบิล

        val bmp = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        var y = PAD.toFloat()
        for (e in els) {
            when (e) {
                is TextEl -> {
                    val fm = e.paint.fontMetrics
                    y += -fm.ascent
                    if (e.center) {
                        if (e.paint.textAlign == Paint.Align.CENTER)
                            c.drawText(e.text, WIDTH / 2f, y, e.paint)
                        else {
                            val w = e.paint.measureText(e.text)
                            c.drawText(e.text, (WIDTH - w) / 2f, y, e.paint)
                        }
                    } else c.drawText(e.text, PAD.toFloat(), y, e.paint)
                    y += fm.descent + 6
                }
                is ImgEl -> {
                    val left = (WIDTH - e.bmp.width) / 2f
                    c.drawBitmap(e.bmp, left, y + 5f, pImg)
                    y += e.bmp.height + 10
                }
            }
        }
        return bmp
    }
}
