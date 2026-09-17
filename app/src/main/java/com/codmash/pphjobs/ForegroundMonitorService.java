package com.codmash.pphjobs;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class ForegroundMonitorService extends Service {

    private static final String ACTION_REFRESH = "com.codmash.pphjobs.REFRESH_MONITOR";
    private static final long REFRESH_MS = 60_000L;
    private static final String DASHBOARD_URL = "https://www.peopleperhour.com/dashboard";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView monitorWebView;
    private boolean loading = false;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshNow();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, ForegroundMonitorService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void refresh(Context context) {
        Intent intent = new Intent(context, ForegroundMonitorService.class);
        intent.setAction(ACTION_REFRESH);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.createChannels(this);
        Notification serviceNotification = NotificationHelper.buildMonitoringNotification(this);
        startForeground(NotificationHelper.MONITOR_NOTIFICATION_ID, serviceNotification);
        createMonitorWebView();
        handler.post(refreshRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            handler.removeCallbacks(refreshRunnable);
            handler.post(refreshRunnable);
        }
        return START_STICKY;
    }

    private void createMonitorWebView() {
        monitorWebView = new WebView(getApplicationContext());
        WebSettings settings = monitorWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " PPHJobsNotify/1.3");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(monitorWebView, true);

        monitorWebView.addJavascriptInterface(new MonitorBridge(), "PPHMonitor");
        monitorWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                loading = false;
                CookieManager.getInstance().flush();
                handler.postDelayed(() -> scanPage(view), 1800);
                handler.postDelayed(() -> scanPage(view), 5000);
            }
        });
    }

    private void refreshNow() {
        if (monitorWebView == null || loading) return;
        loading = true;
        monitorWebView.loadUrl(DASHBOARD_URL);
        handler.postDelayed(() -> loading = false, 20_000);
    }

    private void scanPage(WebView view) {
        if (view == null) return;
        String script = "(function(){try{" +
                "function clean(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "var parts=[];" +
                "var selectors=[" +
                "'[class*=unread]'," +
                "'[class*=notification]'," +
                "'[class*=message]'," +
                "'[class*=workstream]'," +
                "'[class*=badge]'," +
                "'[class*=counter]'," +
                "'[aria-label*=notification i]'," +
                "'[aria-label*=message i]'," +
                "'a[href*=workstream]'," +
                "'a[href*=notification]'," +
                "'a[href*=message]'," +
                "'a[href*=inbox]'" +
                "];" +
                "selectors.forEach(function(q){" +
                "document.querySelectorAll(q).forEach(function(el){" +
                "var txt=clean(el.innerText||el.textContent||el.getAttribute('aria-label')||el.getAttribute('title'));" +
                "var cls=String(el.className||'').toLowerCase();" +
                "var aria=String(el.getAttribute('aria-label')||'').toLowerCase();" +
                "var href=String(el.getAttribute('href')||'');" +
                "if(!txt && !href)return;" +
                "if(txt.length>180)txt=txt.substring(0,180);" +
                "if(/unread|notification|message|workstream|badge|counter/.test(cls+' '+aria+' '+href.toLowerCase())){" +
                "parts.push((txt||href));" +
                "}" +
                "});" +
                "});" +
                "var title=clean(document.title);if(title)parts.push('TITLE:'+title);" +
                "parts=parts.filter(function(v,i,a){return v && a.indexOf(v)===i;}).slice(0,25);" +
                "var summary=parts.join(' || ');" +
                "if(window.PPHMonitor)PPHMonitor.onSnapshot(summary,location.href);" +
                "}catch(e){if(window.PPHMonitor)PPHMonitor.onSnapshot('',location.href);}})();";
        view.evaluateJavascript(script, null);
    }

    private final class MonitorBridge {
        @JavascriptInterface
        public void onSnapshot(String snapshot, String url) {
            if (snapshot == null) snapshot = "";
            final String value = snapshot.trim();
            getSharedPreferences("pph_background_monitor", MODE_PRIVATE)
                    .edit().putLong("last_check", System.currentTimeMillis()).apply();

            if (value.isEmpty()) return;

            android.content.SharedPreferences prefs = getSharedPreferences("pph_background_monitor", MODE_PRIVATE);
            String previous = prefs.getString("snapshot", null);
            prefs.edit().putString("snapshot", value).apply();

            if (previous == null || previous.equals(value)) return;

            String message = findMeaningfulDifference(previous, value);
            if (message == null || message.isEmpty()) return;

            NotificationHelper.showOnce(
                    ForegroundMonitorService.this,
                    "background_snapshot_" + Integer.toHexString(value.hashCode()),
                    "New PeoplePerHour activity",
                    message,
                    url == null || url.isEmpty() ? DASHBOARD_URL : url
            );
        }
    }

    private String findMeaningfulDifference(String oldValue, String newValue) {
        String[] oldParts = oldValue.split(" \\|\\| ");
        String[] newParts = newValue.split(" \\|\\| ");
        java.util.HashSet<String> oldSet = new java.util.HashSet<>();
        for (String s : oldParts) oldSet.add(s.trim());

        java.util.ArrayList<String> added = new java.util.ArrayList<>();
        for (String s : newParts) {
            String t = s.trim();
            if (t.isEmpty() || oldSet.contains(t)) continue;
            String lower = t.toLowerCase();
            if (lower.startsWith("title:") && added.size() > 0) continue;
            if (t.length() > 160) t = t.substring(0, 160) + "…";
            added.add(t);
            if (added.size() >= 3) break;
        }

        if (added.isEmpty()) return null;
        return android.text.TextUtils.join(" • ", added);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (monitorWebView != null) {
            monitorWebView.stopLoading();
            monitorWebView.removeJavascriptInterface("PPHMonitor");
            monitorWebView.destroy();
            monitorWebView = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
