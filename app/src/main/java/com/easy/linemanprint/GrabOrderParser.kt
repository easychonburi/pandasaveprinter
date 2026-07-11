package com.easy.linemanprint

import kotlin.math.abs

// ตัวอ่านออเดอร์ของ Grab (GrabFood merchant)
object GrabOrderParser {

    private val itemRegex = Regex("^(\\d+)\\s*[xX×]\\s*(.+)$")
    private val priceRegex = Regex("(-?\\d{1,3}(?:,\\d{3})*\\.\\d{2})\\s*$")
    private val gfRegex = Regex("GF-\\d+")

    // เจอคำพวกนี้ = จบส่วนรายการอาหารแล้ว
    private val STOP_WORDS = listOf(
        "ค่าอาหาร", "รวมภาษี", "รวมทั้งหมด", "คนขับ",
        "แก้ไขคำสั่งซื้อ", "รหัสการจอง", "ยกเลิกคำสั่งซื้อ", "รายงานปัญหา"
    )

    // ข้อความกวนใจของ Grab ที่ต้องข้าม (ไม่ใช่ตัวเลือกเมนู)
    private val JUNK = listOf("หากไม่มี", "ให้ติดต่อลูกค้า", "สินค้าทดแทน", "โฆษณา")

    private fun buildLines(nodes: List<NodeText>): List<String> {
        if (nodes.isEmpty()) return emptyList()
        val sorted = nodes.sortedWith(compareBy({ it.top }, { it.left }))
        val rows = ArrayList<ArrayList<NodeText>>()
        for (n in sorted) {
            val last = rows.lastOrNull()
            if (last != null && abs(last.first().top - n.top) <= 24) last.add(n)
            else rows.add(arrayListOf(n))
        }
        return rows.map { row ->
            row.sortedBy { it.left }.joinToString(" ") { it.text.trim() }.trim()
        }.filter { it.isNotBlank() }
    }

    private fun trailingPrice(s: String): Pair<String, String> {
        val m = priceRegex.find(s) ?: return Pair(s.trim(), "")
        val name = s.removeRange(m.range).trim()
        return Pair(name, m.groupValues[1])
    }

    private fun amountFrom(lines: List<String>, keyword: String): String {
        val l = lines.firstOrNull { it.contains(keyword) } ?: return ""
        return priceRegex.find(l)?.groupValues?.get(1) ?: ""
    }

    fun parse(nodes: List<NodeText>): Order {
        val lines = buildLines(nodes)
        val all = lines.joinToString("\n")

        val gf = gfRegex.find(all)?.value ?: ""
        val orderNo = gf.removePrefix("GF-")   // ตัวเลขไว้โชว์ใหญ่ๆ

        val custLine = lines.firstOrNull { it.contains("รายการสำหรับ") } ?: ""
        val isNew = all.contains("ลูกค้าใหม่")
        val customer = custLine.substringAfter("รายการสำหรับ", "")
            .replace("โฆษณา", "").replace("ลูกค้าใหม่", "").replace("ลูกค้าเก่า", "").trim()

        val note = lines.firstOrNull {
            it.contains("ช้อนส้อม") || it.contains("ไม่รับช้อน") || it.contains("หมายเหตุ")
        }?.replace("✕", "")?.replace("✗", "")?.replace("X", "")?.trim() ?: ""

        val items = ArrayList<OrderItem>()
        for (line in lines) {
            if (STOP_WORDS.any { line.contains(it) }) {
                if (items.isNotEmpty()) break else continue
            }
            if (JUNK.any { line.contains(it) }) continue
            val m = itemRegex.find(line)
            if (m != null) {
                val qty = m.groupValues[1].toIntOrNull() ?: 1
                val (name, price) = trailingPrice(m.groupValues[2])
                items.add(OrderItem(qty, name, price))
            } else if (items.isNotEmpty()) {
                if (line.contains("รายการสำหรับ") || line.contains("ลูกค้า") ||
                    line.contains(gf)) continue
                val (opt, _) = trailingPrice(line)     // ตัดราคา 0.00 / +10.00 ทิ้ง
                if (opt.isNotEmpty() && opt.length <= 60) {
                    val trimmed = opt.trim().trim('\'', '"', ' ')
                    val isNote = opt.trim().startsWith("'") || opt.trim().startsWith("\"")
                    // โน้ตลูกค้า (ข้อความในเครื่องหมายคำพูด) -> ใส่ • ให้พิมพ์ตัวเด่น
                    if (isNote && trimmed.isNotEmpty()) items.last().options.add("• $trimmed")
                    else items.last().options.add(opt)
                }
            }
        }

        val subtotal = amountFrom(lines, "ค่าอาหาร")
        val total = amountFrom(lines, "รวมทั้งหมด")
        val discount = amountFrom(lines, "ส่วนลด")     // มีก็เอา ไม่มีก็ปล่อยว่าง
        val net = if (total.isNotEmpty()) total else subtotal

        val ok = orderNo.isNotEmpty() && items.isNotEmpty()

        return Order(
            orderNo = orderNo,
            lmfCode = gf,          // ใช้ GF-xxx กันพิมพ์ซ้ำ + อ้างอิงท้ายใบ
            branch = "",
            dateTime = "",
            customer = customer,
            isNewCustomer = isNew,
            items = items,
            note = note,
            payment = "",
            subtotal = subtotal,
            discount = discount,
            net = net,
            parsedOk = ok,
            platform = "GRAB"
        )
    }
}
