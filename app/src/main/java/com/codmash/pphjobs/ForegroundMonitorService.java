package com.codmash.pphjobs;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ForegroundMonitorService extends Service {

    private static final String ACTION_REFRESH = "com.codmash.pphjobs.REFRESH_MONITOR";
    private static final long REFRESH_MS = 45_000L;
    private static final String DASHBOARD_URL = "https://www.peopleperhour.com/dashboard";
    private static final String PREFS = "pph_background_monitor";
    private static final String RECORD_SEPARATOR = "\u001E";
    private static final String FIELD_SEPARATOR = "\u001D";

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
        settings.setUserAgentString(settings.getUserAgentString() + " PPHJobsNotify/1.5");

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
                handler.postDelayed(() -> scanPage(view), 1500);
                handler.postDelayed(() -> scanPage(view), 4500);
                handler.postDelayed(() -> scanPage(view), 9000);
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
                "function abs(h){try{return h?new URL(h,location.href).href:'';}catch(e){return h||'';}}" +
                "function visible(el){if(!el)return false;var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&s.opacity!=='0'&&r.width>0&&r.height>0;}" +
                "function colorActive(el){if(!visible(el))return false;var s=getComputedStyle(el);var vals=[s.backgroundColor,s.color,s.borderTopColor,s.borderRightColor,s.borderBottomColor,s.borderLeftColor].join(' ').toLowerCase();return !/rgb\\(255, ?255, ?255\\)|rgb\\(0, ?0, ?0\\)|rgba\\(0, ?0, ?0, ?0\\)|transparent/.test(vals);}" +
                "function looksActive(el){if(!el||!visible(el))return false;var c=(String(el.className||'')+' '+String(el.id||'')+' '+String(el.getAttribute('aria-label')||'')+' '+String(el.getAttribute('title')||'')).toLowerCase();if(/unread|active|new|badge|dot|indicator|counter|has-notification|has_notification|pending/.test(c))return true;var t=clean(el.innerText||el.textContent||'');if(/^\\d+$/.test(t)&&parseInt(t,10)>0)return true;return colorActive(el);}" +
                "function inspect(type,selectors,target){var active=false,parts=[];selectors.forEach(function(q){try{document.querySelectorAll(q).forEach(function(el){var arr=[el];if(el.children)Array.prototype.forEach.call(el.children,function(c){arr.push(c);});arr.forEach(function(c){if(looksActive(c)){active=true;var x=clean(c.innerText||c.textContent||c.getAttribute('aria-label')||c.getAttribute('title')||String(c.className||''));if(x)parts.push(x.substring(0,120));}});});}catch(e){}});parts=parts.filter(function(v,i,a){return a.indexOf(v)===i;}).slice(0,8);if(window.PPHMonitor)PPHMonitor.onIndicatorState(type,active,parts.join('|'),target||location.href);}" +
                "var rows=[],seen={};" +
                "function add(el){if(!el)return;var txt=clean(el.innerText||el.textContent||el.getAttribute('aria-label')||el.getAttribute('title'));var href=abs(el.getAttribute('href')||((el.closest&&el.closest('a'))?el.closest('a').getAttribute('href'):'')||'');var cls=String(el.className||'').toLowerCase();var aria=String(el.getAttribute('aria-label')||'').toLowerCase();var all=(cls+' '+aria+' '+href.toLowerCase()+' '+txt.toLowerCase());if(!/unread|notification|message|workstream|inbox|badge|counter|proposal|offer|invoice|payment/.test(all))return;if(!txt)txt=href;if(!txt)return;if(txt.length>240)txt=txt.substring(0,240);var key=txt+'|'+href;if(seen[key])return;seen[key]=1;rows.push(txt+'\\u001D'+href);}" +
                "var selectors=['[class*=unread]','[class*=notification]','[class*=message]','[class*=workstream]','[class*=inbox]','[class*=badge]','[class*=counter]','[aria-label*=notification i]','[aria-label*=message i]','[aria-label*=workstream i]','a[href*=workstream]','a[href*=notification]','a[href*=message]','a[href*=inbox]'];" +
                "selectors.forEach(function(q){try{document.querySelectorAll(q).forEach(add);}catch(e){}});" +
                "inspect('messages',['a[href*=workstream]','a[href*=message]','a[href*=inbox]','[class*=message]','[class*=workstream]','[class*=inbox]','[aria-label*=message i]','[title*=message i]'],'https://www.peopleperhour.com/dashboard');" +
                "inspect('notifications',['a[href*=notification]','[class*=notification]','[aria-label*=notification i]','[title*=notification i]','[class*=bell]','[aria-label*=alert i]'],'https://www.peopleperhour.com/dashboard');" +
                "if(window.PPHMonitor)PPHMonitor.onSnapshot(rows.join('\\u001E'),location.href);" +
                "}catch(e){if(window.PPHMonitor)PPHMonitor.onSnapshot('',location.href);}})();";

        view.evaluateJavascript(script, null);
    }

    private final class MonitorBridge {
        @JavascriptInterface
        public void onIndicatorState(String type, boolean active, String signature, String url) {
            NotificationHelper.handleIndicatorState(
                    ForegroundMonitorService.this,
                    type,
                    active,
                    signature,
                    url == null || url.isEmpty() ? DASHBOARD_URL : url
            );
        }

        @JavascriptInterface
        public void onSnapshot(String snapshot, String pageUrl) {
            if (snapshot == null) snapshot = "";
            String value = snapshot.trim();

            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply();

            if (value.isEmpty()) return;

            LinkedHashSet<String> current = parseSnapshot(value);
            if (current.isEmpty()) return;

            String previousRaw = prefs.getString("snapshot_v15", null);
            prefs.edit().putString("snapshot_v15", value).apply();

            if (previousRaw == null) return;

            Set<String> previous = parseSnapshot(previousRaw);
            List<String> added = new ArrayList<>();
            for (String item : current) {
                if (!previous.contains(item)) added.add(item);
            }
            if (added.isEmpty()) return;

            int index = 0;
            for (String rawItem : added) {
                NotificationItem item = parseItem(rawItem);
                if (item.text.isEmpty()) continue;
                String targetUrl = item.url.startsWith("https://www.peopleperhour.com")
                        ? item.url
                        : (pageUrl != null && pageUrl.startsWith("https://www.peopleperhour.com") ? pageUrl : DASHBOARD_URL);

                NotificationHelper.show(
                        ForegroundMonitorService.this,
                        "pph_live_" + System.currentTimeMillis() + "_" + (index++),
                        "New PeoplePerHour activity",
                        item.text,
                        targetUrl
                );
            }
        }
    }

    private LinkedHashSet<String> parseSnapshot(String raw) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) return result;
        String[] records = raw.split(RECORD_SEPARATOR, -1);
        for (String record : records) {
            String item = record == null ? "" : record.trim();
            if (!item.isEmpty()) result.add(item);
        }
        return result;
    }

    private NotificationItem parseItem(String raw) {
        if (raw == null) return new NotificationItem("", "");
        int pos = raw.indexOf(FIELD_SEPARATOR);
        if (pos < 0) return new NotificationItem(raw.trim(), "");
        return new NotificationItem(raw.substring(0, pos).trim(), raw.substring(pos + FIELD_SEPARATOR.length()).trim());
    }

    private static final class NotificationItem {
        final String text;
        final String url;
        NotificationItem(String text, String url) {
            this.text = text == null ? "" : text;
            this.url = url == null ? "" : url;
        }
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
