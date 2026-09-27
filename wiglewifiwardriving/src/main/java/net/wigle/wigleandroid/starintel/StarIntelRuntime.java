package net.wigle.wigleandroid.starintel;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.provider.Settings;
import android.location.Location;
import android.net.wifi.ScanResult;

import net.wigle.wigleandroid.ListFragment;
import net.wigle.wigleandroid.MainActivity;
import net.wigle.wigleandroid.db.DatabaseHelper;
import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.model.NetworkType;
import net.wigle.wigleandroid.util.Logging;
import net.wigle.wigleandroid.util.PreferenceKeys;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-mailbox actor for StarIntel ingest, watchlist state, notifications,
 * and Wear OS fan-out. Wi-Fi scan callbacks never block on network I/O.
 */
public final class StarIntelRuntime {
    public interface ImportListener {
        void onProgress(
                long networks,
                long observations,
                int queued,
                boolean done,
                String message
        );
    }
    private static final int MAX_NEARBY = 50;
    private static final int FLUSH_BATCH = 100;
    private static final int IMPORT_PAGE = 250;
    private static final int IMPORT_OUTBOX_BACKPRESSURE = 2000;
    private static final long FLUSH_PERIOD_MS = 5000L;

    private static volatile StarIntelRuntime instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final StarIntelCredentialStore credentialStore;
    private final StarIntelOutbox outbox;
    private final StarIntelClient client;
    private final MacWatchlistEngine watchlist;
    private final PhoneWearBridge wearBridge;
    private final ExecutorService mailbox;
    private final AtomicBoolean importRunning = new AtomicBoolean(false);

    private final LinkedHashMap<String, JSONObject> nearby =
            new LinkedHashMap<String, JSONObject>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, JSONObject> eldest) {
                    return size() > MAX_NEARBY;
                }
            };
    private final Set<String> announcedNetworks = new HashSet<>();

    private long watchHits;
    private long lastFlushAt;
    private JSONObject lastAlert;

    private StarIntelRuntime(final Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(
                PreferenceKeys.SHARED_PREFS, Context.MODE_PRIVATE);
        this.credentialStore = new StarIntelCredentialStore(this.context);
        this.outbox = new StarIntelOutbox(this.context);
        this.client = new StarIntelClient();
        this.watchlist = new MacWatchlistEngine(this.context);
        this.wearBridge = new PhoneWearBridge(this.context);
        this.mailbox = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "starintel-runtime");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static StarIntelRuntime get(final Context context) {
        StarIntelRuntime local = instance;
        if (local == null) {
            synchronized (StarIntelRuntime.class) {
                local = instance;
                if (local == null) {
                    local = new StarIntelRuntime(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public void beginWifiScan() {
        mailbox.execute(watchlist::beginScan);
    }

    public void onWifiObservation(
            final Network network,
            final ScanResult result,
            final Location location
    ) {
        if (network == null || network.getBssid() == null) return;

        final long now = System.currentTimeMillis();
        final String dataset = prefs.getString(
                PreferenceKeys.PREF_STARINTEL_DATASET, "wigle-android");
        final Network frozen = new Network(
                network.getBssid(),
                network.getSsid(),
                network.getFrequency(),
                network.getCapabilities(),
                network.getLevel(),
                NetworkType.WIFI
        );
        frozen.setRcois(network.getRcois());

        final Location frozenLocation = location == null ? null : new Location(location);
        mailbox.execute(() -> {
            try {
                final JSONObject observation =
                        StarIntelDocuments.observation(
                                frozen, result, frozenLocation, dataset, now);
                final JSONObject networkDocument =
                        StarIntelDocuments.wirelessNetwork(
                                frozen, frozenLocation, dataset, now);
                final JSONObject nearbyRecord =
                        nearbyRecord(frozen, frozenLocation, now);
                handleObservation(
                        frozen, observation, networkDocument, nearbyRecord, dataset, now);
            } catch (Exception ex) {
                Logging.error("Unable to build StarIntel Wi-Fi documents", ex);
            }
        });
    }

    public void endWifiScan() {
        mailbox.execute(() -> {
            watchlist.endScan();
            publishSnapshotInternal();
            maybeFlush(false);
        });
    }

    public void requestSnapshot() {
        mailbox.execute(this::publishSnapshotInternal);
    }

    public void flushNow() {
        mailbox.execute(() -> maybeFlush(true));
    }

    public void importExistingWifi(
            final DatabaseHelper dbHelper,
            final ImportListener listener
    ) {
        if (dbHelper == null) {
            notifyImport(listener, 0L, 0L, true, "WiGLE database unavailable");
            return;
        }
        if (!importRunning.compareAndSet(false, true)) {
            notifyImport(listener, 0L, 0L, false, "Import already running");
            return;
        }

        mailbox.execute(() -> {
            try {
                runExistingWifiImport(dbHelper, listener);
            } catch (Exception ex) {
                Logging.error("StarIntel existing Wi-Fi import failed", ex);
                notifyImport(listener, 0L, 0L, true,
                        "Import failed: " + ex.getClass().getSimpleName());
            } finally {
                importRunning.set(false);
            }
        });
    }

    public boolean isImportRunning() {
        return importRunning.get();
    }

    private void runExistingWifiImport(
            final DatabaseHelper dbHelper,
            final ImportListener listener
    ) throws Exception {
        if (!prefs.getBoolean(PreferenceKeys.PREF_STARINTEL_ENABLED, false)) {
            notifyImport(listener, 0L, 0L, true,
                    "Enable StarIntel ingest before importing");
            return;
        }
        final String baseUrl = prefs.getString(PreferenceKeys.PREF_STARINTEL_BASE_URL, "");
        if (baseUrl == null || baseUrl.trim().isEmpty() || !credentialStore.hasToken()) {
            notifyImport(listener, 0L, 0L, true,
                    "Configure server URL and API credential first");
            return;
        }

        final String dataset = prefs.getString(
                PreferenceKeys.PREF_STARINTEL_DATASET, "wigle-android");
        final String androidId = String.valueOf(Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID));

        long networkCount = 0L;
        long observationCount = 0L;
        String afterBssid = "";

        while (true) {
            int rows = 0;
            try (Cursor cursor = dbHelper.getStarIntelWifiNetworksForExport(
                    afterBssid, IMPORT_PAGE)) {
                while (cursor.moveToNext()) {
                    final String bssid = cursor.getString(0);
                    final String ssid = cursor.getString(1);
                    final int frequency = cursor.getInt(2);
                    final String capabilities = cursor.getString(3);
                    final long lastTime = cursor.getLong(4);
                    final double lat = cursor.getDouble(5);
                    final double lon = cursor.getDouble(6);
                    final int level = cursor.getInt(7);
                    final String rcois = cursor.getString(8);

                    final Network network = new Network(
                            bssid, ssid, frequency, capabilities, level, NetworkType.WIFI);
                    network.setRcois(rcois);
                    final Location location = locationForImport(
                            lat, lon, 0d, 0f, lastTime);
                    outbox.enqueue(
                            StarIntelDocuments.wirelessNetwork(
                                    network,
                                    location,
                                    dataset,
                                    lastTime > 0L ? lastTime : System.currentTimeMillis()),
                            "wireless-network"
                    );
                    afterBssid = bssid;
                    networkCount++;
                    rows++;
                }
            }
            maybeFlush(true);
            notifyImport(listener, networkCount, observationCount, false,
                    "Importing network inventory");
            if (rows < IMPORT_PAGE) break;
            if (outbox.count() >= IMPORT_OUTBOX_BACKPRESSURE) {
                notifyImport(listener, networkCount, observationCount, true,
                        "Import paused: server unavailable or outbox backpressure reached");
                return;
            }
        }

        long afterObservationId = 0L;
        while (true) {
            int rows = 0;
            try (Cursor cursor = dbHelper.getStarIntelWifiObservationsForExport(
                    afterObservationId, IMPORT_PAGE)) {
                while (cursor.moveToNext()) {
                    final long rowId = cursor.getLong(0);
                    final String bssid = cursor.getString(1);
                    final int level = cursor.getInt(2);
                    final double lat = cursor.getDouble(3);
                    final double lon = cursor.getDouble(4);
                    final double altitude = cursor.getDouble(5);
                    final float accuracy = cursor.getFloat(6);
                    final long observedAt = cursor.getLong(7);
                    final String ssid = cursor.getString(8);
                    final int frequency = cursor.getInt(9);
                    final String capabilities = cursor.getString(10);
                    final String rcois = cursor.getString(11);

                    final Network network = new Network(
                            bssid, ssid, frequency, capabilities, level, NetworkType.WIFI);
                    network.setRcois(rcois);
                    final Location location = locationForImport(
                            lat, lon, altitude, accuracy, observedAt);
                    final String stableKey =
                            androidId + ":" + rowId + ":" + bssid;
                    outbox.enqueue(
                            StarIntelDocuments.observation(
                                    network,
                                    null,
                                    location,
                                    dataset,
                                    observedAt > 0L ? observedAt : System.currentTimeMillis(),
                                    stableKey),
                            "observation"
                    );
                    afterObservationId = rowId;
                    observationCount++;
                    rows++;
                }
            }
            maybeFlush(true);
            notifyImport(listener, networkCount, observationCount, false,
                    "Importing historical observations");
            if (rows < IMPORT_PAGE) break;
            if (outbox.count() >= IMPORT_OUTBOX_BACKPRESSURE) {
                notifyImport(listener, networkCount, observationCount, true,
                        "Import paused: server unavailable or outbox backpressure reached");
                return;
            }
        }

        maybeFlush(true);
        notifyImport(listener, networkCount, observationCount, true,
                "Existing WiGLE database import complete");
    }

    private Location locationForImport(
            final double lat,
            final double lon,
            final double altitude,
            final float accuracy,
            final long time
    ) {
        if (lat == 0d && lon == 0d) return null;
        final Location location = new Location("wigle-db-import");
        location.setLatitude(lat);
        location.setLongitude(lon);
        if (altitude != 0d) location.setAltitude(altitude);
        if (accuracy > 0f) location.setAccuracy(accuracy);
        if (time > 0L) location.setTime(time);
        return location;
    }

    private void notifyImport(
            final ImportListener listener,
            final long networks,
            final long observations,
            final boolean done,
            final String message
    ) {
        if (listener != null) {
            listener.onProgress(networks, observations, outbox.count(), done, message);
        }
    }

    public int queuedCount() {
        return outbox.count();
    }

    public boolean hasCredential() {
        return credentialStore.hasToken();
    }

    private void handleObservation(
            final Network network,
            final JSONObject observation,
            final JSONObject networkDocument,
            final JSONObject nearbyRecord,
            final String dataset,
            final long now
    ) throws Exception {
        nearby.put(network.getBssid(), nearbyRecord);

        final boolean serverEnabled =
                prefs.getBoolean(PreferenceKeys.PREF_STARINTEL_ENABLED, false);
        final boolean liveIngest =
                prefs.getBoolean(PreferenceKeys.PREF_STARINTEL_LIVE_INGEST, true);

        if (serverEnabled && liveIngest) {
            if (announcedNetworks.add(network.getBssid())) {
                outbox.enqueue(networkDocument, "wireless-network");
            }
            outbox.enqueue(observation, "observation");
        }

        final MacWatchlistEngine.Match match = watchlist.onSeen(network.getBssid());
        if (match != null && match.enteredRange) {
            final int severity = Math.max(0, Math.min(100,
                    prefs.getInt(PreferenceKeys.PREF_STARINTEL_ALERT_SEVERITY, 85)));
            final JSONObject alert = StarIntelDocuments.alert(
                    network, observation, match.rule, severity, dataset, now);
            lastAlert = alert;
            watchHits++;

            if (serverEnabled) {
                outbox.enqueue(alert, "alert");
            }
            WatchAlertNotifier.notify(context, alert, network, match.rule);
            if (prefs.getBoolean(PreferenceKeys.PREF_STARINTEL_WEAR_SYNC, true)) {
                wearBridge.publishAlert(alert);
            }
        }

        maybeFlush(false);
    }

    private void maybeFlush(final boolean force) {
        if (!prefs.getBoolean(PreferenceKeys.PREF_STARINTEL_ENABLED, false)) return;

        final String baseUrl = prefs.getString(PreferenceKeys.PREF_STARINTEL_BASE_URL, "");
        final String token = credentialStore.loadToken();
        if (baseUrl == null || baseUrl.trim().isEmpty() || token.isEmpty()) return;

        final long now = System.currentTimeMillis();
        if (!force && outbox.count() < 25 && now - lastFlushAt < FLUSH_PERIOD_MS) return;
        lastFlushAt = now;

        final List<StarIntelOutbox.Item> batch = outbox.take(FLUSH_BATCH);
        if (batch.isEmpty()) return;
        try {
            if (client.postBulk(baseUrl, token, batch)) {
                outbox.delete(batch);
            } else {
                outbox.markAttempt(batch);
            }
        } catch (Exception ex) {
            outbox.markAttempt(batch);
            Logging.warn("StarIntel bulk ingest failed: " + ex.getClass().getSimpleName());
        }
    }

    private void publishSnapshotInternal() {
        if (!prefs.getBoolean(PreferenceKeys.PREF_STARINTEL_WEAR_SYNC, true)) return;

        try {
            final JSONObject snapshot = new JSONObject();
            snapshot.put("updated_at", System.currentTimeMillis());
            snapshot.put("scanning", MainActivity.isScanning(context));
            snapshot.put("run_networks", ListFragment.lameStatic.runNets);
            snapshot.put("new_networks", ListFragment.lameStatic.newWifi);
            snapshot.put("database_networks", ListFragment.lameStatic.dbNets);
            snapshot.put("watch_hits", watchHits);
            snapshot.put("watch_rules", watchlist.watchedRuleCount());
            snapshot.put("queued_documents", outbox.count());

            final JSONArray networks = new JSONArray();
            final ArrayList<JSONObject> ordered = new ArrayList<>(nearby.values());
            for (int i = ordered.size() - 1; i >= 0; i--) {
                networks.put(ordered.get(i));
            }
            snapshot.put("networks", networks);
            if (lastAlert != null) snapshot.put("last_alert", lastAlert);
            wearBridge.publishSnapshot(snapshot);
        } catch (Exception ex) {
            Logging.error("Unable to build Wear OS StarIntel snapshot", ex);
        }
    }

    private static JSONObject nearbyRecord(
            final Network network,
            final Location location,
            final long now
    ) throws Exception {
        final JSONObject json = new JSONObject()
                .put("bssid", network.getBssid())
                .put("ssid", network.getSsid())
                .put("rssi", network.getLevel())
                .put("frequency_mhz", network.getFrequency())
                .put("channel", network.getChannel() == null ? JSONObject.NULL : network.getChannel())
                .put("seen_at", now);
        if (location != null) {
            json.put("lat", location.getLatitude());
            json.put("lon", location.getLongitude());
            json.put("accuracy_m", (double) location.getAccuracy());
        }
        return json;
    }
}
