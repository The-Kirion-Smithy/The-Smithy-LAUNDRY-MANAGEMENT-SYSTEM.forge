package forge.kirion.laundry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LaundryModelsTest {
    @Test fun moneyParsing() {
        assertEquals(12550L, parseCents("125.50"))
        assertEquals(0L, parseCents("0"))
        assertThrows(IllegalArgumentException::class.java) { parseCents("-1") }
        assertThrows(IllegalArgumentException::class.java) { parseCents("1.001") }
    }
    @Test fun priceSnapshotMath() {
        assertEquals(3750L, lineTotalCents(2500, "1.5", "kg"))
        assertEquals(2000L, lineTotalCents(2000, "999", "flat"))
        assertThrows(IllegalArgumentException::class.java) { lineTotalCents(500, "1.5", "item") }
    }
    @Test fun strictlyForwardStages() {
        assertEquals(Stage.WASHING, Stage.RECEIVED.next())
        assertEquals(Stage.PICKED_UP, Stage.READY.next())
        assertEquals(null, Stage.PICKED_UP.next())
    }
}
