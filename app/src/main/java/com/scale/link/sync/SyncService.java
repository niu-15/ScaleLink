package com.scale.link.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.scale.link.data.DbHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 同步服务：WebDAV 配置读写 + 后台线程执行双向同步。
 * 双向同步 = 先下载云端备份合并到本地（去重）→ 再把合并后的全量快照上传，避免多设备互相覆盖。
 */
public class SyncService {

    public static final String KEY_URL = "dav_url";
    public static final String KEY_USER = "dav_user";
    public static final String KEY_PASS = "dav_pass";
    public static final String KEY_AUTO = "dav_auto";
    public static final String KEY_LAST = "dav_last";
    public static final String KEY_LAST_MSG = "dav_last_msg";

    public interface Listener {
        void onResult(boolean ok, String message);
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("scalelink_pref", Context.MODE_PRIVATE);
    }

    public static String url(Context c) { return prefs(c).getString(KEY_URL, ""); }
    public static String user(Context c) { return prefs(c).getString(KEY_USER, ""); }
    public static String pass(Context c) { return prefs(c).getString(KEY_PASS, ""); }
    public static boolean auto(Context c) { return prefs(c).getBoolean(KEY_AUTO, false); }
    public static long lastSync(Context c) { return prefs(c).getLong(KEY_LAST, 0); }
    public static String lastMessage(Context c) { return prefs(c).getString(KEY_LAST_MSG, ""); }

    public static boolean isConfigured(Context c) {
        return !url(c).isEmpty() && !user(c).isEmpty() && !pass(c).isEmpty();
    }

    public static void save(Context c, String url, String user, String pass, boolean auto) {
        prefs(c).edit()
                .putString(KEY_URL, url == null ? "" : url.trim())
                .putString(KEY_USER, user == null ? "" : user.trim())
                .putString(KEY_PASS, pass == null ? "" : pass)
                .putBoolean(KEY_AUTO, auto)
                .apply();
    }

    public static void setAuto(Context c, boolean auto) {
        prefs(c).edit().putBoolean(KEY_AUTO, auto).apply();
    }

    public static String friendlyTime(long ts) {
        if (ts <= 0) return "从未同步";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(ts));
    }

    private static WebDavClient client(Context c) {
        return new WebDavClient(url(c), user(c), pass(c));
    }

    /** 额外上传一份 CSV 表格，方便在坚果云/Excel 里直接查看 */
    private static void putCsv(WebDavClient w, DbHelper db) throws Exception {
        String csv = Backup.toCsv(db);
        w.put(Backup.FILE_DIR + "/" + Backup.FILE_CSV, csv.getBytes("UTF-8"));
    }

    private static void finish(Context c, boolean ok, String msg, Listener l) {
        prefs(c).edit().putString(KEY_LAST_MSG, msg)
                .putLong(KEY_LAST, ok ? System.currentTimeMillis() : lastSync(c)).apply();
        if (l != null) {
            final boolean okk = ok;
            final String m = msg;
            new Handler(Looper.getMainLooper()).post(() -> l.onResult(okk, m));
        }
    }

    private static void run(Context c, final Task task, final Listener l) {
        new Thread(() -> {
            try {
                String msg = task.run();
                finish(c, true, msg, l);
            } catch (Exception e) {
                Log.w("SyncService", "同步失败", e);
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                finish(c, false, m, l);
            }
        }).start();
    }

    private interface Task { String run() throws Exception; }

    public static void test(final Context c, final Listener l) {
        run(c, () -> {
            client(c).test();
            return "连接成功，账号验证通过";
        }, l);
    }

    /** 双向同步 */
    public static void sync(final Context c, final DbHelper db, final Listener l) {
        run(c, () -> {
            WebDavClient w = client(c);
            String path = Backup.FILE_DIR + "/" + Backup.FILE_NAME;
            w.ensureDir(Backup.FILE_DIR);
            Backup.Result r = new Backup.Result();
            byte[] remote = w.get(path);
            if (remote != null) {
                r = Backup.merge(db, new String(remote, "UTF-8"));
            }
            String json = Backup.toJson(db);
            w.put(path, json.getBytes("UTF-8"));
            putCsv(w, db);
            int total = db.getAllMeasurements().size();
            return "同步完成：" + r.summary() + "；云端现有 " + total + " 条（JSON + CSV）";
        }, l);
    }

    /** 仅上传（云端覆盖为本地快照） */
    public static void upload(final Context c, final DbHelper db, final Listener l) {
        run(c, () -> {
            WebDavClient w = client(c);
            String path = Backup.FILE_DIR + "/" + Backup.FILE_NAME;
            w.ensureDir(Backup.FILE_DIR);
            String json = Backup.toJson(db);
            w.put(path, json.getBytes("UTF-8"));
            putCsv(w, db);
            int total = db.getAllMeasurements().size();
            return "已上传 " + total + " 条记录（JSON + CSV 表格）";
        }, l);
    }

    /** 仅从云端拉取并合并 */
    public static void restore(final Context c, final DbHelper db, final Listener l) {
        run(c, () -> {
            WebDavClient w = client(c);
            String path = Backup.FILE_DIR + "/" + Backup.FILE_NAME;
            byte[] remote = w.get(path);
            if (remote == null) throw new Exception("云端还没有备份文件，请先上传一次");
            Backup.Result r = Backup.merge(db, new String(remote, "UTF-8"));
            return "恢复完成：" + r.summary();
        }, l);
    }

    /** 测量后自动上传（静默，不打扰用户） */
    public static void autoUpload(final Context c, final DbHelper db) {
        if (!isConfigured(c) || !auto(c)) return;
        new Thread(() -> {
            try {
                WebDavClient w = client(c);
                String path = Backup.FILE_DIR + "/" + Backup.FILE_NAME;
                w.ensureDir(Backup.FILE_DIR);
                String json = Backup.toJson(db);
                w.put(path, json.getBytes("UTF-8"));
                putCsv(w, db);
                prefs(c).edit().putLong(KEY_LAST, System.currentTimeMillis())
                        .putString(KEY_LAST_MSG, "自动上传成功（JSON + CSV）").apply();
            } catch (Exception e) {
                // 不弹窗打扰，但把原因写到状态里，用户在「我的」页能看到
                Log.w("SyncService", "自动上传失败", e);
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                prefs(c).edit().putString(KEY_LAST_MSG, "自动上传失败：" + m).apply();
            }
        }).start();
    }
}
