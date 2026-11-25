package kr.coachinglabs.cycleaicoach;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 앱 저장 데이터(훈련 계획·라이딩 기록 등)를 앱 내부 파일에 즉시 기록하는 저장소.
 * WebView 의 localStorage 는 디스크 반영이 몇 초 늦어, 그 사이 앱이 강제종료되면 방금 저장한 내용이 사라진다.
 * 웹 화면이 localStorage 에 쓸 때마다 같은 값을 여기에도 동기(fsync)로 써 두고, 앱을 다시 켜면 이 값을 우선 복원한다.
 */
final class Kv {
    private Kv() { }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "kv");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String fileName(String k) {
        try { return URLEncoder.encode(k, "UTF-8"); } catch (Exception e) { return k.replaceAll("[^A-Za-z0-9._-]", "_"); }
    }

    private static String keyName(String f) {
        try { return URLDecoder.decode(f, "UTF-8"); } catch (Exception e) { return f; }
    }

    /** 임시 파일에 쓰고 디스크 동기화 후 이름 바꾸기(쓰는 도중 꺼져도 이전 값은 남음) */
    static synchronized boolean set(Context c, String k, String v) {
        if (k == null || v == null) return false;
        File d = dir(c);
        File t = new File(d, fileName(k) + ".tmp"), f = new File(d, fileName(k));
        try (FileOutputStream o = new FileOutputStream(t)) {
            o.write(v.getBytes(StandardCharsets.UTF_8));
            o.flush();
            o.getFD().sync();
        } catch (Exception e) {
            t.delete();
            return false;
        }
        if (t.renameTo(f)) return true;
        f.delete();
        return t.renameTo(f);
    }

    static synchronized void del(Context c, String k) {
        if (k == null) return;
        new File(dir(c), fileName(k)).delete();
    }

    /** 저장된 전체 값을 {"키":"값"} JSON 으로 */
    static synchronized String all(Context c) {
        JSONObject o = new JSONObject();
        File[] fs = dir(c).listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (!f.isFile() || f.getName().endsWith(".tmp")) continue;
                try (FileInputStream in = new FileInputStream(f)) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    o.put(keyName(f.getName()), new String(b.toByteArray(), StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
            }
        }
        return o.toString();
    }
}
