package io.github.hankaviator.phoset;

/** Parses Photos' localized count without imposing a batch-size policy. */
final class ReviewCountParser {
    private ReviewCountParser() {}

    static long firstCount(CharSequence message) {
        if (message == null) {
            return -1;
        }
        long count = 0;
        boolean found = false;
        for (int index = 0; index < message.length();) {
            int codePoint = Character.codePointAt(message, index);
            index += Character.charCount(codePoint);
            int digit = Character.digit(codePoint, 10);
            if (digit >= 0) {
                found = true;
                if (count > (Long.MAX_VALUE - digit) / 10) {
                    return -1;
                }
                count = count * 10 + digit;
            } else if (found) {
                // Grouping punctuation is part of the number only between digits.
                boolean grouping = codePoint == ',' || codePoint == '.'
                        || codePoint == '\'' || codePoint == 0x2019
                        || codePoint == 0x066c || Character.isSpaceChar(codePoint);
                if (!grouping || index == message.length()
                        || Character.digit(Character.codePointAt(message, index), 10) < 0) {
                    break;
                }
            }
        }
        return found ? count : -1;
    }
}
