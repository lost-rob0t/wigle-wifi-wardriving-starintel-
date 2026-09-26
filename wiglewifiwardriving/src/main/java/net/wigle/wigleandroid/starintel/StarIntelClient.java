package net.wigle.wigleandroid.starintel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Minimal client for the canonical StarIntel HTTP document boundary. */
public final class StarIntelClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    public boolean postBulk(
            final String baseUrl,
            final String token,
            final List<StarIntelOutbox.Item> items
    ) throws IOException {
        if (items == null || items.isEmpty()) return true;
        final JSONArray body = new JSONArray();
        try {
            for (StarIntelOutbox.Item item : items) {
                body.put(new JSONObject(item.payload));
            }
        } catch (Exception ex) {
            throw new IOException("Invalid StarIntel outbox JSON", ex);
        }
        final Request request = authorized(
                new Request.Builder()
                        .url(endpoint(baseUrl, "/api/v1/documents/bulk"))
                        .post(RequestBody.create(JSON, body.toString())),
                token
        ).build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }

    public boolean postDocument(
            final String baseUrl,
            final String token,
            final JSONObject document
    ) throws IOException {
        final Request request = authorized(
                new Request.Builder()
                        .url(endpoint(baseUrl, "/api/v1/documents"))
                        .post(RequestBody.create(JSON, document.toString())),
                token
        ).build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }

    public int health(final String baseUrl) throws IOException {
        final Request request = new Request.Builder()
                .url(endpoint(baseUrl, "/health"))
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            return response.code();
        }
    }

    private static Request.Builder authorized(final Request.Builder builder, final String token) {
        if (token != null && !token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.header("Content-Type", "application/json");
        return builder;
    }

    private static String endpoint(final String baseUrl, final String path) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("StarIntel base URL is empty");
        }
        final String base = baseUrl.trim().replaceAll("/+$", "");
        return base + path;
    }
}
