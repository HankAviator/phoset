package io.github.hankaviator.phoset;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ReviewCountParserTest {
    @Test
    public void acceptsObservedBatchesAndLargerCounts() {
        assertEquals(103L, ReviewCountParser.firstCount("Google 相册中的 103 张照片已移至回收站"));
        assertEquals(106L, ReviewCountParser.firstCount("Google 相册中的 106 张照片已移至回收站"));
        assertEquals(100L, ReviewCountParser.firstCount("100 photos moved to trash"));
        assertEquals(101L, ReviewCountParser.firstCount("101 photos moved to trash"));
        assertEquals(12345L, ReviewCountParser.firstCount("12345 photos moved to trash"));
    }

    @Test
    public void readsLocalizedGroupingAndDigits() {
        for (String count : new String[]{"1,234", "1.234", "1 234", "1\u00a0234",
                "1\u202f234", "1\u2009234", "1'234", "1\u2019234", "١٬٢٣٤", "１２３４"}) {
            assertEquals(count, 1234L, ReviewCountParser.firstCount(count + " photos"));
        }
    }

    @Test
    public void stopsAtTheEndOfTheFirstNumber() {
        assertEquals(106L, ReviewCountParser.firstCount("106 photos, 3 videos"));
        assertEquals(106L, ReviewCountParser.firstCount("106. Photos need review"));
    }

    @Test
    public void rejectsMissingCountsAndOverflow() {
        assertEquals(-1L, ReviewCountParser.firstCount(null));
        assertEquals(-1L, ReviewCountParser.firstCount("Photos need review"));
        assertEquals(0L, ReviewCountParser.firstCount("0 photos"));
        assertEquals(-1L, ReviewCountParser.firstCount("9223372036854775808 photos"));
        assertEquals(Long.MAX_VALUE,
                ReviewCountParser.firstCount("9223372036854775807 photos"));
    }
}
