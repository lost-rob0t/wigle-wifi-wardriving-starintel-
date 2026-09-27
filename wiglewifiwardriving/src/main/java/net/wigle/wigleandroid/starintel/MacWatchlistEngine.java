package net.wigle.wigleandroid.starintel;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;

import net.wigle.wigleandroid.util.PreferenceKeys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scan-episode state machine for MAC/OUI watch rules.
 * A device fires once on entry, rearms after three completed scans without it.
 */
public final class MacWatchlistEngine {
    private static final int REARM_MISSED_SCANS = 3;

    public static final class Match {
        public final String rule;
        public final String bssid;
        public final boolean enteredRange;

        Match(final String rule, final String bssid, final boolean enteredRange) {
            this.rule = rule;
            this.bssid = bssid;
            this.enteredRange = enteredRange;
        }
    }

    private static final class State {
        boolean inRange;
        int missedScans;
    }

    private final Context context;
    private final Map<String, State> states = new HashMap<>();
    private final Set<String> seenThisScan = new HashSet<>();
    private List<String> rules = Collections.emptyList();

    public MacWatchlistEngine(final Context context) {
        this.context = context.getApplicationContext();
    }

    public void beginScan() {
        reloadRules();
        seenThisScan.clear();
    }

    public Match onSeen(final String bssid) {
        final String normalized = normalize(bssid);
        final String rule = matchingRule(normalized);
        if (rule == null) return null;

        seenThisScan.add(normalized);
        final State state = states.computeIfAbsent(normalized, ignored -> new State());
        final boolean entered = !state.inRange;
        state.inRange = true;
        state.missedScans = 0;
        return new Match(rule, normalized, entered);
    }

    public void endScan() {
        for (Map.Entry<String, State> entry : states.entrySet()) {
            final State state = entry.getValue();
            if (!state.inRange) continue;
            if (seenThisScan.contains(entry.getKey())) {
                state.missedScans = 0;
                continue;
            }
            state.missedScans++;
            if (state.missedScans >= REARM_MISSED_SCANS) {
                state.inRange = false;
                state.missedScans = 0;
            }
        }
    }

    public int watchedRuleCount() {
        return rules.size();
    }

    private void reloadRules() {
        final SharedPreferences prefs =
                context.getSharedPreferences(PreferenceKeys.SHARED_PREFS, Context.MODE_PRIVATE);
        final String raw = prefs.getString(PreferenceKeys.PREF_ALERT_ADDRS, "[]");
        final String[] parsed;
        try {
            final String[] values = new Gson().fromJson(raw, String[].class);
            parsed = values == null ? new String[0] : values;
        } catch (RuntimeException ex) {
            rules = Collections.emptyList();
            return;
        }

        final ArrayList<String> next = new ArrayList<>();
        for (String value : parsed) {
            final String normalized = normalize(value);
            if (!normalized.isEmpty() && !next.contains(normalized)) {
                next.add(normalized);
            }
        }
        next.sort(Comparator.comparingInt(String::length).reversed());
        rules = Collections.unmodifiableList(next);
    }

    private String matchingRule(final String normalizedBssid) {
        for (String rule : rules) {
            if (normalizedBssid.equals(rule) || normalizedBssid.startsWith(rule)) {
                return rule;
            }
        }
        return null;
    }

    private static String normalize(final String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.US);
    }
}
