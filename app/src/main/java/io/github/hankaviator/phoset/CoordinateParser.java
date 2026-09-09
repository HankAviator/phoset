package io.github.hankaviator.phoset;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CoordinateParser {
    private static final Pattern PAIR = Pattern.compile(
            "^\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*,\\s*"
                    + "([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*$");

    private CoordinateParser() {}

    static Coordinate parsePair(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = PAIR.matcher(value);
        if (!matcher.matches()) {
            return null;
        }

        try {
            BigDecimal latitude = new BigDecimal(matcher.group(1));
            BigDecimal longitude = new BigDecimal(matcher.group(2));
            if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                    || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                    || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                    || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
                return null;
            }
            return new Coordinate(latitude, longitude);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static Coordinate parseGeoUri(String value) {
        if (value == null || !value.regionMatches(true, 0, "geo:", 0, 4)) {
            return null;
        }

        String schemeSpecific = value.substring(4);
        int queryStart = schemeSpecific.indexOf('?');
        String position = queryStart >= 0
                ? schemeSpecific.substring(0, queryStart)
                : schemeSpecific;

        if (queryStart >= 0) {
            String queryCoordinate = queryParameter(
                    schemeSpecific.substring(queryStart + 1), "q");
            if (queryCoordinate != null) {
                return parsePair(queryCoordinate);
            }
        }
        return parsePair(position);
    }

    static Coordinate parseGoogleMapsUri(String value) {
        if (value == null) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || !isGoogleMapsHostAndPath(host, uri.getPath())) {
                return null;
            }

            String query = uri.getRawQuery();
            Coordinate coordinate = parsePair(queryParameter(query, "query"));
            return coordinate != null
                    ? coordinate
                    : parsePair(queryParameter(query, "q"));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isGoogleMapsHostAndPath(String host, String path) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        boolean googleDomain = normalizedHost.equals("google.com")
                || normalizedHost.endsWith(".google.com")
                || normalizedHost.matches("(?:.+\\.)?google\\.[a-z.]{2,}");
        if (!googleDomain) {
            return false;
        }
        return normalizedHost.startsWith("maps.")
                || (path != null && (path.equals("/maps") || path.startsWith("/maps/")));
    }

    private static String queryParameter(String rawQuery, String wantedName) {
        if (rawQuery == null) {
            return null;
        }
        for (String parameter : rawQuery.split("&")) {
            int separator = parameter.indexOf('=');
            String rawName = separator >= 0 ? parameter.substring(0, separator) : parameter;
            if (decode(rawName).equals(wantedName)) {
                return decode(separator >= 0 ? parameter.substring(separator + 1) : "");
            }
        }
        return null;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return value;
        }
    }
}
