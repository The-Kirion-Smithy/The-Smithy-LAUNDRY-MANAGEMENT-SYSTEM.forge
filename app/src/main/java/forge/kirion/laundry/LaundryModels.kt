package forge.kirion.laundry

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

enum class Stage(val display: String) {
    RECEIVED("Received"),
    WASHING("Washing"),
    DRYING("Drying"),
    FOLDING("Folding / Ironing"),
    READY("Ready"),
    PICKED_UP("Picked up");

    fun next(): Stage? = when (this) {
        RECEIVED -> WASHING
        WASHING -> DRYING
        DRYING -> FOLDING
        FOLDING -> READY
        READY -> PICKED_UP
        PICKED_UP -> null
    }
}

data class LaundryCustomer(val id: String, val name: String, val phone: String)
data class LaundryService(
    val id: String, val name: String, val unit: String,
    val priceCents: Long, val turnaroundHours: Int
)
data class LaundryOrder(
    val id: String, val ticket: String, val customer: String,
    val service: String, val quantity: Double, val unit: String,
    val stage: Stage, val totalCents: Long, val paidCents: Long,
    val promisedAt: Long, val createdAt: Long
) {
    val balanceCents: Long get() = totalCents - paidCents
}
data class DayReport(
    val received: Long, val pickedUp: Long, val ready: Long,
    val kilograms: Double, val collectedCents: Long,
    val cashCents: Long, val outstandingCents: Long
)

fun peso(cents: Long): String =
    NumberFormat.getCurrencyInstance(Locale("en", "PH")).format(cents / 100.0)

fun parseCents(text: String): Long {
    val input = text.trim()
    require(input.isNotBlank()) { "Enter an amount" }
    val parsed = try { BigDecimal(input) } catch (_: NumberFormatException) {
        throw IllegalArgumentException("Amount must be numeric")
    }
    require(parsed.signum() >= 0) { "Amount cannot be negative" }
    return try { parsed.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact() }
    catch (_: ArithmeticException) { throw IllegalArgumentException("Use at most 2 decimal places and a smaller amount") }
}

fun lineTotalCents(unitPriceCents: Long, text: String, unit: String): Long {
    require(unit in listOf("kg", "item", "flat")) { "Unknown pricing unit" }
    val count = if (unit == "flat") BigDecimal.ONE else try {
        BigDecimal(text.trim())
    } catch (_: NumberFormatException) { throw IllegalArgumentException("Enter a valid quantity") }
    require(count.signum() > 0 && count <= BigDecimal("9999")) { "Quantity must be between 0 and 9999" }
    if (unit == "item") require(count.stripTrailingZeros().scale() <= 0) { "Item quantity must be a whole number" }
    if (unit == "kg") require(count.stripTrailingZeros().scale() <= 2) { "Weight supports 2 decimal places" }
    return try {
        BigDecimal.valueOf(unitPriceCents).multiply(count).setScale(0, RoundingMode.HALF_UP).longValueExact()
    } catch (_: ArithmeticException) { throw IllegalArgumentException("Calculated total is too large") }
}
