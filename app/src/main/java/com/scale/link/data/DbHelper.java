package com.scale.link.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.scale.link.model.Measurement;
import com.scale.link.model.UserProfile;
import com.scale.link.protocol.BodyComposition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 本地 SQLite：用户档案 + 测量记录。默认全部留在手机，只有开启 WebDAV 同步时才会上传。
 */
public class DbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "scalelink.db";
    private static final int DB_VERSION = 6;
    /** 与 MainActivity 里的体成分校准存的是同一处偏好值 */
    private static final String PREF = "scalelink_pref";
    private static final String KEY_FAT_OFFSET = "fat_calib_offset";

    private final Context appCtx;

    public DbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.appCtx = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE users (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "gender INTEGER DEFAULT 1," +
                "age INTEGER DEFAULT 30," +
                "height REAL DEFAULT 170," +
                "target_weight REAL DEFAULT 0," +
                "avatar TEXT DEFAULT '')");
        db.execSQL("CREATE TABLE measurements (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "timestamp INTEGER NOT NULL," +
                "user_id INTEGER NOT NULL," +
                "weight REAL NOT NULL," +
                "fat REAL DEFAULT 0," +
                "water REAL DEFAULT 0," +
                "muscle REAL DEFAULT 0," +
                "bone REAL DEFAULT 0," +
                "visceral REAL DEFAULT 0," +
                "bmr REAL DEFAULT 0," +
                "resistance REAL DEFAULT 0," +
                "source TEXT DEFAULT ''," +
                "note TEXT DEFAULT '')");
        db.execSQL("CREATE INDEX idx_measure_ts ON measurements(timestamp)");
        db.execSQL("CREATE INDEX idx_measure_user ON measurements(user_id)");
        db.execSQL("INSERT INTO users(name,gender,age,height,target_weight) VALUES('我',1,30,170,0)");
    }

    /**
     * 按当前的算法与校准值，把所有「由 App 自己算出来的」体成分重算一遍。
     * 只处理 resistance > 0 的记录——那是秤只给了体重+阻抗、体脂靠公式推算的；
     * 秤直接输出体脂的记录不动。
     *
     * @return 重算的条数
     */
    public int recomputeComposition(UserProfile u) {
        if (u == null || u.height <= 0) return 0;
        return recomputeWith(getWritableDatabase(), u);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v2 -> v3：清掉物理上不可能是人体体重的记录。
        // 旧版本在误连到温湿度计之类的设备时，会把随便几个字节硬解成体重写进来，
        // 这种数可能落在 20~250 之间（比如 70.9），所以只能在「不是秤就断开」那层根治，
        // 这里只处理明显不可能的（<20kg / >250kg）。
        if (oldVersion < 3) {
            try {
                db.execSQL("DELETE FROM measurements WHERE weight < 20 OR weight > 250");
            } catch (Exception ignored) {}
        }

        // v3 -> v4：体脂等体成分算法换掉了（旧公式对 BMI 23 的人会算出 29% 体脂，
        // 实际约 17%，和京东健康差了 10 个百分点）。这里把历史上由 App 自己算出来的
        // 体成分重算一遍，避免新旧记录在同一张趋势图上一高一低。
        // 只重算 resistance > 0 的记录：说明这台秤只给了体重+阻抗，体脂是算出来的；
        // 秤直接输出体脂的记录不去动它。
        //
        // v5：又去掉了公式里偏大的 1.5 个百分点修正，并且引入了「体成分校准」，
        // 所以再重算一次 —— 已校准过的用户要沿用他那个偏移，不能被打回默认值。
        if (oldVersion < 5) {
            try {
                UserProfile u = null;
                Cursor cu = db.rawQuery("SELECT gender, age, height FROM users ORDER BY id LIMIT 1", null);
                if (cu.moveToNext()) {
                    u = new UserProfile();
                    u.gender = cu.getInt(0);
                    u.age = cu.getInt(1);
                    u.height = cu.getDouble(2);
                }
                cu.close();

                if (u != null && u.height > 0) {
                    try {
                        u.fatOffset = appCtx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                                .getFloat(KEY_FAT_OFFSET, 0f);
                    } catch (Exception ignored) {}
                    recomputeWith(db, u);
                }
            } catch (Exception ignored) {}
        }

        // v5 -> v6：用户表加一列头像表情
        if (oldVersion < 6) {
            try {
                db.execSQL("ALTER TABLE users ADD COLUMN avatar TEXT DEFAULT ''");
            } catch (Exception ignored) {}
        }
    }

    /** 用给定的库连接重算体成分（供 onUpgrade 使用，避免 getWritableDatabase 重入） */
    private static int recomputeWith(SQLiteDatabase db, UserProfile u) {
        List<Measurement> list = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT id, weight, resistance FROM measurements WHERE resistance > 0", null);
        while (c.moveToNext()) {
            Measurement m = new Measurement();
            m.id = (long) c.getInt(0);
            m.weight = c.getDouble(1);
            m.resistance = c.getFloat(2);
            // 清零后重算，enrich 只在字段为空时才填
            m.fat = 0;
            m.water = 0;
            m.muscle = 0;
            m.bone = 0;
            m.visceral = 0;
            m.bmr = 0;
            list.add(m);
        }
        c.close();

        for (Measurement m : list) {
            BodyComposition.enrich(m, u);
            ContentValues v = new ContentValues();
            v.put("fat", m.fat);
            v.put("water", m.water);
            v.put("muscle", m.muscle);
            v.put("bone", m.bone);
            v.put("visceral", m.visceral);
            v.put("bmr", m.bmr);
            db.update("measurements", v, "id = ?", new String[]{String.valueOf(m.id)});
        }
        return list.size();
    }

    // ---------------- 用户 ----------------

    public List<UserProfile> getUsers() {
        List<UserProfile> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM users ORDER BY id", null);
        if (c != null) {
            while (c.moveToNext()) list.add(readUser(c));
            c.close();
        }
        if (list.isEmpty()) {
            UserProfile u = new UserProfile("我", 1, 30, 170, 0);
            u.id = (int) addUser(u);
            list.add(u);
        }
        return list;
    }

    private UserProfile readUser(Cursor c) {
        UserProfile u = new UserProfile();
        u.id = c.getInt(c.getColumnIndexOrThrow("id"));
        u.name = c.getString(c.getColumnIndexOrThrow("name"));
        u.gender = c.getInt(c.getColumnIndexOrThrow("gender"));
        u.age = c.getInt(c.getColumnIndexOrThrow("age"));
        u.height = c.getDouble(c.getColumnIndexOrThrow("height"));
        u.targetWeight = c.getDouble(c.getColumnIndexOrThrow("target_weight"));
        u.avatar = c.getString(c.getColumnIndexOrThrow("avatar"));
        if (u.avatar == null) u.avatar = "";
        return u;
    }

    public long addUser(UserProfile u) {
        return getWritableDatabase().insert("users", null, userValues(u));
    }

    public void updateUser(UserProfile u) {
        getWritableDatabase().update("users", userValues(u), "id=?",
                new String[]{String.valueOf(u.id)});
    }

    private ContentValues userValues(UserProfile u) {
        ContentValues v = new ContentValues();
        v.put("name", u.name);
        v.put("gender", u.gender);
        v.put("age", u.age);
        v.put("height", u.height);
        v.put("target_weight", u.targetWeight);
        v.put("avatar", u.avatar == null ? "" : u.avatar);
        return v;
    }

    public boolean hasUser(String name) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM users WHERE name=? LIMIT 1", new String[]{name});
        boolean has = c != null && c.moveToFirst();
        if (c != null) c.close();
        return has;
    }

    public void deleteUser(int id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("users", "id=?", new String[]{String.valueOf(id)});
        db.delete("measurements", "user_id=?", new String[]{String.valueOf(id)});
    }

    // ---------------- 测量记录 ----------------

    public long addMeasurement(Measurement m) {
        ContentValues v = new ContentValues();
        v.put("timestamp", m.timestamp);
        v.put("user_id", m.user_id);
        v.put("weight", m.weight);
        v.put("fat", m.fat);
        v.put("water", m.water);
        v.put("muscle", m.muscle);
        v.put("bone", m.bone);
        v.put("visceral", m.visceral);
        v.put("bmr", m.bmr);
        v.put("resistance", m.resistance);
        v.put("source", m.source == null ? "" : m.source);
        v.put("note", m.note == null ? "" : m.note);
        return getWritableDatabase().insert("measurements", null, v);
    }

    public List<Measurement> getMeasurements(int userId, int limit) {
        List<Measurement> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM measurements WHERE user_id=? ORDER BY timestamp DESC LIMIT " + limit,
                new String[]{String.valueOf(userId)});
        if (c != null) {
            while (c.moveToNext()) list.add(readMeasurement(c));
            c.close();
        }
        return list;
    }

    /** 指定时间之后（含）的记录，升序 */
    public List<Measurement> getSince(int userId, long fromTs) {
        List<Measurement> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM measurements WHERE user_id=? AND timestamp>=? ORDER BY timestamp ASC",
                new String[]{String.valueOf(userId), String.valueOf(fromTs)});
        if (c != null) {
            while (c.moveToNext()) list.add(readMeasurement(c));
            c.close();
        }
        return list;
    }

    public List<Measurement> getAllMeasurements() {
        List<Measurement> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM measurements ORDER BY timestamp ASC", null);
        if (c != null) {
            while (c.moveToNext()) list.add(readMeasurement(c));
            c.close();
        }
        return list;
    }

    public Measurement getLatest(int userId) {
        List<Measurement> l = getMeasurements(userId, 1);
        return l.isEmpty() ? null : l.get(0);
    }

    public int countMeasurements(int userId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM measurements WHERE user_id=?",
                new String[]{String.valueOf(userId)});
        int n = 0;
        if (c != null && c.moveToFirst()) n = c.getInt(0);
        if (c != null) c.close();
        return n;
    }

    /** 去重指纹集合：user|timestamp|weight */
    public Set<String> measurementKeys() {
        Set<String> keys = new HashSet<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT user_id, timestamp, weight FROM measurements", null);
        if (c != null) {
            while (c.moveToNext()) {
                keys.add(c.getInt(0) + "|" + c.getLong(1) + "|"
                        + Math.round(c.getDouble(2) * 100));
            }
            c.close();
        }
        return keys;
    }

    public void updateNote(long id, String note) {
        ContentValues v = new ContentValues();
        v.put("note", note == null ? "" : note);
        getWritableDatabase().update("measurements", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteMeasurement(long id) {
        getWritableDatabase().delete("measurements", "id=?", new String[]{String.valueOf(id)});
    }

    public void clearMeasurements(int userId) {
        getWritableDatabase().delete("measurements", "user_id=?", new String[]{String.valueOf(userId)});
    }

    public void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("measurements", null, null);
        db.delete("users", null, null);
        db.execSQL("INSERT INTO users(name,gender,age,height,target_weight) VALUES('我',1,30,170,0)");
    }

    // ---------------- 清理「非秤设备产生的假记录」 ----------------

    public static final String SRC_AUTO = "ble_auto";

    /**
     * 找出「来源不可信」的记录：旧版本用「自动探测」把任意设备的字节猜成体重，
     * 这类记录一律标成 source=ble_auto。误连温湿度计产生的 70.9kg 就在其中。
     * 只列出，不删除，由用户确认后再调 applyClean。
     */
    public Report scanUnreliable(int userId) {
        Report r = new Report();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM measurements WHERE user_id=? AND (source=? OR weight<20 OR weight>250)",
                new String[]{String.valueOf(userId), SRC_AUTO});
        if (c != null) {
            while (c.moveToNext()) {
                r.outlier++;
                r.ids.add(c.getLong(0));
            }
            c.close();
        }
        return r;
    }

    // ---------------- 记录体检（清理重复与异常） ----------------

    public static class Report {
        public int total;
        public int dup;
        public int outlier;
        public final List<Long> ids = new ArrayList<>();

        public int count() { return dup + outlier; }
    }

    /**
     * 扫描可清理的记录（不删除，只统计）：
     *  1. 同一分钟内的多条记录（一次称重的多个数据包被分别入库），保留体重最大的那条；
     *  2. 与前后相邻记录中位数偏离超过 30% 的异常值（单脚读数、解析偏移）。
     */
    public Report scanJunk(int userId) {
        Report r = new Report();
        List<Measurement> all = getMeasurements(userId, 100000);
        r.total = all.size();
        if (all.size() < 2) return r;

        // 1) 分钟级去重
        Map<Long, List<Measurement>> buckets = new LinkedHashMap<>();
        for (Measurement m : all) {
            long key = m.timestamp / 60000L;
            List<Measurement> b = buckets.get(key);
            if (b == null) {
                b = new ArrayList<>();
                buckets.put(key, b);
            }
            b.add(m);
        }
        List<Measurement> kept = new ArrayList<>();
        for (List<Measurement> b : buckets.values()) {
            if (b.size() == 1) {
                kept.add(b.get(0));
                continue;
            }
            Measurement best = b.get(0);
            for (Measurement m : b) {
                if (m.weight > best.weight + 0.05) best = m;
                else if (Math.abs(m.weight - best.weight) <= 0.05
                        && m.resistance > 0 && best.resistance <= 0) best = m;
            }
            kept.add(best);
            for (Measurement m : b) {
                if (m.id != best.id) {
                    r.dup++;
                    r.ids.add(m.id);
                }
            }
        }

        // 2) 邻域离群检测（kept 为时间倒序，转成升序便于取邻域）
        Collections.reverse(kept);
        for (int i = 0; i < kept.size(); i++) {
            int lo = Math.max(0, i - 5);
            int hi = Math.min(kept.size() - 1, i + 5);
            // 邻域时间跨度过大（历史导入、间隔很久）时不做离群判断，避免误删
            if (kept.get(hi).timestamp - kept.get(lo).timestamp > 30L * 24 * 3600 * 1000) continue;
            double med = neighborMedian(kept, i, 5);
            if (med <= 0) continue;
            double w = kept.get(i).weight;
            if (w < med * 0.72 || w > med * 1.38) {
                r.outlier++;
                r.ids.add(kept.get(i).id);
            }
        }
        return r;
    }

    /** 取第 i 条前后 span 条（不含自身）的体重中位数，样本不足返回 0 */
    private double neighborMedian(List<Measurement> list, int i, int span) {
        List<Double> vals = new ArrayList<>();
        for (int k = i - span; k <= i + span; k++) {
            if (k == i || k < 0 || k >= list.size()) continue;
            vals.add(list.get(k).weight);
        }
        if (vals.size() < 3) return 0;
        Collections.sort(vals);
        int n = vals.size();
        return n % 2 == 1 ? vals.get(n / 2) : (vals.get(n / 2 - 1) + vals.get(n / 2)) / 2.0;
    }

    /** 执行清理，返回实际删除条数 */
    public int applyClean(Report r) {
        if (r == null || r.ids.isEmpty()) return 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        int deleted = 0;
        try {
            StringBuilder sb = new StringBuilder();
            int inBatch = 0;
            for (Long id : r.ids) {
                if (inBatch == 0) sb.setLength(0);
                if (inBatch > 0) sb.append(',');
                sb.append(id);
                inBatch++;
                if (inBatch >= 400) {
                    deleted += db.delete("measurements", "id IN (" + sb + ")", null);
                    inBatch = 0;
                }
            }
            if (inBatch > 0) deleted += db.delete("measurements", "id IN (" + sb + ")", null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return deleted;
    }

    /** 近期体重中位数（最多取 10 条），用于判断新读数是否合理 */
    public double recentMedianWeight(int userId) {
        List<Measurement> list = getMeasurements(userId, 10);
        if (list.isEmpty()) return 0;
        List<Double> vals = new ArrayList<>();
        for (Measurement m : list) if (m.weight > 0) vals.add(m.weight);
        if (vals.isEmpty()) return 0;
        Collections.sort(vals);
        int n = vals.size();
        return n % 2 == 1 ? vals.get(n / 2) : (vals.get(n / 2 - 1) + vals.get(n / 2)) / 2.0;
    }

    private Measurement readMeasurement(Cursor c) {
        Measurement m = new Measurement();
        m.id = c.getLong(c.getColumnIndexOrThrow("id"));
        m.timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"));
        m.user_id = c.getInt(c.getColumnIndexOrThrow("user_id"));
        m.weight = c.getDouble(c.getColumnIndexOrThrow("weight"));
        m.fat = c.getDouble(c.getColumnIndexOrThrow("fat"));
        m.water = c.getDouble(c.getColumnIndexOrThrow("water"));
        m.muscle = c.getDouble(c.getColumnIndexOrThrow("muscle"));
        m.bone = c.getDouble(c.getColumnIndexOrThrow("bone"));
        m.visceral = c.getDouble(c.getColumnIndexOrThrow("visceral"));
        m.bmr = c.getDouble(c.getColumnIndexOrThrow("bmr"));
        m.resistance = c.getDouble(c.getColumnIndexOrThrow("resistance"));
        m.source = c.getString(c.getColumnIndexOrThrow("source"));
        m.note = c.getString(c.getColumnIndexOrThrow("note"));
        return m;
    }

    /** 导出 CSV */
    public String exportCsv(int userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("时间,体重(kg),体脂率(%),水分率(%),肌肉量(kg),骨量(kg),内脏脂肪,基础代谢(kcal),阻抗(ohm),来源,备注\n");
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
        for (Measurement m : getMeasurements(userId, 100000)) {
            sb.append(fmt.format(new java.util.Date(m.timestamp)).replace(",", " "))
                    .append(',').append(m.weight)
                    .append(',').append(m.fat)
                    .append(',').append(m.water)
                    .append(',').append(m.muscle)
                    .append(',').append(m.bone)
                    .append(',').append(m.visceral)
                    .append(',').append(m.bmr)
                    .append(',').append(m.resistance)
                    .append(',').append(m.source == null ? "" : m.source.replace(",", " "))
                    .append(',').append(m.note == null ? "" : m.note.replace(",", " "))
                    .append('\n');
        }
        return sb.toString();
    }
}
