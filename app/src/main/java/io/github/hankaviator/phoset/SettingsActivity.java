package io.github.hankaviator.phoset;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

/** Material 3 Expressive settings surfaced by LSPosed's module settings button. */
public final class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());
        applySystemBarInsets();

        bindFeature(R.id.geo_card, R.id.geo_switch, FeatureFlags.GEO_INTENT_FIX);
        bindFeature(R.id.sync_card, R.id.sync_switch, FeatureFlags.RECONCILE_CHANGES);
        bindFeature(R.id.trash_card, R.id.trash_switch,
                FeatureFlags.SKIP_TRASH_CONFIRMATION);
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.settings_root);
        View appBar = findViewById(R.id.app_bar);
        View scroll = findViewById(R.id.settings_scroll);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            appBar.setPadding(bars.left, bars.top, bars.right, 0);
            scroll.setPadding(bars.left, 0, bars.right, bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void bindFeature(int cardId, int switchId, String feature) {
        MaterialCardView card = findViewById(cardId);
        MaterialSwitch toggle = findViewById(switchId);
        toggle.setChecked(FeaturePreferences.isEnabled(this, feature));
        toggle.setOnCheckedChangeListener((button, enabled) ->
                FeaturePreferences.setEnabled(this, feature, enabled));
        card.setOnClickListener(view -> toggle.toggle());
    }
}
