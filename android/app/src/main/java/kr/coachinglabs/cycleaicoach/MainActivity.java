package kr.coachinglabs.cycleaicoach;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Cycle AI Coach 안드로이드 앱.
 * assets/www 의 index.html · chuncheon_base.json · presets.json 을
 * https://appassets.androidplatform.net/assets/www/ 주소로 띄운다(파일 fetch 허용).
 * 외부 링크(카카오맵·OSM·사진 원본)는 기기 브라우저로, tel: 은 전화 앱으로 연다.
 * 가민 ZIP 저장은 JS 의 AndroidBridge.saveFile(name, base64) 로 다운로드 폴더에 저장한다.
 */
public class MainActivity extends AppCompatActivity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/www/index.html";
    private static final int REQ_LOC = 41;
    private static final int REQ_ONB_LOC = 42;
    private static final int REQ_ONB_NOTI = 43;
    private long lastBack = 0;
    private WebView web;
    private GeolocationPermissions.Callback geoCb;
    private String geoOrigin;
    /** 앱 화면이 사용자에게 보이는 중인지(화면 꺼짐으로 멈춘 경우는 제외) */
    private boolean uiOnTop = false;
    /**
     * 라이딩 기록 중 앱 화면을 켠 채로 전원 버튼을 누르면, 안드로이드는 앱을 '잠든 맨 앞 앱'으로 취급해
     * 위치 업데이트를 크게 줄인다(GPS 끊김 → 점 사이 직선). 홈 버튼으로 나간 경우(포그라운드 서비스만 남음)는
     * 정상이므로, 화면이 꺼지는 순간 앱 화면을 뒤로 보내 같은 상태로 만든다. 다시 켜면 알림이나 앱 아이콘으로 돌아온다.
     */
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!Intent.ACTION_SCREEN_OFF.equals(i.getAction())) return;
            String st = RideService.state();
            if (uiOnTop && RideService.isRunning() && ("rec".equals(st) || "pause".equals(st))) {
                uiOnTop = false;
                moveTaskToBack(true);
            }
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        RideService.act = new java.lang.ref.WeakReference<>(this);
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setSupportMultipleWindows(false);
        s.setTextZoom(100);
        s.setGeolocationEnabled(true);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (hasLocation()) {
                    callback.invoke(origin, true, false);
                } else {
                    geoOrigin = origin;
                    geoCb = callback;
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
                }
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u);
                return true;
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // 사이드바·라이딩 화면은 웹 안에서 먼저 닫는다
                web.evaluateJavascript("window.cacBack?String(cacBack()):'false'", v -> {
                    if (v != null && v.contains("exit")) {
                        // 첫 화면: 2초 안에 한 번 더 누르면 종료
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastBack < 2000) {
                            finish();
                        } else {
                            lastBack = now;
                            Toast.makeText(MainActivity.this, "뒤로 버튼을 한 번 더 누르면 앱이 종료됩니다", Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }
                    if (v != null && v.contains("true")) return;
                    if (web.canGoBack()) web.goBack();
                    else finish();
                });
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START);

        if (savedInstanceState == null && !prefs().getBoolean("onboarded", false)) onboard(0);
        ContextCompat.registerReceiver(this, screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStart() {
        super.onStart();
        uiOnTop = true;
    }

    @Override
    protected void onStop() {
        // 화면 꺼짐으로 멈춘 경우는 screenOff 가 처리하도록 표시를 남겨 둠
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isInteractive()) uiOnTop = false;
        super.onStop();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("cac", MODE_PRIVATE);
    }

    /** 최초 실행 안내: 0 위치 권한 → 1 알림 권한(Android 13+) → 2 백그라운드(배터리 사용 제한 해제) */
    private void onboard(int step) {
        if (isFinishing()) return;
        if (step == 0) {
            if (hasLocation()) { onboard(1); return; }
            new AlertDialog.Builder(this)
                    .setTitle("위치 권한을 허용해 주세요")
                    .setMessage("라이딩 모드에서 GPS로 코스·속도·거리·소모 칼로리를 기록하려면 위치 권한이 필요합니다.\n\n다음 창에서 '정확한 위치'를 켜고 '앱 사용 중에만 허용'을 눌러 주세요.")
                    .setCancelable(false)
                    .setPositiveButton("계속", (d, w) -> ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_ONB_LOC))
                    .setNegativeButton("나중에", (d, w) -> onboard(1))
                    .show();
        } else if (step == 1) {
            if (prefs().contains("notiConsent")) { onboard(2); return; }
            new AlertDialog.Builder(this)
                    .setTitle("알림 수신에 동의해 주세요")
                    .setMessage("라이딩을 기록하는 동안 앱을 나가도 휴대폰 상단 알림창에서 기록 타이머·거리·칼로리를 확인하고, 일시정지·재개·종료를 바로 누를 수 있습니다.\n\n알림은 라이딩 기록 중에만 표시되며 광고성 알림은 보내지 않습니다.")
                    .setCancelable(false)
                    .setPositiveButton("동의", (d, w) -> {
                        prefs().edit().putBoolean("notiConsent", true).apply();
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_ONB_NOTI);
                        } else {
                            onboard(2);
                        }
                    })
                    .setNegativeButton("동의 안 함", (d, w) -> {
                        prefs().edit().putBoolean("notiConsent", false).apply();
                        onboard(2);
                    })
                    .show();
        } else if (step == 2) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) { onboardDone(); return; }
            new AlertDialog.Builder(this)
                    .setTitle("백그라운드 실행을 허용해 주세요")
                    .setMessage("화면을 끄거나 다른 앱을 쓰는 동안 휴대폰 절전 기능이 기록을 멈추지 않도록, 이 앱의 배터리 사용 제한(배터리 최적화)을 해제해 주세요.\n\n다음 창에서 '허용'을 눌러 주세요. 나중에 휴대폰 설정 > 애플리케이션 > Cycle AI Coach > 배터리에서도 바꿀 수 있습니다.")
                    .setCancelable(false)
                    .setPositiveButton("설정하기", (d, w) -> {
                        onboardDone();
                        try {
                            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) { }
                        }
                    })
                    .setNegativeButton("나중에", (d, w) -> onboardDone())
                    .show();
        }
    }

    private void onboardDone() {
        prefs().edit().putBoolean("onboarded", true).apply();
    }

    /** 알림 버튼 → 웹 라이딩 모드로 전달 */
    void rideCmd(String c) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.rdCmd&&rdCmd('" + c + "')", null);
        });
    }

    private boolean hasLocation() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOC && geoCb != null) {
            geoCb.invoke(geoOrigin, hasLocation(), false);
            geoCb = null;
        }
        if (requestCode == REQ_ONB_LOC) onboard(1);
        if (requestCode == REQ_ONB_NOTI) onboard(2);
    }

    private void openExternal(Uri u) {
        try {
            Intent i = "tel".equals(u.getScheme()) ? new Intent(Intent.ACTION_DIAL, u) : new Intent(Intent.ACTION_VIEW, u);
            startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(screenOff); } catch (Exception ignored) { }
        // 화면 회전 등으로 화면만 다시 만들 때는 기록 서비스를 유지(새 화면이 이어 받음)
        if (!isChangingConfigurations()) {
            try { stopService(new Intent(this, RideService.class)); } catch (Exception ignored) { }
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** JS → 안드로이드: 파일 저장, 라이딩 중 화면 켜짐 유지 */
    private class Bridge {
        @JavascriptInterface
        public void rideService(boolean on) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, RideService.class);
                try {
                    if (on) {
                        if (RideService.isRunning()) return; // 이미 기록 중: 다시 시작하지 않음
                        if (hasLocation()) ContextCompat.startForegroundService(MainActivity.this, i);
                    } else {
                        RideService.stopping = true; // 정상 종료: 남은 GPS 점은 웹이 이미 가져감
                        stopService(i);
                    }
                } catch (Exception ignored) {
                }
            });
        }

        /** 기록 상태·경과 시간(ms)·거리(m)·칼로리·현재 속도(m/s)·체중으로 알림 갱신(서비스가 없으면 시작) */
        @JavascriptInterface
        public void rideUpdate(String st, double elapsedMs, double dist, double kcal, double v, double kg) {
            runOnUiThread(() -> {
                // 서비스가 이미 돌고 있으면 숫자만 전달(앱이 화면 밖이어도 안전). 없을 때만 새로 시작
                if (RideService.update(st, (long) elapsedMs, dist, kcal, v, kg)) return;
                Intent i = new Intent(MainActivity.this, RideService.class)
                        .setAction(RideService.A_UPDATE)
                        .putExtra("st", st)
                        .putExtra("el", (long) elapsedMs)
                        .putExtra("dist", dist)
                        .putExtra("kcal", kcal)
                        .putExtra("v", v)
                        .putExtra("kg", kg);
                try {
                    if (hasLocation()) ContextCompat.startForegroundService(MainActivity.this, i);
                } catch (Exception ignored) {
                }
            });
        }

        /** 강제종료 대비 저장소: 웹 localStorage 값을 앱 내부 파일에 즉시 기록 */
        @JavascriptInterface
        public void kvSet(String k, String v) {
            Kv.set(MainActivity.this, k, v);
        }

        @JavascriptInterface
        public void kvDel(String k) {
            Kv.del(MainActivity.this, k);
        }

        @JavascriptInterface
        public String kvAll() {
            return Kv.all(MainActivity.this);
        }

        /** 서비스가 모은 GPS 점(JSON)을 가져가고 비움 */
        @JavascriptInterface
        public String ridePull() {
            return RideService.pull();
        }

        /** 서비스 기준 운동 시간(ms, 일시정지 제외). 서비스가 없으면 -1 */
        @JavascriptInterface
        public double rideElapsed() {
            return RideService.elapsed();
        }

        /** 서비스의 기록 상태: "rec" | "pause" | ""(서비스 없음) */
        @JavascriptInterface
        public String rideState() {
            return RideService.state();
        }

        /** 서비스가 GPS를 직접 받는 중인지 */
        @JavascriptInterface
        public boolean rideActive() {
            return RideService.isActive();
        }

        @JavascriptInterface
        public void keepScreenOn(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public String saveFile(String name, String base64) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                String mime = safe.endsWith(".zip") ? "application/zip" : safe.endsWith(".gpx") ? "application/gpx+xml" : "application/octet-stream";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "저장 위치를 만들 수 없습니다";
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) return "파일을 열 수 없습니다";
                        out.write(data);
                    }
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists() && !dir.mkdirs()) return "다운로드 폴더를 만들 수 없습니다";
                    try (FileOutputStream out = new FileOutputStream(new File(dir, safe))) {
                        out.write(data);
                    }
                }
                return "ok";
            } catch (Exception e) {
                return String.valueOf(e.getMessage());
            }
        }
    }
}
