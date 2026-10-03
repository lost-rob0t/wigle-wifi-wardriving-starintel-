package net.wigle.wigleandroid.warstar;

import android.os.Bundle;
import android.content.Intent;
import android.app.Activity;
import android.net.Uri;
import android.location.Location;
import androidx.core.content.FileProvider;
import net.wigle.wigleandroid.MainActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** WarStar mission console. Network work stays off the UI thread. */
public final class WarStarFragment extends Fragment {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private WarStarClient client;
    private TextView status;
    private LinearLayout targets;
    private EditText url;
    private EditText token;
    private boolean exportLatestRun;

    private interface Work { String run() throws Exception; }
    private void run(Work work) {
        status.setText("Working…");
        io.execute(() -> {
            String result;
            try { result = work.run(); }
            catch (Exception exception) { result = exception.getMessage(); }
            if (getActivity() != null) {
                String message = result;
                getActivity().runOnUiThread(() -> status.setText(message));
            }
        });
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(requireContext());
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, 12, 0, 12);
        return view;
    }

    private Button button(String label, LinearLayout parent) {
        Button button = new Button(requireContext());
        button.setText(label);
        parent.addView(button);
        return button;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle state) {
        client = new WarStarClient(requireContext());
        ScrollView scroll = new ScrollView(requireContext());
        LinearLayout column = new LinearLayout(requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad, pad, pad);
        scroll.addView(column);

        column.addView(text("WARSTAR  /  FIELD CONSOLE", 22));
        column.addView(text("Connect to StarIntel, sync wireless target documents, and upload collected observations.", 14));
        url = new EditText(requireContext());
        url.setHint("https://server.example");
        url.setSingleLine(true);
        url.setText(client.url());
        column.addView(url);
        token = new EditText(requireContext());
        token.setHint("StarIntel bearer token");
        token.setSingleLine(true);
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        column.addView(token);
        button("Connect with API key", column).setOnClickListener(view -> {
            String base = url.getText().toString();
            String bearer = token.getText().toString();
            token.setText("");
            run(() -> {
                client.signIn(base, bearer);
                return "Connected to StarIntel";
            });
        });
        EditText username = new EditText(requireContext());
        username.setHint("StarIntel username");
        username.setSingleLine(true);
        column.addView(username);
        EditText password = new EditText(requireContext());
        password.setHint("StarIntel password");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        column.addView(password);
        button("Sign in with password", column).setOnClickListener(view -> {
            String base = url.getText().toString();
            String user = username.getText().toString();
            String secret = password.getText().toString();
            password.setText("");
            run(() -> {
                client.signInPassword(base, user, secret);
                return "Connected to StarIntel";
            });
        });
        button("Sign out", column).setOnClickListener(view -> {
            client.signOut();
            status.setText("Signed out");
        });
        button("Sync target documents", column).setOnClickListener(view -> run(() -> {
            JSONArray docs = client.syncTargets();
            if (getActivity() != null) getActivity().runOnUiThread(() -> showTargets(docs));
            return "Synced " + docs.length() + " target documents";
        }));
        button("Upload next 100 observations", column).setOnClickListener(view -> run(() ->
                "Uploaded " + client.uploadNextBatch() + " observations"));
        button("Import WiGLE CSV / CSV.GZ", column).setOnClickListener(view -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.setType("*/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(pick, 721);
        });
        button("Upload entire local database", column).setOnClickListener(view -> run(() -> {
            int total = 0;
            int count;
            do { count = client.uploadNextBatch(); total += count; }
            while (count > 0 && !Thread.currentThread().isInterrupted());
            return "Uploaded " + total + " local observations";
        }));
        button("Export latest run • StarIntel 0.10.1", column).setOnClickListener(view -> chooseExport(true));
        button("Export entire database • StarIntel 0.10.1", column).setOnClickListener(view -> chooseExport(false));
        button("Create low coverage GPX route", column).setOnClickListener(view -> run(() -> {
            MainActivity activity = MainActivity.getMainActivity();
            if (activity == null || MainActivity.getStaticState() == null ||
                    MainActivity.getStaticState().dbHelper == null || activity.getGPSListener() == null) {
                throw new IllegalStateException("Scanner and GPS must be running");
            }
            Location fix = activity.getGPSListener().getCurrentLocation();
            String gpx = CoverageRoutes.gpx(MainActivity.getStaticState().dbHelper, fix);
            File file = new File(requireContext().getCacheDir(), "warstar-coverage.gpx");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(gpx.getBytes(StandardCharsets.UTF_8));
            }
            Uri uri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".gpxprovider", file);
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("application/gpx+xml");
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(share, "Open coverage route in a map app"));
            });
            return "Route generated from nearby observations";
        }));
        status = text(client.signedIn() ? "Connected" : "Sign in to sync and upload", 14);
        column.addView(status);
        column.addView(text("WIRELESS TARGETS", 18));
        targets = new LinearLayout(requireContext());
        targets.setOrientation(LinearLayout.VERTICAL);
        column.addView(targets);
        showTargets(client.cachedTargets());
        return scroll;
    }

    private void showTargets(JSONArray docs) {
        targets.removeAllViews();
        if (docs.length() == 0) {
            targets.addView(text("No wireless targets yet.", 14));
            return;
        }
        for (int i = 0; i < docs.length(); i++) {
            JSONObject doc = docs.optJSONObject(i);
            if (doc == null) continue;
            JSONObject data = doc.optJSONObject("data");
            String id = doc.optString("id", doc.optString("_id", "Target"));
            String subject = doc.optString("target", data == null ? "" : data.optString("target"));
            targets.addView(text(id + "\n" + subject, 14));
            if (subject.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) {
                String radio = doc.optString("radio", data == null ? "WIFI" : data.optString("radio", "WIFI"));
                String type = "BT".equalsIgnoreCase(radio) || "BLE".equalsIgnoreCase(radio)
                        ? radio.toUpperCase(java.util.Locale.ROOT) : "WIFI";
                button("Mark • alert when seen", targets).setOnClickListener(view -> {
                    try {
                        DeviceTags.markTarget(requireContext(), type, subject, id);
                        status.setText("Marked " + subject + " as a " + type + " target");
                    } catch (org.json.JSONException exception) {
                        status.setText("Could not mark target");
                    }
                });
            }
        }
    }

    private void chooseExport(boolean latestRun) {
        exportLatestRun = latestRun;
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("application/x-ndjson");
        create.putExtra(Intent.EXTRA_TITLE, latestRun ? "warstar-run-0.10.1.jsonl" : "warstar-database-0.10.1.jsonl");
        startActivityForResult(create, 722);
    }

    @Override public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 722 && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri destination = data.getData();
            boolean latest = exportLatestRun;
            run(() -> {
                MainActivity.State state = MainActivity.getStaticState();
                if (state == null || state.dbHelper == null) throw new java.io.IOException("Scanner database unavailable");
                try (java.io.OutputStream output = requireContext().getContentResolver().openOutputStream(destination)) {
                    if (output == null) throw new java.io.IOException("Cannot create export");
                    int count = StarIntelExport.write(state.dbHelper, output, latest, client.installationId());
                    return "Exported " + count + " wireless observations as 0.10.1 documents";
                }
            });
        }
        if (requestCode == 721 && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri selected = data.getData();
            run(() -> {
                try (java.io.InputStream input = requireContext().getContentResolver().openInputStream(selected)) {
                    if (input == null) throw new java.io.IOException("Cannot open export");
                    return "Imported " + client.importWigleCsv(input, selected.toString()) + " observations";
                }
            });
        }
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        io.shutdownNow();
    }
}
