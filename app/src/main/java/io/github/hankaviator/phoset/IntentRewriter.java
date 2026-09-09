package io.github.hankaviator.phoset;

import android.app.SearchManager;
import android.content.Intent;
import android.net.Uri;

final class IntentRewriter {
    private IntentRewriter() {}

    static Intent rewrite(Intent original) {
        if (original == null) {
            return null;
        }

        Coordinate coordinate = null;
        String action = original.getAction();
        Uri data = original.getData();

        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            if ("geo".equalsIgnoreCase(data.getScheme())) {
                coordinate = CoordinateParser.parseGeoUri(data.toString());
            } else {
                coordinate = CoordinateParser.parseGoogleMapsUri(data.toString());
            }
        } else if (Intent.ACTION_SEARCH.equals(action)
                || Intent.ACTION_WEB_SEARCH.equals(action)) {
            coordinate = CoordinateParser.parsePair(original.getStringExtra(SearchManager.QUERY));
        }

        if (coordinate == null) {
            return original;
        }

        Intent rewritten = new Intent(Intent.ACTION_VIEW, Uri.parse(coordinate.toPinnedGeoUri()));
        rewritten.setFlags(original.getFlags());
        rewritten.setPackage(null);
        rewritten.setComponent(null);
        rewritten.setSelector(null);
        return rewritten;
    }
}
