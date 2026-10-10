package com.nexa.messenger;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import androidx.core.view.WindowInsetsControllerCompat;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    public static final String APP_URL = "https://nexa-qydr.onrender.com";
    /** True while this activity is visible; the FCM service reads it to avoid double-displaying. */
    public static boolean isForeground = false;
    private static final String BACKEND_URL = "https://nexa-backend-e6pq.onrender.com";
    private static final String PREFS = "nexa_prefs";
    private static final String KEY_UID = "uid";
    private static final String KEY_FCM_TOKEN = "fcm_token";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1002;

    private WebView webView;
    private ProgressBar progressBar;
    private LinearLayout layoutOffline;
    private Button btnRetry;

    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingPermissionRequest;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        handleNotificationIntent(getIntent());
        // Paint the window background in the LAST-KNOWN theme before the page
        // loads, so a returning dark-theme user sees no light flash (and light
        // stays light). Overridden by setTheme once the page has loaded.
        updateNativeTheme(getSharedPreferences(PREFS, MODE_PRIVATE).getString("theme", "light"));
        setupWebView();
        setupBackNavigation();
        checkAndRequestAppPermissions();
        initFirebase();

        // Load the Nexa Messenger App
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(APP_URL);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isForeground = true;
        NexaMessagingService.ensureChannels(this);
    }

    @Override
    protected void onPause() {
        isForeground = false;
        super.onPause();
    }

    private void initViews() {
        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        layoutOffline = findViewById(R.id.layoutOffline);
        btnRetry = findViewById(R.id.btnRetry);

        btnRetry.setOnClickListener(v -> {
            layoutOffline.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            webView.reload();
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        // Enable smooth scrolling and disable accidental overscroll refresh
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }

        // Custom User Agent identifying native app
        String defaultUA = settings.getUserAgentString();
        // Report the REAL installed version so the web layer can tell whether
        // this device is actually behind before prompting for an update.
        settings.setUserAgentString(defaultUA + " NexaMobileNative/" + BuildConfig.VERSION_NAME);

        // Let the web app tell us its theme so the native window background
        // always matches (prevents the dark->light flash on the first frame).
        webView.addJavascriptInterface(new NexaAndroidBridge(), "NexaAndroid");
        webView.setBackgroundColor(Color.parseColor("#EDF1F7"));

        // Never let Chromium auto-darken our light theme.
        try {
            if (Build.VERSION.SDK_INT >= 33) settings.setAlgorithmicDarkeningAllowed(false);
            else if (Build.VERSION.SDK_INT >= 29) settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        } catch (Throwable ignored) {}

        webView.setWebViewClient(new NexaWebViewClient());
        webView.setWebChromeClient(new NexaWebChromeClient());

        // File Download Handler
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimetype);
                    String cookies = CookieManager.getInstance().getCookie(url);
                    request.addRequestHeader("cookie", cookies);
                    request.addRequestHeader("User-Agent", userAgent);
                    request.setDescription("Downloading file from Nexa...");
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                    request.setTitle(fileName);
                    request.allowScanningByMediaScanner();
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                        Toast.makeText(MainActivity.this, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(url));
                    startActivity(intent);
                }
            }
        });
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });
    }

    private void checkAndRequestAppPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (pendingPermissionRequest != null) {
                pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                pendingPermissionRequest = null;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    } else if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    // Custom WebChromeClient for Permissions, File Chooser, Progress
    private class NexaWebChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (newProgress < 100) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(newProgress);
            } else {
                progressBar.setVisibility(View.GONE);
            }
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> {
                pendingPermissionRequest = request;
                request.grant(request.getResources());
            });
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
            if (MainActivity.this.filePathCallback != null) {
                MainActivity.this.filePathCallback.onReceiveValue(null);
            }
            MainActivity.this.filePathCallback = filePathCallback;

            Intent intent = fileChooserParams.createIntent();
            try {
                startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
            } catch (ActivityNotFoundException e) {
                MainActivity.this.filePathCallback = null;
                Toast.makeText(MainActivity.this, "Cannot open file chooser", Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }
    }

    // Custom WebViewClient for Navigation & Error Handling
    private class NexaWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            layoutOffline.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            progressBar.setVisibility(View.GONE);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            
            // Keep app within Nexa domain
            if (url.startsWith(APP_URL) || url.contains("onrender.com")) {
                return false;
            }

            // Handle external schemes (tel:, mailto:, sms:, whatsapp:)
            if (url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:") || url.startsWith("whatsapp:")) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                } catch (Exception ignored) {}
            }

            // Open external websites in browser
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } catch (Exception ignored) {}
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                webView.setVisibility(View.GONE);
                layoutOffline.setVisibility(View.VISIBLE);
            }
        }
    }

    // JS -> native: keep the window/status/nav bars in sync with the web theme.
    public class NexaAndroidBridge {
        @JavascriptInterface
        public void setTheme(String theme) {
            updateNativeTheme(theme);
        }

        // The installed app version. The web layer calls this to gate the update
        // prompt on the device's real build, so an up-to-date device is never
        // prompted again (and never sees a duplicate prompt).
        @JavascriptInterface
        public String getAppVersion() {
            return BuildConfig.VERSION_NAME;
        }

        // The web app reports the signed-in uid so we can tie the FCM token to it.
        @JavascriptInterface
        public void setUser(String uid, String name) {
            if (uid == null || uid.trim().isEmpty()) return;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_UID, uid).apply();
            registerFcmToken();
        }

        // Hand the pending notification deep link to the web app exactly once.
        // Returns "" (empty string) when there is nothing to open, which is
        // unambiguous across the JS bridge.
        @JavascriptInterface
        public String getPendingOpenUid() {
            String uid = consumePendingOpenUid();
            return uid == null ? "" : uid;
        }
    }

    private void updateNativeTheme(String theme) {
        final boolean dark = "nexa".equals(theme) || "dark".equals(theme);
        final int bg = dark ? 0xFF060C1E : 0xFFEDF1F7;
        // Remember for the next cold start so the native window background is
        // already correct before the page paints (no flash on either theme).
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("theme", dark ? "nexa" : "light").apply();
        runOnUiThread(() -> {
            try {
                getWindow().setBackgroundDrawable(new ColorDrawable(bg));
                webView.setBackgroundColor(bg);
                getWindow().setStatusBarColor(bg);
                getWindow().setNavigationBarColor(bg);
                WindowInsetsControllerCompat c = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
                c.setAppearanceLightStatusBars(!dark);
                c.setAppearanceLightNavigationBars(!dark);
            } catch (Throwable ignored) {}
        });
    }

    // ── Native FCM ──────────────────────────────────────────────────────
    private void initFirebase() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this); // config comes from google-services.json
            }
            // Ask for the FCM token up-front. Whether it can be saved depends on
            // having a uid (we get that from the web app via NexaAndroid.setUser).
            registerFcmToken();
        } catch (Throwable t) {
            // No google-services.json / Firebase not configured — push is simply off.
            android.util.Log.w("NexaFCM", "Firebase init skipped: " + t.getMessage());
        }
    }

    private void registerFcmToken() {
        if (FirebaseApp.getApps(this).isEmpty()) return;
        final String uid = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_UID, null);
        if (uid == null || uid.isEmpty()) return;
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful() || task.getResult() == null) return;
                String token = task.getResult();
                String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FCM_TOKEN, null);
                if (token.equals(saved)) return; // already registered this token
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_FCM_TOKEN, token).apply();
                postFcmToken(uid, token);
            });
        } catch (Throwable t) {
            android.util.Log.w("NexaFCM", "getToken failed: " + t.getMessage());
        }
    }

    // POST the token to the shared backend, identical to the web /api/save-token.
    private void postFcmToken(final String uid, final String token) {
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
                int code = conn.getResponseCode();
                android.util.Log.i("NexaFCM", "save-token HTTP " + code);
            } catch (Throwable t) {
                android.util.Log.w("NexaFCM", "save-token failed: " + t.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // ── Notification deep link ──────────────────────────────────────────
    // NativeMessagingService stores data.chatUid in prefs before launching us.
    private String consumePendingOpenUid() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String uid = sp.getString("pending_open_uid", null);
        if (uid != null) sp.edit().remove("pending_open_uid").apply();
        return uid;
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent == null) return;
        String chatUid = intent.getStringExtra("chatUid");
        if (chatUid != null && !chatUid.isEmpty()) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("pending_open_uid", chatUid).apply();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Bring the pending chat into the already-running web app.
        handleNotificationIntent(intent);
        try {
            String uid = consumePendingOpenUid();
            if (uid != null) {
                webView.evaluateJavascript(
                    "window.__nexaOpenChatFromNative && window.__nexaOpenChatFromNative(" + JSONObject.quote(uid) + ");",
                    null);
            }
        } catch (Throwable ignored) {}
    }
}
