package com.codmash.pphjobs;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NotificationSyncService extends JobService {

    private static final int JOB_ID = 71041;
    private static final long INTERVAL_MS = 15L * 60L * 1000L;
    private static final String PREFS = "pph_sync_prefs";
    private static final String JOBS_URL = "https://www.peopleperhour.com/freelance-jobs";
    private static final String DASHBOARD_URL = "https://www.peopleperhour.com/dashboard";

    private static final Pattern JOB_PATTERN = Pattern.compile(
            "<a[^>]+href=[\"'](?:https?://(?:www\\.)?peopleperhour\\.com)?(/freelance-jobs/[^\"'#?]+(?:\\?[^\"']*)?)[\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern ACTIVITY_PATTERN = Pattern.compile(
            "<[^>]*(?:class|aria-label)=[\"'][^\"']*(?:unread|notification|workstream|message)[^\"']*[\"'][^>]*>(.{0,500}?)</[^>]+>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    public static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;

        for (JobInfo job : scheduler.getAllPendingJobs()) {
            if (job.getId() == JOB_ID) return;
        }

        JobInfo job = new JobInfo.Builder(
                JOB_ID,
                new ComponentName(context, NotificationSyncService.class)
        )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .build();

        scheduler.schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            boolean retry = false;
            try {
                performSync(getApplicationContext());
            } catch (Exception e) {
                retry = true;
            } finally {
                jobFinished(params, retry);
            }
        }, "pph-notification-sync").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    private static void performSync(Context context) {
        NotificationHelper.createChannel(context);
        syncJobs(context);
        syncAccountActivity(context);
    }

    private static void syncJobs(Context context) {
        try {
            String html = fetch(JOBS_URL, null);
            if (html == null || html.isEmpty()) return;

            LinkedHashMap<String, String> jobs = extractJobs(html);
            if (jobs.isEmpty()) return;

            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Set<String> oldJobs = prefs.getStringSet("known_jobs", null);
            LinkedHashSet<String> currentJobs = new LinkedHashSet<>(jobs.keySet());

            if (oldJobs != null) {
                List<String> newLinks = new ArrayList<>();
                for (String link : currentJobs) {
                    if (!oldJobs.contains(link)) newLinks.add(link);
                }

                if (!newLinks.isEmpty()) {
                    String first = newLinks.get(0);
                    String title = jobs.get(first);
                    String body = newLinks.size() == 1
                            ? title
                            : newLinks.size() + " new PeoplePerHour jobs. Latest: " + title;

                    NotificationHelper.showOnce(
                            context,
                            "jobs|" + first,
                            "New PeoplePerHour job" + (newLinks.size() > 1 ? "s" : ""),
                            body,
                            "https://www.peopleperhour.com" + first
                    );
                }
            }

            LinkedHashSet<String> limited = new LinkedHashSet<>();
            int count = 0;
            for (String link : currentJobs) {
                limited.add(link);
                if (++count >= 40) break;
            }
            prefs.edit().putStringSet("known_jobs", limited).apply();
        } catch (Exception ignored) {
        }
    }

    private static void syncAccountActivity(Context context) {
        try {
            String cookie = null;
            try {
                cookie = CookieManager.getInstance().getCookie("https://www.peopleperhour.com");
            } catch (Exception ignored) {
            }
            if (cookie == null || cookie.trim().isEmpty()) return;

            String html = fetch(DASHBOARD_URL, cookie);
            if (html == null || html.isEmpty()) return;

            String lower = html.toLowerCase();
            if (lower.contains("name=\"password\"") && lower.contains("log in")) return;

            String summary = extractActivitySummary(html);
            if (summary.isEmpty()) return;

            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String signature = Integer.toHexString(summary.hashCode());
            String previous = prefs.getString("activity_signature", null);

            if (previous != null && !previous.equals(signature)) {
                NotificationHelper.showOnce(
                        context,
                        "activity|" + signature,
                        "New PeoplePerHour activity",
                        summary,
                        DASHBOARD_URL
                );
            }

            prefs.edit().putString("activity_signature", signature).apply();
        } catch (Exception ignored) {
        }
    }

    private static LinkedHashMap<String, String> extractJobs(String html) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        Matcher matcher = JOB_PATTERN.matcher(html);

        while (matcher.find() && result.size() < 40) {
            String link = matcher.group(1);
            String title = cleanText(matcher.group(2));
            if (link == null || title.length() < 6) continue;
            if (title.equalsIgnoreCase("view job") || title.equalsIgnoreCase("read more")) continue;
            result.putIfAbsent(link, title);
        }
        return result;
    }

    private static String extractActivitySummary(String html) {
        Matcher matcher = ACTIVITY_PATTERN.matcher(html);
        LinkedHashSet<String> parts = new LinkedHashSet<>();

        while (matcher.find() && parts.size() < 4) {
            String text = cleanText(matcher.group(1));
            if (text.length() < 4 || text.length() > 180) continue;
            String lower = text.toLowerCase();
            if (lower.equals("notifications") || lower.equals("messages") || lower.equals("workstream")) continue;
            parts.add(text);
        }

        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append(" • ");
            out.append(part);
            if (out.length() > 280) break;
        }
        return out.length() > 300 ? out.substring(0, 300) : out.toString();
    }

    private static String fetch(String urlString, String cookie) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/153 Mobile Safari/537.36");
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);

            int status = connection.getResponseCode();
            if (status < 200 || status >= 400) return null;

            InputStream stream = connection.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                body.append(buffer, 0, read);
                if (body.length() > 3_000_000) break;
            }
            reader.close();
            return body.toString();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String cleanText(String html) {
        if (html == null) return "";
        return html
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
