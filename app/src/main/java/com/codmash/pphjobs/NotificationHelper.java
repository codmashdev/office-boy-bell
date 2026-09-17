package com.codmash.pphjobs;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public final class NotificationHelper {

    private static final String CHANNEL_ID = "pph_alerts";
    private static final String MONITOR_CHANNEL_ID = "pph_monitor";
    public static final int MONITOR_NOTIFICATION_ID = 71042;
    private static final String PREFS = "pph_notification_prefs";
    private static final String INDICATOR_PREFS = "pph_indicator_state";

    private NotificationHelper() {}

    public static void createChannel(Context context) {
        createChannels(context);
    }

    public static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;

            NotificationChannel alerts = new NotificationChannel(
                    CHANNEL_ID,
                    "PeoplePerHour Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            alerts.setDescription("PeoplePerHour messages, notifications, jobs and account activity");
            alerts.enableVibration(true);
            alerts.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            manager.createNotificationChannel(alerts);

            NotificationChannel monitor = new NotificationChannel(
                    MONITOR_CHANNEL_ID,
                    "PPH Background Monitor",
                    NotificationManager.IMPORTANCE_LOW
            );
            monitor.setDescription("Keeps PeoplePerHour activity monitoring active while the app is in the background");
            monitor.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            manager.createNotificationChannel(monitor);
        }
    }

    public static Notification buildMonitoringNotification(Context context) {
        createChannels(context);
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                MONITOR_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, MONITOR_CHANNEL_ID)
                : new Notification.Builder(context);

        return builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("PPH monitoring active")
                .setContentText("Watching PPH message and notification activity")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    public static void showEnabledNoticeOnce(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean("enabled_notice_shown_v15", false)) return;
        prefs.edit().putBoolean("enabled_notice_shown_v15", true).apply();
        show(context,
                "pph_notifications_enabled_v15",
                "PPH activity monitoring enabled",
                "The app now watches PPH message and notification indicators, including colored-dot changes.",
                "https://www.peopleperhour.com/freelance-jobs");
    }

    public static synchronized void handleIndicatorState(
            Context context,
            String type,
            boolean active,
            String signature,
            String url
    ) {
        if (context == null) return;

        String normalizedType = type == null ? "activity" : type.trim().toLowerCase();
        if (normalizedType.isEmpty()) normalizedType = "activity";

        String normalizedSignature = signature == null ? "" : signature.trim();
        if (normalizedSignature.length() > 1500) {
            normalizedSignature = normalizedSignature.substring(0, 1500);
        }

        SharedPreferences prefs = context.getSharedPreferences(INDICATOR_PREFS, Context.MODE_PRIVATE);
        String signatureKey = "signature_" + normalizedType;
        String activeKey = "active_" + normalizedType;
        boolean hasPrevious = prefs.contains(signatureKey) || prefs.contains(activeKey);
        String previousSignature = prefs.getString(signatureKey, "");
        boolean previousActive = prefs.getBoolean(activeKey, false);

        prefs.edit()
                .putString(signatureKey, normalizedSignature)
                .putBoolean(activeKey, active)
                .putLong("last_seen_" + normalizedType, System.currentTimeMillis())
                .apply();

        if (!hasPrevious) return;
        if (!active) return;

        boolean becameActive = !previousActive;
        boolean changedWhileActive = previousActive && !normalizedSignature.equals(previousSignature);
        if (!becameActive && !changedWhileActive) return;

        String title;
        String body;
        if (normalizedType.contains("message") || normalizedType.contains("inbox") || normalizedType.contains("workstream")) {
            title = "New PeoplePerHour message activity";
            body = "PeoplePerHour shows new message/WorkStream activity. Tap to check it.";
        } else if (normalizedType.contains("notification") || normalizedType.contains("alert") || normalizedType.contains("bell")) {
            title = "New PeoplePerHour notification";
            body = "PeoplePerHour shows new notification activity. Tap to check it.";
        } else {
            title = "New PeoplePerHour activity";
            body = "New activity was detected in your PeoplePerHour account.";
        }

        show(
                context,
                "indicator_" + normalizedType + "_" + System.currentTimeMillis(),
                title,
                body,
                url
        );
    }

    public static void showOnce(Context context, String key, String title, String body, String url) {
        if (key == null || key.trim().isEmpty()) key = title + "|" + body;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String prefKey = "seen_" + Integer.toHexString(key.hashCode());
        if (prefs.getBoolean(prefKey, false)) return;
        prefs.edit().putBoolean(prefKey, true).apply();
        show(context, key, title, body, url);
    }

    public static void show(Context context, String key, String title, String body, String url) {
        createChannels(context);

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("open_url", url == null ? "https://www.peopleperhour.com/dashboard" : url);

        int requestCode = key == null ? (int) System.currentTimeMillis() : key.hashCode();
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);

        builder.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title == null || title.isEmpty() ? "PeoplePerHour" : title)
                .setContentText(body == null ? "New PeoplePerHour activity" : body)
                .setStyle(new Notification.BigTextStyle().bigText(body == null ? "New PeoplePerHour activity" : body))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setPriority(Notification.PRIORITY_HIGH)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(requestCode, builder.build());
    }
}
