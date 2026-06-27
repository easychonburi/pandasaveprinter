package com.easy.linemanprint

// โครงข้อมูลออเดอร์ที่อ่านได้จากหน้า WMA
data class OrderItem(
    val qty: Int,
    val name: String,
    val price: String,
    val options: MutableList<String> = mutableListOf()
)

data class Order(
    val orderNo: String,     // เช่น 4561
    val lmfCode: String,     // เช่น LMF-260627-684004561 (ใช้กันพิมพ์ซ้ำ)
    val branch: String,      // เช่น อ่าวอุดม
    val dateTime: String,    // เช่น 27 มิ.ย. 69, 17:38
    val customer: String,    // เช่น บิว
    val isNewCustomer: Boolean,
    val items: List<OrderItem>,
    val note: String,        // เช่น ไม่รับช้อนส้อมพลาสติก
    val payment: String,     // เช่น E-Payment
    val subtotal: String,
    val discount: String,
    val net: String,
    val parsedOk: Boolean    // อ่านครบหรือไม่ -> ถ้า false จะพิมพ์ใบสำรอง
)

// เก็บออเดอร์ล่าสุดไว้สำหรับปุ่ม "พิมพ์ซ้ำ"
object LastOrder {
    @Volatile var value: Order? = null
}
