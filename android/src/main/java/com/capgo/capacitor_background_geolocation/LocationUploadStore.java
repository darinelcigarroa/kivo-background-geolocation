package com.capgo.capacitor_background_geolocation;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import com.getcapacitor.Logger;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

// KIVO fork addition: per-location HTTP upload pipeline.
//
// Mirrors the upstream GeofenceStore pattern but for continuous location pings
// during an active trip. POSTs happen from a WorkManager job (offline buffer +
// exponential backoff) entirely in native code, so WebView throttling on
// Xiaomi/HyperOS no longer breaks live tracking.
//
// Configured once per session from JS via BackgroundGeolocation.configureUpload.
// Cleared via BackgroundGeolocation.clearUpload.
final class LocationUploadStore {

    static final String EXTRA_LOCATION_PAYLOAD = "payload";

    private static final String PREFS_NAME = "KivoBackgroundGeolocationUpload";
    private static final String KEY_URL = "url";
    private static final String KEY_HEADERS = "headers";
    private static final String KEY_PAYLOAD = "commonPayload";
    private static final String KEY_MIN_INTERVAL_MS = "minIntervalMs";

    // The throttle resets if the process is recycled — that's fine, the first
    // ping after a relaunch should fire immediately.
    private static volatile long lastEnqueueMs = 0L;

    private LocationUploadStore() {}

    static void saveSetup(Context context, String url, JSONObject headers, JSONObject commonPayload, long minIntervalMs) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (url == null || url.isEmpty()) {
            editor.remove(KEY_URL);
        } else {
            editor.putString(KEY_URL, url);
        }
        editor.putString(KEY_HEADERS, headers == null ? "{}" : headers.toString());
        editor.putString(KEY_PAYLOAD, commonPayload == null ? "{}" : commonPayload.toString());
        editor.putLong(KEY_MIN_INTERVAL_MS, minIntervalMs > 0 ? minIntervalMs : 5000L);
        editor.apply();
        lastEnqueueMs = 0L;
    }

    static void clear(Context context) {
        prefs(context).edit().clear().apply();
        lastEnqueueMs = 0L;
    }

    static String getUrl(Context context) {
        return prefs(context).getString(KEY_URL, null);
    }

    static long getMinIntervalMs(Context context) {
        return prefs(context).getLong(KEY_MIN_INTERVAL_MS, 5000L);
    }

    static void enqueueUpload(Context context, Location location) {
        String url = getUrl(context);
        if (url == null || url.isEmpty() || location == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long minIntervalMs = getMinIntervalMs(context);
        if (now - lastEnqueueMs < minIntervalMs) {
            return;
        }
        lastEnqueueMs = now;

        JSONObject payload;
        try {
            payload = buildLocationPayload(context, location, now);
        } catch (JSONException e) {
            Logger.error("Failed to build location upload payload", e);
            return;
        }

        Data inputData = new Data.Builder().putString(EXTRA_LOCATION_PAYLOAD, payload.toString()).build();
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(LocationUploadWorker.class)
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build();
        WorkManager.getInstance(context).enqueue(request);
    }

    static void sendUpload(Context context, JSONObject payload) throws IOException {
        String urlString = getUrl(context);
        if (urlString == null || urlString.isEmpty()) {
            return;
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Content-Length", String.valueOf(body.length));

            JSONObject headers = jsonFromString(prefs(context).getString(KEY_HEADERS, "{}"));
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = headers.optString(key, null);
                if (value != null) {
                    connection.setRequestProperty(key, value);
                }
            }

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body);
            }
            int responseCode = connection.getResponseCode();
            Logger.debug("Location upload POST finished with response code: " + responseCode);

            // 4xx = client error — retrying won't change the outcome. Drop the
            // payload silently so WorkManager doesn't queue exponential retries
            // for what is fundamentally a bad request. The 401/403/404 subset
            // additionally purges the stored config so subsequent fixes are
            // no-ops until JS reconfigures with fresh credentials.
            if (responseCode >= 400 && responseCode < 500) {
                if (responseCode == 401 || responseCode == 403 || responseCode == 404) {
                    Logger.error("Location upload rejected (" + responseCode + "), clearing upload config", null);
                    clear(context);
                } else {
                    Logger.error("Location upload rejected (" + responseCode + "), dropping payload", null);
                }
                return;
            }

            // 5xx or network-level oddity — return retriable so WorkManager backs off.
            if (responseCode < HttpURLConnection.HTTP_OK || responseCode >= HttpURLConnection.HTTP_MULT_CHOICE) {
                throw new IOException("Location upload POST failed with response code: " + responseCode);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static JSONObject buildLocationPayload(Context context, Location location, long timestampMs) throws JSONException {
        JSONObject payload = new JSONObject();
        JSONObject common = jsonFromString(prefs(context).getString(KEY_PAYLOAD, "{}"));
        Iterator<String> keys = common.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            payload.put(key, common.get(key));
        }
        // Compact KIVO-style schema. Note that we send `lat`/`lng`/`heading`
        // (not `latitude`/`longitude`/`bearing` like the upstream capgo plugin)
        // to match the backend validator at ServiceController::updateLocation.
        payload.put("lat", location.getLatitude());
        payload.put("lng", location.getLongitude());
        if (location.hasAccuracy()) {
            payload.put("accuracy", location.getAccuracy());
        }
        if (location.hasSpeed()) {
            payload.put("speed", location.getSpeed());
        }
        if (location.hasBearing()) {
            payload.put("heading", location.getBearing());
        }
        payload.put("reason", "native");
        return payload;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static JSONObject jsonFromString(String value) {
        if (value == null || value.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(value);
        } catch (JSONException exception) {
            return new JSONObject();
        }
    }
}
