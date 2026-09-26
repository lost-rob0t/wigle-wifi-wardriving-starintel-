package net.wigle.wigleandroid;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.wigle.wigleandroid.starintel.StarIntelClient;
import net.wigle.wigleandroid.starintel.StarIntelCredentialStore;
import net.wigle.wigleandroid.starintel.StarIntelRuntime;
import net.wigle.wigleandroid.util.PreferenceKeys;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** StarIntel server, live ingest, MAC watchlist, and Wear OS control surface. */
public final class StarIntelFragment extends Fragment {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private StarIntelCredentialStore credentials;
    private StarIntelRuntime runtime;

    private Switch enabled;
    private Switch live;
    private Switch wear;
    private EditText baseUrl;
    private EditText dataset;
    private EditText token;
    private TextView status;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull final android.view.LayoutInflater inflater,
            @Nullable final ViewGroup container,
            @Nullable final Bundle savedInstanceState
    ) {
        final Context context = requireContext();
        prefs = context.getSharedPreferences(PreferenceKeys.SHARED_PREFS, Context.MODE_PRIVATE);
        credentials = new StarIntelCredentialStore(context);
        runtime = StarIntelRuntime.get(context);

        final ScrollView scroll = new ScrollView(context);
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(18);
        root.setPadding(pad, pad, pad, dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView title = text("StarIntel Sensor + Watchlist", 22f);
        root.addView(title);
        final TextView subtitle = text(
                "Live Wi-Fi observations use the StarIntel 0.10.1 wire contract. " +
                        "MAC/OUI range hits become canonical alert documents and sync to Wear OS.",
                14f);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        enabled = toggle("Enable StarIntel server ingest",
                prefs.getBoolean(PreferenceKeys.PREF_STARINTEL_ENABLED, false));
        live = toggle("Live-ingest Wi-Fi observations",
                prefs.getBoolean(PreferenceKeys.PREF_STARINTEL_LIVE_INGEST, true));
        wear = toggle("Sync live data + alerts to Wear OS",
                prefs.getBoolean(PreferenceKeys.PREF_STARINTEL_WEAR_SYNC, true));
        root.addView(enabled);
        root.addView(live);
        root.addView(wear);

        baseUrl = field("Server URL (https://starintel.example)", false);
        baseUrl.setText(prefs.getString(PreferenceKeys.PREF_STARINTEL_BASE_URL, ""));
        root.addView(baseUrl);

        dataset = field("Dataset", false);
        dataset.setText(prefs.getString(PreferenceKeys.PREF_STARINTEL_DATASET, "wigle-android"));
        root.addView(dataset);

        token = field(credentials.hasToken()
                ? "API key (stored in Android Keystore; leave blank to keep)"
                : "API key (stored in Android Keystore)", true);
        root.addView(token);

        final Button save = button("Save + connect");
        save.setOnClickListener(v -> save());
        root.addView(save);

        final Button watchlist = button("MAC / OUI Watchlist");
        watchlist.setOnClickListener(v -> {
            final Intent intent = new Intent(context, MacFilterActivity.class);
            intent.putExtra(FilterActivity.ADDR_FILTER_MESSAGE, FilterActivity.INTENT_ALERT_FILTER);
            startActivity(intent);
        });
        root.addView(watchlist);

        final Button flush = button("Flush queued documents now");
        flush.setOnClickListener(v -> {
            runtime.flushNow();
            renderStatus("Flush requested");
        });
        root.addView(flush);

        final Button health = button("Test server health");
        health.setOnClickListener(v -> testHealth());
        root.addView(health);

        status = text("", 14f);
        status.setPadding(0, dp(14), 0, 0);
        root.addView(status);
        renderStatus("Ready");

        return scroll;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (status != null && runtime != null) renderStatus("Ready");
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void save() {
        final SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(PreferenceKeys.PREF_STARINTEL_ENABLED, enabled.isChecked());
        editor.putBoolean(PreferenceKeys.PREF_STARINTEL_LIVE_INGEST, live.isChecked());
        editor.putBoolean(PreferenceKeys.PREF_STARINTEL_WEAR_SYNC, wear.isChecked());
        editor.putString(PreferenceKeys.PREF_STARINTEL_BASE_URL, baseUrl.getText().toString().trim());
        final String datasetValue = dataset.getText().toString().trim();
        editor.putString(PreferenceKeys.PREF_STARINTEL_DATASET,
                datasetValue.isEmpty() ? "wigle-android" : datasetValue);
        editor.apply();

        final String tokenValue = token.getText().toString().trim();
        if (!tokenValue.isEmpty()) {
            credentials.saveToken(tokenValue);
            token.setText("");
        }
        runtime.flushNow();
        renderStatus("Saved");
    }

    private void testHealth() {
        final String url = baseUrl.getText().toString().trim();
        if (url.isEmpty()) {
            renderStatus("Set the StarIntel server URL first");
            return;
        }
        renderStatus("Checking server…");
        worker.execute(() -> {
            String result;
            try {
                final int code = new StarIntelClient().health(url);
                result = code >= 200 && code < 300
                        ? "Server healthy (HTTP " + code + ")"
                        : "Server returned HTTP " + code;
            } catch (Exception ex) {
                result = "Health check failed: " + ex.getClass().getSimpleName();
            }
            final String finalResult = result;
            if (getActivity() != null) {
                requireActivity().runOnUiThread(() -> renderStatus(finalResult));
            }
        });
    }

    private void renderStatus(final String prefix) {
        final String credential = credentials != null && credentials.hasToken()
                ? "credential: keystore" : "credential: missing";
        final int queued = runtime == null ? 0 : runtime.queuedCount();
        status.setText(prefix + "  •  " + credential + "  •  queued: " + queued);
    }

    private TextView text(final String value, final float sp) {
        final TextView view = new TextView(requireContext());
        view.setText(value);
        view.setTextSize(sp);
        return view;
    }

    private Switch toggle(final String label, final boolean checked) {
        final Switch view = new Switch(requireContext());
        view.setText(label);
        view.setChecked(checked);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private EditText field(final String hint, final boolean secret) {
        final EditText view = new EditText(requireContext());
        view.setHint(hint);
        view.setSingleLine(true);
        if (secret) {
            view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        }
        view.setPadding(dp(8), dp(10), dp(8), dp(10));
        return view;
    }

    private Button button(final String label) {
        final Button view = new Button(requireContext());
        view.setText(label);
        view.setAllCaps(false);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        view.setLayoutParams(params);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
