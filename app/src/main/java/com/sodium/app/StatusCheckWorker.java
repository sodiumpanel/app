package com.sodium.app;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.Notification;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class StatusCheckWorker extends Worker {

    public StatusCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        SharedPreferences prefs = getApplicationContext()
                .getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences alertPrefs = getApplicationContext()
                .getSharedPreferences("sodium_alerts", Context.MODE_PRIVATE);

        try {
            JSONArray panels = new JSONArray(prefs.getString(MainActivity.KEY_PANELS, "[]"));
            int notifId = 1000;

            for (int i = 0; i < panels.length(); i++) {
                JSONObject panel = panels.getJSONObject(i);
                if (!panel.optBoolean("notifications", false)) continue;

                String token = panel.optString("token", "");
                String baseUrl = panel.optString("url", "");
                String panelName = panel.optString("name", "Panel");

                if (token.isEmpty() || baseUrl.isEmpty()) continue;

                try {
                    URL url = new URL(baseUrl + "/api/servers");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);

                    if (conn.getResponseCode() != 200) continue;

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject resp = new JSONObject(sb.toString());
                    JSONArray servers = resp.getJSONArray("servers");

                    for (int j = 0; j < servers.length(); j++) {
                        JSONObject srv = servers.getJSONObject(j);
                        String srvName = srv.optString("name", "Server");
                        String srvId = srv.optString("id", "");
                        boolean nodeOnline = srv.optBoolean("node_online", false);
                        boolean suspended = srv.optBoolean("suspended", false);

                        String alertKey = baseUrl + "_" + srvId;
                        boolean wasOffline = alertPrefs.getBoolean(alertKey + "_offline", false);
                        boolean wasSuspended = alertPrefs.getBoolean(alertKey + "_suspended", false);

                        if (!nodeOnline && !wasOffline) {
                            sendNotification(panelName + ": " + srvName,
                                    "Server node is offline", notifId++);
                            alertPrefs.edit().putBoolean(alertKey + "_offline", true).apply();
                        } else if (nodeOnline && wasOffline) {
                            sendNotification(panelName + ": " + srvName,
                                    "Server is back online", notifId++);
                            alertPrefs.edit().putBoolean(alertKey + "_offline", false).apply();
                        }

                        if (suspended && !wasSuspended) {
                            sendNotification(panelName + ": " + srvName,
                                    "Server has been suspended", notifId++);
                            alertPrefs.edit().putBoolean(alertKey + "_suspended", true).apply();
                        } else if (!suspended && wasSuspended) {
                            alertPrefs.edit().putBoolean(alertKey + "_suspended", false).apply();
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        return Result.success();
    }

    private void sendNotification(String title, String message, int id) {
        Context ctx = getApplicationContext();
        Intent intent = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            builder = new Notification.Builder(ctx, MainActivity.CHANNEL_ID);
        } else {
            builder = new Notification.Builder(ctx);
        }

        Notification notif = builder
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id, notif);
    }
}
