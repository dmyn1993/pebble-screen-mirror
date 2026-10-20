package com.pebblemirror.companion.pebble;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import java.util.Base64;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.pebblemirror.companion.capture.FrameEncoder;
import com.pebblemirror.companion.protocol.WatchProtocol;
import com.pebblemirror.companion.protocol.WatchValue;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Legacy PebbleKit Classic bridge. The official Pebble app relays AppMessages
 * between the watch and any installed companion app using these broadcasts:
 *
 * <pre>
 *   us -> Pebble app:  com.getpebble.action.app.SEND     (uuid, transaction_id, msg_data)
 *   Pebble app -> us:  com.getpebble.action.app.RECEIVE  (uuid, transaction_id, msg_data)
 *   us -> Pebble app:  com.getpebble.action.app.ACK/NACK (transaction_id)
 *   Pebble app -> us:  com.getpebble.action.app.RECEIVE_ACK/NACK
 * </pre>
 *
 * msg_data is the original PebbleKit JSON array format.
 */
public final class LegacyPebbleLink implements AutoCloseable {
    private static final String TAG = "PebbleMirrorLegacy";

    public static final String PEBBLE_APP_PACKAGE = "coredevices.coreapp";
    private static final String ACTION_SEND = "com.getpebble.action.app.SEND";
    private static final String ACTION_START = "com.getpebble.action.app.START";
    private static final String ACTION_STOP = "com.getpebble.action.app.STOP";
    private static final String ACTION_RECEIVE = "com.getpebble.action.app.RECEIVE";
    private static final String ACTION_ACK = "com.getpebble.action.app.ACK";
    private static final String ACTION_NACK = "com.getpebble.action.app.NACK";
    private static final String ACTION_RECEIVE_ACK = "com.getpebble.action.app.RECEIVE_ACK";
    private static final String ACTION_RECEIVE_NACK = "com.getpebble.action.app.RECEIVE_NACK";
    private static final String EXTRA_UUID = "uuid";
    private static final String EXTRA_TRANSACTION_ID = "transaction_id";
    private static final String EXTRA_MSG_DATA = "msg_data";
    private static final long ACK_TIMEOUT_MS = 15000;

    public interface Listener {
        void onMessage(Map<Integer, WatchValue> message);
    }

    public interface FrameCallback {
        void onResult(boolean success);
    }

    /** Ask the official Pebble app to open the watchapp (idempotent). */
    public static void launchWatchapp(Context context, UUID watchappUuid) {
        Intent intent = new Intent(ACTION_START)
                .setPackage(PEBBLE_APP_PACKAGE)
                .putExtra(EXTRA_UUID, watchappUuid);
        try {
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to request watchapp launch", t);
        }
    }

    /** Ask the official Pebble app to close the watchapp (idempotent). */
    public static void stopWatchapp(Context context, UUID watchappUuid) {
        Intent intent = new Intent(ACTION_STOP)
                .setPackage(PEBBLE_APP_PACKAGE)
                .putExtra(EXTRA_UUID, watchappUuid);
        try {
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to request watchapp stop", t);
        }
    }

    private interface Job {
        void run(JobCallback callback);
    }

    private interface JobCallback {
        void done(boolean success);
    }

    private final Context context;
    private final UUID watchappUuid;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final Map<Integer, Pending> pending = new HashMap<>();

    private boolean sending;
    private boolean closed;
    private int nextTransactionId;
    private long callbackToken;
    private Runnable idleListener;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleBroadcast(intent);
        }
    };

    public LegacyPebbleLink(Context context, UUID watchappUuid, Listener listener) {
        this.context = context.getApplicationContext();
        this.watchappUuid = watchappUuid;
        this.listener = listener;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_RECEIVE);
        filter.addAction(ACTION_RECEIVE_ACK);
        filter.addAction(ACTION_RECEIVE_NACK);
        ContextCompat.registerReceiver(this.context, receiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
    }

    public void send(Map<Integer, WatchValue> message) {
        enqueue(new MessageJob(message, null));
    }

    public void sendFrame(FrameEncoder.TileFrame frame, int maxInboxBytes,
                          FrameCallback callback) {
        List<Map<Integer, WatchValue>> messages =
                WatchProtocol.frameMessages(frame, maxInboxBytes);
        if (messages.isEmpty()) {
            if (callback != null) {
                callback.onResult(true);
            }
            return;
        }
        enqueue(new FrameJob(messages, callback));
    }

    public boolean isBusy() {
        synchronized (lock) {
            return sending || !queue.isEmpty();
        }
    }

    public void setIdleListener(Runnable listener) {
        idleListener = listener;
    }

    public void clear() {
        synchronized (lock) {
            queue.clear();
        }
    }

    private void enqueue(Job job) {
        if (closed) {
            return;
        }
        synchronized (lock) {
            queue.add(job);
        }
        main.post(this::drain);
    }

    private void drain() {
        if (closed) {
            return;
        }

        final Job job;
        final long token;
        synchronized (lock) {
            if (sending || queue.isEmpty()) {
                return;
            }
            job = queue.poll();
            sending = true;
            token = ++callbackToken;
        }

        try {
            job.run(success -> main.post(() -> {
                boolean idle;
                synchronized (lock) {
                    if (token != callbackToken) {
                        return;
                    }
                    sending = false;
                    if (!success) {
                        queue.clear();
                    }
                    idle = queue.isEmpty();
                }
                if (!success) {
                    Log.w(TAG, "AppMessage exchange failed; queued messages dropped");
                }
                if (idle && idleListener != null) {
                    main.post(idleListener);
                }
                drain();
            }));
        } catch (Throwable t) {
            Log.e(TAG, "AppMessage job failed", t);
            main.post(() -> {
                synchronized (lock) {
                    sending = false;
                    queue.clear();
                }
                drain();
            });
        }
    }

    private void sendOne(Map<Integer, WatchValue> message, JobCallback callback) {
        final int transactionId;
        synchronized (lock) {
            int id = nextTransactionId;
            nextTransactionId = (nextTransactionId + 1) & 0xff;
            while (pending.containsKey(id)) {
                id = nextTransactionId;
                nextTransactionId = (nextTransactionId + 1) & 0xff;
            }
            transactionId = id;
        }

        final String json;
        try {
            json = encodeJson(message);
        } catch (Throwable t) {
            Log.e(TAG, "Unable to serialize AppMessage", t);
            callback.done(false);
            return;
        }

        Pending pendingMessage = new Pending(callback);
        pending.put(transactionId, pendingMessage);

        Intent intent = new Intent(ACTION_SEND)
                .setPackage(PEBBLE_APP_PACKAGE)
                .putExtra(EXTRA_UUID, watchappUuid)
                .putExtra(EXTRA_TRANSACTION_ID, transactionId)
                .putExtra(EXTRA_MSG_DATA, json);
        try {
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            pending.remove(transactionId);
            Log.e(TAG, "Unable to broadcast AppMessage", t);
            callback.done(false);
            return;
        }

        main.postDelayed(() -> {
            Pending timedOut = pending.remove(transactionId);
            if (timedOut != null) {
                Log.w(TAG, "AppMessage ACK timed out for transaction " + transactionId);
                timedOut.callback.done(false);
            }
        }, ACK_TIMEOUT_MS);
    }

    private void handleBroadcast(Intent intent) {
        if (closed) {
            return;
        }
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        if (ACTION_RECEIVE.equals(action)) {
            handleIncoming(intent);
        } else if (ACTION_RECEIVE_ACK.equals(action) || ACTION_RECEIVE_NACK.equals(action)) {
            int transactionId = intent.getIntExtra(EXTRA_TRANSACTION_ID, -1);
            Pending pendingMessage = pending.remove(transactionId);
            if (pendingMessage != null) {
                pendingMessage.callback.done(ACTION_RECEIVE_ACK.equals(action));
            }
        }
    }

    private void handleIncoming(Intent intent) {
        UUID uuid = readUuid(intent);
        int transactionId = intent.getIntExtra(EXTRA_TRANSACTION_ID, -1);
        String json = intent.getStringExtra(EXTRA_MSG_DATA);
        boolean ack = false;
        if (uuid == null || !uuid.equals(watchappUuid) || json == null) {
            Log.w(TAG, "Ignoring malformed inbound AppMessage");
        } else {
            try {
                Map<Integer, WatchValue> message = decodeJson(json);
                if (listener != null) {
                    listener.onMessage(message);
                }
                ack = true;
            } catch (Throwable t) {
                Log.e(TAG, "Unable to parse inbound AppMessage", t);
            }
        }

        if (transactionId >= 0) {
            Intent result = new Intent(ack ? ACTION_ACK : ACTION_NACK)
                    .setPackage(PEBBLE_APP_PACKAGE)
                    .putExtra(EXTRA_TRANSACTION_ID, transactionId);
            try {
                context.sendBroadcast(result);
            } catch (Throwable t) {
                Log.w(TAG, "Unable to send AppMessage ACK", t);
            }
        }
    }

    private static UUID readUuid(Intent intent) {
        try {
            Object value = intent.getSerializableExtra(EXTRA_UUID);
            if (value instanceof UUID) {
                return (UUID) value;
            }
            String text = intent.getStringExtra(EXTRA_UUID);
            return text == null ? null : UUID.fromString(text);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String encodeJson(Map<Integer, WatchValue> message) throws Exception {
        JSONArray array = new JSONArray();
        for (Map.Entry<Integer, WatchValue> entry : message.entrySet()) {
            WatchValue value = entry.getValue();
            JSONObject object = new JSONObject();
            object.put("key", entry.getKey());
            if (value.type == WatchValue.TYPE_BYTES) {
                object.put("type", "bytes");
                object.put("length", 0);
                object.put("value", Base64.getEncoder().encodeToString(value.bytes));
            } else if (value.type == WatchValue.TYPE_STRING) {
                object.put("type", "string");
                object.put("length", 0);
                object.put("value", value.text);
            } else {
                object.put("type", value.type == WatchValue.TYPE_INT ? "int" : "uint");
                object.put("length", value.width);
                object.put("value", value.number);
            }
            array.put(object);
        }
        return array.toString();
    }

    public static Map<Integer, WatchValue> decodeJson(String json) throws Exception {
        Map<Integer, WatchValue> out = new HashMap<>();
        JSONArray array = new JSONArray(json);
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.getJSONObject(i);
            int key = object.getInt("key");
            String type = object.getString("type");
            int length = object.optInt("length", 0);
            if ("bytes".equals(type)) {
                byte[] bytes = Base64.getDecoder().decode(object.getString("value"));
                out.put(key, WatchValue.bytes(bytes));
            } else if ("string".equals(type)) {
                out.put(key, WatchValue.text(object.getString("value")));
            } else if ("uint".equals(type)) {
                out.put(key, WatchValue.uint(length, object.getLong("value")));
            } else if ("int".equals(type)) {
                out.put(key, WatchValue.sint(length, object.getLong("value")));
            }
        }
        return out;
    }

    @Override
    public void close() {
        closed = true;
        try {
            context.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        synchronized (lock) {
            queue.clear();
            pending.clear();
        }
        main.removeCallbacksAndMessages(null);
    }

    private static final class Pending {
        final JobCallback callback;

        Pending(JobCallback callback) {
            this.callback = callback;
        }
    }

    private final class MessageJob implements Job {
        private final Map<Integer, WatchValue> message;
        private final FrameCallback frameCallback;

        MessageJob(Map<Integer, WatchValue> message, FrameCallback frameCallback) {
            this.message = message;
            this.frameCallback = frameCallback;
        }

        @Override
        public void run(JobCallback callback) {
            sendOne(message, success -> {
                if (frameCallback != null) {
                    frameCallback.onResult(success);
                }
                callback.done(success);
            });
        }
    }

    private final class FrameJob implements Job {
        private final List<Map<Integer, WatchValue>> messages;
        private final FrameCallback frameCallback;

        FrameJob(List<Map<Integer, WatchValue>> messages, FrameCallback frameCallback) {
            this.messages = messages;
            this.frameCallback = frameCallback;
        }

        @Override
        public void run(JobCallback callback) {
            sendAt(0, callback);
        }

        private void sendAt(int index, JobCallback callback) {
            if (index >= messages.size()) {
                if (frameCallback != null) {
                    frameCallback.onResult(true);
                }
                callback.done(true);
                return;
            }
            sendOne(messages.get(index), success -> {
                if (!success) {
                    if (frameCallback != null) {
                        frameCallback.onResult(false);
                    }
                    callback.done(false);
                } else {
                    sendAt(index + 1, callback);
                }
            });
        }
    }
}
