package net.wigle.wigleandroid.starintel;

import android.location.Location;
import android.net.wifi.ScanResult;
import android.os.Build;

import net.wigle.wigleandroid.model.Network;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Canonical StarIntel 0.10.1 documents emitted by the Android sensor. */
public final class StarIntelDocuments {
    public static final String SCHEMA_VERSION = "0.10.1";

    private StarIntelDocuments() {}

    public static String wirelessNetworkId(final String bssid) {
        return "starintel:wireless-network:wifi:" + compactMac(bssid);
    }

    public static JSONObject wirelessNetwork(
            final Network network,
            final Location location,
            final String dataset,
            final long now
    ) {
        final String timestamp = iso(now);
        final JSONObject data = new JSONObject();
        data.put("bssid", network.getBssid());
        data.put("ssid", network.getSsid());
        data.put("security", security(network));
        data.put("channel", network.getChannel() == null ? JSONObject.NULL : network.getChannel());
        data.put("frequency_mhz", network.getFrequency());
        data.put("band", band(network.getFrequency()));
        data.put("signal_dbm", network.getLevel());
        data.put("first_seen", timestamp);
        data.put("last_seen", timestamp);
        data.put("observations", 1);
        if (location != null) {
            data.put("latitude", location.getLatitude());
            data.put("longitude", location.getLongitude());
            data.put("location_accuracy_m", (double) location.getAccuracy());
        }

        return envelope(
                wirelessNetworkId(network.getBssid()),
                dataset,
                "wireless-network",
                timestamp,
                data,
                sensorSources(timestamp),
                new JSONArray()
        );
    }

    public static JSONObject observation(
            final Network network,
            final ScanResult result,
            final Location location,
            final String dataset,
            final long now
    ) {
        final String timestamp = iso(now);
        final String networkId = wirelessNetworkId(network.getBssid());
        final String observationId = "starintel:observation:wigle:" +
                compactMac(network.getBssid()) + ":" + UUID.randomUUID();

        final JSONObject value = new JSONObject();
        value.put("bssid", network.getBssid());
        value.put("ssid", network.getSsid());
        value.put("signal_dbm", network.getLevel());
        value.put("frequency_mhz", network.getFrequency());
        value.put("channel", network.getChannel() == null ? JSONObject.NULL : network.getChannel());
        value.put("security", security(network));
        value.put("capabilities", network.getCapabilities());
        value.put("passpoint", network.isPasspoint());
        value.put("rcois", network.getRcois() == null ? JSONObject.NULL : network.getRcois());
        if (result != null) {
            value.put("scan_timestamp_us", result.timestamp);
        }
        if (location != null) {
            final JSONObject geo = new JSONObject();
            geo.put("lat", location.getLatitude());
            geo.put("lon", location.getLongitude());
            geo.put("accuracy_meters", (double) location.getAccuracy());
            if (location.hasAltitude()) geo.put("altitude", location.getAltitude());
            value.put("geospatial", geo);
        }

        final JSONObject data = new JSONObject();
        data.put("observer_id", "android:" + Build.MANUFACTURER + ":" + Build.MODEL);
        data.put("subject_id", networkId);
        data.put("observation_type", "wifi-scan");
        data.put("value", value);
        data.put("unit", "dBm");
        data.put("method", "android-wifimanager-scan");
        data.put("instrument", Build.MANUFACTURER + " " + Build.MODEL);
        data.put("observed_at", timestamp);

        final JSONArray evidence = new JSONArray();
        evidence.put(new JSONObject()
                .put("evidence_id", observationId + ":scan")
                .put("kind", "wireless-scan")
                .put("observation", "Wi-Fi BSSID observed by Android WifiManager")
                .put("collected_at", timestamp)
                .put("observed_at", timestamp)
                .put("metadata", value));

        return envelope(
                observationId,
                dataset,
                "observation",
                timestamp,
                data,
                sensorSources(timestamp),
                evidence
        );
    }

    public static JSONObject alert(
            final Network network,
            final JSONObject observation,
            final String watchRule,
            final int severity,
            final String dataset,
            final long now
    ) {
        final String timestamp = iso(now);
        final String observationId = observation.optString("_id");
        final String ruleId = "wigle-mac-watch:" + shortHash(watchRule);
        final String alertId = "starintel:alert:wigle-mac-watch:" +
                shortHash(watchRule) + ":" + shortHash(observationId);

        final JSONArray subjectIds = new JSONArray()
                .put(wirelessNetworkId(network.getBssid()))
                .put(observationId);

        final JSONObject data = new JSONObject();
        data.put("alert_type", "wireless-mac-in-range");
        data.put("subject_ids", subjectIds);
        data.put("condition", "watched MAC/OUI observed in Wi-Fi scan");
        data.put("threshold", severity);
        data.put("triggered_at", timestamp);
        data.put("severity", severity);
        data.put("status", "triggered");
        data.put("acknowledged_by", new JSONArray());
        data.put("rule_id", ruleId);
        data.put("trigger_event_id", observationId);
        data.put("first_triggered_at", timestamp);
        data.put("last_triggered_at", timestamp);
        data.put("occurrence_count", 1);

        final JSONArray evidence = new JSONArray();
        evidence.put(new JSONObject()
                .put("evidence_id", alertId + ":rule")
                .put("kind", "rule-match")
                .put("collected_at", timestamp)
                .put("metadata", new JSONObject()
                        .put("rule_id", ruleId)
                        .put("watch_value", watchRule)
                        .put("bssid", network.getBssid())
                        .put("signal_dbm", network.getLevel())));

        final JSONObject alert = envelope(
                alertId,
                dataset,
                "alert",
                timestamp,
                data,
                new JSONArray().put(new JSONObject()
                        .put("kind", "watchlist")
                        .put("name", "wigle-android")
                        .put("access_method", "wifi-scan-watchlist")
                        .put("accessed_at", timestamp)),
                evidence
        );
        alert.put("extensions", new JSONObject().put("wigle", new JSONObject()
                .put("watch_value", watchRule)
                .put("bssid", network.getBssid())
                .put("ssid", network.getSsid())
                .put("signal_dbm", network.getLevel())
                .put("observation_id", observationId)));
        return alert;
    }

    private static JSONObject envelope(
            final String id,
            final String dataset,
            final String dtype,
            final String timestamp,
            final JSONObject data,
            final JSONArray sources,
            final JSONArray evidence
    ) {
        return new JSONObject()
                .put("_id", id)
                .put("dataset", dataset == null || dataset.trim().isEmpty() ? "wigle-android" : dataset.trim())
                .put("dtype", dtype)
                .put("schema_version", SCHEMA_VERSION)
                .put("version", 1)
                .put("date_added", timestamp)
                .put("date_updated", timestamp)
                .put("sources", sources)
                .put("evidence", evidence)
                .put("data", data);
    }

    private static JSONArray sensorSources(final String timestamp) {
        return new JSONArray().put(new JSONObject()
                .put("kind", "sensor")
                .put("sensor", "android-wifi")
                .put("name", "WiGLE Android StarIntel fork")
                .put("access_method", "WifiManager.ScanResult")
                .put("accessed_at", timestamp));
    }

    private static String security(final Network network) {
        final String caps = network.getCapabilities() == null ? "" :
                network.getCapabilities().toUpperCase(Locale.US);
        if (caps.contains("SAE") && (caps.contains("WPA2") || caps.contains("PSK"))) {
            return "wpa2wpa3-psk";
        }
        switch (network.getCrypto()) {
            case Network.CRYPTO_WEP:
                return "wep";
            case Network.CRYPTO_WPA:
                return "wpa-psk";
            case Network.CRYPTO_WPA2:
                return caps.contains("EAP") ? "wpa2-enterprise" : "wpa2-psk";
            case Network.CRYPTO_WPA3:
                return caps.contains("EAP") ? "wpa3-enterprise" : "wpa3-psk";
            case Network.CRYPTO_NONE:
                return "open";
            default:
                return "unknown";
        }
    }

    private static String band(final int frequencyMhz) {
        if (frequencyMhz >= 2400 && frequencyMhz < 2500) return "2.4ghz";
        if (frequencyMhz >= 4900 && frequencyMhz < 5925) return "5ghz";
        if (frequencyMhz >= 5925 && frequencyMhz < 7125) return "6ghz";
        return "unknown";
    }

    private static String compactMac(final String value) {
        if (value == null) return "unknown";
        return value.replaceAll("[^0-9A-Fa-f]", "").toLowerCase(Locale.US);
    }

    private static String iso(final long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }

    private static String shortHash(final String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] bytes = digest.digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            final StringBuilder out = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                out.append(String.format(Locale.US, "%02x", bytes[i]));
            }
            return out.toString();
        } catch (Exception ex) {
            return Integer.toHexString(value == null ? 0 : value.hashCode());
        }
    }
}
