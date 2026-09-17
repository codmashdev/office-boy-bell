package com.codmash.pphjobs;

import android.content.Context;
import android.webkit.JavascriptInterface;

public class WebNotificationBridge {

    private final Context context;

    public WebNotificationBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    @JavascriptInterface
    public void notifyNative(String title, String body, String url) {
        String safeTitle = title == null || title.trim().isEmpty() ? "PeoplePerHour" : title.trim();
        String safeBody = body == null || body.trim().isEmpty() ? "New PeoplePerHour activity" : body.trim();
        String safeUrl = url != null && url.startsWith("https://www.peopleperhour.com")
                ? url
                : "https://www.peopleperhour.com/freelance-jobs";

        NotificationHelper.showOnce(
                context,
                "web|" + safeTitle + "|" + safeBody,
                safeTitle,
                safeBody,
                safeUrl
        );
    }
}
