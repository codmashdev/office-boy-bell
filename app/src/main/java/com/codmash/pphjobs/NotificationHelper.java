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
    private static final String PREFS = "pph_notification_prefs";

    private NotificationHelper() {}

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "PeoplePerHour Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("PeoplePerHour job and account activity alerts");
            channel.enableVibration(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    public static void showEnabledNoticeOnce(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean("enabled_notice_shown", false)) return;
        prefs.edit().putBoolean("enabled_notice_shown", true).apply();
        show(context,
                "pph_notifications_enabled",
                "PPH notifications enabled",
                "PeoplePerHour alerts can now appear on your notification tray and lock screen.",
                "https://www.peopleperhour.com/freelance-jobs");
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
        createChannel(context);

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("open_url", url == null ? "https://www.peopleperhour.com/freelance-jobs" : url);

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
                .setPriority(Notification.PRIORITY_HIGH);

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(requestCode, builder.build());
        }
    }
}
