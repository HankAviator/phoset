package io.github.hankaviator.phoset;

import java.math.BigDecimal;
import java.util.Objects;

final class Coordinate {
    private final BigDecimal latitude;
    private final BigDecimal longitude;

    Coordinate(BigDecimal latitude, BigDecimal longitude) {
        this.latitude = normalize(latitude);
        this.longitude = normalize(longitude);
    }

    String toGeoUri() {
        return "geo:" + toPair();
    }

    String toPinnedGeoUri() {
        return toGeoUri() + "?q=" + toPair();
    }

    private String toPair() {
        return latitude.toPlainString() + "," + longitude.toPlainString();
    }

    private static BigDecimal normalize(BigDecimal value) {
        if (value.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return value.stripTrailingZeros();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Coordinate)) {
            return false;
        }
        Coordinate that = (Coordinate) other;
        return latitude.compareTo(that.latitude) == 0
                && longitude.compareTo(that.longitude) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(latitude.stripTrailingZeros(), longitude.stripTrailingZeros());
    }
}
