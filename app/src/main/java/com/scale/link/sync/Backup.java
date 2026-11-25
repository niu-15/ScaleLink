package com.scale.link.sync;

import com.scale.link.data.DbHelper;
import com.scale.link.model.Measurement;
import com.scale.link.model.UserProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JSON 备份：全量导出 / 合并导入（按 用户+时间+体重 去重，不覆盖本地已有数据）。
 */
public class Backup {

    public static final String FILE_DIR = "ScaleLink";
    public static final String FILE_NAME = "scalelink-backup.json";
    /** 云端可读表格副本：Excel / WPS / 坚果云预览都能直接打开 */
    public static final String FILE_CSV = "体脂记录.csv";

    public static class Result {
        public int addedUsers;
        public int addedMeasurements;
        public int skipped;
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("新增 ").append(addedMeasurements).append(" 条记录");
            if (addedUsers > 0) sb.append("、").append(addedUsers).append(" 位用户");
            if (skipped > 0) sb.append("，跳过 ").append(skipped).append(" 条重复");
            return sb.toString();
        }
    }

    public static String toJson(DbHelper db) throws Exception {
        List<UserProfile> users = db.getUsers();
        Map<Integer, Integer> idToIndex = new HashMap<>();
        JSONArray ua = new JSONArray();
        for (int i = 0; i < users.size(); i++) {
            UserProfile u = users.get(i);
            idToIndex.put(u.id, i);
            JSONObject o = new JSONObject();
            o.put("name", u.name);
            o.put("gender", u.gender);
            o.put("age", u.age);
            o.put("height", u.height);
            o.put("target", u.targetWeight);
            ua.put(o);
        }

        JSONArray ma = new JSONArray();
        for (Measurement m : db.getAllMeasurements()) {
            Integer idx = idToIndex.get(m.user_id);
            if (idx == null) continue;
            JSONObject o = new JSONObject();
            o.put("u", idx);
            o.put("ts", m.timestamp);
            o.put("w", m.weight);
            o.put("fat", m.fat);
            o.put("water", m.water);
            o.put("muscle", m.muscle);
            o.put("bone", m.bone);
            o.put("visceral", m.visceral);
            o.put("bmr", m.bmr);
            o.put("res", m.resistance);
            o.put("src", m.source == null ? "" : m.source);
            o.put("note", m.note == null ? "" : m.note);
            ma.put(o);
        }

        JSONObject root = new JSONObject();
        root.put("app", "ScaleLink");
        root.put("schema", 2);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("users", ua);
        root.put("measurements", ma);
        return root.toString(2);
    }

    /**
     * 全量 CSV（所有用户），带 UTF-8 BOM，Excel / WPS 直接打开不乱码。
     * 只是给人看的表格副本，恢复仍以 JSON 为准。
     */
    public static String toCsv(DbHelper db) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("用户,时间,体重(kg),体脂率(%),水分率(%),肌肉量(kg),骨量(kg),内脏脂肪,基础代谢(kcal),阻抗(ohm),来源,备注\n");
        Map<Integer, String> names = new HashMap<>();
        for (UserProfile u : db.getUsers()) {
            names.put(u.id, u.name == null ? "用户" : u.name);
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        List<Measurement> list = new ArrayList<>(db.getAllMeasurements());
        Collections.sort(list, new Comparator<Measurement>() {
            @Override
            public int compare(Measurement a, Measurement b) {
                return Long.compare(a.timestamp, b.timestamp);
            }
        });
        for (Measurement m : list) {
            String who = names.get(m.user_id);
            sb.append(csv(who == null ? "用户" : who)).append(',')
                    .append(fmt.format(new Date(m.timestamp))).append(',')
                    .append(m.weight).append(',')
                    .append(m.fat).append(',')
                    .append(m.water).append(',')
                    .append(m.muscle).append(',')
                    .append(m.bone).append(',')
                    .append(m.visceral).append(',')
                    .append(m.bmr).append(',')
                    .append(m.resistance).append(',')
                    .append(csv(m.source)).append(',')
                    .append(csv(m.note)).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String s) {
        if (s == null) return "";
        return s.replace(",", " ").replace("\r", " ").replace("\n", " ").replace("\"", " ");
    }

    public static Result merge(DbHelper db, String json) throws Exception {
        if (json == null || json.trim().isEmpty()) throw new Exception("云端文件为空");
        JSONObject root = new JSONObject(json);
        if (!root.has("users") || !root.has("measurements")) {
            throw new Exception("文件格式不是 ScaleLink 备份");
        }
        Result r = new Result();

        JSONArray ua = root.getJSONArray("users");
        List<UserProfile> local = db.getUsers();
        List<Integer> indexToId = new ArrayList<>();
        for (int i = 0; i < ua.length(); i++) {
            JSONObject o = ua.getJSONObject(i);
            String name = o.optString("name", "用户");
            UserProfile match = null;
            for (UserProfile lu : local) {
                if (lu.name != null && lu.name.equals(name)) { match = lu; break; }
            }
            if (match != null) {
                indexToId.add(match.id);
            } else {
                UserProfile u = new UserProfile();
                u.name = name;
                u.gender = o.optInt("gender", 1);
                u.age = o.optInt("age", 30);
                u.height = o.optDouble("height", 170);
                u.targetWeight = o.optDouble("target", 0);
                int id = (int) db.addUser(u);
                local.add(u);
                indexToId.add(id);
                r.addedUsers++;
            }
        }

        Set<String> keys = db.measurementKeys();
        JSONArray ma = root.getJSONArray("measurements");
        for (int i = 0; i < ma.length(); i++) {
            JSONObject o = ma.getJSONObject(i);
            int idx = o.optInt("u", 0);
            if (idx < 0 || idx >= indexToId.size()) { r.skipped++; continue; }
            int uid = indexToId.get(idx);
            long ts = o.optLong("ts", 0);
            double w = o.optDouble("w", 0);
            if (ts <= 0 || w <= 0) { r.skipped++; continue; }
            String key = uid + "|" + ts + "|" + Math.round(w * 100);
            if (keys.contains(key)) { r.skipped++; continue; }

            Measurement m = new Measurement();
            m.user_id = uid;
            m.timestamp = ts;
            m.weight = w;
            m.fat = o.optDouble("fat", 0);
            m.water = o.optDouble("water", 0);
            m.muscle = o.optDouble("muscle", 0);
            m.bone = o.optDouble("bone", 0);
            m.visceral = o.optDouble("visceral", 0);
            m.bmr = o.optDouble("bmr", 0);
            m.resistance = o.optDouble("res", 0);
            m.source = o.optString("src", "");
            m.note = o.optString("note", "");
            db.addMeasurement(m);
            keys.add(key);
            r.addedMeasurements++;
        }
        return r;
    }
}
