package net.wigle.wigleandroid.starintel;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Durable at-least-once outbox for StarIntel documents. */
public final class StarIntelOutbox extends SQLiteOpenHelper {
    private static final String DB_NAME = "starintel-outbox.db";
    private static final int DB_VERSION = 1;

    public static final class Item {
        public final String id;
        public final String kind;
        public final String payload;
        public final int attempts;

        Item(final String id, final String kind, final String payload, final int attempts) {
            this.id = id;
            this.kind = kind;
            this.payload = payload;
            this.attempts = attempts;
        }
    }

    public StarIntelOutbox(final Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(final SQLiteDatabase db) {
        db.execSQL("CREATE TABLE outbox (" +
                "id TEXT PRIMARY KEY," +
                "kind TEXT NOT NULL," +
                "payload TEXT NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "attempts INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX outbox_created_idx ON outbox(created_at)");
    }

    @Override
    public void onUpgrade(final SQLiteDatabase db, final int oldVersion, final int newVersion) {
        if (oldVersion != newVersion) {
            throw new IllegalStateException("Unsupported StarIntel outbox migration " +
                    oldVersion + " -> " + newVersion);
        }
    }

    public synchronized boolean enqueue(final JSONObject document, final String kind) {
        final String id = document.optString("_id", "");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("StarIntel document requires _id");
        }
        final ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("kind", kind == null ? "document" : kind);
        values.put("payload", document.toString());
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(
                "outbox", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L;
    }

    public synchronized List<Item> take(final int limit) {
        final ArrayList<Item> items = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "outbox",
                new String[]{"id", "kind", "payload", "attempts"},
                null, null, null, null,
                "created_at ASC",
                Integer.toString(Math.max(1, limit)))) {
            while (cursor.moveToNext()) {
                items.add(new Item(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getInt(3)
                ));
            }
        }
        return items;
    }

    public synchronized void delete(final List<Item> items) {
        if (items == null || items.isEmpty()) return;
        final SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Item item : items) {
                db.delete("outbox", "id = ?", new String[]{item.id});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void markAttempt(final List<Item> items) {
        if (items == null || items.isEmpty()) return;
        final SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Item item : items) {
                db.execSQL("UPDATE outbox SET attempts = attempts + 1 WHERE id = ?",
                        new Object[]{item.id});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized int count() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM outbox", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }
}
