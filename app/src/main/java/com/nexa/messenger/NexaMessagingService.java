package com.nexa.messenger;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Native FCM receiver.
 *
 * Messages are data-only (the backend sends no `notification` block), so this
 * runs for every message. When the app is in the FOREGROUND we skip — the web
 * app's own firebase-messaging SW + messaging.onMessage renders the toast /
 * system notification, and we must not double up. When the app is backgrounded
 * or killed, this builds the system notification so the user still gets alerted.
 */
public class NexaMessagingService extends FirebaseMessagingService {

    static final String DEFAULT_CHANNEL_ID = "nexa_default";
    static final String CALL_CHANNEL_ID = "nexa_calls";
    private static final String PREFS = "nexa_prefs";
    private static final String KEY_UID = "uid";
    private static final String KEY_FCM_TOKEN = "fcm_token";
    private static final String BACKEND_URL = "https://nexa-backend-e6pq.onrender.com";

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_FCM_TOKEN, token).apply();
        android.util.Log.i("NexaFCM", "onNewToken");
        String uid = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_UID, null);
        if (uid != null && !uid.isEmpty()) {
            postToken(uid, token);
        }
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        super.onMessageReceived(message);

        Map<String, String> data = message.getData();
        String title = data.get("title");
        String body = data.get("body");
        if (title == null || title.isEmpty()) title = "Nexa Messenger";
        if (body == null || body.isEmpty()) body = "You have a new message";

        boolean isCall = "true".equals(data.get("isCall"));

        // Calls ALWAYS notify, even in the foreground. This message carries an
        // android.notification block, so onMessageReceived only runs while the
        // app is foregrounded and the WebView can never show it (Android WebView
        // has no Web Push). Skipping here would mean calls never notify at all.
        // Messages still skip in the foreground: the web app shows those itself.
        if (MainActivity.isForeground && !isCall) return;
        String msgId = data.get("messageId");
        String chatUid = data.get("chatUid");
        if (chatUid == null || chatUid.isEmpty()) chatUid = data.get("senderUid");

        ensureChannels(this);

        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(
                    this, isCall ? CALL_CHANNEL_ID : DEFAULT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setColor(getResources().getColor(R.color.notif_accent))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(isCall ? NotificationCompat.CATEGORY_CALL : NotificationCompat.CATEGORY_MESSAGE);

            if (isCall) builder.setOngoing(true);

            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (chatUid != null && !chatUid.isEmpty()) open.putExtra("chatUid", chatUid);
            int reqCode = (msgId != null ? msgId.hashCode() : (int) (System.currentTimeMillis() & 0xffff));
            PendingIntent pi = PendingIntent.getActivity(
                    this, reqCode, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(pi);

            // Use the (tag, id, notification) overload so a message delivered to
            // several of the user's tokens collapses into ONE notification
            // (same stable tag the web SW uses).
            String tag = (!isCall && msgId != null && !msgId.isEmpty()) ? ("nexa-msg-" + msgId) : null;
            int notifId = isCall ? 2002 : (msgId != null ? msgId.hashCode() : 1001);
            NotificationManagerCompat.from(this).notify(tag, notifId, builder.build());
        } catch (SecurityException se) {
            // POST_NOTIFICATIONS not granted.
            android.util.Log.w("NexaFCM", "notify blocked: " + se.getMessage());
        } catch (Throwable t) {
            android.util.Log.w("NexaFCM", "notify failed: " + t.getMessage());
        }
    }

    /** Create the notification channels up-front so the OS doesn't file messages under "Miscellaneous". */
    static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(DEFAULT_CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    DEFAULT_CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH));
        }
        if (nm.getNotificationChannel(CALL_CHANNEL_ID) == null) {
            NotificationChannel call = new NotificationChannel(
                    CALL_CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_HIGH);
            call.setDescription("Incoming voice & video calls");
            // A ringing call should bypass silent mode / Do Not Disturb.
            call.setBypassDnd(true);
            nm.createNotificationChannel(call);
        }
    }

    private void postToken(final String uid, final String token) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(BACKEND_URL + "/api/save-token");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                JSONObject body = new JSONObject();
                body.put("uid", uid);
                body.put("token", token);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                android.util.Log.i("NexaFCM", "save-token (onNewToken) HTTP " + conn.getResponseCode());
            } catch (Throwable t) {
                android.util.Log.w("NexaFCM", "save-token (onNewToken) failed: " + t.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
