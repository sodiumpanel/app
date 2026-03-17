package com.sodium.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import android.os.Build;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private WebView webView;
    private LinearLayout panelListView;
    private LinearLayout addPanelView;
    private LinearLayout statusView;
    private LinearLayout settingsView;
    private FrameLayout webviewContainer;
    private LinearLayout panelContainer;
    private LinearLayout emptyState;
    private ScrollView panelScroll;
    private EditText inputName;
    private EditText inputUrl;
    private TextView addPanelTitle;
    private SharedPreferences prefs;

    static final String PREF_NAME = "sodium_prefs";
    static final String KEY_PANELS = "panels";
    static final String CHANNEL_ID = "sodium_alerts";

    private int editingIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.parseColor("#0a0a0a"));
            getWindow().setNavigationBarColor(Color.parseColor("#0a0a0a"));
        }

        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

        createNotificationChannel();

        panelListView = findViewById(R.id.panel_list_view);
        addPanelView = findViewById(R.id.add_panel_view);
        statusView = findViewById(R.id.status_view);
        settingsView = findViewById(R.id.settings_view);
        webviewContainer = findViewById(R.id.webview_container);
        panelContainer = findViewById(R.id.panel_container);
        emptyState = findViewById(R.id.empty_state);
        panelScroll = findViewById(R.id.panel_scroll);
        webView = findViewById(R.id.webview);
        inputName = findViewById(R.id.input_name);
        inputUrl = findViewById(R.id.input_url);
        addPanelTitle = findViewById(R.id.add_panel_title);

        ImageButton fabAdd = findViewById(R.id.fab_add);
        ImageButton btnBack = findViewById(R.id.btn_back);
        ImageButton btnCloseWebview = findViewById(R.id.btn_close_webview);
        Button btnSave = findViewById(R.id.btn_save);

        setupWebView();
        loadPanels();

        fabAdd.setOnClickListener(v -> showAddPanel());
        btnBack.setOnClickListener(v -> showPanelList());
        btnCloseWebview.setOnClickListener(v -> closeWebView());
        btnSave.setOnClickListener(v -> savePanel());

        scheduleStatusWorker();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Server Alerts", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Alerts when servers or nodes go offline");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void scheduleStatusWorker() {
        JSONArray panels = getPanels();
        boolean anyEnabled = false;
        for (int i = 0; i < panels.length(); i++) {
            try {
                JSONObject p = panels.getJSONObject(i);
                if (p.optBoolean("notifications", false)) {
                    anyEnabled = true;
                    break;
                }
            } catch (Exception ignored) {}
        }

        if (anyEnabled) {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                    StatusCheckWorker.class, 15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build();
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    "sodium_status", ExistingPeriodicWorkPolicy.KEEP, req);
        } else {
            WorkManager.getInstance(this).cancelUniqueWork("sodium_status");
        }
    }

    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                extractToken(url);
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.setBackgroundColor(Color.parseColor("#0a0a0a"));
    }

    private void extractToken(String pageUrl) {
        webView.evaluateJavascript(
                "(function(){ return localStorage.getItem('auth_token') || ''; })()",
                value -> {
                    if (value == null || value.equals("null") || value.equals("\"\"")) return;
                    String token = value.replaceAll("^\"|\"$", "");
                    if (token.isEmpty()) return;

                    JSONArray panels = getPanels();
                    for (int i = 0; i < panels.length(); i++) {
                        try {
                            JSONObject p = panels.getJSONObject(i);
                            String pUrl = p.getString("url");
                            if (pageUrl.startsWith(pUrl)) {
                                p.put("token", token);
                                panels.put(i, p);
                                prefs.edit().putString(KEY_PANELS, panels.toString()).apply();
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                });
    }

    private void showPanelList() {
        editingIndex = -1;
        inputName.setText("");
        inputUrl.setText("");
        hideAll();
        panelListView.setVisibility(View.VISIBLE);
        loadPanels();
    }

    private void showAddPanel() {
        editingIndex = -1;
        inputName.setText("");
        inputUrl.setText("");
        addPanelTitle.setText("Add Panel");
        hideAll();
        addPanelView.setVisibility(View.VISIBLE);
    }

    private void showEditPanel(int index, String name, String url) {
        editingIndex = index;
        inputName.setText(name);
        inputUrl.setText(url);
        addPanelTitle.setText("Edit Panel");
        hideAll();
        addPanelView.setVisibility(View.VISIBLE);
    }

    private void openPanel(String url) {
        hideAll();
        webviewContainer.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    private void closeWebView() {
        webView.loadUrl("about:blank");
        showPanelList();
    }

    private void hideAll() {
        panelListView.setVisibility(View.GONE);
        addPanelView.setVisibility(View.GONE);
        webviewContainer.setVisibility(View.GONE);
        statusView.setVisibility(View.GONE);
        settingsView.setVisibility(View.GONE);
    }

    private void showPanelSettings(int index) {
        try {
            JSONArray panels = getPanels();
            JSONObject panel = panels.getJSONObject(index);

            hideAll();
            settingsView.setVisibility(View.VISIBLE);

            TextView title = findViewById(R.id.settings_title);
            title.setText(panel.getString("name") + " Settings");

            Switch switchNotif = findViewById(R.id.switch_notifications);
            Switch switchPolling = findViewById(R.id.switch_polling);
            TextView tokenStatus = findViewById(R.id.token_status);

            switchNotif.setChecked(panel.optBoolean("notifications", false));
            switchPolling.setChecked(panel.optBoolean("polling", false));

            String token = panel.optString("token", "");
            tokenStatus.setText(token.isEmpty() ? "Not logged in — open panel to authenticate" : "Authenticated");
            tokenStatus.setTextColor(token.isEmpty() ? Color.parseColor("#ff5555") : Color.parseColor("#50fa7b"));

            ImageButton btnSettingsBack = findViewById(R.id.btn_settings_back);
            btnSettingsBack.setOnClickListener(v -> showPanelList());

            Button btnStatusPage = findViewById(R.id.btn_status_page);
            btnStatusPage.setOnClickListener(v -> showStatusPage(index));

            switchNotif.setOnCheckedChangeListener((btn, checked) -> {
                try {
                    JSONArray p = getPanels();
                    JSONObject obj = p.getJSONObject(index);
                    obj.put("notifications", checked);
                    p.put(index, obj);
                    prefs.edit().putString(KEY_PANELS, p.toString()).apply();
                    scheduleStatusWorker();
                } catch (Exception ignored) {}
            });

            switchPolling.setOnCheckedChangeListener((btn, checked) -> {
                try {
                    JSONArray p = getPanels();
                    JSONObject obj = p.getJSONObject(index);
                    obj.put("polling", checked);
                    p.put(index, obj);
                    prefs.edit().putString(KEY_PANELS, p.toString()).apply();
                    scheduleStatusWorker();
                } catch (Exception ignored) {}
            });
        } catch (Exception e) {
            Toast.makeText(this, "Error loading settings", Toast.LENGTH_SHORT).show();
            showPanelList();
        }
    }

    private void showStatusPage(int panelIndex) {
        try {
            JSONArray panels = getPanels();
            JSONObject panel = panels.getJSONObject(panelIndex);
            String baseUrl = panel.getString("url");
            String token = panel.optString("token", "");
            String panelName = panel.getString("name");

            hideAll();
            statusView.setVisibility(View.VISIBLE);

            TextView statusTitle = findViewById(R.id.status_title);
            statusTitle.setText(panelName + " Status");

            ImageButton btnStatusBack = findViewById(R.id.btn_status_back);
            btnStatusBack.setOnClickListener(v -> showPanelSettings(panelIndex));

            LinearLayout statusContainer = findViewById(R.id.status_container);
            TextView statusLoading = findViewById(R.id.status_loading);
            statusContainer.removeAllViews();
            statusLoading.setVisibility(View.VISIBLE);
            statusLoading.setText("Loading servers...");

            if (token.isEmpty()) {
                statusLoading.setText("Not authenticated — open panel first to login");
                return;
            }

            new Thread(() -> {
                try {
                    URL url = new URL(baseUrl + "/api/servers");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);

                    int code = conn.getResponseCode();
                    if (code != 200) {
                        runOnUiThread(() -> statusLoading.setText("Error: HTTP " + code));
                        return;
                    }

                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject resp = new JSONObject(sb.toString());
                    JSONArray servers = resp.getJSONArray("servers");

                    runOnUiThread(() -> {
                        statusLoading.setVisibility(View.GONE);
                        if (servers.length() == 0) {
                            statusLoading.setVisibility(View.VISIBLE);
                            statusLoading.setText("No servers found");
                            return;
                        }
                        LayoutInflater inflater = LayoutInflater.from(this);
                        for (int i = 0; i < servers.length(); i++) {
                            try {
                                JSONObject srv = servers.getJSONObject(i);
                                View card = inflater.inflate(R.layout.status_item, statusContainer, false);

                                TextView srvName = card.findViewById(R.id.srv_name);
                                TextView srvNode = card.findViewById(R.id.srv_node);
                                TextView srvStatus = card.findViewById(R.id.srv_status);
                                TextView srvAddress = card.findViewById(R.id.srv_address);
                                View srvDot = card.findViewById(R.id.srv_dot);

                                srvName.setText(srv.optString("name", "Unknown"));
                                srvNode.setText(srv.optString("node_name", "Unknown Node"));
                                srvAddress.setText(srv.optString("node_address", "—"));

                                boolean nodeOnline = srv.optBoolean("node_online", false);
                                boolean suspended = srv.optBoolean("suspended", false);

                                if (suspended) {
                                    srvStatus.setText("Suspended");
                                    srvStatus.setTextColor(Color.parseColor("#ffb86c"));
                                    srvDot.setBackgroundColor(Color.parseColor("#ffb86c"));
                                } else if (nodeOnline) {
                                    srvStatus.setText("Online");
                                    srvStatus.setTextColor(Color.parseColor("#50fa7b"));
                                    srvDot.setBackgroundColor(Color.parseColor("#50fa7b"));
                                } else {
                                    srvStatus.setText("Offline");
                                    srvStatus.setTextColor(Color.parseColor("#ff5555"));
                                    srvDot.setBackgroundColor(Color.parseColor("#ff5555"));
                                }

                                statusContainer.addView(card);
                            } catch (Exception ignored) {}
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> statusLoading.setText("Connection error: " + e.getMessage()));
                }
            }).start();
        } catch (Exception e) {
            Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show();
        }
    }

    private void savePanel() {
        String name = inputName.getText().toString().trim();
        String url = inputUrl.getText().toString().trim();

        if (name.isEmpty()) {
            Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show();
            return;
        }
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter a URL", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);

        try {
            JSONArray panels = getPanels();
            JSONObject panel;
            if (editingIndex >= 0) {
                panel = panels.getJSONObject(editingIndex);
                panel.put("name", name);
                panel.put("url", url);
                panels.put(editingIndex, panel);
            } else {
                panel = new JSONObject();
                panel.put("name", name);
                panel.put("url", url);
                panel.put("token", "");
                panel.put("notifications", false);
                panel.put("polling", false);
                panels.put(panel);
            }

            prefs.edit().putString(KEY_PANELS, panels.toString()).apply();
            Toast.makeText(this, editingIndex >= 0 ? "Panel updated" : "Panel added", Toast.LENGTH_SHORT).show();
            showPanelList();
        } catch (Exception e) {
            Toast.makeText(this, "Error saving panel", Toast.LENGTH_SHORT).show();
        }
    }

    private void deletePanel(int index) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Delete Panel")
                .setMessage("Are you sure?")
                .setPositiveButton("Delete", (d, w) -> {
                    try {
                        JSONArray panels = getPanels();
                        JSONArray newPanels = new JSONArray();
                        for (int i = 0; i < panels.length(); i++) {
                            if (i != index) newPanels.put(panels.get(i));
                        }
                        prefs.edit().putString(KEY_PANELS, newPanels.toString()).apply();
                        loadPanels();
                        scheduleStatusWorker();
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    JSONArray getPanels() {
        try {
            return new JSONArray(prefs.getString(KEY_PANELS, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void loadPanels() {
        panelContainer.removeAllViews();
        JSONArray panels = getPanels();

        if (panels.length() == 0) {
            emptyState.setVisibility(View.VISIBLE);
            panelScroll.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            panelScroll.setVisibility(View.VISIBLE);

            LayoutInflater inflater = LayoutInflater.from(this);
            for (int i = 0; i < panels.length(); i++) {
                try {
                    JSONObject panel = panels.getJSONObject(i);
                    String name = panel.getString("name");
                    String url = panel.getString("url");
                    boolean hasToken = !panel.optString("token", "").isEmpty();
                    boolean notifOn = panel.optBoolean("notifications", false);
                    final int index = i;

                    View itemView = inflater.inflate(R.layout.panel_item, panelContainer, false);

                    TextView nameView = itemView.findViewById(R.id.panel_name);
                    TextView urlView = itemView.findViewById(R.id.panel_url);
                    TextView statusBadge = itemView.findViewById(R.id.panel_status_badge);
                    ImageButton btnEdit = itemView.findViewById(R.id.btn_edit);
                    ImageButton btnDelete = itemView.findViewById(R.id.btn_delete);
                    ImageButton btnSettings = itemView.findViewById(R.id.btn_settings);

                    nameView.setText(name);
                    urlView.setText(url);

                    if (hasToken) {
                        statusBadge.setText(notifOn ? "Monitoring" : "Connected");
                        statusBadge.setTextColor(notifOn ? Color.parseColor("#50fa7b") : Color.parseColor("#8be9fd"));
                        statusBadge.setVisibility(View.VISIBLE);
                    } else {
                        statusBadge.setVisibility(View.GONE);
                    }

                    itemView.setOnClickListener(v -> openPanel(url));
                    btnEdit.setOnClickListener(v -> showEditPanel(index, name, url));
                    btnDelete.setOnClickListener(v -> deletePanel(index));
                    btnSettings.setOnClickListener(v -> showPanelSettings(index));

                    panelContainer.addView(itemView);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webviewContainer.getVisibility() == View.VISIBLE) {
            if (webView.canGoBack()) webView.goBack();
            else closeWebView();
        } else if (addPanelView.getVisibility() == View.VISIBLE) {
            showPanelList();
        } else if (statusView.getVisibility() == View.VISIBLE) {
            showPanelList();
        } else if (settingsView.getVisibility() == View.VISIBLE) {
            showPanelList();
        } else {
            super.onBackPressed();
        }
    }
}
