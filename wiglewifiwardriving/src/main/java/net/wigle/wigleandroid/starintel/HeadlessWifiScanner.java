package net.wigle.wigleandroid.starintel;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.core.content.ContextCompat;

import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.util.Logging;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground-service-owned Wi-Fi scanner used when the activity is absent.
 * The regular WiGLE activity remains the rich local-DB path while visible;
 * this keeps live StarIntel ingest/watch alerts alive when the UI is gone.
 */
public final class HeadlessWifiScanner {
    private static final long SCAN_PERIOD_MS = 15000L;

    private final Context context;
    private final WifiManager wifiManager;
    private final LocationManager locationManager;
    private final StarIntelRuntime runtime;
    private final HandlerThread thread;
    private final Handler handler;
    private final AtomicBoolean active = new AtomicBoolean(false);

    private volatile Location lastLocation;
    private boolean receiverRegistered;
    private boolean locationRegistered;

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context ignored, final Intent intent) {
            if (!active.get()) return;
            handleScanResults();
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(final Location location) {
            if (location != null) lastLocation = location;
        }

        @Override public void onStatusChanged(final String provider, final int status, final Bundle extras) {}
        @Override public void onProviderEnabled(final String provider) {}
        @Override public void onProviderDisabled(final String provider) {}
    };

    private final Runnable scanLoop = new Runnable() {
        @Override
        public void run() {
            if (!active.get()) return;
            try {
                if (wifiManager != null && wifiManager.isWifiEnabled()) {
                    wifiManager.startScan();
                }
            } catch (SecurityException ex) {
                Logging.warn("Headless Wi-Fi scan permission unavailable");
            } catch (RuntimeException ex) {
                Logging.warn("Headless Wi-Fi scan failed: " + ex.getClass().getSimpleName());
            } finally {
                if (active.get()) handler.postDelayed(this, SCAN_PERIOD_MS);
            }
        }
    };

    public HeadlessWifiScanner(final Context context) {
        this.context = context.getApplicationContext();
        this.wifiManager =
                (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        this.locationManager =
                (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
        this.runtime = StarIntelRuntime.get(this.context);
        this.thread = new HandlerThread("wigle-headless-wifi");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    public synchronized void setActive(final boolean enabled) {
        if (enabled == active.get()) return;
        if (enabled) start();
        else stop();
    }

    public boolean isActive() {
        return active.get();
    }

    public synchronized void close() {
        stop();
        thread.quitSafely();
    }

    private void start() {
        if (!hasLocationPermission()) {
            Logging.warn("Headless Wi-Fi scanner waiting for location permission");
            return;
        }
        active.set(true);
        registerReceiver();
        registerLocation();
        handler.removeCallbacks(scanLoop);
        handler.post(scanLoop);
        Logging.info("Headless Wi-Fi scanner active");
    }

    private void stop() {
        active.set(false);
        handler.removeCallbacks(scanLoop);
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(scanReceiver);
            } catch (IllegalArgumentException ignored) {
                // already unregistered
            }
            receiverRegistered = false;
        }
        if (locationRegistered && locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
                // permission changed
            }
            locationRegistered = false;
        }
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        final IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        ContextCompat.registerReceiver(
                context, scanReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    private void registerLocation() {
        if (locationRegistered || locationManager == null || !hasLocationPermission()) return;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 5000L, 0f, locationListener, thread.getLooper());
                final Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (gps != null) lastLocation = gps;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 5000L, 0f, locationListener, thread.getLooper());
                final Location network =
                        locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (network != null && (lastLocation == null ||
                        network.getTime() > lastLocation.getTime())) {
                    lastLocation = network;
                }
            }
            locationRegistered = true;
        } catch (SecurityException ex) {
            Logging.warn("Headless location permission unavailable");
        }
    }

    private void handleScanResults() {
        if (wifiManager == null || !hasLocationPermission()) return;
        final List<ScanResult> results;
        try {
            results = wifiManager.getScanResults();
        } catch (SecurityException ex) {
            Logging.warn("Headless Wi-Fi scan results permission unavailable");
            return;
        }

        runtime.beginWifiScan();
        if (results != null) {
            final Location location = lastLocation == null ? null : new Location(lastLocation);
            for (ScanResult result : results) {
                if (result == null || result.BSSID == null) continue;
                runtime.onWifiObservation(new Network(result), result, location);
            }
        }
        runtime.endWifiScan();
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
}
