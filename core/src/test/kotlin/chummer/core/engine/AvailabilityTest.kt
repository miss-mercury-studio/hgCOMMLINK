package chummer.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AvailabilityTest {
    @Test
    fun ratedAvailabilityReadsTheMinimumRating() {
        // Cyberlimb upgrades: "Rating - MinRating + 1" at rating 4 for a limb that starts at 2.
        assertEquals(Availability.Value(3, null), Availability.parse("+Rating - MinRating + 1", 4, 2))
        assertEquals(Availability.Value(5, null), Availability.parse("+Rating - MinRating + 1", 4, 0))
        assertEquals(Availability.Value(15, 'R'), Availability.parse("(Rating * 5)R", 3))
        assertEquals(Availability.Value(0, null), Availability.parse("", 3))
    }
}
