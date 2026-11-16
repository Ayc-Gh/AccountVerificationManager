package cn.local.accountmanager.core;

import org.junit.Test;
import java.time.Instant;
import static org.junit.Assert.*;

public class RetentionPeriodTest {
    @Test public void fiveUnitsUseElapsedTimeAndLabels() {
        long now = Instant.parse("2028-01-31T23:59:30Z").toEpochMilli();
        long[] millis = {60000, 3600000, 86400000, 604800000, 2592000000L};
        String[] labels = {"分", "时", "天", "周", "月"};
        for (int unit = 0; unit < 5; unit++) {
            RetentionPeriod period = RetentionPeriod.parse(" 2 ", unit);
            assertEquals(now + 2 * millis[unit], period.expiresAt(now));
            assertEquals("2" + labels[unit], period.label());
        }
        assertEquals(Instant.parse("2028-03-01T23:59:30Z").toEpochMilli(), RetentionPeriod.parse("1", 4).expiresAt(now));
    }
    @Test public void invalidNumbersAndUnitsAreRejected() {
        for (String input : new String[]{null, "", " ", "0", "000000", "-1", "+1", "1.5", "1e2", "1000000", "99999999999999", "1 0", "２", "abc"})
            assertThrows(IllegalArgumentException.class, () -> RetentionPeriod.parse(input, 0));
        assertThrows(IllegalArgumentException.class, () -> RetentionPeriod.parse("1", -1));
        assertThrows(IllegalArgumentException.class, () -> RetentionPeriod.parse("1", 5));
    }
    @Test public void dateAndArithmeticOverflowAreRejected() {
        RetentionPeriod period = RetentionPeriod.parse("999999", 4);
        assertThrows(IllegalArgumentException.class, () -> period.expiresAt(1800000000000L));
        assertThrows(IllegalArgumentException.class, () -> period.expiresAt(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> RetentionPeriod.parse("1", 0).expiresAt(253402300799999L));
        assertEquals(1800000000000L + 999999L * 60000L, RetentionPeriod.parse("999999", 0).expiresAt(1800000000000L));
    }
    @Test public void calculationUsesTheSuppliedSaveInstant() {
        RetentionPeriod period = RetentionPeriod.parse("5", 0);
        assertEquals(301000, period.expiresAt(1000));
        assertEquals(360000, period.expiresAt(60000));
    }
}
