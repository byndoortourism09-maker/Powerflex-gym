package com.powerflex.gym;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.net.Uri;
import android.content.Intent;
import android.widget.Toast;
import android.view.View;
import android.view.WindowManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.BroadcastReceiver;
import java.util.ArrayList;

public class MainActivity extends Activity {

    private WebView webView;
    private static final int SMS_PERMISSION_CODE = 101;
    private static final int CAMERA_PERMISSION_CODE = 102;
    private static final int FILE_CHOOSER_CODE = 103;

    private ValueCallback<Uri[]> fileChooserCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen / immersive
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF0D0D0D);
        }

        // Setup WebView as content view
        webView = new WebView(this);
        webView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(webView);

        // WebView settings
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Inject Android SMS bridge as window.AndroidSMS
        webView.addJavascriptInterface(new SMSBridge(), "AndroidSMS");

        // WebViewClient
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Let WhatsApp / SMS links open in native apps
                if (url.startsWith("https://wa.me") ||
                    url.startsWith("whatsapp://") ||
                    url.startsWith("sms:")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                    } catch (Exception e) { /* ignore */ }
                    return true;
                }
                return false;
            }
        });

        // WebChromeClient for camera / file chooser
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb,
                                              WebChromeClient.FileChooserParams params) {
                fileChooserCallback = cb;
                Intent intent = params.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_CODE);
                } catch (Exception e) {
                    fileChooserCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        // Request SMS permission on startup
        requestSMSPermissionIfNeeded();

        // Load the app
        webView.loadUrl("file:///android_asset/public/index.html");
    }

    /* ─── SMS JavascriptInterface ─── */
    public class SMSBridge {

        @JavascriptInterface
        public boolean hasSMSPermission() {
            return ContextCompat.checkSelfPermission(
                MainActivity.this, Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestSMSPermission() {
            requestSMSPermissionIfNeeded();
        }

        @JavascriptInterface
        public boolean sendSMS(String phoneNumber, String message) {
            if (!hasSMSPermission()) {
                requestSMSPermissionIfNeeded();
                return false;
            }
            try {
                SmsManager smsManager = SmsManager.getDefault();
                // Split message if too long
                ArrayList<String> parts = smsManager.divideMessage(message);
                if (parts.size() == 1) {
                    smsManager.sendTextMessage(phoneNumber, null, message, null, null);
                } else {
                    smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null);
                }
                // Notify JS that SMS was sent
                runOnUiThread(() -> webView.evaluateJavascript(
                    "if(window.onSMSSent) window.onSMSSent('" + phoneNumber + "');", null
                ));
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(
                    MainActivity.this, "SMS failed: " + e.getMessage(), Toast.LENGTH_SHORT
                ).show());
                return false;
            }
        }
    }

    /* ─── Permission request ─── */
    private void requestSMSPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_PHONE_STATE
                },
                SMS_PERMISSION_CODE
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == SMS_PERMISSION_CODE) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            // Notify JS
            String js = granted
                ? "if(window.onSMSPermissionGranted) window.onSMSPermissionGranted();"
                : "if(window.onSMSPermissionDenied) window.onSMSPermissionDenied();";
            webView.post(() -> webView.evaluateJavascript(js, null));
        }
    }

    /* ─── File chooser result (for camera) ─── */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_CODE) {
            if (fileChooserCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    }
                }
                fileChooserCallback.onReceiveValue(results);
                fileChooserCallback = null;
            }
        }
    }

    /* ─── Back button ─── */
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
