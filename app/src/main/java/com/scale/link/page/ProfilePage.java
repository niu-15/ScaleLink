package com.scale.link.page;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.scale.link.MainActivity;
import com.scale.link.model.UserProfile;
import com.scale.link.sync.SyncService;
import com.scale.link.sync.WebDavClient;
import com.scale.link.ui.Theme;
import com.scale.link.ui.Ui;

import java.util.List;
import java.util.Locale;

/**
 * 我的：用户档案（头像/姓名/性别年龄身高）、云端同步（WebDAV）、显示设置、数据备份、关于。
 */
public class ProfilePage implements Page {

    private final MainActivity host;
    private final Theme t;
    private LinearLayout page;
    private View root;

    public ProfilePage(MainActivity host) {
        this.host = host;
        this.t = host.th;
    }

    @Override
    public View build() {
        ScrollView sv = new ScrollView(host);
        sv.setClipToPadding(false);
        page = Ui.col(host);
        page.setPadding(Ui.dp(host, 16), Ui.dp(host, 16), Ui.dp(host, 16), Ui.dp(host, 28));
        sv.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root = sv;
        refresh();
        return sv;
    }

    @Override
    public void refresh() {
        if (page == null) return;
        page.removeAllViews();
        page.addView(buildUserCard());
        page.addView(Ui.spacer(host, 12));
        page.addView(buildSyncCard());
        page.addView(Ui.spacer(host, 20));
        page.addView(Ui.sectionTitle(host, t, "显示"));
        page.addView(buildDisplayCard());
        page.addView(Ui.spacer(host, 20));
        page.addView(Ui.sectionTitle(host, t, "数据"));
        page.addView(buildDataCard());
        page.addView(Ui.spacer(host, 20));
        page.addView(buildAbout());
    }

    // ---------------- 用户 ----------------

    private View buildUserCard() {
        LinearLayout card = Ui.card(host, t);
        LinearLayout row = Ui.row(host);

        UserProfile u = host.user();
        // 有表情就用表情，没有再退回名字首字
        boolean hasEmoji = u.avatar != null && !u.avatar.isEmpty();
        TextView avatar = Ui.tvMedium(host,
                hasEmoji ? u.avatar : (u.name.isEmpty() ? "我" : u.name.substring(0, 1)),
                hasEmoji ? 23f : 19f, t.accent);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(Ui.round(host, t.accentSoft, 24));
        row.addView(avatar, Ui.lp(Ui.dp(host, 48), Ui.dp(host, 48)));
        Ui.tap(avatar, () -> host.showAvatarPicker());

        LinearLayout info = Ui.col(host);
        LinearLayout.LayoutParams ilp = Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.leftMargin = Ui.dp(host, 14);
        TextView name = Ui.tvMedium(host, u.name, 16f, t.text);
        TextView meta = Ui.tv(host, (u.gender == 1 ? "男" : "女") + " · " + u.age + " 岁 · "
                + String.format(Locale.getDefault(), "%.0f", u.height) + " cm", 12f, t.textDim);
        meta.setPadding(0, Ui.dp(host, 4), 0, 0);
        info.addView(name);
        info.addView(meta);
        row.addView(info, ilp);

        TextView switchBtn = Ui.textButton(host, t, "切换");
        row.addView(switchBtn);
        Ui.tap(switchBtn, () -> host.showUserSwitcher());
        Ui.tap(info, () -> host.showUserEditor(host.user()));

        card.addView(row);
        return card;
    }

    // ---------------- 同步 ----------------

    private View buildSyncCard() {
        LinearLayout card = Ui.card(host, t);
        boolean configured = SyncService.isConfigured(host);

        LinearLayout top = Ui.row(host);
        LinearLayout left = Ui.col(host);
        left.addView(Ui.tvMedium(host, "云端同步", 14f, t.text));
        TextView sub = Ui.tv(host, configured ? SyncService.url(host) : "支持坚果云、群晖、Nextcloud 等 WebDAV",
                11f, t.textFaint);
        sub.setPadding(0, Ui.dp(host, 3), 0, 0);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        left.addView(sub);
        top.addView(left, Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView state = Ui.chip(host, t, configured ? "已配置" : "未配置",
                configured ? t.accentSoft : t.surfaceAlt,
                configured ? t.accent : t.textFaint);
        top.addView(state);
        card.addView(top);

        String lastMsg = SyncService.lastMessage(host);
        TextView status = Ui.tv(host, configured
                ? "上次同步：" + SyncService.friendlyTime(SyncService.lastSync(host))
                + (lastMsg.isEmpty() ? "" : "\n" + lastMsg)
                : "配置后可在多台手机之间同步记录，卸载重装也不丢数据。", 11.5f, t.textDim);
        status.setPadding(0, Ui.dp(host, 10), 0, Ui.dp(host, 12));
        status.setLineSpacing(Ui.dpf(host, 2), 1f);
        card.addView(status);

        LinearLayout btnRow = Ui.row(host);
        // 三个按钮完全同色同形：浅底 + 细边 + 正文色，只靠文字区分动作
        TextView bSync = Ui.lightButton(host, t, configured ? "立即同步" : "配置 WebDAV");
        btnRow.addView(bSync, Ui.weightLp(1f, Ui.dp(host, 44)));
        if (configured) {
            TextView bUp = Ui.lightButton(host, t, "上传");
            LinearLayout.LayoutParams lp = Ui.weightLp(1f, Ui.dp(host, 44));
            lp.leftMargin = Ui.dp(host, 8);
            btnRow.addView(bUp, lp);
            TextView bDown = Ui.lightButton(host, t, "恢复");
            LinearLayout.LayoutParams lp2 = Ui.weightLp(1f, Ui.dp(host, 44));
            lp2.leftMargin = Ui.dp(host, 8);
            btnRow.addView(bDown, lp2);
            Ui.tap(bUp, () -> runSync("upload"));
            Ui.tap(bDown, () -> runSync("restore"));
        }
        card.addView(btnRow);

        if (configured) {
            TextView legend = Ui.tv(host, "云端保存两份：JSON（用于恢复）+ 体脂记录.csv（Excel 可直接打开）\n"
                    + "同步 = 先合并云端再上传　上传 = 用本机覆盖云端　恢复 = 只把云端并到本机",
                    10.5f, t.textFaint);
            legend.setPadding(0, Ui.dp(host, 8), 0, 0);
            legend.setLineSpacing(Ui.dpf(host, 2), 1f);
            card.addView(legend);
        }
        Ui.tap(bSync, () -> {
            if (SyncService.isConfigured(host)) runSync("sync");
            else showWebDavDialog();
        });

        LinearLayout autoRow = Ui.row(host);
        autoRow.setPadding(0, Ui.dp(host, 14), 0, 0);
        LinearLayout al = Ui.col(host);
        al.addView(Ui.tv(host, "自动上传", 13f, t.text));
        TextView asub = Ui.tv(host, "每次测量完成后自动上传一次备份", 11f, t.textFaint);
        asub.setPadding(0, Ui.dp(host, 2), 0, 0);
        al.addView(asub);
        autoRow.addView(al, Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        Switch sw = makeSwitch(SyncService.auto(host));
        sw.setOnCheckedChangeListener((btn, checked) -> {
            if (!SyncService.isConfigured(host) && checked) {
                btn.setChecked(false);
                showWebDavDialog();
                return;
            }
            SyncService.setAuto(host, checked);
        });
        autoRow.addView(sw);
        card.addView(autoRow);

        TextView manage = Ui.textButton(host, t, configured ? "修改同步配置" : "使用说明");
        LinearLayout.LayoutParams mlp = Ui.matchWrap();
        mlp.topMargin = Ui.dp(host, 8);
        card.addView(manage, mlp);
        Ui.tap(manage, () -> {
            if (configured) showWebDavDialog(); else showWebDavHelp();
        });
        return card;
    }

    private void runSync(String mode) {
        final AlertDialog progress = new AlertDialog.Builder(host)
                .setMessage(mode.equals("restore") ? "正在从云端恢复…" : "正在同步…")
                .setCancelable(false)
                .create();
        progress.show();
        SyncService.Listener l = (ok, message) -> {
            if (progress.isShowing()) progress.dismiss();
            host.refreshAll();
            new AlertDialog.Builder(host)
                    .setTitle(ok ? "完成" : "同步失败")
                    .setMessage(message)
                    .setPositiveButton("知道了", null)
                    .show();
        };
        if (mode.equals("upload")) SyncService.upload(host, host.db, l);
        else if (mode.equals("restore")) SyncService.restore(host, host.db, l);
        else SyncService.sync(host, host.db, l);
    }

    private void showWebDavHelp() {
        new AlertDialog.Builder(host)
                .setTitle("WebDAV 怎么配")
                .setMessage("以坚果云为例：\n\n"
                        + "1. 打开坚果云网页版 → 账户信息 → 安全选项 → 添加应用\n"
                        + "2. 生成一个「应用密码」（不要用登录密码）\n"
                        + "3. 回到这里点「配置 WebDAV」\n"
                        + "   · 服务器地址：https://dav.jianguoyun.com/dav/\n"
                        + "   · 账号：你的坚果云登录邮箱\n"
                        + "   · 密码：上一步生成的应用密码\n\n"
                        + "备份文件会存到「我的坚果云/ScaleLink/scalelink-backup.json」。\n"
                        + "群晖 / Nextcloud 填各自的 WebDAV 地址即可。")
                .setPositiveButton("去配置", (d, w) -> showWebDavDialog())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showWebDavDialog() {
        LinearLayout box = Ui.col(host);
        box.setPadding(Ui.dp(host, 20), Ui.dp(host, 12), Ui.dp(host, 20), Ui.dp(host, 4));

        TextView tip = Ui.tv(host, "坚果云请填 https://dav.jianguoyun.com/dav/ ，密码用「应用密码」而不是登录密码。",
                11.5f, t.textDim);
        tip.setPadding(0, 0, 0, Ui.dp(host, 10));
        tip.setLineSpacing(Ui.dpf(host, 2), 1f);
        box.addView(tip);

        final EditText etUrl = input("服务器地址", SyncService.url(host).isEmpty()
                ? WebDavClient.PRESET_NUTSTORE : SyncService.url(host));
        final EditText etUser = input("账号（坚果云为登录邮箱）", SyncService.user(host));
        final EditText etPass = input("密码 / 应用密码", SyncService.pass(host));
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(etUrl);
        box.addView(etUser);
        box.addView(etPass);

        TextView preset = Ui.textButton(host, t, "填入坚果云地址");
        box.addView(preset, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(host, 36)));
        Ui.tap(preset, () -> etUrl.setText(WebDavClient.PRESET_NUTSTORE));

        final AlertDialog dialog = new AlertDialog.Builder(host)
                .setTitle("WebDAV 同步")
                .setView(box)
                .setPositiveButton("保存", null)
                .setNeutralButton("测试连接", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = etUrl.getText().toString().trim();
            String user = etUser.getText().toString().trim();
            String pass = etPass.getText().toString();
            if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                host.toast("三项都要填");
                return;
            }
            if (!url.startsWith("http")) {
                host.toast("地址要以 http:// 或 https:// 开头");
                return;
            }
            SyncService.save(host, url, user, pass, SyncService.auto(host));
            dialog.dismiss();
            refresh();
            host.toast("已保存，正在同步…");
            runSync("sync");
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            String url = etUrl.getText().toString().trim();
            String user = etUser.getText().toString().trim();
            String pass = etPass.getText().toString();
            if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                host.toast("三项都要填");
                return;
            }
            SyncService.save(host, url, user, pass, SyncService.auto(host));
            final AlertDialog progress = new AlertDialog.Builder(host)
                    .setMessage("正在连接…").setCancelable(false).create();
            progress.show();
            SyncService.test(host, (ok, message) -> {
                if (progress.isShowing()) progress.dismiss();
                new AlertDialog.Builder(host)
                        .setTitle(ok ? "连接成功" : "连接失败")
                        .setMessage(message)
                        .setPositiveButton("知道了", null)
                        .show();
            });
        });
    }

    private EditText input(String hint, String value) {
        EditText et = new EditText(host);
        et.setHint(hint);
        et.setText(value == null ? "" : value);
        et.setTextSize(14f);
        et.setTextColor(t.text);
        et.setHintTextColor(t.textFaint);
        et.setBackground(Ui.glass(host, t, 14));
        et.setPadding(Ui.dp(host, 12), Ui.dp(host, 10), Ui.dp(host, 12), Ui.dp(host, 10));
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.bottomMargin = Ui.dp(host, 8);
        et.setLayoutParams(lp);
        return et;
    }

    // ---------------- 显示 ----------------

    private View buildDisplayCard() {
        LinearLayout card = Ui.card(host, t);
        card.setPadding(Ui.dp(host, 16), Ui.dp(host, 4), Ui.dp(host, 12), Ui.dp(host, 4));

        LinearLayout darkRow = switchRow("深色模式", "夜间看数据更舒服", Theme.isDarkMode(host),
                (btn, checked) -> {
                    Theme.setDarkMode(host, checked);
                    host.rebuildUi();
                });
        card.addView(darkRow);
        card.addView(Ui.divider(host, t));

        LinearLayout jinRow = switchRow("用「斤」显示体重", "秤面是斤的话打开更顺手",
                host.useJin(), (btn, checked) -> {
                    host.setUseJin(checked);
                    host.refreshAll();
                });
        card.addView(jinRow);
        return card;
    }

    private LinearLayout switchRow(String title, String sub, boolean checked,
                                   CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = Ui.row(host);
        row.setPadding(0, Ui.dp(host, 12), 0, Ui.dp(host, 12));
        LinearLayout l = Ui.col(host);
        l.addView(Ui.tv(host, title, 13.5f, t.text));
        TextView s = Ui.tv(host, sub, 11f, t.textFaint);
        s.setPadding(0, Ui.dp(host, 2), 0, 0);
        l.addView(s);
        row.addView(l, Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        Switch sw = makeSwitch(checked);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        return row;
    }

    private Switch makeSwitch(boolean checked) {
        Switch sw = new Switch(host);
        sw.setChecked(checked);
        int[][] states = new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}};
        sw.setThumbTintList(new ColorStateList(states,
                new int[]{t.accent, t.dark ? 0xFF5A7787 : 0xFFC3D2DA}));
        sw.setTrackTintList(new ColorStateList(states,
                new int[]{Ui.alpha(t.accent, 0.35f), t.track}));
        return sw;
    }

    // ---------------- 数据 ----------------

    private View buildDataCard() {
        LinearLayout card = Ui.card(host, t);
        card.setPadding(Ui.dp(host, 16), Ui.dp(host, 4), Ui.dp(host, 12), Ui.dp(host, 4));

        card.addView(linkRow("手动添加记录", "没称也能补一条：体重、体脂、备注", () -> host.showManual()));
        card.addView(Ui.divider(host, t));
        card.addView(linkRow("导出 CSV 表格", "方便在 Excel 里做表", () -> host.exportCsv()));
        card.addView(Ui.divider(host, t));
        card.addView(linkRow("导出备份文件", "JSON 格式，可自己存到网盘", () -> host.exportJsonFile()));
        card.addView(Ui.divider(host, t));
        card.addView(linkRow("从文件导入", "按时间去重，不会覆盖已有记录", () -> host.importJsonFile()));
        card.addView(Ui.divider(host, t));
        card.addView(linkRow("记录体检", "查找同一分钟的重复与异常读数，一键清理",
                () -> host.showRecordCheck()));
        card.addView(Ui.divider(host, t));
        card.addView(linkRow("体重校准", "秤面和 App 对不上时用这个",
                () -> host.showCalibration()));
        card.addView(Ui.divider(host, t));
        card.addView(linkRow("体成分校准",
                Math.abs(host.fatOffset()) < 0.01
                        ? "和京东健康/官方 App 对不上时，填一次它的体脂率"
                        : String.format(Locale.getDefault(),
                        "已对齐官方口径，当前偏移 %+.1f 个百分点", host.fatOffset()),
                () -> host.showFatCalibration()));
        card.addView(Ui.divider(host, t));
        card.addView(linkRow("清空本机数据", "删除所有记录，不可恢复", this::confirmClear));
        return card;
    }

    private void confirmClear() {
        new AlertDialog.Builder(host)
                .setTitle("清空本机数据")
                .setMessage("会删除本机全部用户与测量记录（云端备份不受影响）。确定继续？")
                .setPositiveButton("确定清空", (d, w) -> {
                    host.db.clearAll();
                    host.reloadUsers();
                    host.toast("已清空");
                    host.refreshAll();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 通用行 ----------------

    private LinearLayout linkRow(String title, String sub, final Runnable action) {
        LinearLayout row = Ui.row(host);
        row.setPadding(0, Ui.dp(host, 12), 0, Ui.dp(host, 12));
        LinearLayout l = Ui.col(host);
        l.addView(Ui.tv(host, title, 13.5f, t.text));
        TextView s = Ui.tv(host, sub, 11f, t.textFaint);
        s.setPadding(0, Ui.dp(host, 2), 0, 0);
        l.addView(s);
        row.addView(l, Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView arrow = Ui.tv(host, "›", 18f, t.textFaint);
        row.addView(arrow);
        Ui.tap(row, action);
        return row;
    }

    // ---------------- 关于 ----------------

    private View buildAbout() {
        LinearLayout card = Ui.card(host, t);
        card.addView(Ui.tvMedium(host, "体脂秤 3.15", 13.5f, t.text));
        TextView body = Ui.tv(host,
                "· 体脂秤蓝牙直连，不依赖任何厂商 App\n"
                        + "· 数据默认只存在本机；开启 WebDAV 后才会上传到你的网盘\n"
                        + "· 体脂率等身体成分为 BIA 估算值，仅用于观察趋势，不作为医疗依据\n"
                        + "· 备份文件为 JSON 明文，可自行加密后再上传",
                11.5f, t.textDim);
        body.setPadding(0, Ui.dp(host, 8), 0, 0);
        body.setLineSpacing(Ui.dpf(host, 3), 1f);
        card.addView(body);
        return card;
    }
}
