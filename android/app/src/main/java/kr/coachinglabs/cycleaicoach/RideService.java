package kr.coachinglabs.cycleaicoach;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 라이딩 기록 중 포그라운드 서비스.
 * - 알림창: 앱 이름 · 기록 상태 · 경과 타이머(크로노미터) · 거리/칼로리, [일시정지/재개] [종료·저장] 버튼
 * - 화면이 꺼지거나 다른 앱을 써도 위치 기록이 이어지도록 앱을 포그라운드로 유지, 부분 웨이크락(최대 6시간)
 * - 알림 버튼은 MainActivity → WebView 의 rdCmd('pause'|'resume'|'stop') 로 전달, WebView 가 rideUpdate 로 알림을 갱신
 * - GPS는 이 서비스가 직접 받아(LocationManager, 1초 간격) 기록 중일 때만 버퍼에 모은다.
 *   앱 화면(WebView)은 백그라운드·화면 꺼짐 동안 멈추므로, 돌아오면 ridePull 로 모인 점을 한꺼번에 가져가 그대로 이어 그린다.
 */
public class RideService extends Service {
    public static final String A_UPDATE = "kr.coachinglabs.cycleaicoach.UPDATE";
    public static final String A_PAUSE = "kr.coachinglabs.cycleaicoach.PAUSE";
    public static final String A_RESUME = "kr.coachinglabs.cycleaicoach.RESUME";
    public static final String A_STOP = "kr.coachinglabs.cycleaicoach.STOP";
    static WeakReference<MainActivity> act;

    private static final String CH = "ride";
    private static final int ID = 7;
    private PowerManager.WakeLock wl;
    private String st = "rec";
    private long el = 0;
    private String tx = "";
    private double baseDist = 0, baseKcal = 0, kg = 70, lastV = 0;

    // 네이티브 GPS 버퍼 [lat, lon, accuracy, speed(m/s, 없으면 -1), time(ms)]
    private static final ArrayList<double[]> BUF = new ArrayList<>();
    private static volatile boolean active = false;
    /** 웹 화면이 정상적으로 기록을 끝낼 때 true. false 인 채로 서비스가 끝나면(앱 강제종료) 남은 점을 파일로 보존 */
    static volatile boolean stopping = false;
    private LocationManager lm;
    private double[] lastPt = null;
    private double nDist = 0, nKcal = 0;
    private long lastNotify = 0;
    private long recSince = -1; // 기록 중일 때 기준 시각(elapsedRealtime), 일시정지면 -1
    /** 이미 포그라운드(알림 표시) 상태인지. 한 번 시작한 뒤에는 startForeground 를 다시 부르지 않음
     *  (앱이 화면 밖일 때 다시 부르면 안드로이드 12~14가 막아 서비스가 꺼지고 GPS가 끊기던 원인) */
    private boolean fg = false;

    /** 실행 중인 서비스에 상태·숫자만 전달하고 알림을 갱신(서비스를 다시 시작하지 않음). 서비스가 없으면 false */
    static boolean update(String st, long el, double dist, double kcal, double v, double kg) {
        RideService me = inst != null ? inst.get() : null;
        if (me == null || !me.fg || stopping) return false;
        me.apply(st, el, dist, kcal, v, kg);
        NotificationManager nm = (NotificationManager) me.getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID, me.build());
        return true;
    }

    private void apply(String st0, long el0, double dist, double kcal, double v, double kg0) {
        st = st0 != null ? st0 : "rec";
        el = el0;
        recSince = "rec".equals(st) ? SystemClock.elapsedRealtime() : -1;
        baseDist = dist;
        baseKcal = kcal;
        kg = kg0;
        lastV = v;
        tx = "";
        if (!"rec".equals(st)) lastPt = null;
    }

    /** 일시정지 시간을 뺀 현재 운동 시간(ms). 서비스가 없으면 -1 */
    static long elapsed() {
        RideService me = inst != null ? inst.get() : null;
        if (me == null) return -1;
        return me.el + (me.recSince >= 0 ? SystemClock.elapsedRealtime() - me.recSince : 0);
    }

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            onFix(l);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    /** 현재 기록 상태. 서비스가 없으면 "" */
    static String state() {
        RideService me = inst != null ? inst.get() : null;
        return me == null || stopping ? "" : me.st;
    }

    /** 서비스가 포그라운드로 실행 중인지 */
    static boolean isRunning() {
        RideService me = inst != null ? inst.get() : null;
        return me != null && me.fg && !stopping;
    }

    /** 서비스가 GPS를 직접 받고 있는지 */
    static boolean isActive() {
        return active;
    }

    /** 모인 GPS 점을 JSON 배열로 넘기고 비운다 */
    static String pull() {
        StringBuilder b = new StringBuilder("[");
        synchronized (BUF) {
            for (int i = 0; i < BUF.size(); i++) {
                double[] q = BUF.get(i);
                if (i > 0) b.append(',');
                b.append('[').append(q[0]).append(',').append(q[1]).append(',').append(q[2]).append(',').append(q[3]).append(',').append((long) q[4]).append(']');
            }
            BUF.clear();
        }
        RideService me = inst != null ? inst.get() : null;
        if (me != null) { me.nDist = 0; me.nKcal = 0; }
        return b.append(']').toString();
    }

    private static WeakReference<RideService> inst;

    private static double met(double kmh) {
        return kmh < 3 ? 0 : kmh < 16 ? 4.0 : kmh < 19.3 ? 6.8 : kmh < 22.5 ? 8.0 : kmh < 25.7 ? 10.0 : kmh < 30.6 ? 12.0 : 15.8;
    }

    private void onFix(Location l) {
        if (stopping || !"rec".equals(st)) { lastPt = null; return; }
        double acc = l.hasAccuracy() ? l.getAccuracy() : 99;
        double spd = l.hasSpeed() ? l.getSpeed() : -1;
        long t = l.getTime() > 0 ? l.getTime() : System.currentTimeMillis();
        synchronized (BUF) {
            BUF.add(new double[]{l.getLatitude(), l.getLongitude(), acc, spd, t});
            if (BUF.size() > 30000) BUF.remove(0);
        }
        // 알림창 표시용 대략 거리·칼로리(앱 화면이 멈춰 있는 동안)
        if (acc <= 40) {
            if (lastPt != null) {
                float[] r = new float[1];
                Location.distanceBetween(lastPt[0], lastPt[1], l.getLatitude(), l.getLongitude(), r);
                double dt = (t - lastPt[2]) / 1000.0;
                if (dt > 0) {
                    double v = r[0] / dt;
                    if (v <= 22 && (r[0] >= Math.max(4, acc * .5) || (v > 1.5 && r[0] >= 2))) {
                        nDist += r[0];
                        nKcal += met(v * 3.6) * 3.5 * kg / 200 * Math.min(dt, 30) / 60;
                        lastPt = new double[]{l.getLatitude(), l.getLongitude(), t};
                    }
                    lastV = spd >= 0 ? spd : v;
                }
            } else {
                lastPt = new double[]{l.getLatitude(), l.getLongitude(), t};
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotify > 5000) {
            lastNotify = now;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(ID, build());
        }
    }

    @SuppressWarnings("MissingPermission")
    private void startGps() {
        if (lm != null) return;
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper());
            active = true;
        } catch (Exception e) {
            active = false;
            lm = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (A_PAUSE.equals(a) || A_RESUME.equals(a) || A_STOP.equals(a)) {
            MainActivity m = act != null ? act.get() : null;
            String c = A_PAUSE.equals(a) ? "pause" : A_RESUME.equals(a) ? "resume" : "stop";
            // 앱 화면이 멈춰 있어도 바로 반영: 일시정지 중에는 GPS 점을 모으지 않음
            long now0 = SystemClock.elapsedRealtime();
            if (A_PAUSE.equals(a) && "rec".equals(st)) {
                if (recSince >= 0) el += now0 - recSince;
                recSince = -1; st = "pause"; lastPt = null;
            } else if (A_RESUME.equals(a) && "pause".equals(st)) {
                recSince = now0; st = "rec"; lastPt = null;
            }
            NotificationManager nm0 = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm0 != null && !A_STOP.equals(a)) nm0.notify(ID, build());
            if (m != null) m.rideCmd(c);
            else if (A_STOP.equals(a)) stopSelf();
            return START_NOT_STICKY;
        }
        stopping = false;
        if (intent != null && A_UPDATE.equals(a)) {
            apply(intent.getStringExtra("st"), intent.getLongExtra("el", 0), intent.getDoubleExtra("dist", 0),
                    intent.getDoubleExtra("kcal", 0), intent.getDoubleExtra("v", lastV), intent.getDoubleExtra("kg", 70));
        }
        inst = new WeakReference<>(this);
        if (!fg) {
            try {
                ServiceCompat.startForeground(this, ID, build(),
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0);
                fg = true;
            } catch (Exception e) {
                stopSelf();
                return START_NOT_STICKY;
            }
        } else {
            NotificationManager nm1 = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm1 != null) nm1.notify(ID, build());
        }
        startGps();
        if (wl == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cac:ride");
                wl.acquire(6 * 60 * 60 * 1000L);
            }
        }
        return START_NOT_STICKY;
    }

    private PendingIntent act(String action, int req) {
        Intent i = new Intent(this, RideService.class).setAction(action);
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null && nm.getNotificationChannel(CH) == null) {
            NotificationChannel c = new NotificationChannel(CH, "라이딩 기록", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("라이딩 기록 중 타이머와 일시정지·종료 버튼");
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean paused = "pause".equals(st);
        if (tx.isEmpty() || nDist > 0) {
            tx = String.format(Locale.US, "%.2fkm · %dkcal · %.1fkm/h", (baseDist + nDist) / 1000, Math.round(baseKcal + nKcal), paused ? 0 : lastV * 3.6);
        }
        long s = el / 1000;
        String clock = s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(paused ? "Cycle AI Coach · 일시정지" : "Cycle AI Coach · 라이딩 기록 중")
                .setContentText(paused ? "일시정지 " + clock + (tx.isEmpty() ? "" : " · " + tx) : (tx.isEmpty() ? "GPS로 기록하고 있습니다" : tx))
                .setSubText(paused ? "일시정지" : "기록 중")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(pi)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(0, paused ? "재개" : "일시정지", act(paused ? A_RESUME : A_PAUSE, 1))
                .addAction(0, "종료·저장", act(A_STOP, 2));
        if (paused) {
            b.setUsesChronometer(false).setShowWhen(false);
        } else {
            long cur = el + (recSince >= 0 ? SystemClock.elapsedRealtime() - recSince : 0);
            b.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - cur);
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        if (lm != null) {
            try { lm.removeUpdates(listener); } catch (Exception ignored) { }
            lm = null;
        }
        active = false;
        fg = false;
        // 앱이 강제로 꺼져 서비스가 끝나는 경우: 웹이 아직 가져가지 못한 점과 운동 시간을 남겨 다음 실행 때 미완료 기록에 이어 붙임
        if (!stopping) {
            long e = el + (recSince >= 0 ? SystemClock.elapsedRealtime() - recSince : 0);
            String pts = pull();
            if (pts.length() > 2 || e > 0) {
                Kv.set(this, "cac.ride.buf", "{\"el\":" + e + ",\"pts\":" + pts + "}");
            }
        }
        stopping = false;
        inst = null;
        synchronized (BUF) { BUF.clear(); }
        if (wl != null && wl.isHeld()) wl.release();
        wl = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
