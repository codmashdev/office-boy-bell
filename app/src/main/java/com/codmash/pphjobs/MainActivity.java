package com.codmash.pphjobs;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String START_URL = "https://www.peopleperhour.com/freelance-jobs";
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1002;

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        NotificationHelper.createChannels(this);
        NotificationSyncService.schedule(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3)
        );
        progressParams.gravity = android.view.Gravity.TOP;
        root.addView(progressBar, progressParams);

        setContentView(root);
        configureWebView();

        String requestedUrl = getIntent() == null ? null : getIntent().getStringExtra("open_url");
        String initialUrl = requestedUrl != null && requestedUrl.startsWith("https://www.peopleperhour.com")
                ? requestedUrl
                : START_URL;

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(initialUrl);
        }

        root.postDelayed(this::showNotificationSetup, 700);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (notificationsAreEnabled()) {
            ForegroundMonitorService.start(this);
        }
    }

    private void showNotificationSetup() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                    .setTitle("Enable PeoplePerHour notifications")
                    .setMessage("Allow notifications so PeoplePerHour message, notification and colored-dot activity can appear in your notification tray and on your lock screen.")
                    .setCancelable(false)
                    .setPositiveButton("Enable notifications", (dialog, which) ->
                            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST))
                    .setNegativeButton("Not now", null)
                    .show();
            return;
        }

        if (notificationsAreEnabled()) {
            ForegroundMonitorService.start(this);
            NotificationHelper.showEnabledNoticeOnce(this);
        } else {
            showNotificationSettingsDialog();
        }
    }

    private boolean notificationsAreEnabled() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        return manager != null && (Build.VERSION.SDK_INT < 24 || manager.areNotificationsEnabled());
    }

    private void showNotificationSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Notifications are off")
                .setMessage("Android currently has notifications disabled for this app. Open App notification settings and turn on Allow notifications.")
                .setPositiveButton("Open settings", (dialog, which) -> openNotificationSettings())
                .setNegativeButton("Later", null)
                .show();
    }

    private void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ForegroundMonitorService.start(this);
                NotificationHelper.show(this,
                        "permission_test_" + System.currentTimeMillis(),
                        "PPH notifications are working",
                        "The app is now watching PPH message, notification and colored-dot activity.",
                        START_URL);
                Toast.makeText(this, "Notifications and activity monitoring enabled", Toast.LENGTH_SHORT).show();
            } else {
                showNotificationSettingsDialog();
            }
        }
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new WebNotificationBridge(this), "PPHAndroid");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                injectNotificationWatcher(view);
                if (notificationsAreEnabled()) {
                    ForegroundMonitorService.refresh(MainActivity.this);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                Intent intent;
                try {
                    intent = params.createIntent();
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }

                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "Unable to open file picker", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimeType);
                    request.addRequestHeader("User-Agent", userAgent);

                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null) request.addRequestHeader("Cookie", cookie);

                    String filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
                    request.setTitle(filename);
                    request.setDescription("Downloading from PeoplePerHour");
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);

                    DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    manager.enqueue(request);
                    Toast.makeText(MainActivity.this, "Download started", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    openExternal(Uri.parse(url));
                }
            }
        });
    }

    private void injectNotificationWatcher(WebView view) {
        String script = "(function(){" +
                "if(window.__pphNativeWatcherV15)return;window.__pphNativeWatcherV15=true;" +
                "function clean(t){return (t||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){if(!el)return false;var s=getComputedStyle(el);var r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&s.opacity!=='0'&&r.width>0&&r.height>0;}" +
                "function colorActive(el){if(!visible(el))return false;var s=getComputedStyle(el);var vals=[s.backgroundColor,s.color,s.borderTopColor,s.borderRightColor,s.borderBottomColor,s.borderLeftColor].join(' ').toLowerCase();if(/rgba?\\(0, ?0, ?0, ?0\\)|transparent/.test(vals)){}return !/rgb\\(255, ?255, ?255\\)|rgb\\(0, ?0, ?0\\)|rgba\\(0, ?0, ?0, ?0\\)|transparent/.test(vals);}" +
                "function looksActive(el){if(!el||!visible(el))return false;var c=(String(el.className||'')+' '+String(el.id||'')+' '+String(el.getAttribute('aria-label')||'')+' '+String(el.getAttribute('title')||'')).toLowerCase();if(/unread|active|new|badge|dot|indicator|counter|has-notification|has_notification|pending/.test(c))return true;var t=clean(el.innerText||el.textContent||'');if(/^\\d+$/.test(t)&&parseInt(t,10)>0)return true;return colorActive(el);}" +
                "function inspect(type,selectors,url){var active=false,parts=[];selectors.forEach(function(q){try{document.querySelectorAll(q).forEach(function(el){var candidates=[el];if(el.children)Array.prototype.forEach.call(el.children,function(c){candidates.push(c);});candidates.forEach(function(c){if(looksActive(c)){active=true;var x=clean(c.innerText||c.textContent||c.getAttribute('aria-label')||c.getAttribute('title')||String(c.className||''));if(x)parts.push(x.substring(0,120));}});});}catch(e){}});parts=parts.filter(function(v,i,a){return a.indexOf(v)===i;}).slice(0,8);if(window.PPHAndroid)PPHAndroid.indicatorState(type,active,parts.join('|'),url||location.href);}" +
                "function scan(){try{" +
                "inspect('messages',[" +
                "'a[href*=workstream]','a[href*=message]','a[href*=inbox]'," +
                "'[class*=message]','[class*=workstream]','[class*=inbox]'," +
                "'[aria-label*=message i]','[title*=message i]'" +
                "],'https://www.peopleperhour.com/dashboard');" +
                "inspect('notifications',[" +
                "'a[href*=notification]','[class*=notification]'," +
                "'[aria-label*=notification i]','[title*=notification i]'," +
                "'[class*=bell]','[aria-label*=alert i]'" +
                "],'https://www.peopleperhour.com/dashboard');" +
                "}catch(e){}}" +
                "var timer=null;new MutationObserver(function(){clearTimeout(timer);timer=setTimeout(scan,250);}).observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['class','style','aria-label','title','data-count']});" +
                "setTimeout(scan,1200);setTimeout(scan,3500);setInterval(scan,10000);" +
                "})();";
        view.evaluateJavascript(script, null);
    }

    private boolean handleUrl(Uri uri) {
        if (uri == null) return false;

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();

        if (scheme.equals("http") || scheme.equals("https")) {
            if (host.equals("peopleperhour.com") || host.endsWith(".peopleperhour.com")) {
                return false;
            }
            openExternal(uri);
            return true;
        }

        if (scheme.equals("tel") || scheme.equals("mailto") || scheme.equals("sms") ||
                scheme.equals("geo") || scheme.equals("whatsapp") || scheme.equals("intent")) {
            openExternal(uri);
            return true;
        }

        return false;
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent == null ? null : intent.getStringExtra("open_url");
        if (webView != null && url != null && url.startsWith("https://www.peopleperhour.com")) {
            webView.loadUrl(url);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK) {
                results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("PPHAndroid");
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
