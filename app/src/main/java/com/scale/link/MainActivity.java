package com.scale.link;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.scale.link.ble.BleManager;
import com.scale.link.data.DbHelper;
import com.scale.link.data.MeasureGate;
import com.scale.link.model.Measurement;
import com.scale.link.model.UserProfile;
import com.scale.link.page.DataPage;
import com.scale.link.page.HomePage;
import com.scale.link.page.Page;
import com.scale.link.page.ProfilePage;
import com.scale.link.protocol.BodyComposition;
import com.scale.link.protocol.ParseContext;
import com.scale.link.sync.Backup;
import com.scale.link.sync.SyncService;
import com.scale.link.ui.AuroraView;
import com.scale.link.ui.IconView;
import com.scale.link.ui.NavBarView;
import com.scale.link.ui.Theme;
import com.scale.link.ui.Ui;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements BleManager.Callback, BleManager.AdvListener {

    // ---------------- 供页面访问的状态 ----------------
    public DbHelper db;
    public BleManager ble;
    public Theme th;
    public List<UserProfile> users = new ArrayList<>();
    public int currentUserId = 1;
    public boolean connected = false;
    public String statusText = "未连接";
    public String protocolText = "";

    /** 原始蓝牙数据日志（最近 400 条），出问题时可直接导出分析 */
    public final ArrayList<String> rawLog = new ArrayList<>();
    private final SimpleDateFormat logFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private Page[] pages;
    private View[] pageViews;
    private final boolean[] dirty = new boolean[4];
    private int tab = 0;
    private FrameLayout content;
    private FrameLayout shell;
    private NavBarView navBar;
    /** 首次进入的资料引导页（覆盖全屏，包含底栏） */
    private View setupOverlay;

    private static final int REQ_PERM = 100;
    private static final int REQ_EXPORT_CSV = 201;
    private static final int REQ_EXPORT_JSON = 202;
    private static final int REQ_IMPORT_JSON = 203;

    private static final String KEY_FACTOR = "calib_factor";
    private static final String KEY_FAT_OFFSET = "fat_calib_offset";
    private static final String KEY_JIN = "unit_jin";
    private static final String KEY_CHART_DAILY = "chart_daily";
    private static final String KEY_LAST_MAC = "last_device_mac";
    private static final String KEY_LAST_NAME = "last_device_name";
    /**
     * 这台设备是否「真的量出过体重」。
     * 以前只要点过连接就把 MAC 存下来，于是误连一次温湿度计（LYWSD03MMC）之后，
     * 每次启动都会自动去连它，还带着重试，把蓝牙占死，真正的秤反而永远连不上。
     * 现在只有量出过数据的设备才算已验证，才允许自动连接。
     */
    private static final String KEY_LAST_VERIFIED = "last_device_verified";
    private static final String KEY_TIDY_ASKED = "tidy_asked";
    private boolean sniffMode = false;

    /** 测量闸门：把一次称重的多个数据包合并成一条记录 */
    private MeasureGate gate;
    /** 正在称重（用于界面显示「测量中」而不是不稳定的瞬时值） */
    public boolean measuring = false;

    // ---------------- 生命周期 ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(Theme.isDarkMode(this) ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(savedInstanceState);
        th = Theme.get(this);
        db = new DbHelper(this);
        users = db.getUsers();
        if (!users.isEmpty()) currentUserId = users.get(0).id;

        ble = new BleManager(this, this);
        ble.setParseContext(new ParseContext(user()));
        ble.setAdvListener(this);

        pages = new Page[]{new HomePage(this), new DataPage(this), new ProfilePage(this)};
        pageViews = new View[3];
        buildGate();
        buildShell();
        showTab(0);
        requestPermissions();
        // 第一次进来先把身高、年龄问清楚，不然算出来的体脂率没有意义。
        // 调试用：adb 带 --ez setup true 可以强制走一遍（不依赖清数据）
        boolean forceSetup = getIntent() != null
                && getIntent().getBooleanExtra("setup", false);
        if (forceSetup || needsProfileSetup()) showProfileSetup();

        // 调试用：adb 带 --ez autoScan true 启动时，不解锁屏幕也能直接开扫，
        // 便于抓「踩上去广播变了哪几个字节」的实验数据（锁屏时无法点按钮）
        autoScanOnStart = getIntent() != null
                && getIntent().getBooleanExtra("autoScan", false);

        // 权限已授予的话，进来就自动连回上次的秤
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            if (autoScanOnStart && hasBlePermissions()) {
                ble.startScan();
                return;
            }
            if (hasBlePermissions()) maybeAutoConnect();
            maybeWarnCalibration();
            maybeOfferTidy();
            maybePurgeFake();
            // 这台秤的体重就在广播里，所以「扫描」本身就是测量状态：
            // 进 App 就保持守候，不用先点连接，站上去直接出数。
            if (hasBlePermissions() && !ble.isConnected() && !ble.isContinuousScanning()) {
                ble.startScan();
            }
        }, 700);
    }

    /**
     * 旧版本误连到非秤设备（温湿度计等）时，会把对方的字节硬解成体重写进历史，
     * 这就是 70.9kg 那条记录的来历。升级后主动提示一次，让用户自己决定要不要删。
     */
    private static final String KEY_FAKE_ASKED = "fake_asked_v28";

    private void maybePurgeFake() {
        if (getSharedPreferences(Theme.PREF, MODE_PRIVATE)
                .getBoolean(KEY_FAKE_ASKED, false)) return;
        final DbHelper.Report rep = db.scanUnreliable(currentUserId);
        if (rep.ids.isEmpty()) {
            getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit()
                    .putBoolean(KEY_FAKE_ASKED, true).apply();
            return;
        }
        getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_FAKE_ASKED, true).apply();
        new AlertDialog.Builder(this)
                .setTitle("有 " + rep.ids.size() + " 条记录来源不可靠")
                .setMessage("这些记录是旧版本「自动猜字节」时写进去的，可能来自别的蓝牙设备"
                        + "（比如温湿度计），并不是你的真实体重。\n\n"
                        + "删除只影响这几条，其它记录不动。")
                .setPositiveButton("删掉这些", (d, w) -> {
                    int n = db.applyClean(rep);
                    refreshAll();
                    toast("已删除 " + n + " 条不可靠记录");
                })
                .setNegativeButton("保留", null)
                .show();
    }

    /**
     * 校准系数检查：系数偏离 1.0 较多时提醒一次。
     * 用「刚站上去的瞬时小数字」去校准，会得到一个把体重缩小一半的错误系数，
     * 之后每次称重都不对，所以这里要显式提出来。
     */
    private void maybeWarnCalibration() {
        float f = calibFactor();
        if (Math.abs(f - 1.0f) < 0.0001f) return;
        if (getSharedPreferences(Theme.PREF, MODE_PRIVATE)
                .getBoolean("calib_warned", false)) return;
        getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit()
                .putBoolean("calib_warned", true).apply();

        new AlertDialog.Builder(this)
                .setTitle("体重校准正在生效")
                .setMessage(String.format(Locale.getDefault(),
                        "当前校准系数 %.3f，App 会把秤的读数换算成原来的 %.0f%%。\n\n"
                                + "如果你是照着「刚踩上去时偏小的数字」校准的，这个系数就是错的，建议重置；"
                                + "如果是和秤面稳定后的读数核对过，保留即可。",
                        f, f * 100))
                .setPositiveButton("重置为默认", (d, w) -> resetCalibration())
                .setNegativeButton("保留", null)
                .show();
    }

    /**
     * 建立测量闸门。它负责把一次称重里的多个蓝牙包合并成一条记录，
     * 并丢掉「只踩了一只脚」的瞬时读数。
     */
    private void buildGate() {
        gate = new MeasureGate(new MeasureGate.Listener() {
            @Override
            public void onLive(double kg, int packetCount, boolean stableSeen) {
                boolean was = measuring;
                measuring = true;
                // 只有秤自己报「稳定」时才显示数字。
                // 站稳之前秤会连续发一串瞬时值（20.8 / 41.6 / 67.6 …），
                // 把它们实时刷出来，看起来就像读数在乱跳。
                final String live;
                if (stableSeen) {
                    statusText = String.format(Locale.getDefault(),
                            "已稳定：%.1f%s，正在记录…", wDisp(kg), wUnit());
                    live = String.format(Locale.getDefault(), "已稳定 %.1f %s · 正在记录",
                            wDisp(kg), wUnit());
                } else {
                    statusText = "测量中：请赤脚站稳，别晃动…";
                    live = "测量中 · 请赤脚站稳";
                }
                runOnUiThread(() -> {
                    if (!was && pages[0] instanceof HomePage) {
                        ((HomePage) pages[0]).setMeasuring(true);
                    }
                    if (pages[0] instanceof HomePage) {
                        ((HomePage) pages[0]).setLive(live);
                    }
                });
            }

            @Override
            public void onCommit(Measurement m, int packetCount) {
                measuring = false;
                advSameCount = 0;
                advPrevKg = 0;
                markDeviceVerified();
                commitMeasurement(m, packetCount);
            }

            @Override
            public void onReject(double kg, String reason) {
                measuring = false;
                statusText = "已忽略不稳定读数";
                runOnUiThread(() -> {
                    toast("已忽略 " + reason);
                    refreshAll();
                });
            }
        }, () -> db.recentMedianWeight(currentUserId));
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void buildShell() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(th.bg);
        shell = root;

        // 背景光晕层：玻璃卡片透过来的层次来源
        root.addView(new AuroraView(this, th), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout outer = Ui.col(this);
        content = new FrameLayout(this);
        outer.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部导航：整条自绘（NavBarView），图标/文字/药丸共用同一个中心线，
        // 不会因为 TextView 的字体内边距、自定义 View 的测量差异而产生错位
        LinearLayout navWrap = Ui.col(this);
        navWrap.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 18));
        // 底栏这一条铺满页面底色：AuroraView 的光晕不再从胶囊边上渗出来，
        // 之前深色模式下那圈不均匀的亮边有一半来自这里
        navWrap.setBackgroundColor(th.bg);

        navBar = new NavBarView(this, th, new String[]{"首页", "数据", "我的"});
        // 不再抬 elevation：视图没有圆角 outline 时，投影会按矩形画，
        // 在深色底上就是四条边各一圈灰雾
        navBar.setElevation(0);
        navBar.setOnSelect(this::showTab);
        navWrap.addView(navBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, NavBarView.heightPx(this)));
        outer.addView(navWrap, Ui.matchWrap());

        root.addView(outer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    // ---------------- 首次进入：资料引导 ----------------

    private static final String KEY_PROFILE_SETUP = "profile_setup_done";

    /**
     * 是否需要引导填资料。
     * 默认用户是「我 / 男 / 30 / 170cm」，而身高直接参与 BMI 和体成分的换算，
     * 按 170 硬算出来的体脂率没有参考价值，所以第一次必须让用户自己填。
     * 已经改过默认值的老用户不再打扰（顺手补一个标记）。
     */
    private boolean needsProfileSetup() {
        if (getSharedPreferences(Theme.PREF, MODE_PRIVATE)
                .getBoolean(KEY_PROFILE_SETUP, false)) return false;
        UserProfile u = user();
        if (u != null && (Math.abs(u.height - 170) > 0.5 || u.age != 30
                || (u.name != null && !"我".equals(u.name)))) {
            getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit()
                    .putBoolean(KEY_PROFILE_SETUP, true).apply();
            return false;
        }
        return true;
    }

    private void showProfileSetup() {
        if (setupOverlay != null || shell == null) return;
        final UserProfile u = user();
        final int[] gender = {u.gender == 0 ? 0 : 1};
        final int[] age = {u.age >= 10 && u.age <= 100 ? u.age : 25};
        final int[] height = {u.height >= 120 && u.height <= 230
                ? (int) Math.round(u.height) : 170};

        // 整屏覆盖：底栏也盖住，避免没填完就点进别的页面
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(th.bg);
        overlay.setClickable(true);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout page = Ui.col(this);
        page.setPadding(Ui.dp(this, 22), Ui.dp(this, 26), Ui.dp(this, 22), Ui.dp(this, 24));

        // 上下各留一段可伸缩空白，内容块在屏内垂直居中，底注沉底
        page.addView(new View(this), Ui.weightLp(1f, 0));

        page.addView(Ui.tvMedium(this, "先完善一下资料", 24f, th.text));
        TextView sub = Ui.tv(this,
                "身高和年龄会直接影响 BMI、体脂率和基础代谢。\n"
                        + "先填准，后面测出来的数字才有参考价值。", 12.5f, th.textDim);
        sub.setLineSpacing(Ui.dpf(this, 3), 1f);
        sub.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 24));
        page.addView(sub);

        LinearLayout card = Ui.card(this, th);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 6));

        LinearLayout gRow = Ui.row(this);
        gRow.setGravity(Gravity.CENTER_VERTICAL);
        gRow.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
        gRow.addView(Ui.tv(this, "性别", 13f, th.text),
                Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView male = genderPill("男", gender[0] == 1);
        final TextView female = genderPill("女", gender[0] != 1);
        gRow.addView(male);
        LinearLayout.LayoutParams flp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.leftMargin = Ui.dp(this, 8);
        gRow.addView(female, flp);
        card.addView(gRow);

        card.addView(Ui.divider(this, th));
        card.addView(stepperRow("年龄", " 岁", 10, 100, age));
        card.addView(Ui.divider(this, th));
        card.addView(stepperRow("身高", " cm", 120, 230, height));
        page.addView(card);

        Ui.tap(male, () -> { gender[0] = 1; syncGender(male, female, 1); });
        Ui.tap(female, () -> { gender[0] = 0; syncGender(male, female, 0); });

        TextView tip = Ui.tv(this, "之后可以在「我的」里随时修改", 11f, th.textFaint);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, Ui.dp(this, 16), 0, 0);
        page.addView(tip);

        TextView go = Ui.primaryButton(this, th, "开始使用");
        LinearLayout.LayoutParams glp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 48));
        glp.topMargin = Ui.dp(this, 18);
        page.addView(go, glp);
        Ui.tap(go, () -> applyProfileSetup(gender[0], age[0], height[0]));

        // 撑开剩余高度，把底注压到屏幕底部，避免下半屏一大片空白
        page.addView(new View(this), Ui.weightLp(1f, 0));
        TextView foot = Ui.tv(this, "蓝牙直连 · 数据只存在本机", 10.5f, th.textFaint);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, 0, 0, Ui.dp(this, 6));
        page.addView(foot);

        sv.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        overlay.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        shell.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setupOverlay = overlay;
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(220).start();
    }

    private TextView genderPill(String text, boolean selected) {
        TextView tv = Ui.tvMedium(this, text, 13f, selected ? th.onAccent : th.textDim);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8));
        tv.setBackground(selected ? Ui.round(this, th.accent, 12)
                : Ui.round(this, th.surfaceAlt, 12));
        tv.setClickable(true);
        return tv;
    }

    private void syncGender(TextView male, TextView female, int gender) {
        boolean m = gender == 1;
        male.setTextColor(m ? th.onAccent : th.textDim);
        male.setBackground(m ? Ui.round(this, th.accent, 12)
                : Ui.round(this, th.surfaceAlt, 12));
        female.setTextColor(m ? th.textDim : th.onAccent);
        female.setBackground(m ? Ui.round(this, th.surfaceAlt, 12)
                : Ui.round(this, th.accent, 12));
    }

    /** 「− 25 岁 +」这种步进行，比弹键盘输入更少出错 */
    private View stepperRow(String label, String unit, int min, int max, final int[] value) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        row.addView(Ui.tv(this, label, 13f, th.text),
                Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView val = Ui.tvMedium(this, value[0] + unit, 16f, th.text);
        val.setGravity(Gravity.CENTER);
        val.setMinWidth(Ui.dp(this, 78));
        TextView minus = stepBtn("−");
        TextView plus = stepBtn("+");
        row.addView(minus);
        row.addView(val);
        row.addView(plus);

        Ui.tap(minus, () -> {
            if (value[0] > min) { value[0]--; val.setText(value[0] + unit); }
        });
        Ui.tap(plus, () -> {
            if (value[0] < max) { value[0]++; val.setText(value[0] + unit); }
        });
        return row;
    }

    private TextView stepBtn(String s) {
        TextView tv = Ui.tvMedium(this, s, 17f, th.text);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        tv.setMinWidth(Ui.dp(this, 38));
        tv.setBackground(Ui.round(this, th.surfaceAlt, 12));
        tv.setClickable(true);
        return tv;
    }

    private void applyProfileSetup(int gender, int age, int height) {
        UserProfile u = user();
        u.gender = gender;
        u.age = age;
        u.height = height;
        db.updateUser(u);
        // 身高变了，历史记录里按旧身高算出来的体脂率要跟着重算
        db.recomputeComposition(u);
        reloadUsers();
        getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_PROFILE_SETUP, true).apply();

        final View ov = setupOverlay;
        setupOverlay = null;
        if (ov == null) {
            refreshAll();
            return;
        }
        ov.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            if (shell != null) shell.removeView(ov);
            refreshAll();
        }).start();
    }

    @Override
    public void onBackPressed() {
        // 资料还没填完，别让返回键把 App 关掉
        if (setupOverlay != null) return;
        super.onBackPressed();
    }

    private void showTab(int index) {
        tab = index;
        if (pageViews[index] == null) pageViews[index] = pages[index].build();
        content.removeAllViews();
        View page = pageViews[index];
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pages[index].refresh();
        dirty[index] = false;

        // 轻微上浮淡入，切换更有质感
        page.setAlpha(0f);
        page.setTranslationY(Ui.dpf(this, 14));
        page.animate().alpha(1f).translationY(0f).setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f)).start();

        navBar.setSelected(index);
    }

    public void rebuildUi() {
        recreate();
    }

    public void refreshAll() {
        for (int i = 0; i < 4; i++) dirty[i] = true;
        if (pages != null && pageViews != null && pageViews[tab] != null) {
            pages[tab].refresh();
            dirty[tab] = false;
        }
    }

    public void reloadUsers() {
        users = db.getUsers();
        if (users.isEmpty()) return;
        boolean found = false;
        for (UserProfile u : users) if (u.id == currentUserId) found = true;
        if (!found) currentUserId = users.get(0).id;
        ble.setParseContext(new ParseContext(user()));
    }

    // ---------------- 供页面使用的工具方法 ----------------

    public UserProfile user() {
        for (UserProfile u : users) {
            if (u.id == currentUserId) {
                u.fatOffset = fatOffset();
                return u;
            }
        }
        if (!users.isEmpty()) {
            users.get(0).fatOffset = fatOffset();
            return users.get(0);
        }
        UserProfile u = new UserProfile("我", 1, 30, 170, 0);
        u.fatOffset = fatOffset();
        return u;
    }

    /** 体成分校准偏移（百分点），0 表示未校准 */
    public double fatOffset() {
        return getSharedPreferences(Theme.PREF, MODE_PRIVATE).getFloat(KEY_FAT_OFFSET, 0f);
    }

    public void setFatOffset(double v) {
        getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit()
                .putFloat(KEY_FAT_OFFSET, (float) v).apply();
    }

    public boolean useJin() {
        return getSharedPreferences(Theme.PREF, MODE_PRIVATE).getBoolean(KEY_JIN, false);
    }

    public void setUseJin(boolean jin) {
        getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit().putBoolean(KEY_JIN, jin).apply();
    }

    public String wUnit() {
        return useJin() ? "斤" : "kg";
    }

    /** 趋势图是否按天合并（一天取最后一次）。默认开：一天称几次不该把曲线拉成锯齿 */
    public boolean chartDaily() {
        return getSharedPreferences(Theme.PREF, MODE_PRIVATE).getBoolean(KEY_CHART_DAILY, true);
    }

    public void setChartDaily(boolean daily) {
        getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_CHART_DAILY, daily).apply();
    }

    public double wDisp(double kg) {
        return useJin() ? kg * 2 : kg;
    }

    public String wFmt(double kg) {
        return String.format(Locale.getDefault(), "%.1f", wDisp(kg));
    }

    public void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 人体体重合理范围，入库前的最后一道校验 */
    private static final double W_MIN_KG = 20.0;
    private static final double W_MAX_KG = 250.0;
    /** 与近期中位数偏离超过这个比例，先不自动入库（防止别的蓝牙设备凑出来的假读数） */
    private static final double W_MAX_DEV = 0.40;

    public float calibFactor() {
        return getSharedPreferences(Theme.PREF, MODE_PRIVATE).getFloat(KEY_FACTOR, 1.0f);
    }

    public boolean hasCalibration() {
        return Math.abs(calibFactor() - 1.0f) > 0.0001f;
    }

    public void resetCalibration() {
        setCalibFactor(1.0f);
        refreshAll();
        toast("已恢复默认系数，App 将直接采用秤的原始读数");
    }

    private void setCalibFactor(float f) {
        getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit().putFloat(KEY_FACTOR, f).apply();
    }

    // ---------------- 权限 ----------------

    private void requestPermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            perms.add(Manifest.permission.BLUETOOTH);
            perms.add(Manifest.permission.BLUETOOTH_ADMIN);
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> need = new ArrayList<>();
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_PERM) {
            boolean allGranted = true;
            for (int g : grants) {
                if (g != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) toast("蓝牙权限未全部授予，可能扫不到秤");
            else {
                maybeAutoConnect();
                refreshAutoWatch();
            }
        }
    }

    // ---------------- 设备连接 ----------------

    private static class ScanDevice {
        BluetoothDevice device;
        String name;
        int rssi;
        List<String> uuids;
        boolean isScale;
        boolean uuidHit;   // 广播里带体脂秤协议 UUID：比名字靠谱得多
        boolean ignored;   // 已判定「不是秤」（温湿度计/手环等），不自动连
        String reason;

        ScanDevice(BluetoothDevice d, String n, int r, List<String> u) {
            device = d;
            name = n;
            rssi = r;
            uuids = u == null ? new ArrayList<>() : u;
            analyze();
        }

        void analyze() {
            String lower = name == null ? "" : name.toLowerCase(Locale.getDefault());
            boolean xiaomiEco = false;
            for (String su : uuids) {
                String s = su.toLowerCase(Locale.getDefault());
                // 这几组是体脂秤专用的服务/服务数据：
                // 181D 体成分、181B 体重、FFB0 ElinkThings、FAA0 部分 ODM、EBE0 小米体脂秤
                if (s.contains("181d") || s.contains("181b") || s.contains("ffb0")
                        || s.contains("faa0") || s.contains("ebe0")) {
                    isScale = true;
                    uuidHit = true;
                    reason = "广播含体脂秤服务";
                    return;
                }
                if (s.contains("fe95")) xiaomiEco = true;   // 小米生态厂商 ID
            }
            // 只保留真正指向秤的词。以前有 "jd"、"ts-" 这类两三个字母的短词，
            // 会把 JDY 模块、TS 传感器统统当成秤，自动连上去反而挡住真秤。
            String[] kw = {"scale", "body", "fat", "weight", "health", "秤", "体脂",
                    "体重", "mibody", "varm", "lefun", "yolanda", "lepu", "tiansheng"};
            for (String k : kw) {
                if (lower.contains(k)) {
                    isScale = true;
                    reason = "名称像体脂秤";
                    return;
                }
            }
            // 0xFE95 是小米生态厂商 ID，温湿度计也用，所以只当线索、不自动连
            reason = xiaomiEco ? "小米生态广播，可能相关" : "未识别，可尝试连接";
        }
    }

    private final LinkedHashMap<String, ScanDevice> scanDevices = new LinkedHashMap<>();
    private AlertDialog scanDialog;
    private LinearLayout scanContainer;
    private TextView scanState;
    /** 连接弹窗里的状态标题/副标题，连上或失败时要实时反映 */
    private TextView dlgStatusTitle, dlgStatusSub;

    public void openDeviceDialog() {
        scanDevices.clear();
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 6), Ui.dp(this, 18), Ui.dp(this, 4));

        // ① 状态卡：一眼看到现在连没连、上次是哪台，可直接一键连
        LinearLayout statusCard = Ui.col(this);
        statusCard.setBackground(Ui.roundStroke(this,
                connected ? Ui.alpha(th.accent, th.dark ? 0.18f : 0.10f) : th.surfaceAlt,
                16, connected ? Ui.alpha(th.accent, 0.3f) : th.border, 1));
        statusCard.setPadding(Ui.dp(this, 12), Ui.dp(this, 11), Ui.dp(this, 12), Ui.dp(this, 11));

        LinearLayout stRow = Ui.row(this);
        LinearLayout stCol = Ui.col(this);
        TextView stTitle = Ui.tvMedium(this, connected ? "已连接" : "未连接",
                13f, connected ? th.accent : th.text);
        TextView stSub = Ui.tv(this, statusText, 10.5f, th.textFaint);
        stSub.setPadding(0, Ui.dp(this, 3), 0, 0);
        stSub.setMaxLines(2);
        stCol.addView(stTitle);
        stCol.addView(stSub);
        dlgStatusTitle = stTitle;
        dlgStatusSub = stSub;
        stRow.addView(stCol, Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (hasLastDevice() && !ble.isIgnored(lastMacRaw())) {
            TextView quick = Ui.textButton(this, th,
                    connected ? "重新连接" : "一键连接");
            quick.setBackground(Ui.round(this, th.accentSoft, 12));
            quick.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
            stRow.addView(quick);
            Ui.tap(quick, () -> {
                if (scanDialog != null && scanDialog.isShowing()) scanDialog.dismiss();
                ble.stopScanQuiet();
                reconnectLast();
            });
        }
        statusCard.addView(stRow);
        String lastMac = lastMacRaw();
        if (hasLastDevice() && !ble.isIgnored(lastMac)) {
            TextView last = Ui.tv(this, "上次设备：" + lastDeviceName(), 10f, th.textFaint);
            last.setPadding(0, Ui.dp(this, 7), 0, 0);
            statusCard.addView(last);
        } else if (!ble.ignoredList().isEmpty()) {
            TextView last = Ui.tv(this, "已忽略 " + ble.ignoredList().size()
                    + " 台非秤设备（列表里灰色的那些，不会再自动连）", 10f, th.textFaint);
            last.setPadding(0, Ui.dp(this, 7), 0, 0);
            statusCard.addView(last);
        }
        LinearLayout.LayoutParams cardLp = Ui.matchWrap();
        cardLp.bottomMargin = Ui.dp(this, 10);
        box.addView(statusCard, cardLp);

        // ② 操作指引
        scanState = Ui.tv(this, "踩一下秤面唤醒它，看到屏幕亮起后再扫描。分不清哪台是秤？挨个点「连接」，"
                + "点错的那台会自动被标记忽略", 11.5f, th.textDim);
        scanState.setPadding(0, 0, 0, Ui.dp(this, 8));
        box.addView(scanState);

        // ②.5 环境自检：缺权限 / 缺定位时直接说明并给一键跳转，
        // 否则用户只能对着一个空列表反复点「扫描」
        View envWarn = buildEnvWarning();
        if (envWarn != null) {
            LinearLayout.LayoutParams wlp = Ui.matchWrap();
            wlp.bottomMargin = Ui.dp(this, 10);
            box.addView(envWarn, wlp);
        }

        // ③ 设备列表
        ScrollView sv = new ScrollView(this);
        scanContainer = Ui.col(this);
        sv.addView(scanContainer, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(sv, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 240)));

        // ④ 按钮
        LinearLayout btns = Ui.row(this);
        btnScanToggle = Ui.primaryButton(this, th, "开始扫描");
        TextView bonded = Ui.ghostButton(this, th, "已配对设备");
        btns.addView(btnScanToggle, Ui.weightLp(1f, Ui.dp(this, 42)));
        LinearLayout.LayoutParams lp = Ui.weightLp(1f, Ui.dp(this, 42));
        lp.leftMargin = Ui.dp(this, 8);
        btns.addView(bonded, lp);
        LinearLayout.LayoutParams blp = Ui.matchWrap();
        blp.topMargin = Ui.dp(this, 10);
        box.addView(btns, blp);

        Ui.tap(btnScanToggle, this::toggleScan);
        Ui.tap(bonded, () -> {
            ble.stopScanQuiet();
            if (scanDialog != null && scanDialog.isShowing()) scanDialog.dismiss();
            openBondedDialog();
        });

        // ⑤ 自动连接开关（记住选择，下次进 App 直接连）
        final TextView autoChip = Ui.chip(this, th, "",
                autoConnectEnabled() ? th.accentSoft : th.surfaceAlt,
                autoConnectEnabled() ? th.accent : th.textDim);
        autoChip.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        autoChip.setText(autoConnectEnabled() ? "启动时自动连接：开" : "启动时自动连接：关");
        LinearLayout.LayoutParams chipLp = Ui.matchWrap();
        chipLp.topMargin = Ui.dp(this, 9);
        box.addView(autoChip, chipLp);
        Ui.tap(autoChip, () -> {
            boolean on = !autoConnectEnabled();
            setAutoConnect(on);
            autoChip.setText(on ? "启动时自动连接：开" : "启动时自动连接：关");
            autoChip.setTextColor(on ? th.accent : th.textDim);
            autoChip.setBackground(Ui.round(this, on ? th.accentSoft : th.surfaceAlt, 9));
            toast(on ? "下次打开 App 会自动连回这台秤" : "已关闭自动连接");
        });

        scanDialog = new AlertDialog.Builder(this)
                .setTitle("连接体脂秤")
                .setView(box)
                .setNegativeButton("关闭", (d, w) -> ble.stopScanQuiet())
                .create();
        scanDialog.setOnDismissListener(d -> {
            ble.stopScanQuiet();
            ui.removeCallbacks(scanTick);
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        scanDialog.show();
        // 扫码期间别让屏幕黑掉：黑屏后系统会降低扫描频率，很容易错过秤的广播窗口
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.removeCallbacks(scanTick);
        ui.post(scanTick);
        startScan();
    }

    // ---------------- 扫描进度与环境自检 ----------------

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    /** 已经自动尝试连接过的设备，避免反复自动重试 */
    private ScanDevice autoPicked;
    /** 守候模式要盯的 MAC（已验证的秤）；null 表示不守候 */
    private String watchMac = null;
    /** adb 带 autoScan 启动时自动开扫（锁屏调试用） */
    private boolean autoScanOnStart = false;
    /** 自动连接连续失败次数，超过上限就不再自动重试 */
    private int autoConnectFailures = 0;

    /**
     * 连上之后迟迟没有体重数据 —— 很可能连错设备了
     * （例如名字像 LYWSD03MMC 的小米温湿度计，它也会正常连上并推数据，但不是秤）。
     */
    private final Runnable noDataHint = () -> {
        if (!connected) return;
        statusText = "已连接，但一直没收到体重数据";
        toast("连上 " + lastDeviceName() + " 后没收到体重数据，它可能不是体脂秤。"
                + "可在「我的 › 忘记上次连接的设备」后重新扫描。");
    };

    private final Runnable scanTick = new Runnable() {
        @Override
        public void run() {
            if (scanDialog == null || !scanDialog.isShowing()) return;
            long sec = ble.scanElapsedMs() / 1000;
            int n = scanDevices.size();
            if (scanState != null) {
                if (n == 0 && sec >= 20) {
                    // 一台设备都扫不到，和「扫到一堆但没秤」是两回事：
                    // 前者几乎一定是权限/定位/蓝牙状态的问题，别让用户一直踩秤。
                    scanState.setText("已扫 " + sec + " 秒，附近一台设备都没扫到。"
                            + "这说明问题在权限/定位/蓝牙，而不是秤 —— 请先处理下面的自检项");
                } else if (n == 0) {
                    scanState.setText(String.format(Locale.getDefault(),
                            "正在寻找…已扫 %d 秒 · 请踩一下秤面把它唤醒", sec));
                } else {
                    scanState.setText(String.format(Locale.getDefault(),
                            "已发现 %d 台设备 · 已扫 %d 秒", n, sec));
                }
            }
            // 状态卡实时反映连接进度：用户能看出「到底卡在哪一步」，
            // 而不是对着一个一直转圈的界面反复点连接
            if (dlgStatusTitle != null && dlgStatusSub != null) {
                boolean conn = connected || ble.isConnected();
                dlgStatusTitle.setText(conn ? "已连接" : ble.isConnecting() ? "连接中…" : "未连接");
                dlgStatusTitle.setTextColor(conn ? th.accent : th.text);
                dlgStatusSub.setText(statusText);
            }
            ui.postDelayed(this, 1000);
        }
    };

    private boolean isLocationEnabled() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager)
                    getSystemService(LOCATION_SERVICE);
            if (lm == null) return true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return lm.isLocationEnabled();
            return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return true;
        }
    }

    /** 缺权限/缺定位时就地把问题说清楚，并给一个一键跳转 */
    private View buildEnvWarning() {
        String issue = null;
        String btn = null;
        Runnable action = null;

        if (!ble.isBluetoothOn()) {
            issue = "手机蓝牙没打开。打开蓝牙后回来点「开始扫描」。";
            btn = "打开蓝牙";
            action = () -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
                } catch (Exception e) {
                    toast("请在系统设置里打开蓝牙");
                }
            };
        } else if (!hasBlePermissions()) {
            issue = "「附近的设备」权限还没授予，系统不会把扫描结果交给 App。";
            btn = "去授权";
            action = this::requestPermissions;
        } else if (!isLocationEnabled()) {
            issue = "系统「定位服务」是关闭的。安卓规定：定位关着的时候蓝牙扫描可能收不到任何广播"
                    + "（国产 ROM 尤其严格）。打开定位后回来重扫。";
            btn = "打开定位";
            action = () -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (Exception e) {
                    toast("请在系统设置里打开定位");
                }
            };
        }
        if (issue == null) return null;

        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.roundStroke(this, Ui.alpha(th.warn, th.dark ? 0.16f : 0.20f),
                14, Ui.alpha(th.warn, 0.55f), 1));
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));

        TextView title = Ui.tvMedium(this, "还差一步就能扫到秤", 12f, th.text);
        TextView body = Ui.tv(this, issue, 11f, th.textDim);
        body.setPadding(0, Ui.dp(this, 3), 0, 0);
        body.setLineSpacing(Ui.dpf(this, 2), 1f);
        card.addView(title);
        card.addView(body);

        if (btn != null) {
            TextView b = Ui.textButton(this, th, btn);
            b.setBackground(Ui.round(this, Ui.alpha(th.warn, th.dark ? 0.22f : 0.32f), 10));
            b.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
            LinearLayout.LayoutParams blp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = Ui.dp(this, 8);
            card.addView(b, blp);
            final Runnable act = action;
            Ui.tap(b, () -> { if (act != null) act.run(); });
        }
        return card;
    }

    private TextView btnScanToggle;

    private void toggleScan() {
        if (ble.isFinding()) {
            ble.stopScan();
            if (btnScanToggle != null) btnScanToggle.setText("开始扫描");
        } else {
            startScan();
        }
    }

    private void startScan() {
        scanDevices.clear();
        autoPicked = null;
        if (scanContainer != null) {
            scanContainer.removeAllViews();
            renderScanList();
        }
        if (scanState != null) scanState.setText("正在寻找…请踩一下秤面");
        if (btnScanToggle != null) btnScanToggle.setText("暂停扫描");
        ble.startScan();
    }

    @Override
    public void onScanResult(final BluetoothDevice device, final String name, final int rssi,
                             final List<String> serviceUuids) {
        runOnUiThread(() -> {
            // 守候模式：已验证的秤一出现就立刻连（这是「踩上去就自动连」的关键）。
            // 放在弹窗判断之前，因为守候时根本没有弹窗。
            if (watchMac != null && !connected && !ble.isConnecting()
                    && (scanDialog == null || !scanDialog.isShowing())) {
                String addr;
                try { addr = device.getAddress(); } catch (Exception e) { addr = ""; }
                if (watchMac.equalsIgnoreCase(addr)) {
                    // 广播直读型：不需要连接，扫描本身就是「在测量」，别打断它
                    if (ble.isAdvScale(addr)) return;
                    ble.stopScanQuiet();
                    statusText = "发现秤，正在自动连接…";
                    connectTo(device, lastDeviceName(), true);
                    return;
                }
            }
            if (scanDialog == null || !scanDialog.isShowing()) return;
            String mac = device.getAddress();
            boolean ign = ble.isIgnored(mac);
            ScanDevice exist = scanDevices.get(mac);
            if (exist != null) {
                exist.ignored = ign;
                if (exist.rssi >= rssi) return;
            }
            ScanDevice sd = new ScanDevice(device, name, rssi, serviceUuids);
            sd.ignored = ign;
            // 广播型秤：名字往往是空的、UUID 也认不出来，但它的广播格式我们已经解开了，
            // 这里打上「★ 不用连接」的标记，避免用户以为连不上是坏了。
            if (ble.isAdvScale(mac)) {
                sd.isScale = true;
                sd.reason = "广播直读 · 不用连接，站上去就有数";
            }
            // 每台设备只记一次（首次发现），这样日志不会刷屏，
            // 但又能完整回答「到底扫到了哪些设备」这个最关键的问题
            if (exist == null) {
                logLine("SCAN " + sd.name + " " + mac + " rssi=" + rssi + " · " + sd.reason
                        + (sd.uuids.isEmpty() ? "" : " · " + sd.uuids));
            }
            scanDevices.put(mac, sd);
            renderScanList();
            maybeAutoPick();
        });
    }

    /**
     * 认出体脂秤就自动连一次：这类秤被踩醒后只广播几秒，
     * 等用户看清名字再点，往往已经错过广播窗口了。
     * 只在「广播里真的带了体脂秤协议 UUID」时才自动连——这是硬信号。
     * 光靠名字匹配太容易误判（温湿度计、手环都可能叫得像秤），
     * 一旦连错设备，蓝牙就被占住，真秤反而连不上。
     */
    private void maybeAutoPick() {
        if (connected || ble.isConnected()) return;
        if (!ble.isFinding()) return;
        ScanDevice best = null;
        for (ScanDevice sd : scanDevices.values()) {
            if (!sd.uuidHit) continue;
            if (sd.ignored) continue;        // 判定过「不是秤」的，别再自动连
            if (sd.rssi < -85) continue;
            if (best == null || sd.rssi > best.rssi) best = sd;
        }
        if (best == null || best == autoPicked) return;
        autoPicked = best;
        final ScanDevice pick = best;
        ui.postDelayed(() -> {
            if (scanDialog == null || !scanDialog.isShowing()) return;
            if (connected || ble.isConnected()) return;
            toast("发现体脂秤：" + pick.name + "，正在自动连接…");
            connectTo(pick.device, pick.name, true);
        }, 1200);
    }

    private void renderScanList() {
        if (scanContainer == null) return;
        scanContainer.removeAllViews();
        List<ScanDevice> list = new ArrayList<>(scanDevices.values());
        java.util.Collections.sort(list, (a, b) -> {
            if (a.ignored != b.ignored) return a.ignored ? 1 : -1;   // 已忽略的沉到底部
            if (a.isScale != b.isScale) return a.isScale ? -1 : 1;
            return Integer.compare(b.rssi, a.rssi);
        });

        if (list.isEmpty()) {
            LinearLayout empty = Ui.col(this);
            empty.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            TextView t = Ui.tv(this, "还没发现设备。这类秤只有在「被踩醒」时才广播，按下面四步试：", 11.5f, th.textDim);
            t.setLineSpacing(Ui.dpf(this, 2), 1f);
            empty.addView(t);
            String[] steps = {
                    "① 踩一下秤面，等秤上的屏幕亮起来（别松脚太快）",
                    "② 关掉「京东健康」等 App —— 被别的 App 连着的秤不会再广播",
                    "③ 手机的「定位服务」要打开，否则安卓可能不给扫描结果",
                    "④ 还是不行：点右下「已配对设备」，从系统蓝牙列表里直接连"
            };
            for (String s : steps) {
                TextView st = Ui.tv(this, s, 11f, th.textFaint);
                st.setPadding(0, Ui.dp(this, 5), 0, 0);
                st.setLineSpacing(Ui.dpf(this, 2), 1f);
                empty.addView(st);
            }
            scanContainer.addView(empty);
            return;
        }

        for (final ScanDevice sd : list) {
            LinearLayout row = Ui.row(this);
            boolean hi = sd.isScale && !sd.ignored;
            row.setBackground(Ui.roundStroke(this,
                    sd.ignored ? th.surfaceAlt : (hi ? th.accentSoft : th.surfaceAlt),
                    14,
                    sd.ignored ? th.border : (hi ? Ui.alpha(th.accent, 0.35f) : th.border), 1));
            row.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            if (sd.ignored) row.setAlpha(0.55f);

            row.addView(signalBars(sd.rssi), Ui.lp(Ui.dp(this, 16), Ui.dp(this, 16)));

            LinearLayout info = Ui.col(this);
            TextView n = Ui.tvMedium(this, (hi ? "★ " : "") + sd.name, 12.5f,
                    hi ? th.accent : th.text);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            String sub = sd.ignored ? "已忽略（不是秤）· 点按可恢复" : sd.reason;
            if (!sd.uuids.isEmpty()) sub += " · " + shortUuid(sd.uuids.get(0));
            // 把 MAC 也显示出来：多台同名设备时能分清，出问题也方便直接核对
            try { sub += " · " + sd.device.getAddress(); } catch (Exception ignored) {}
            TextView d = Ui.tv(this, sub, 10f, th.textFaint);
            d.setPadding(0, Ui.dp(this, 3), 0, 0);
            d.setSingleLine(true);
            info.addView(n);
            info.addView(d);
            LinearLayout.LayoutParams infoLp = Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT);
            infoLp.leftMargin = Ui.dp(this, 9);
            row.addView(info, infoLp);

            TextView go = Ui.textButton(this, th, sd.ignored ? "恢复" : "连接");
            row.addView(go);

            final Runnable connectAction = () -> {
                if (sd.ignored) {
                    unignoreDevice(sd);
                } else {
                    connectTo(sd.device, sd.name);
                }
            };
            Ui.tap(row, connectAction);
            Ui.tap(go, connectAction);

            LinearLayout wrap = Ui.col(this);
            wrap.setPadding(0, 0, 0, Ui.dp(this, 8));
            wrap.addView(row, Ui.matchWrap());
            scanContainer.addView(wrap);
        }
    }

    /** 解除某台设备的忽略标记（用户确认它其实是秤） */
    private void unignoreDevice(final ScanDevice sd) {
        String mac = "";
        try { mac = sd.device.getAddress(); } catch (Exception ignored) {}
        final String m = mac;
        new AlertDialog.Builder(this)
                .setTitle("恢复这台设备")
                .setMessage("「" + sd.name + "」之前因为查不到体重特征被判定不是体脂秤。\n\n"
                        + "如果确认它就是你的秤，可以解除忽略再试一次。")
                .setPositiveButton("恢复并连接", (d, w) -> {
                    ble.unignoreDevice(m);
                    sd.ignored = false;
                    renderScanList();
                    connectTo(sd.device, sd.name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 三格信号条：越靠左越弱，直观判断远近 */    private View signalBars(int rssi) {
        int level = rssi >= -62 ? 3 : rssi >= -76 ? 2 : 1;
        LinearLayout box = Ui.row(this);
        box.setGravity(Gravity.BOTTOM);
        int[] hs = {6, 10, 14};
        for (int i = 0; i < 3; i++) {
            View bar = new View(this);
            int color = i < level ? th.accent : Ui.alpha(th.textFaint, 0.35f);
            bar.setBackground(Ui.round(this, color, 1.5f));
            LinearLayout.LayoutParams lp = Ui.lp(Ui.dp(this, 3), Ui.dp(this, hs[i]));
            if (i > 0) lp.leftMargin = Ui.dp(this, 2);
            box.addView(bar, lp);
        }
        return box;
    }

    private String shortUuid(String uuid) {
        if (uuid == null) return "";
        String u = uuid.toUpperCase(Locale.getDefault());
        // "data:0000181D-..." / "MFR:0XFE95" 这两种前缀要单独处理，否则显示成一长串看不懂的东西
        if (u.startsWith("DATA:")) {
            String v = u.substring(5);
            if (v.startsWith("0000") && v.length() >= 8) return "数据 0x" + v.substring(4, 8);
            return v.length() > 13 ? v.substring(0, 13) + "…" : v;
        }
        if (u.startsWith("MFR:")) return "厂商 " + u.substring(4);
        if (u.startsWith("0000") && u.length() >= 8) return "0x" + u.substring(4, 8);
        return u.length() > 13 ? u.substring(0, 13) + "…" : u;
    }

    @Override
    public void onScanFinished() {
        runOnUiThread(() -> {
            if (btnScanToggle != null) btnScanToggle.setText("开始扫描");
            if (scanDialog != null && scanDialog.isShowing() && scanState != null) {
                scanState.setText(scanDevices.isEmpty()
                        ? "已暂停寻找。踩一下秤面让它亮起来，再点「开始扫描」"
                        : "已暂停寻找，点设备即可连接");
            }
        });
    }

    public void openBondedDialog() {
        List<BluetoothDevice> bonded = ble.getBondedDevices();
        final List<BluetoothDevice> candidates = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (BluetoothDevice d : bonded) {
            String n;
            try {
                n = d.getName() != null ? d.getName() : d.getAddress();
            } catch (Exception e) {
                n = "未知设备";
            }
            String lower = n.toLowerCase(Locale.getDefault());
            if (lower.contains("headset") || lower.contains("buds") || lower.contains("airpod")
                    || lower.contains("watch") || lower.contains("car") || lower.contains("speaker")
                    || lower.contains("sound") || lower.contains("audio")) continue;
            candidates.add(d);
            names.add(n + "\n" + d.getAddress());
        }
        if (candidates.isEmpty()) {
            toast("系统蓝牙列表里没有可选设备，请先在手机设置里配对");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择已配对设备")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    connectTo(candidates.get(which), names.get(which).split("\n")[0]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 连接与重连 ----------------

    private void connectTo(BluetoothDevice device, String name) {
        connectTo(device, name, false);
    }

    private void connectTo(BluetoothDevice device, String name, boolean quiet) {
        if (device == null) {
            toast("设备不可用，请重新扫描");
            return;
        }

        // 这台秤的体重就在广播里，广播本身不可连接（connectable=false），
        // 对它发起 connectGatt 必然 HCI 0x08 超时。所以「连接」这个动作
        // 在这里直接改成「开始守候广播」——效果一样，还不会失败。
        if (ble.isAdvScale(device.getAddress())) {
            if (scanDialog != null && scanDialog.isShowing()) scanDialog.dismiss();
            try {
                getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit()
                        .putString(KEY_LAST_MAC, device.getAddress())
                        .putString(KEY_LAST_NAME, name == null ? "体脂秤" : name)
                        .putBoolean(KEY_LAST_VERIFIED, true)
                        .apply();
            } catch (Exception ignored) {}
            watchMac = device.getAddress();
            if (!ble.isContinuousScanning()) ble.startScan();
            statusText = "已锁定「" + (name == null ? "体脂秤" : name) + "」，请赤脚站上去";
            runOnUiThread(() -> {
                toast("这台秤不用连接，站上去就会自动读数");
                refreshAll();
            });
            return;
        }

        if (scanDialog != null && scanDialog.isShowing()) scanDialog.dismiss();
        ble.stopScanQuiet();
        ble.setAutoReconnect(false);
        ble.connect(device);
        statusText = "正在连接 " + (name == null ? "设备" : name) + "…";
        try {
            String oldMac = getSharedPreferences(Theme.PREF, MODE_PRIVATE).getString(KEY_LAST_MAC, "");
            android.content.SharedPreferences.Editor e = getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit()
                    .putString(KEY_LAST_MAC, device.getAddress())
                    .putString(KEY_LAST_NAME, name == null ? "上次的设备" : name);
            // 换了一台设备，之前的「已验证」就不算数了，必须重新量出数据才恢复自动连接
            if (!device.getAddress().equals(oldMac)) e.putBoolean(KEY_LAST_VERIFIED, false);
            e.apply();
        } catch (Exception ignored) {}
        if (!quiet) toast("正在连接，请赤脚站到秤上");
        refreshAll();
    }

    /** 量出过真实体重后，才算这台设备是秤，才允许下次开机自动连 */
    private void markDeviceVerified() {
        try {
            getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit()
                    .putBoolean(KEY_LAST_VERIFIED, true).apply();
        } catch (Exception ignored) {}
    }

    private boolean lastDeviceVerified() {
        return getSharedPreferences(Theme.PREF, MODE_PRIVATE)
                .getBoolean(KEY_LAST_VERIFIED, false);
    }

    public boolean hasLastDevice() {
        return !getSharedPreferences(Theme.PREF, MODE_PRIVATE)
                .getString(KEY_LAST_MAC, "").isEmpty();
    }

    public String lastDeviceName() {
        return getSharedPreferences(Theme.PREF, MODE_PRIVATE)
                .getString(KEY_LAST_NAME, "上次的设备");
    }

    public String lastMacRaw() {
        try {
            return getSharedPreferences(Theme.PREF, MODE_PRIVATE).getString(KEY_LAST_MAC, "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 忘掉上次连接的设备。
     * 万一之前误连了别的蓝牙设备（比如名字叫 LYWSD03MMC 的温湿度计），
     * App 每次启动都会自动连它，导致永远连不到秤，所以必须能一键清掉。
     */
    public void forgetLastDevice() {
        if (!hasLastDevice()) {
            toast("还没有连接记录");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("忘记这台设备")
                .setMessage("将清除「" + lastDeviceName() + "」的连接记录，之后不会自动连它。")
                .setPositiveButton("忘记", (d, w) -> {
                    getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit()
                            .remove(KEY_LAST_MAC).remove(KEY_LAST_NAME)
                            .putBoolean(KEY_LAST_VERIFIED, false).apply();
                    ble.disconnect();
                    toast("已忘记，请重新扫描连接秤");
                    refreshAll();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void reconnectLast() {
        String mac = getSharedPreferences(Theme.PREF, MODE_PRIVATE).getString(KEY_LAST_MAC, "");
        if (mac.isEmpty()) {
            toast("还没有连接过设备");
            return;
        }
        BluetoothDevice d = ble.deviceByMac(mac);
        if (d == null) {
            toast("蓝牙未开启或设备已不可用");
            return;
        }
        connectTo(d, lastDeviceName());
    }

    /** 首页「重连」按钮在连接中会变成「停止连接」，需要这个判断 */
    public boolean bleConnecting() {
        return !connected && ble.isConnecting();
    }

    public void stopConnecting() {
        ble.abortConnect();
        statusText = "已停止连接";
        toast("已停止连接");
        refreshAll();
    }

    // ---------------- 自动连接 ----------------

    private static final String KEY_AUTO = "auto_connect";

    public boolean autoConnectEnabled() {
        return getSharedPreferences(Theme.PREF, MODE_PRIVATE).getBoolean(KEY_AUTO, true);
    }

    public void setAutoConnect(boolean on) {
        getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit().putBoolean(KEY_AUTO, on).apply();
        ble.setAutoReconnect(on);
    }

    /** 打开 App 时自动连回上次的秤（不弹窗、不打断） */
    private void maybeAutoConnect() {
        if (!autoConnectEnabled() || connected || !hasLastDevice()) return;
        // 只自动连「量出过体重」的设备。没验证过的一律不碰，
        // 否则一旦误连过温湿度计之类的设备，每次开机都会去连它并把蓝牙占死。
        if (!lastDeviceVerified()) return;
        if (!ble.isBluetoothOn()) return;
        final String lastMac = getSharedPreferences(Theme.PREF, MODE_PRIVATE)
                .getString(KEY_LAST_MAC, "");
        // 上次那台是「广播直读型」的秤：不要连接，直接开扫，站上去就有数
        if (ble.isAdvScale(lastMac)) {
            watchMac = lastMac;
            statusText = "正在守候体脂秤…请赤脚站上去";
            refreshAll();
            if (!ble.isContinuousScanning()) ble.startScan();
            return;
        }
        final BluetoothDevice d = ble.deviceByMac(lastMac);
        if (d == null) return;
        ble.setAutoReconnect(false);
        statusText = "正在自动连接上次的秤…";
        refreshAll();
        ble.connect(d);
    }

    // ---------------- BLE 回调 ----------------

    @Override
    public void onConnected(String deviceName) {
        connected = true;
        autoConnectFailures = 0;
        statusText = "已连接：" + deviceName;
        ui.removeCallbacks(noDataHint);
        ui.postDelayed(noDataHint, 25000);
        runOnUiThread(() -> {
            toast("已连接，请赤脚站稳约 5 秒");
            refreshAll();
        });
    }

    @Override
    public void onDisconnected() {
        connected = false;
        measuring = false;
        statusText = "连接已断开";
        ui.removeCallbacks(noDataHint);
        // 秤称完通常会自己休眠断开，这里把还没结算的读数结算掉
        if (gate != null) gate.flush();
        runOnUiThread(this::refreshAll);
    }

    @Override
    public void onProtocolDetected(String name, String hint) {
        protocolText = name + (hint == null || hint.isEmpty() ? "" : " · " + hint);
        statusText = "协议已识别，请站上秤";
        runOnUiThread(this::refreshAll);
    }

    @Override
    public void onState(String state) {
        statusText = state;
        runOnUiThread(this::refreshAll);
    }

    @Override
    public void onError(String message) {
        statusText = "错误：" + message;
        runOnUiThread(() -> {
            toast(message);
            refreshAll();
        });
        // 自动连接失败后重新开始守候，否则会「试坏一次就再也不自动连了」。
        // 但必须有次数上限：万一那台设备能广播却始终拒绝连接，
        // 无上限重试会变成耗电和无意义的反复连接。
        if (watchMac != null && !connected) {
            autoConnectFailures++;
            if (autoConnectFailures >= 3) {
                watchMac = null;
                toast("自动连接多次失败，请手动点「连接」");
            } else {
                ui.postDelayed(this::refreshAutoWatch, 2000);
            }
        }
    }

    @Override
    public void onSniff(String line) {
        android.util.Log.d("ScaleLinkSniff", line);
        // 只有用户主动开了「协议嗅探」才把 HEX 顶到状态栏上，
        // 否则每条蓝牙包都会把「已连接 / 测量中」这类有用状态覆盖成看不懂的十六进制
        if (ble != null && ble.isSniffMode()) statusText = "嗅探：" + line;
        logLine(line);
    }

    /**
     * 连上后发现对方不是秤。
     * 关键动作有两个：① 立刻把这台设备记进忽略列表（蓝牙层已持久化），
     * ② 如果「上次的设备」正好是它，顺手清掉，否则每次开机都会自动扑上去连它，
     *    真秤反而永远轮不到 —— 之前那条 70.9kg 就是这么来的。
     */
    @Override
    public void onNotScale(final String deviceName, final String mac) {
        final String name = deviceName == null ? "该设备" : deviceName;
        runOnUiThread(() -> {
            String lastMac;
            try {
                lastMac = getSharedPreferences(Theme.PREF, MODE_PRIVATE).getString(KEY_LAST_MAC, "");
            } catch (Exception e) {
                lastMac = "";
            }
            if (mac != null && !mac.isEmpty() && mac.equalsIgnoreCase(lastMac)) {
                getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit()
                        .remove(KEY_LAST_MAC).remove(KEY_LAST_NAME)
                        .putBoolean(KEY_LAST_VERIFIED, false).apply();
                watchMac = null;
                ble.stopScanQuiet();
            }
            statusText = "「" + name + "」不是体脂秤，已自动忽略";
            refreshAll();
            new AlertDialog.Builder(this)
                    .setTitle("这不是体脂秤")
                    .setMessage("「" + name + "」的服务里没有体重特征（也没有已知的秤协议），"
                            + "它可能是温湿度计、手环一类的设备。\n\n"
                            + "已把它加入忽略列表：以后扫描时不会再自动连它，也不会解析它的任何数据。")
                    .setPositiveButton("知道了", null)
                    .setNeutralButton("仍然尝试", (d, w) -> {
                        BluetoothDevice dev = ble.deviceByMac(mac);
                        if (dev == null) {
                            toast("设备已不可用，请重新扫描");
                            return;
                        }
                        ble.connectAnyway(dev);
                    })
                    .show();
        });
    }

    private void logLine(String line) {
        synchronized (rawLog) {
            rawLog.add(logFmt.format(new Date()) + "  " + line);
            while (rawLog.size() > 400) rawLog.remove(0);
        }
    }

    /** 原始数据诊断面板：把最近收到的包按 HEX 列出来，方便定位解析问题 */    /**
    // ---------------- 广播抓包（不连接设备） ----------------

    /**
     * 不连接任何设备，只把附近所有 BLE 广播原样抓下来。
     *
     * 为什么这条路可能直接解决问题：体脂秤的体重不一定只在「连接后的通知」里，
     * 有相当一部分型号会把体重放进广播的 service data。如果广播里就有体重，
     * App 根本不需要建立连接 —— 扫描即测量，连不上的问题就彻底绕过去了。
     *
     * 界面上按「广播变化次数」排序：你踩上秤的一瞬间，秤的广播内容会变，
     * 变化最多的那台就是秤。
     */
    private static class AdvDev {
        String mac = "";
        String name = "";
        int rssi;
        String lastHex = "";
        int changes;
        String serviceData = "";
        String mfr = "";
        String uuids = "";
        String candidates = "";
        final ArrayList<String> frames = new ArrayList<>();
    }

    private final LinkedHashMap<String, AdvDev> advMap = new LinkedHashMap<>();
    private TextView advText;
    private AlertDialog advDialog;
    private long advSince;
    private boolean advDirty;
    private final SimpleDateFormat advTimeFmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private final Runnable advRender = new Runnable() {
        @Override
        public void run() {
            if (advDialog == null || !advDialog.isShowing()) return;
            advDirty = false;
            renderAdvReport();
            ui.postDelayed(this, 700);
        }
    };

    public void showAdvSniffer() {
        synchronized (advMap) { advMap.clear(); }
        advSince = System.currentTimeMillis();

        advText = new TextView(this);
        advText.setTextSize(9.5f);
        advText.setTypeface(Typeface.MONOSPACE);
        advText.setTextColor(th.textDim);
        advText.setTextIsSelectable(true);
        advText.setLineSpacing(Ui.dpf(this, 2), 1f);
        advText.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));

        ScrollView sv = new ScrollView(this);
        sv.addView(advText, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tip = Ui.tv(this, "现在开始抓广播。请踩上秤站住 5 秒 —— 秤被唤醒时广播内容会变，"
                + "变化最多的那台就是它。抓完点「复制」发我。", 11f, th.textFaint);
        tip.setLineSpacing(Ui.dpf(this, 2), 1f);
        tip.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 4));

        LinearLayout box = Ui.col(this);
        box.addView(tip);
        box.addView(sv, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 380)));

        advDialog = new AlertDialog.Builder(this)
                .setTitle("广播抓包（不连接设备）")
                .setView(box)
                .setPositiveButton("复制全部", (d, w) -> copyToClipboard(buildAdvText(true)))
                .setNeutralButton("清空重抓", (d, w) -> {
                    synchronized (advMap) { advMap.clear(); }
                    advSince = System.currentTimeMillis();
                    renderAdvReport();
                })
                .setNegativeButton("停止", (d, w) -> {
                    ble.stopScanQuiet();
                    ble.setAdvListener(this);
                })
                .create();
        advDialog.setOnDismissListener(d -> {
            ble.stopScanQuiet();
            ui.removeCallbacks(advRender);
        });
        advDialog.show();

        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!ble.isScanning() && !ble.isFinding()) ble.startScan();
        ui.removeCallbacks(advRender);
        ui.postDelayed(advRender, 400);
    }

    @Override
    public void onAdv(final BluetoothDevice device, final String name, final int rssi,
                      final byte[] raw, final List<String> uuids,
                      final String serviceDataHex, final String mfrHex) {
        if (advDialog == null || !advDialog.isShowing()) return;
        String mac;
        try { mac = device.getAddress(); } catch (Exception e) { mac = "?"; }
        final String hex = toHexStr(raw);
        synchronized (advMap) {
            AdvDev d = advMap.get(mac);
            if (d == null) {
                if (advMap.size() >= 60) return;
                d = new AdvDev();
                d.mac = mac;
                advMap.put(mac, d);
            }
            d.name = (name == null || name.isEmpty()) ? d.name : name;
            d.rssi = rssi;
            d.uuids = uuids == null ? "" : uuids.toString();

            boolean changed = !hex.equals(d.lastHex);
            if (changed && !d.lastHex.isEmpty()) {
                d.changes++;
                // 只保留最近 6 帧，够看出「怎么变的」，又不会把内存吃满
                d.frames.add(0, advTimeFmt.format(new Date()) + "  " + hex);
                while (d.frames.size() > 6) d.frames.remove(d.frames.size() - 1);
            } else if (d.frames.isEmpty()) {
                d.frames.add(0, advTimeFmt.format(new Date()) + "  " + hex);
            }
            d.lastHex = hex;
            if (serviceDataHex != null && !serviceDataHex.isEmpty()) d.serviceData = serviceDataHex;
            if (mfrHex != null && !mfrHex.isEmpty()) d.mfr = mfrHex;
            d.candidates = weightCandidates(raw);
            advDirty = true;
        }
    }

    /**
     * 在广播原始字节里找「像体重」的 2 字节小端值。
     * 两个常见缩放都试一遍（÷200 和 ÷100），把落在 20~250kg 的列出来。
     * 只是候选提示，用来判断「广播里到底有没有体重」，最终还得看 HEX 本身。
     */
    private String weightCandidates(byte[] raw) {
        if (raw == null || raw.length < 3) return "";
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i + 1 < raw.length && shown < 3; i++) {
            int v = (raw[i] & 0xFF) | ((raw[i + 1] & 0xFF) << 8);
            if (v == 0) continue;
            double kg200 = v / 200.0;
            double kg100 = v / 100.0;
            String hit = null;
            if (kg200 >= 20 && kg200 <= 250) hit = String.format(Locale.getDefault(), "%.2f", kg200);
            else if (kg100 >= 20 && kg100 <= 250) hit = String.format(Locale.getDefault(), "%.2f", kg100);
            if (hit == null) continue;
            if (sb.length() > 0) sb.append("  ");
            sb.append(String.format(Locale.getDefault(), "@%d=%s", i, hit));
            shown++;
        }
        return sb.toString();
    }

    private String buildAdvText(boolean withHeader) {
        StringBuilder sb = new StringBuilder();
        long sec = (System.currentTimeMillis() - advSince) / 1000;
        List<AdvDev> list;
        synchronized (advMap) {
            list = new ArrayList<>(advMap.values());
            if (list.size() > 8) {
                // 变化多的排前面：踩秤时秤的广播一定会变，这条排序直接把它顶上来
                java.util.Collections.sort(list, (a, b) -> Integer.compare(b.changes, a.changes));
                list = new ArrayList<>(list.subList(0, Math.min(8, list.size())));
            }
        }
        if (withHeader) {
            sb.append("=== 广播抓包 ").append(new java.text.SimpleDateFormat("MM-dd HH:mm:ss",
                    Locale.getDefault()).format(new Date(advSince))).append(" · 共 ")
                    .append(sec).append(" 秒 ===\n");
            sb.append("设备数：").append(advMap.size()).append("\n\n");
        }
        for (AdvDev d : list) {
            sb.append(d.changes > 0 ? "★ " : "  ")
                    .append(d.name.isEmpty() ? "未命名" : d.name)
                    .append("  ").append(d.mac)
                    .append("  ").append(d.rssi).append("dBm")
                    .append("  变化").append(d.changes).append("次\n");
            if (!d.serviceData.isEmpty()) sb.append("    serviceData: ").append(d.serviceData).append('\n');
            if (!d.mfr.isEmpty()) sb.append("    manufacturer: ").append(d.mfr).append('\n');
            if (!d.uuids.isEmpty()) sb.append("    uuids: ").append(d.uuids).append('\n');
            if (!d.candidates.isEmpty()) sb.append("    疑似体重: ").append(d.candidates).append('\n');
            for (String f : d.frames) sb.append("    ").append(f).append('\n');
            sb.append('\n');
        }
        if (list.isEmpty()) sb.append("还没有抓到任何广播。\n");
        return sb.toString();
    }

    private void renderAdvReport() {
        if (advText == null) return;
        long sec = (System.currentTimeMillis() - advSince) / 1000;
        advText.setText("已抓 " + sec + " 秒 · 设备 " + advMap.size()
                + " 台\n" + "（★ = 广播内容变过，踩秤时变化最多的就是秤）\n\n"
                + buildAdvText(false));
    }

    private String toHexStr(byte[] d) {
        if (d == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }

    /**
     * 连接自检：把所有会影响「能不能连上」的条件逐条检查并给出结论。
     * 这样用户不用猜，也不用凭感觉反复点「连接」；
     * 结果可以一键复制，出问题时直接发回来就能定位。
     */
    public void showDiagnostics() {
        StringBuilder sb = new StringBuilder();
        boolean ok = true;

        // 1. 蓝牙开关
        boolean bt = ble.isBluetoothOn();
        sb.append(bt ? "✅ " : "❌ ").append("蓝牙开关：").append(bt ? "已打开" : "关闭");
        if (!bt) { sb.append("　→ 打开蓝牙后重试"); ok = false; }
        sb.append('\n');

        // 2. 附近设备权限
        boolean perm = hasBlePermissions();
        sb.append(perm ? "✅ " : "❌ ").append("「附近的设备」权限：")
                .append(perm ? "已授权" : "未授权");
        if (!perm) { sb.append("　→ 点「去授权」"); ok = false; }
        sb.append('\n');

        // 3. 定位服务（部分国产系统即使声明了 neverForLocation 也要求开着）
        boolean loc = isLocationEnabled();
        sb.append(loc ? "✅ " : "⚠️ ").append("系统定位服务：").append(loc ? "已开启" : "关闭");
        if (!loc) sb.append("　→ 部分机型关闭定位时会拿不到扫描结果，建议打开");
        sb.append('\n');

        // 4. 扫描器
        boolean scannerOk = ble.isScannerAvailable();
        sb.append(scannerOk ? "✅ " : "❌ ").append("BLE 扫描器：")
                .append(scannerOk ? "可用" : "不可用（关闭再打开蓝牙）");
        if (!scannerOk) ok = false;
        sb.append('\n');

        // 5. 已配对设备（不依赖扫描的备用通道）
        List<BluetoothDevice> bonded = ble.getBondedDevices();
        sb.append("ℹ️ 系统已配对设备：").append(bonded.size()).append(" 台");
        for (int i = 0; i < Math.min(bonded.size(), 5); i++) {
            sb.append("\n    · ").append(bleSafeName(bonded.get(i)));
        }
        sb.append('\n');

        // 6. 上次连接的设备 & 是否验证过
        String mac = getSharedPreferences(Theme.PREF, MODE_PRIVATE).getString(KEY_LAST_MAC, "");
        if (mac.isEmpty()) {
            sb.append("ℹ️ 上次设备：无记录\n");
        } else {
            boolean v = lastDeviceVerified();
            sb.append(v ? "✅ " : "⚠️ ").append("上次设备：").append(lastDeviceName())
                    .append("（").append(mac).append("）");
            sb.append(v ? " 已验证（量出过体重）" : " 未验证，可能不是秤；不会自动连接");
            sb.append('\n');
        }

        // 7. 当前状态
        sb.append("ℹ️ 当前状态：").append(connected ? "已连接" : ble.isConnecting() ? "连接中" : "未连接");
        sb.append("　协议：").append(protocolText.isEmpty() ? "未识别" : protocolText).append('\n');
        sb.append("ℹ️ 本次共记录蓝牙包：").append(rawLogSize()).append(" 条\n\n");

        sb.append(ok ? "以上必检项都正常。若仍扫不到秤，按顺序试：\n"
                        + "① 踩一下秤面让它亮起来（这类秤只醒几秒）\n"
                        + "② 彻底关闭京东健康（它连着秤时秤不再广播）\n"
                        + "③ 用「从系统蓝牙列表连接」走备用通道\n"
                        + "④ 手机蓝牙关掉再打开一次"
                : "先解决上面标 ❌ 的项，再回来重新连接。");

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(11.5f);
        tv.setTextColor(th.textDim);
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(Ui.dpf(this, 3), 1f);
        tv.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));

        new AlertDialog.Builder(this)
                .setTitle("连接自检")
                .setView(tv)
                .setPositiveButton("复制结果", (d, w) -> copyToClipboard(sb.toString()))
                .setNegativeButton("关闭", null)
                .show();
    }

    private String bleSafeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return (n == null || n.isEmpty() ? "未命名" : n) + "（" + d.getAddress() + "）";
        } catch (Exception e) {
            return d.getAddress();
        }
    }

    private int rawLogSize() {
        synchronized (rawLog) { return rawLog.size(); }
    }

    public void showRawLog() {        StringBuilder sb = new StringBuilder();
        synchronized (rawLog) {
            int from = Math.max(0, rawLog.size() - 200);
            for (int i = from; i < rawLog.size(); i++) sb.append(rawLog.get(i)).append('\n');
        }
        final String text = sb.length() == 0
                ? "还没有收到任何数据。\n\n先连接体脂秤，赤脚站上去称一次，再回来这里查看。"
                : sb.toString();

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(9.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(th.textDim);
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(Ui.dpf(this, 2), 1f);
        tv.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));

        ScrollView sv = new ScrollView(this);
        sv.addView(tv, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("原始数据 · 最近 " + Math.min(200, rawLog.size()) + " 条")
                .setView(sv)
                .setPositiveButton("复制全部", (d, w) -> copyToClipboard(text))
                .setNeutralButton("清空", (d, w) -> {
                    synchronized (rawLog) { rawLog.clear(); }
                    toast("日志已清空");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ScaleLink raw", text));
                toast("已复制到剪贴板");
            }
        } catch (Exception e) {
            toast("复制失败：" + e.getMessage());
        }
    }

    @Override
    public void onMeasurement(final Measurement m) {
        // 不直接入库：先交给闸门聚合，等这一次称重结束再落一条记录
        ui.removeCallbacks(noDataHint);
        if (gate != null) gate.feed(m);
    }

    /** 广播里直接读到的体重：上一次的读数、以及「连续几次一致」的计数 */
    private double advPrevKg = 0;
    private int advSameCount = 0;
    private long advLastTs = 0;

    /**
     * 这台秤不连接，体重直接来自广播。
     * kg = 0 表示秤面上没人（已经从上面下来），闸门会靠「静默 2.2 秒」自动结算。
     */
    @Override
    public void onAdvWeight(String mac, String name, final double kg, final double resistance) {
        if (kg <= 0) {
            // 人下去了：清零稳定计数，并把「秤面已空」告诉用户
            advSameCount = 0;
            advPrevKg = 0;
            if (measuring) statusText = "测量结束，正在保存…";
            runOnUiThread(this::refreshAll);
            return;
        }
        ui.removeCallbacks(noDataHint);
        advLastTs = System.currentTimeMillis();

        // 广播没有「稳定位」可用，自己判断：连续多次读数相差 ≤0.3kg 就算站稳。
        // 广播约 3~5Hz，8 次一致 ≈ 2 秒。之前只要 4 次，
        // 「一只脚先落、停一下再补另一只」过程中的 62.5kg 这种中途值会被当成站稳入库。
        if (advPrevKg > 0 && Math.abs(kg - advPrevKg) <= 0.3) {
            advSameCount++;
        } else {
            advSameCount = 0;
        }
        advPrevKg = kg;
        final boolean steady = advSameCount >= 7;
        if (steady && advSameCount == 7) {
            android.util.Log.d("ScaleLinkSniff", String.format(Locale.getDefault(),
                    "ADVW 站稳 %.2fkg 阻抗=%.0f", kg, resistance));
        }

        // 记住这台秤，下次开 App 直接守候它
        if (mac != null && !mac.equalsIgnoreCase(watchMac == null ? "" : watchMac)) {
            watchMac = mac;
            try {
                getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit()
                        .putString(KEY_LAST_MAC, mac)
                        .putString(KEY_LAST_NAME, name)
                        .apply();
            } catch (Exception ignored) {}
        }

        Measurement m = new Measurement();
        m.weight = kg;
        m.resistance = (float) resistance;
        m.source = "ble_adv";
        m.note = steady ? "测量中·稳定" : "测量中";
        m.timestamp = System.currentTimeMillis();
        if (gate != null) gate.feed(m);
    }

    /** 一次称重结束时调用：写入唯一的一条记录 */
    private void commitMeasurement(final Measurement m, final int packets) {
        runOnUiThread(() -> {
            m.user_id = currentUserId;
            m.timestamp = System.currentTimeMillis();

            // ---- 入库前的合法性校验 ----
            // 之前 70.9kg 那条记录，就是误连小米温湿度计后把它的字节硬解成体重写进去的。
            // 蓝牙层已经改成「服务里没有体重特征就直接断开并拉黑」，这里是第二道防线：
            // 不可能的人体体重一律丢弃，和近期中位数差距过大的先不入库只提示。
            if (m.weight < W_MIN_KG || m.weight > W_MAX_KG) {
                toast(String.format(Locale.getDefault(), "读数 %.1f%s 不在人体范围内，已忽略",
                        wDisp(m.weight), wUnit()));
                logLine("跳过入库：体重 " + m.weight + "kg 超出合理范围");
                return;
            }
            double med = db.recentMedianWeight(currentUserId);
            if (med > 0 && Math.abs(m.weight - med) / med > W_MAX_DEV) {
                toast(String.format(Locale.getDefault(),
                        "读数 %.1f%s 与近期 %.1f%s 相差过大，未记录。确认无误可点「手动添加」",
                        wDisp(m.weight), wUnit(), wDisp(med), wUnit()));
                logLine("跳过入库：体重 " + m.weight + "kg 偏离近期中位数 " + med + "kg 超过 40%");
                return;
            }

            float factor = calibFactor();
            double rawWeight = m.weight;
            if (Math.abs(factor - 1.0f) > 0.0001f) {
                m.weight = Math.round(rawWeight * factor * 100) / 100.0;
                boolean scaleGaveFat = m.fat > 0 && m.resistance > 0;
                if (!scaleGaveFat) m.fat = 0;
                m.muscle = 0;
                m.bone = 0;
                m.water = 0;
                m.bmr = 0;
                m.visceral = 0;
            }
            BodyComposition.enrich(m, user());
            m.source = m.source == null || m.source.isEmpty() ? "ble" : m.source;

            // 双保险：1 分钟内已有几乎相同的记录，就覆盖它而不是再插一条
            List<Measurement> recent = db.getMeasurements(currentUserId, 1);
            if (!recent.isEmpty()) {
                Measurement last = recent.get(0);
                if (Math.abs(last.timestamp - m.timestamp) < 60000
                        && Math.abs(last.weight - m.weight) < 0.5) {
                    m.id = last.id;
                    if (m.note == null || m.note.isEmpty()) m.note = last.note;
                }
            }
            if (m.id > 0) db.deleteMeasurement(m.id);
            long id = db.addMeasurement(m);
            m.id = id;
            android.util.Log.d("ScaleLinkSniff", String.format(Locale.getDefault(),
                    "COMMIT id=%d %.2fkg 阻抗=%.0f 来源=%s", id, m.weight, m.resistance, m.source));

            SyncService.autoUpload(this, db);
            refreshAll();

            String msg = String.format(Locale.getDefault(), "已记录 %.1f%s", wDisp(m.weight), wUnit());
            if (packets > 1) msg += String.format(Locale.getDefault(), "（合并 %d 个数据包）", packets);
            if (Math.abs(factor - 1.0f) > 0.0001f) {
                msg += String.format(Locale.getDefault(), "（原始 %.1f，已校准）", wDisp(rawWeight));
            }
            toast(msg);
        });
    }

    // ---------------- 手动添加 ----------------

    public void showManual() {
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 4));

        final EditText etWeight = input("体重 " + wUnit() + "（必填）");
        final EditText etFat = input("体脂率 %（可选）");
        final EditText etRes = input("阻抗 Ω（可选，用于估算体脂）");
        final EditText etNote = input("备注（可选）");
        etWeight.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etFat.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etRes.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(etWeight);
        box.addView(etFat);
        box.addView(etRes);
        box.addView(etNote);

        new AlertDialog.Builder(this)
                .setTitle("手动添加记录")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String ws = etWeight.getText().toString().trim();
                    if (ws.isEmpty()) {
                        toast("请填写体重");
                        return;
                    }
                    Measurement m = new Measurement();
                    try {
                        double v = Double.parseDouble(ws);
                        m.weight = useJin() ? v / 2.0 : v;
                        String fs = etFat.getText().toString().trim();
                        if (!fs.isEmpty()) m.fat = Double.parseDouble(fs);
                        String rs = etRes.getText().toString().trim();
                        if (!rs.isEmpty()) m.resistance = Double.parseDouble(rs);
                    } catch (NumberFormatException e) {
                        toast("数字格式有误");
                        return;
                    }
                    m.note = etNote.getText().toString().trim();
                    m.timestamp = System.currentTimeMillis();
                    m.user_id = currentUserId;
                    m.source = "manual";
                    BodyComposition.enrich(m, user());
                    m.id = db.addMeasurement(m);
                    SyncService.autoUpload(this, db);
                            refreshAll();
                    toast("已保存");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText input(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(14f);
        et.setTextColor(th.text);
        et.setHintTextColor(th.textFaint);
        et.setBackground(Ui.glass(this, th, 14));
        et.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.bottomMargin = Ui.dp(this, 8);
        et.setLayoutParams(lp);
        return et;
    }

    // ---------------- 校准 ----------------

    /**
     * 体成分校准：秤只给体重+阻抗，体脂是我们用公开公式估的，
     * 和京东健康/小米这类厂商私有算法必然有出入。
     * 这里让用户拿官方 App 的读数校一次，记下差值，之后所有记录整体对齐那个口径。
     */
    public void showFatCalibration() {
        List<Measurement> data = db.getMeasurements(currentUserId, 1);
        if (data.isEmpty()) {
            toast("请先测量一次，再来校准");
            return;
        }
        final Measurement latest = data.get(0);
        final UserProfile u = user();
        final double saved = u.fatOffset;

        // 先把校准值清零算一遍，得到「纯公式」的基准
        u.fatOffset = 0;
        Measurement probe = new Measurement();
        probe.weight = latest.weight;
        probe.resistance = latest.resistance;
        BodyComposition.enrich(probe, u);
        final double autoFat = probe.fat;
        u.fatOffset = saved;

        if (autoFat <= 0) {
            toast("这条记录没有阻抗数据，无法反推体脂");
            return;
        }

        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 4));

        TextView info = Ui.tv(this, String.format(Locale.getDefault(),
                "本机按体重 %.1f kg、阻抗 %.0f Ω、身高 %.0f cm、年龄 %d 推算：体脂 %.1f%%\n\n"
                        + "请填京东健康上显示的体脂率，之后所有记录都按这个口径对齐。\n"
                        + "（口径不同不代表谁算错了，选一个你信的就固定用它。）",
                latest.weight, latest.resistance, u.height, u.age, autoFat), 12f, th.textDim);
        info.setLineSpacing(Ui.dpf(this, 2), 1f);
        info.setPadding(0, 0, 0, Ui.dp(this, 12));
        box.addView(info);

        final EditText etFat = input("京东健康显示的体脂率，如 16.8");
        etFat.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(etFat);

        new AlertDialog.Builder(this)
                .setTitle("体成分校准")
                .setView(box)
                .setPositiveButton("校准", (d, w) -> {
                    double target;
                    try {
                        target = Double.parseDouble(etFat.getText().toString().trim());
                    } catch (Exception e) {
                        toast("请输入数字，如 16.8");
                        return;
                    }
                    if (target < 3 || target > 60) {
                        toast("体脂率应在 3~60 之间");
                        return;
                    }
                    setFatOffset(target - autoFat);
                    int n = db.recomputeComposition(user());
                    refreshAll();
                    toast(String.format(Locale.getDefault(),
                            "已校准，%d 条记录按 %.1f%% 重算", n, target));
                })
                .setNeutralButton("重置", (d, w) -> {
                    setFatOffset(0);
                    db.recomputeComposition(user());
                    refreshAll();
                    toast("已恢复为公式默认值");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void showCalibration() {
        List<Measurement> data = db.getMeasurements(currentUserId, 1);
        if (data.isEmpty()) {
            toast("请先测量一次，再来校准");
            return;
        }
        final Measurement latest = data.get(0);
        final float oldFactor = calibFactor();
        final double shown = latest.weight;

        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 4));

        TextView info = Ui.tv(this, String.format(Locale.getDefault(),
                "App 当前显示：%.1f %s\n请照着秤面数字填写，并选择秤上的单位。\n注意：刚踩上去的一瞬间秤显示的是偏小的过渡值，请等数字不再跳动后再照着填。",
                wDisp(shown), wUnit()), 12f, th.textDim);
        info.setLineSpacing(Ui.dpf(this, 2), 1f);
        info.setPadding(0, 0, 0, Ui.dp(this, 10));
        box.addView(info);

        final EditText etReading = input("秤面显示的数值，如 80.7");
        etReading.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(etReading);

        final int[] unitIdx = {0};
        final String[] units = {"公斤", "斤", "磅"};
        LinearLayout unitRow = Ui.row(this);
        TextView[] chips = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView chip = Ui.chip(this, th, units[i],
                    i == 0 ? th.accentSoft : th.surfaceAlt,
                    i == 0 ? th.accent : th.textDim);
            chip.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
            chips[i] = chip;
            LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36));
            if (i > 0) lp.leftMargin = Ui.dp(this, 8);
            unitRow.addView(chip, lp);
            Ui.tap(chip, () -> {
                unitIdx[0] = idx;
                for (int k = 0; k < chips.length; k++) {
                    chips[k].setTextColor(k == idx ? th.accent : th.textDim);
                    chips[k].setBackground(Ui.round(this, k == idx ? th.accentSoft : th.surfaceAlt, 10));
                }
            });
        }
        box.addView(unitRow);

        new AlertDialog.Builder(this)
                .setTitle("校准体重")
                .setView(box)
                .setPositiveButton("校准", (d, w) -> {
                    String s = etReading.getText().toString().trim();
                    if (s.isEmpty()) {
                        toast("请填写秤面数值");
                        return;
                    }
                    double shownValue;
                    try {
                        shownValue = Double.parseDouble(s);
                    } catch (NumberFormatException e) {
                        toast("数字格式有误");
                        return;
                    }
                    if (shownValue <= 0 || shown <= 0) return;
                    double trueKg = shownValue;
                    if (unitIdx[0] == 1) trueKg = shownValue / 2.0;
                    else if (unitIdx[0] == 2) trueKg = shownValue * 0.45359237;

                    double raw = shown / oldFactor;
                    if (raw <= 0) return;
                    float newFactor = (float) (trueKg / raw);
                    setCalibFactor(newFactor);

                    latest.weight = Math.round(trueKg * 100) / 100.0;
                    if (!(latest.fat > 0 && latest.resistance > 0)) latest.fat = 0;
                    latest.muscle = 0;
                    latest.bone = 0;
                    latest.water = 0;
                    latest.bmr = 0;
                    latest.visceral = 0;
                    BodyComposition.enrich(latest, user());
                    db.deleteMeasurement(latest.id);
                    db.addMeasurement(latest);
                    refreshAll();
                    toast(String.format(Locale.getDefault(),
                            "校准完成：%.1f kg（系数 %.3f）", trueKg, newFactor));
                })
                .setNeutralButton("重置", (d, w) -> {
                    setCalibFactor(1.0f);
                    refreshAll();
                    toast("已恢复默认系数");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 用户管理 ----------------

    public void showUserSwitcher() {
        List<String> names = new ArrayList<>();
        for (UserProfile u : users) {
            names.add(u.name + (u.id == currentUserId ? "（当前）" : ""));
        }
        names.add("＋ 新增用户");
        new AlertDialog.Builder(this)
                .setTitle("切换用户")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which == users.size()) {
                        showUserEditor(null);
                        return;
                    }
                    UserProfile u = users.get(which);
                    currentUserId = u.id;
                    ble.setParseContext(new ParseContext(u));
                    refreshAll();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 可选的表情头像。全部用 Unicode 转义写死，避免源文件编码把 emoji 弄坏 */
    private static final String[] AVATARS = {
            "\uD83D\uDE00", "\uD83D\uDE03", "\uD83D\uDE04", "\uD83D\uDE01", "\uD83D\uDE06", "\uD83D\uDE0A", "\uD83D\uDE42", "\uD83D\uDE09",
            "\uD83D\uDE0D", "\uD83D\uDE18", "\uD83D\uDE1C", "\uD83D\uDE0E", "\uD83D\uDE0F", "\uD83D\uDE0C", "\uD83D\uDE34", "\uD83D\uDE07",
            "\uD83D\uDC31", "\uD83D\uDC36", "\uD83D\uDC3C", "\uD83D\uDC37", "\uD83D\uDC38", "\uD83D\uDC35", "\uD83D\uDC30", "\uD83D\uDC3B",
            "\uD83D\uDC2F", "\uD83D\uDC28", "\uD83D\uDC14", "\uD83D\uDC27", "\uD83E\uDD8B", "\uD83C\uDF38", "\uD83C\uDF3F", "\u2B50",
            "\uD83C\uDF19", "\u2600\uFE0F", "\uD83C\uDF40", "\uD83C\uDF88", "\uD83C\uDFB5", "\uD83D\uDCAA", "\uD83E\uDD17", "\uD83E\uDD14",
    };

    /** 选头像表情：8 个一行的小格子，选完立即生效 */
    public void showAvatarPicker() {
        final UserProfile u = user();
        final LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 2));

        final AlertDialog[] holder = new AlertDialog[1];
        final int perRow = 8;
        for (int i = 0; i < AVATARS.length; i += perRow) {
            LinearLayout row = Ui.row(this);
            for (int j = 0; j < perRow; j++) {
                final int k = i + j;
                if (k >= AVATARS.length) {
                    row.addView(new View(this), Ui.weightLp(1f, Ui.dp(this, 48)));
                    continue;
                }
                final String emoji = AVATARS[k];
                TextView cell = new TextView(this);
                cell.setText(emoji);
                cell.setTextSize(22f);
                cell.setGravity(Gravity.CENTER);
                boolean cur = emoji.equals(u.avatar);
                cell.setBackground(Ui.round(this, cur ? th.accentSoft : 0x00000000, 12));
                row.addView(cell, Ui.weightLp(1f, Ui.dp(this, 48)));
                Ui.tap(cell, () -> {
                    u.avatar = emoji;
                    db.updateUser(u);
                    reloadUsers();
                    refreshAll();
                    if (holder[0] != null) holder[0].dismiss();
                });
            }
            box.addView(row);
        }

        holder[0] = new AlertDialog.Builder(this)
                .setTitle("选个头像")
                .setView(box)
                .setNeutralButton("用名字首字", (d, w) -> {
                    u.avatar = "";
                    db.updateUser(u);
                    reloadUsers();
                    refreshAll();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void showUserEditor(final UserProfile user) {
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 4));

        final EditText etName = input("姓名");
        final EditText etAge = input("年龄");
        final EditText etHeight = input("身高 cm");
        final EditText etTarget = input("目标体重 " + wUnit() + "（可选）");
        etAge.setInputType(InputType.TYPE_CLASS_NUMBER);
        etHeight.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etTarget.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        final boolean[] female = {user != null && user.gender == 0};
        if (user != null) {
            etName.setText(user.name);
            etAge.setText(String.valueOf(user.age));
            etHeight.setText(String.format(Locale.getDefault(), "%.0f", user.height));
            if (user.targetWeight > 0) etTarget.setText(String.format(Locale.getDefault(), "%.1f",
                    wDisp(user.targetWeight)));
        } else {
            etAge.setText("30");
            etHeight.setText("170");
        }

        box.addView(etName);
        LinearLayout genderRow = Ui.row(this);
        TextView gl = Ui.tv(this, "性别", 13f, th.textDim);
        final TextView gt = Ui.chip(this, th, female[0] ? "女" : "男",
                th.accentSoft, th.accent);
        genderRow.addView(gl, Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        genderRow.addView(gt);
        LinearLayout.LayoutParams grlp = Ui.matchWrap();
        grlp.bottomMargin = Ui.dp(this, 10);
        box.addView(genderRow, grlp);
        Ui.tap(gt, () -> {
            female[0] = !female[0];
            gt.setText(female[0] ? "女" : "男");
        });

        box.addView(etAge);
        box.addView(etHeight);
        box.addView(etTarget);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(user == null ? "新增用户" : "编辑用户")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        UserProfile u = user == null ? new UserProfile() : user;
                        String name = etName.getText().toString().trim();
                        u.name = name.isEmpty() ? "我" : name;
                        u.gender = female[0] ? 0 : 1;
                        u.age = Integer.parseInt(etAge.getText().toString().trim());
                        u.height = Double.parseDouble(etHeight.getText().toString().trim());
                        String t = etTarget.getText().toString().trim();
                        double tv = t.isEmpty() ? 0 : Double.parseDouble(t);
                        u.targetWeight = useJin() ? tv / 2.0 : tv;
                        if (user == null) {
                            u.id = (int) db.addUser(u);
                        } else {
                            db.updateUser(u);
                        }
                        currentUserId = u.id;
                        reloadUsers();
                        refreshAll();
                        toast("已保存");
                    } catch (Exception e) {
                        toast("输入有误，请检查年龄/身高");
                    }
                })
                .setNegativeButton("取消", null);
        if (user != null && users.size() > 1) {
            b.setNeutralButton("删除", (d, w) -> new AlertDialog.Builder(this)
                    .setTitle("删除用户")
                    .setMessage("会同时删除该用户的全部记录，确定？")
                    .setPositiveButton("删除", (dd, ww) -> {
                        db.deleteUser(user.id);
                        reloadUsers();
                        refreshAll();
                        toast("已删除");
                    })
                    .setNegativeButton("取消", null)
                    .show());
        }
        b.show();
    }

    public void showTargetDialog() {
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 4));
        TextView tip = Ui.tv(this, "设定目标后，首页会显示完成进度和还差多少。", 12f, th.textDim);
        tip.setPadding(0, 0, 0, Ui.dp(this, 10));
        box.addView(tip);
        final EditText et = input("目标体重 " + wUnit());
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        UserProfile u = user();
        if (u.targetWeight > 0) et.setText(String.format(Locale.getDefault(), "%.1f", wDisp(u.targetWeight)));
        box.addView(et);

        new AlertDialog.Builder(this)
                .setTitle("目标体重")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String s = et.getText().toString().trim();
                    UserProfile cur = user();
                    if (s.isEmpty()) {
                        cur.targetWeight = 0;
                    } else {
                        try {
                            double v = Double.parseDouble(s);
                            cur.targetWeight = useJin() ? v / 2.0 : v;
                        } catch (NumberFormatException e) {
                            toast("数字格式有误");
                            return;
                        }
                    }
                    db.updateUser(cur);
                    reloadUsers();
                    refreshAll();
                    toast(cur.targetWeight > 0 ? "目标已保存" : "已清除目标");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void toggleSniff() {
        sniffMode = !sniffMode;
        ble.setSniffMode(sniffMode);
        toast(sniffMode ? "嗅探已开启：重新连接秤会记录原始 HEX" : "嗅探已关闭");
        refreshAll();
    }

    // ---------------- 导出 / 导入 ----------------

    public void exportCsv() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "ScaleLink_" + user().name + "_"
                + new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date()) + ".csv");
        try {
            startActivityForResult(i, REQ_EXPORT_CSV);
        } catch (Exception e) {
            toast("当前系统不支持选择保存位置");
        }
    }

    public void exportJsonFile() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "scalelink-backup.json");
        try {
            startActivityForResult(i, REQ_EXPORT_JSON);
        } catch (Exception e) {
            toast("当前系统不支持选择保存位置");
        }
    }

    public void importJsonFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT_JSON);
        } catch (Exception e) {
            toast("无法打开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQ_EXPORT_CSV) {
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(db.exportCsv(currentUserId).getBytes("UTF-8"));
                os.close();
                toast("CSV 已导出");
            } else if (requestCode == REQ_EXPORT_JSON) {
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(Backup.toJson(db).getBytes("UTF-8"));
                os.close();
                toast("备份已导出");
            } else if (requestCode == REQ_IMPORT_JSON) {
                InputStream in = getContentResolver().openInputStream(uri);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                Backup.Result r = Backup.merge(db, new String(bos.toByteArray(), "UTF-8"));
                reloadUsers();
                refreshAll();
                new AlertDialog.Builder(this)
                        .setTitle("导入完成")
                        .setMessage(r.summary())
                        .setPositiveButton("知道了", null)
                        .show();
            }
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("操作失败")
                    .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    // ---------------- 记录体检 ----------------

    /** 启动后如果发现大量重复/异常记录，提示一次（只提示一次） */
    private void maybeOfferTidy() {
        boolean asked = getSharedPreferences(Theme.PREF, MODE_PRIVATE)
                .getBoolean(KEY_TIDY_ASKED, false);
        if (asked) return;
        int total = db.countMeasurements(currentUserId);
        if (total < 120) return;
        final DbHelper.Report rep = db.scanJunk(currentUserId);
        if (rep.count() < 20) return;

        getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_TIDY_ASKED, true).apply();

        new AlertDialog.Builder(this)
                .setTitle("发现可整理的记录")
                .setMessage(String.format(Locale.getDefault(),
                        "当前共 %d 条记录，其中：\n\n· 同一分钟内重复 %d 条（一次称重发了多个数据包）\n· 明显异常 %d 条（如只踩了一只脚的瞬时读数）\n\n可一键清理，只保留每次称重最准确的那一条。建议先确认一次体重是否正常再清理。",
                        rep.total, rep.dup, rep.outlier))
                .setPositiveButton("一键整理", (d, w) -> {
                    int n = db.applyClean(rep);
                    refreshAll();
                    toast("已整理，删除 " + n + " 条冗余记录");
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 我的 → 记录体检 */
    public void showRecordCheck() {
        final DbHelper.Report rep = db.scanJunk(currentUserId);
        String msg;
        if (rep.count() == 0) {
            msg = String.format(Locale.getDefault(),
                    "共 %d 条记录，没有发现重复或异常。", rep.total);
        } else {
            msg = String.format(Locale.getDefault(),
                    "共 %d 条记录，可整理 %d 条：\n\n· 同一分钟重复：%d 条\n· 体重明显异常：%d 条\n\n整理后每次称重只保留最准确的一条，趋势曲线会更干净。",
                    rep.total, rep.count(), rep.dup, rep.outlier);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("记录体检")
                .setMessage(msg)
                .setNegativeButton("关闭", null);
        if (rep.count() > 0) {
            b.setPositiveButton("清理 " + rep.count() + " 条", (d, w) -> {
                int n = db.applyClean(rep);
                refreshAll();
                toast("已删除 " + n + " 条冗余记录");
            });
        }
        b.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        autoConnectFailures = 0;   // 每次回到 App 都给自动连接一次新的机会
        refreshAutoWatch();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 退到后台就停止守候扫描，别白耗电，也别和别的 App（比如京东健康）抢扫描
        if (scanDialog == null || !scanDialog.isShowing()) ble.stopScanQuiet();
        watchMac = null;
    }

    /**
     * 「踩上去就自动连」：在首页待着时，如果有一台已验证的秤，
     * 就保持轻量扫描守着它的广播。秤被踩醒后广播的第一秒就能连上，
     * 不用用户一边踩秤一边手忙脚乱点连接。
     *
     * 只守已验证的 MAC，限定在 App 前台，所以不会乱连、也不长期耗电。
     */
    public void refreshAutoWatch() {
        if (connected || !autoConnectEnabled() || !lastDeviceVerified()) {
            watchMac = null;
            if (!connected && scanDialog == null) ble.stopScanQuiet();
            return;
        }
        String mac = getSharedPreferences(Theme.PREF, MODE_PRIVATE).getString(KEY_LAST_MAC, "");
        if (mac.isEmpty()) { watchMac = null; return; }
        // 上次连的要是已经被判定「不是秤」，就不要再守候它了
        if (ble.isIgnored(mac)) {
            watchMac = null;
            getSharedPreferences(Theme.PREF, MODE_PRIVATE).edit()
                    .remove(KEY_LAST_MAC).remove(KEY_LAST_NAME)
                    .putBoolean(KEY_LAST_VERIFIED, false).apply();
            return;
        }
        watchMac = mac;
        if (ble.isConnecting() || (scanDialog != null && scanDialog.isShowing())) return;
        if (!ble.isFinding()) ble.startScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gate != null) gate.flush();
        if (ble != null) ble.disconnect();
    }
}
