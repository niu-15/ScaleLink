package com.scale.link.ble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.scale.link.model.Measurement;
import com.scale.link.protocol.ParseContext;
import com.scale.link.protocol.ProtocolRegistry;
import com.scale.link.protocol.ScaleProtocol;
import com.scale.link.protocol.WeightVoter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BLE 蓝牙管理器：扫描体脂秤 -> 连接 -> 发现服务 -> 自动匹配协议 -> 订阅通知 -> 解析测量数据。
 *
 * 稳定性设计（体脂秤这类低功耗设备连接很容易失败）：
 *  1. 连接失败自动重试 3 次，并清理旧的 GATT 句柄（否则后续连接会一直失败）
 *  2. 连接 12 秒、服务发现 8 秒超时保护，不会无限卡在「正在连接」
 *  3. 描述符（CCCD）写入串行排队，避免一次连写多个导致订阅失效
 *  4. 意外断开时自动重连一次，不用手动点
 */
public class BleManager {
    private static final String TAG = "BleManager";
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int MAX_ATTEMPTS = 3;          // 指数退避，最多 3 次（更快失败，别把秤的清醒期耗光）
    private static final long CONNECT_TIMEOUT_MS = 8000;
    /** 后台连接（autoConnect）本来就慢，给足 45 秒 */
    private static final long AUTOCONNECT_TIMEOUT_MS = 45000;
    private static final long DISCOVERY_TIMEOUT_MS = 10000;
    private static final long CONNECT_DELAY_MS = 250;   // 停扫后等 250ms 再连，规避 133
    private static final long DISCOVERY_DELAY_MS = 300; // 连接参数协商后再发现服务
    private static final long DESC_TIMEOUT_MS = 2500;   // 单个 CCCD 写入超时

    public interface Callback {
        void onScanResult(BluetoothDevice device, String name, int rssi, List<String> serviceUuids);
        void onScanFinished();
        void onConnected(String deviceName);
        void onDisconnected();
        void onProtocolDetected(String protocolName, String hint);
        void onMeasurement(Measurement m);
        void onSniff(String line);
        void onError(String message);
        void onState(String state);

        /** 连上后发现对方根本没有体重服务：不是秤（温湿度计、手环等） */
        void onNotScale(String deviceName, String mac);

        /**
         * 从广播里直接读到了体重（不需要连接）。
         * 有些秤（本款的 OPPO/小米 ODM 贴牌就是）广播本身标记为不可连接，
         * 连接请求必然 HCI 0x08 超时，但它的厂商数据里带着实时体重。
         * kg 为 0 表示秤面上无人（已经从上面下来）。
         */
        void onAdvWeight(String mac, String name, double kg, double resistance);
    }

    /**
     * 纯广播抓包回调：只扫描、不连接。
     * 体脂秤的体重不一定只在 GATT 通知里 —— 不少型号（小米系尤其）会把体重
     * 直接放进广播的 service data。如果广播里就有，那根本不需要连接，
     * 扫描即测量，连接失败这个问题就直接绕过去了。
     * 所以这里把完整的广播原始字节（含长度/类型/数据）原样交出去。
     */
    public interface AdvListener {
        void onAdv(BluetoothDevice device, String name, int rssi, byte[] raw,
                   List<String> uuids, String serviceDataHex, String mfrHex);
    }

    private AdvListener advListener;

    public void setAdvListener(AdvListener l) { this.advListener = l; }

    private Context ctx;
    private Callback cb;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean scanning = false;
    private boolean sniffMode = false;
    private ParseContext parseContext;
    private ScaleProtocol activeProtocol;
    private List<UUID> discoveredServices = new ArrayList<>();

    /**
     * 小米体成分服务 ebe0ccb0（实测天晟/京东健康这台秤确实是它）。
     * 该服务下有 14 个特征值，其中 9 个都开了 notify；
     * 只有 ebe0ccc1（handle 0x36）是体重，其余是历史记录/配对/设备信息等，
     * 把它们的包也当体重解析，就会出现 41.6 / 135.3 / 156.2 这种乱跳。
     */
    private static final UUID MI_BC_SERVICE =
            UUID.fromString("ebe0ccb0-7a0a-4b0c-8a1a-6ff2997da3a6");
    private static final UUID CHAR_WEIGHT =
            UUID.fromString("ebe0ccc1-7a0a-4b0c-8a1a-6ff2997da3a6");
    /** 第二个厂商标准备用 notify 携带体重+阻抗（8edfffef） */
    private static final UUID CHAR_WEIGHT_ALT =
            UUID.fromString("8edfffef-3d1b-9c37-4623-ad7265f14076");

    /** 当前设备是否已确认为小米体成分服务系的秤 */
    private boolean miScale;
    /** 是否已进入「只收体重特征」模式（此时其余 notify 一律不解析） */
    private boolean weightSubscribed;

    /**
     * 体重物理范围。人体体重的合理区间，超出的一律视为解析错误。
     * 这条闸门的意义在于：只要设备不是秤，字节随便怎么解都可能「算出」一个数，
     * 20kg 以下或 250kg 以上至少能在最后一步把垃圾挡掉。
     */
    private static final double MIN_KG = 20.0;
    private static final double MAX_KG = 250.0;

    /** 被判定为「不是秤」的设备 MAC（持久化，避免每次开机又自动扑上去连） */
    private static final String PREF_IGNORE = "ble_ignore_v1";
    private java.util.Set<String> ignoredMacs;

    /**
     * 本次连接里「锁定」的解析器。
     * 未按服务 UUID 识别出协议时，同一个数据包会被多个解析器轮流尝试，
     * 而它们的缩放系数/偏移并不一致（÷200 与 ÷100、byte1 与 byte11），
     * 结果就是同一次称重里读数在 78.1 / 156.2 之间翻倍乱跳。
     * 所以一旦某个解析器成功解析，本次连接就只用它。
     */
    private ScaleProtocol stickyProtocol;
    private ScaleProtocol candidateProto;
    private int candidateHits;
    private int stickyMiss;          // 锁定后连续解析失败次数，过多则解锁重认
    private int discoveryRetries;    // 服务发现失败重试次数
    /** 自动探测：兜底用于协议完全未知的设备 */
    private final WeightVoter voter = new WeightVoter();

    /** 连接状态机 */
    private BluetoothDevice pendingDevice;
    private int attempts = 0;
    private boolean wantConnection = false;   // 用户希望保持连接
    private boolean autoReconnect = true;     // 意外断开是否自动重连
    private boolean everReady = false;        // 本次会话是否成功订阅过
    private volatile boolean ready = false;   // 已订阅完成，可收数据
    private boolean autoConnecting = false;   // 当前这次是否是系统后台连接

    /** 描述符写入队列（CCCD 必须一个个写，连写会失败） */
    private final ArrayDeque<BluetoothGattCharacteristic> descQueue = new ArrayDeque<>();
    private boolean descWriting = false;
    private final Runnable descWatchdog = () -> {
        // 有些设备写描述符后不回调，超时跳过，避免整个订阅卡死
        descWriting = false;
        drainDescQueue();
    };

    private final Runnable connectTimeout = () -> {
        cb.onState("连接超时，正在重试…");
        closeGatt();
        if (wantConnection && attempts < MAX_ATTEMPTS) retryConnect();
        else { cb.onError("连接超时：请踩醒秤、靠近手机后重试"); cb.onDisconnected(); }
    };

    private final Runnable discoveryTimeout = () -> {
        if (ready) return;
        cb.onError("读取设备服务超时，请靠近秤后重试");
        closeGatt();
        cb.onDisconnected();
    };

    public BleManager(Context ctx, Callback cb) {
        this.ctx = ctx;
        this.cb = cb;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        loadAdvScaleMacs();
    }

    // ---------------- 广播型秤的 MAC 记忆 ----------------
    // 这台秤的广播不可连接，体重全在广播里。判定过一次就要记住，
    // 否则下次开机仍然会去 connect，然后白白等 8 秒超时。

    private static final String PREF_ADV_SCALE = "adv_scale_macs";

    private void loadAdvScaleMacs() {
        try {
            String raw = ctx.getSharedPreferences("scalelink_pref", Context.MODE_PRIVATE)
                    .getString(PREF_ADV_SCALE, "");
            if (raw != null && !raw.isEmpty()) {
                for (String s : raw.split(",")) {
                    if (!s.trim().isEmpty()) advScaleMacs.add(s.trim().toUpperCase());
                }
            }
        } catch (Exception ignored) {}
    }

    private void rememberAdvScale(String mac) {
        advScaleMacs.add(mac.toUpperCase());
        try {
            StringBuilder sb = new StringBuilder();
            for (String m : advScaleMacs) {
                if (sb.length() > 0) sb.append(',');
                sb.append(m);
            }
            ctx.getSharedPreferences("scalelink_pref", Context.MODE_PRIVATE).edit()
                    .putString(PREF_ADV_SCALE, sb.toString()).apply();
        } catch (Exception ignored) {}
    }

    public boolean isBluetoothOn() { return adapter != null && adapter.isEnabled(); }

    // ---------------- 忽略列表（判定过「不是秤」的设备） ----------------

    private java.util.Set<String> ignored() {
        if (ignoredMacs == null) {
            ignoredMacs = new java.util.HashSet<>();
            try {
                String raw = ctx.getSharedPreferences("scalelink_pref", Context.MODE_PRIVATE)
                        .getString(PREF_IGNORE, "");
                if (raw != null && !raw.isEmpty()) {
                    for (String s : raw.split(",")) {
                        if (!s.trim().isEmpty()) ignoredMacs.add(s.trim().toUpperCase());
                    }
                }
            } catch (Exception ignored) {}
        }
        return ignoredMacs;
    }

    private void saveIgnored() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String s : ignored()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(s);
            }
            ctx.getSharedPreferences("scalelink_pref", Context.MODE_PRIVATE).edit()
                    .putString(PREF_IGNORE, sb.toString()).apply();
        } catch (Exception ignored) {}
    }

    public boolean isIgnored(String mac) {
        return mac != null && ignored().contains(mac.toUpperCase());
    }

    /** 把一台设备标记为「不是秤」，同时持久化，重启后也不会自动连它 */
    public void ignoreDevice(String mac) {
        if (mac == null || mac.isEmpty()) return;
        if (ignored().add(mac.toUpperCase())) saveIgnored();
    }

    public void unignoreDevice(String mac) {
        if (mac == null || mac.isEmpty()) return;
        if (ignored().remove(mac.toUpperCase())) saveIgnored();
    }

    public java.util.List<String> ignoredList() {
        return new java.util.ArrayList<>(ignored());
    }


    /** 给「连接自检」用：蓝牙开着但扫描器拿不到，说明蓝牙服务已经异常，需要开关一次蓝牙 */
    public boolean isScannerAvailable() {
        if (adapter == null || !adapter.isEnabled()) return false;
        if (!checkPermissions()) return false;
        try {
            return adapter.getBluetoothLeScanner() != null;
        } catch (Exception e) {
            return false;
        }
    }
    public boolean isConnected() { return ready; }
    public void setParseContext(ParseContext pc) { this.parseContext = pc; }
    public void setSniffMode(boolean on) { this.sniffMode = on; }
    public boolean isSniffMode() { return sniffMode; }
    public void setAutoReconnect(boolean on) { this.autoReconnect = on; }

    private boolean hasPerm(String perm) {
        return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPerm(Manifest.permission.BLUETOOTH_SCAN) ||
                    !hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) return false;
        } else {
            if (!hasPerm(Manifest.permission.BLUETOOTH) ||
                    !hasPerm(Manifest.permission.BLUETOOTH_ADMIN)) return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) return false;
        }
        return true;
    }

    // ---------------- 扫描 ----------------

    /**
     * 体脂秤不是一直广播的：只有被踩醒、屏幕亮起的那几秒才发广播。
     * 所以「扫 15 秒就停」很容易刚好错过，这也是扫不到秤最主要的原因。
     * 这里改成持续轮询：扫 18 秒 → 停 1.2 秒 → 再扫，直到用户主动停止或连上。
     * 中间停顿是为了避开系统的扫描频率限制（否则会报 errorCode 3/4 直接卡死）。
     */
    private static final long SCAN_CYCLE_MS = 18000L;
    private static final long SCAN_PAUSE_MS = 1200L;
    private static final long SCAN_RETRY_MS = 2500L;

    private boolean continuous = false;
    private long scanStartTs = 0;
    private int scanRounds = 0;
    /** 诊断用：每个 MAC 上一次的广播 HEX，用来「内容变化才打日志」 */
    private final java.util.HashMap<String, String> advLogged = new java.util.HashMap<>();

    // ================== 广播直读体重（无需连接的秤） ==================
    // 实测这台「天晟×京东健康」的广播：connectable=false，连接必然 HCI 0x08 超时，
    // 但厂商数据里带着实时体重。布局（AD 结构 0xFF 之后）：
    //   [0]=0xC0 标记  [1]=8位滚动计数  [2..3]=体重 大端 ÷100 kg
    //   [4..5]=测量标志/阻抗  [6..8]=秤内部时钟 时:分:秒  [9..14]=自身 MAC
    // 空秤时 [2..3] 恒为 00 00，所以「0」就是「人已经下来了」。
    private static final int ADV_SCALE_MARKER = 0xC0;
    /** 已经确认是「广播型秤」的 MAC，对这些设备不要再尝试 connect */
    private final java.util.HashSet<String> advScaleMacs = new java.util.HashSet<>();
    /** 上一次上报的体重，避免同一读数重复刷 UI */
    private final java.util.HashMap<String, Double> advLastKg = new java.util.HashMap<>();
    /** 上报限流：同一个 MAC 最快 200ms 一次 */
    private final java.util.HashMap<String, Long> advLastTs = new java.util.HashMap<>();

    private final Runnable scanCycleEnd = this::endScanRound;
    private final Runnable scanRestart = () -> { if (continuous) startScanRound(); };

    /** 开始持续寻找设备 */
    public void startScan() {
        if (!isBluetoothOn()) { cb.onError("蓝牙未开启，请先打开手机蓝牙"); return; }
        if (!checkPermissions()) { cb.onError("缺少蓝牙/定位权限，请先在系统中授权"); return; }

        // 正在连接或已连接时不要打断（旧代码在这里 closeGatt，
        // 导致「点了连接又顺手点扫描」时连接被无声掐断，看起来就是连不上）
        if (!isConnected() && !wantConnection) closeGatt();

        discoveredServices.clear();
        advLogged.clear();
        continuous = true;
        scanStartTs = System.currentTimeMillis();
        scanRounds = 0;
        startScanRound();
    }

    private void startScanRound() {
        handler.removeCallbacks(scanCycleEnd);
        handler.removeCallbacks(scanRestart);
        if (!continuous) return;
        if (!isBluetoothOn()) {
            continuous = false;
            cb.onError("蓝牙已关闭，无法继续扫描");
            return;
        }
        if (!checkPermissions()) {
            continuous = false;
            cb.onError("蓝牙权限被收回，请重新授权");
            return;
        }
        stopScannerHard();
        scanRounds++;
        scanning = true;
        cb.onState(scanRounds == 1
                ? "正在寻找附近的体脂秤…（踩一下秤面让它醒过来）"
                : "继续寻找中…（第 " + scanRounds + " 轮，踩一下秤面）");

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                scanning = false;
                handler.postDelayed(scanRestart, SCAN_RETRY_MS);
                return;
            }
            scanner.startScan(null, settings, scanCallback);
        } catch (Exception e) {
            scanning = false;
            handler.postDelayed(scanRestart, SCAN_RETRY_MS);
            return;
        }
        handler.postDelayed(scanCycleEnd, SCAN_CYCLE_MS);
    }

    private void stopScannerHard() {
        handler.removeCallbacks(scanCycleEnd);
        try {
            if (scanner != null && checkPermissions()) scanner.stopScan(scanCallback);
        } catch (Exception ignored) {}
        scanning = false;
    }

    private void endScanRound() {
        stopScannerHard();
        if (!continuous) { cb.onScanFinished(); return; }
        handler.postDelayed(scanRestart, SCAN_PAUSE_MS);
    }

    public boolean isScanning() { return scanning; }

    /** 是否处于「持续扫描」状态（含两轮之间那 1.2 秒的停顿） */
    public boolean isContinuousScanning() { return continuous; }
    /** 是否处于「持续找秤」状态（包含轮次之间的短暂停顿） */
    public boolean isFinding() { return continuous; }
    public long scanElapsedMs() { return scanStartTs == 0 ? 0 : System.currentTimeMillis() - scanStartTs; }
    public int scanRounds() { return scanRounds; }

    /** 停止扫描并通知 UI（用户主动） */
    public void stopScan() { stopScanInternal(true); }

    /** 静默停止扫描（连接前调用，不触发"扫描结束"提示） */
    public void stopScanQuiet() { stopScanInternal(false); }

    private void stopScanInternal(boolean notifyUi) {
        continuous = false;
        handler.removeCallbacks(scanCycleEnd);
        handler.removeCallbacks(scanRestart);
        stopScannerHard();
        if (notifyUi) handler.post(() -> cb.onScanFinished());
    }

    /** 已配对（系统蓝牙列表中）的设备：不依赖扫描的备用连接通道 */
    public List<BluetoothDevice> getBondedDevices() {
        List<BluetoothDevice> list = new ArrayList<>();
        if (adapter == null || !checkPermissions()) return list;
        try {
            java.util.Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) list.addAll(bonded);
        } catch (Exception ignored) {}
        return list;
    }

    /** 按 MAC 取设备对象，用于「重连上次设备」（不需要扫描） */
    public BluetoothDevice deviceByMac(String mac) {
        if (adapter == null || mac == null || mac.isEmpty()) return null;
        try {
            return adapter.getRemoteDevice(mac);
        } catch (Exception e) {
            return null;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            try {
                BluetoothDevice d = result.getDevice();
                String name = null;
                try { name = d.getName(); } catch (SecurityException ignored) {}
                List<String> uuids = new ArrayList<>();
                android.bluetooth.le.ScanRecord rec = null;
                try { rec = result.getScanRecord(); } catch (Exception ignored) {}
                if (rec != null) {
                    try {
                        if (rec.getServiceUuids() != null) {
                            for (android.os.ParcelUuid pu : rec.getServiceUuids()) {
                                uuids.add(pu.getUuid().toString());
                            }
                        }
                    } catch (Exception ignored) {}
                    // 关键：很多体脂秤（小米系尤其）把体重放在「广播的 service data」里，
                    // 而不是 service UUID 列表。getServiceUuids() 读不到这些，
                    // 所以只看它会出现「明明是秤却认不出来」。
                    try {
                        java.util.Map<android.os.ParcelUuid, byte[]> sd = rec.getServiceData();
                        if (sd != null) {
                            for (android.os.ParcelUuid k : sd.keySet()) {
                                uuids.add("data:" + k.getUuid().toString());
                            }
                        }
                    } catch (Exception ignored) {}
                    // 厂商自定义数据：用来识别小米系（0xFE95）等私有协议设备
                    try {
                        android.util.SparseArray<byte[]> mfr = rec.getManufacturerSpecificData();
                        if (mfr != null) {
                            for (int i = 0; i < mfr.size(); i++) {
                                uuids.add("mfr:0x" + Integer.toHexString(mfr.keyAt(i)));
                            }
                        }
                    } catch (Exception ignored) {}
                }
                cb.onScanResult(d, name != null ? name : "未知设备", result.getRssi(), uuids);

                // 先试「广播里直接带体重」这条路：成功的话根本不用连接，
                // 也就彻底绕开 connectable=false 导致的 HCI 0x08 超时。
                try {
                    parseAdvWeight(d, name, rec);
                } catch (Exception ignored) {}

                // 诊断用：把每台设备「每一次内容变化」的广播原文打到 Logcat。
                // connectable 特别关键 —— 只在 adv 可连接时才值得去 connect，
                // 否则必然吃 HCI 0x08（连接建立超时）。
                // 按 HEX 去重（而不是按 MAC），这样同一个地址换了广播内容也能看见，
                // 才能判断「踩上去之后哪几个字节变了」= 体重在哪。
                try {
                    String mac = d.getAddress();
                    StringBuilder sb = new StringBuilder("ADV ");
                    sb.append(name == null ? "?" : name).append(' ').append(mac)
                            .append(" rssi=").append(result.getRssi())
                            .append(" connectable=").append(result.isConnectable());
                    String hex = "";
                    if (rec != null) {
                        byte[] b = rec.getBytes();
                        if (b != null) hex = toHex(b);
                    }
                    String last = advLogged.get(mac);
                    if (last == null || !last.equals(hex)) {
                        advLogged.put(mac, hex);
                        sb.append(" hex=").append(hex).append(" uuids=").append(uuids);
                        Log.d("ScaleLinkSniff", sb.toString());
                    }
                } catch (Exception ignored) {}

                // 纯广播抓包：把 service data 与厂商数据单独也交出去，
                // 这两处才是体重真正可能出现的地方，混在整包 HEX 里很难看出来
                if (advListener != null && rec != null) {
                    try {
                        StringBuilder sd = new StringBuilder();
                        try {
                            java.util.Map<android.os.ParcelUuid, byte[]> map = rec.getServiceData();
                            if (map != null) {
                                for (android.os.ParcelUuid k : map.keySet()) {
                                    if (sd.length() > 0) sd.append(' ');
                                    sd.append(shortUuid(k.getUuid().toString())).append('=')
                                            .append(toHex(map.get(k)));
                                }
                            }
                        } catch (Exception ignored) {}
                        StringBuilder mf = new StringBuilder();
                        try {
                            android.util.SparseArray<byte[]> arr = rec.getManufacturerSpecificData();
                            if (arr != null) {
                                for (int i = 0; i < arr.size(); i++) {
                                    if (mf.length() > 0) mf.append(' ');
                                    mf.append(String.format("0x%04X", arr.keyAt(i))).append('=')
                                            .append(toHex(arr.get(i)));
                                }
                            }
                        } catch (Exception ignored) {}
                        byte[] raw = rec.getBytes();
                        advListener.onAdv(d, name, result.getRssi(), raw, uuids,
                                sd.toString(), mf.toString());
                    } catch (Exception e) {
                        Log.w(TAG, "adv listener error", e);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "onScanResult error", e);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            String reason;
            switch (errorCode) {
                case 1: reason = "蓝牙忙（可能被其他APP占用）"; break;
                case 2: reason = "系统扫描注册失败"; break;
                case 3: reason = "扫描频率被限制"; break;
                case 4: reason = "扫描次数超限"; break;
                case 5: reason = "定位服务未开启"; break;
                default: reason = "错误码 " + errorCode;
            }
            // 持续找秤模式下不要直接放弃：系统限流很常见，等两秒自动重来
            if (continuous) {
                cb.onState("扫描被系统暂停（" + reason + "），马上自动重试…");
                handler.removeCallbacks(scanCycleEnd);
                handler.postDelayed(scanRestart, SCAN_RETRY_MS);
                return;
            }
            cb.onError("扫描失败：" + reason + (errorCode == 1
                    ? "，请关闭京东健康等APP后重试" : "，请关闭再打开蓝牙"));
        }
    };

    // ---------------- 连接 ----------------

    /** 连接设备（失败会按 1/2/4/8 秒指数退避重试，最多 5 次） */
    public void connect(BluetoothDevice device) {
        if (!checkPermissions()) { cb.onError("缺少蓝牙权限"); return; }
        if (device == null) { cb.onError("设备不可用，请重新扫描"); return; }
        String mac = "";
        try { mac = device.getAddress(); } catch (Exception ignored) {}
        if (isIgnored(mac)) {
            cb.onNotScale(safeName(device), mac);
            return;
        }
        // 广播直读型的秤：它的广播标着不可连接，连它只会等 8 秒然后失败。
        // 直接转成「持续扫描」，数据从广播里来。
        if (isAdvScale(mac)) {
            cb.onState("这台秤不需要连接，站上去就会自动读数");
            if (!continuous) startScan();
            return;
        }
        // 防重入：正在连同一台设备就别重复发起（重复 connectGatt 是 133 的常见来源）
        if (wantConnection && pendingDevice != null
                && pendingDevice.getAddress().equals(device.getAddress())
                && gatt != null) {
            return;
        }
        stopScanQuiet();
        pendingDevice = device;
        attempts = 1;
        everReady = false;
        wantConnection = true;
        openGatt(false);
    }

    /** 用户明确要求「尽管试一次」：解除忽略再连，连上后按嗅探模式全量订阅 */
    public void connectAnyway(BluetoothDevice device) {
        String mac = "";
        try { mac = device.getAddress(); } catch (Exception ignored) {}
        unignoreDevice(mac);
        connect(device);
    }

    /**
     * 退避重试。
     * 注意：这里曾经有两个致命组合 bug，会造成「无限重连风暴」，把秤的蓝牙模块打到不响应：
     *   ① 连接成功时把 attempts 清 0 → MAX_ATTEMPTS 永远到不了，重试无上限；
     *   ② 退避公式写成 1L << (attempts - 2)，attempts=1 时是 1L << -1，
     *      Java 会把移位量按 63 取模变成 1L << 63 = Long.MIN_VALUE，再乘 1000 overflow 成 0，
     *      也就是「零延迟立刻重连」。
     * 秤称完会自己主动断开，于是：连上 → 秤断开 → 立刻扑上去再连 → 秤再断开……死循环。
     */
    private void retryConnect() {
        if (attempts >= MAX_ATTEMPTS) {
            boolean wasReady = everReady;
            wantConnection = false;
            if (wasReady) {
                cb.onState("秤已休眠，踩一下秤面即可重新连接");
            } else {
                cb.onError("多次连接失败：请踩醒秤、靠近手机，或在系统蓝牙里忽略该设备后重试");
                cb.onState("未连接");
            }
            return;
        }
        attempts++;
        // 1s / 2s / 4s，且永远为正数
        long delay = Math.min(1000L << Math.min(attempts - 1, 3), 8000L);
        // 第 2 次起不再硬连，改成系统后台连接：
        // 秤只在很短的窗口里接受连接，硬连几乎必然吃 HCI 0x08（连接建立超时）；
        // autoConnect 会让控制器一直挂着等它，秤一亮就接上。
        final boolean auto = attempts >= 2;
        cb.onState(auto
                ? "改用系统后台连接等待秤（踩一下秤面）…"
                : "正在重试连接（第 " + attempts + " 次，约 " + (delay / 1000) + " 秒后）…");
        handler.postDelayed(() -> {
            if (!wantConnection || pendingDevice == null) return;
            openGatt(true, auto);
        }, delay);
    }

    private void openGatt(final boolean isRetry) {
        openGatt(isRetry, false);
    }

    /**
     * @param autoConnect true = 交给系统「白名单后台连接」。
     *
     * 实测现象：秤被踩醒后能扫到（信号最强），但直接 connect 会被回 HCI 0x08
     * （连接建立超时）—— 说明它那一刻的广播不是可连接的，只在某个很短的窗口里接受连接。
     * 这种设备用 autoConnect=true 才是正解：控制器会在底层一直挂着，秤一旦进入
     * 可连接状态就自动接上，不需要人蹲在旁边反复点。
     */
    private void openGatt(final boolean isRetry, final boolean autoConnect) {
        closeGatt();
        final BluetoothDevice d = pendingDevice;
        if (d == null) return;
        resetParsing();
        autoConnecting = autoConnect;
        final String name = safeName(d);
        if (autoConnect) {
            cb.onState("正在等待「" + name + "」接受连接（最长 45 秒，踩一下秤面会更快）…");
        } else {
            cb.onState(isRetry
                    ? "正在重连 " + name + "（第 " + attempts + " 次）…"
                    : "正在连接 " + name + " …");
        }

        // 停扫后紧接着连会撞射频，等 250ms 再发起，明显降低 133 概率
        handler.postDelayed(() -> {
            if (!wantConnection || pendingDevice != d) return;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    gatt = d.connectGatt(ctx, autoConnect, gattCallback,
                            BluetoothDevice.TRANSPORT_LE);
                } else {
                    gatt = d.connectGatt(ctx, autoConnect, gattCallback);
                }
                if (gatt == null) {
                    cb.onError("无法建立连接，请关闭再打开手机蓝牙");
                    return;
                }
            } catch (SecurityException e) {
                cb.onError("连接被系统拒绝：" + e.getMessage());
                return;
            }
            handler.postDelayed(connectTimeout,
                    autoConnect ? AUTOCONNECT_TIMEOUT_MS : CONNECT_TIMEOUT_MS);
        }, CONNECT_DELAY_MS);
    }

    private String safeName(BluetoothDevice d) {
        if (d == null) return "设备";
        if (!checkPermissions()) return "设备";
        try { return d.getName() != null ? d.getName() : d.getAddress(); }
        catch (SecurityException e) { return "设备"; }
    }

    /** 释放 GATT：不 close 会残留句柄，导致后续连接一直失败（133） */
    private void closeGatt() {
        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(discoveryTimeout);
        handler.removeCallbacks(descWatchdog);
        descQueue.clear();
        descWriting = false;
        ready = false;
        if (gatt != null) {
            // 清掉系统缓存的服务列表。133 有很大一部分来自 GATT 缓存与设备真实服务不一致，
            // Android 没有公开 API，只能反射调用隐藏方法；失败就算了，不影响主流程。
            try {
                java.lang.reflect.Method m = gatt.getClass().getMethod("refresh");
                m.invoke(gatt);
            } catch (Throwable ignored) {}
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
    }

    /**
     * 用户主动中止连接：清空重试意愿并释放句柄。
     * 没有这个入口时，一旦进入重试循环，用户除了杀进程没有任何办法停下来。
     */
    public void abortConnect() {
        wantConnection = false;
        attempts = MAX_ATTEMPTS;
        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(discoveryTimeout);
        closeGatt();
        pendingDevice = null;
        cb.onState("已停止连接");
        cb.onDisconnected();
    }

    /** 是否正在尝试连接。注意重试等待期间 gatt 是空的，不能拿 gatt 是否为空来判断 */
    public boolean isConnecting() { return wantConnection && !ready; }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            handler.removeCallbacks(connectTimeout);

            if (newState == BluetoothProfile.STATE_CONNECTED
                    && status == BluetoothGatt.GATT_SUCCESS) {
                // 注意：这里绝对不要 attempts = 0。
                // 一旦清零，重试次数就永远达不到上限，秤主动断开后会变成无限重连。
                ready = false;
                cb.onConnected(safeName(g.getDevice()));
                cb.onState("正在读取设备服务…");
                // 先要高优先级连接参数，服务发现和订阅会明显更快
                try {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                } catch (Exception ignored) {}
                final BluetoothGatt conn = g;
                handler.removeCallbacks(discoveryTimeout);
                handler.postDelayed(discoveryTimeout, DISCOVERY_TIMEOUT_MS);
                // 连接参数协商要几百毫秒，立刻发现服务容易失败
                handler.postDelayed(() -> {
                    if (gatt != conn) return;
                    try {
                        if (checkPermissions()) conn.discoverServices();
                    } catch (Exception e) {
                        cb.onError("读取服务失败：" + e.getMessage());
                    }
                }, DISCOVERY_DELAY_MS);
                return;
            }

            boolean wasReady = ready;
            ready = false;
            everReady = everReady || wasReady;
            cb.onSniff("GATT 断开 status=" + status + " newState=" + newState
                    + " wasReady=" + wasReady + " attempts=" + attempts);
            closeGatt();

            boolean normalDisconnect = newState == BluetoothProfile.STATE_DISCONNECTED
                    && status == BluetoothGatt.GATT_SUCCESS;

            if (normalDisconnect) {
                // 体脂秤称完会自己休眠断开，这是它正常的工作方式。
                // 以前这里会自动重连，结果「连上→秤断开→立刻再连」死循环，
                // 秤的蓝牙模块被反复握手打到不再响应，表现就是之后怎么都连不上。
                wantConnection = false;
                cb.onDisconnected();
                cb.onState(wasReady ? "测量结束（秤已休眠），下次踩醒即可" : "连接已断开");
                return;
            }

            // 连接失败（常见 133 / 8 / 62）
            cb.onDisconnected();
            if (wantConnection && !wasReady) {
                // 只有「还没连成功过」才重试；连成功后掉线说明是秤主动休眠，不该再打扰它
                cb.onState("连接失败（" + status + "），正在重试…");
                retryConnect();
            } else if (wantConnection) {
                wantConnection = false;
                closeGatt();
                cb.onState("秤已休眠，踩一下秤面即可重新连接");
            } else {
                cb.onError(describeGattError(status, wasReady));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            handler.removeCallbacks(discoveryTimeout);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // 体脂秤刚连上时读服务失败很常见（射频还没稳定），再给一次机会
                if (discoveryRetries < 1) {
                    discoveryRetries++;
                    final BluetoothGatt conn = g;
                    cb.onState("读取服务失败，正在重试…");
                    handler.postDelayed(() -> {
                        if (gatt != conn) return;
                        try { if (checkPermissions()) conn.discoverServices(); }
                        catch (Exception ignored) {}
                        handler.postDelayed(discoveryTimeout, DISCOVERY_TIMEOUT_MS);
                    }, 600);
                    return;
                }
                cb.onError("读取服务失败（" + status + "），请靠近秤重试");
                return;
            }
            discoveryRetries = 0;
            discoveredServices.clear();
            for (BluetoothGattService s : g.getServices()) discoveredServices.add(s.getUuid());

            miScale = discoveredServices.contains(MI_BC_SERVICE);
            if (miScale) {
                cb.onSniff("··· 设备含小米体成分服务 ebe0ccb0，体重只认 ebe0ccc1");
            }

            ScaleProtocol protocol = ProtocolRegistry.detect(discoveredServices);
            if (protocol != null) {
                activeProtocol = protocol;
                cb.onProtocolDetected(protocol.name(), protocol.debugHint());
                if (miScale) subscribeWeightOnly(g);
                else subscribe(g, protocol);
            } else if (miScale) {
                activeProtocol = null;
                cb.onProtocolDetected("小米体成分秤（ebb0ccb0）",
                        "体重特征 ebe0ccc1（handle 0x36），阻抗备用 8edfffef");
                subscribeWeightOnly(g);
            } else {
                activeProtocol = null;
                if (sniffMode) {
                    cb.onProtocolDetected("未知协议（嗅探模式）",
                            "已列出全部服务与特征，请把日志发回以适配协议");
                    sniffAll(g);
                } else if (hasWeightChar(g)) {
                    // 服务 UUID 不认识，但确实存在体重特征：还是能按体重特征收数据
                    miScale = true;
                    cb.onProtocolDetected("体重特征直连",
                            "发现 ebe0ccc1/8edfffef，只订阅体重特征");
                    subscribeWeightOnly(g);
                } else {
                    rejectNotScale(g);
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            handleNotification(c.getUuid(), c.getValue());
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            handler.removeCallbacks(descWatchdog);
            descWriting = false;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "descriptor write failed: " + status);
            }
            drainDescQueue();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) cb.onError("写入特征失败：" + status);
        }
    };

    /**
     * 连上的根本不是秤（温湿度计、手环、耳机……）。
     *
     * 以前这里会走「未知协议」分支，把全部特征都订阅下来，再用几个解析器轮着硬套；
     * 温湿度计的包长度、字节位置都可能被凑出一个「看起来像体重」的数，
     * 于是就有了 70.9kg 这种凭空出现的记录。现在直接断开并拉黑，一个字节都不解析。
     */
    private void rejectNotScale(BluetoothGatt g) {
        String mac = "";
        String name = "该设备";
        try {
            mac = g.getDevice().getAddress();
            name = safeName(g.getDevice());
        } catch (Exception ignored) {}
        cb.onSniff("✗ 服务表里没有 ebe0ccc1 / 已知体重协议，判定不是体脂秤：" + name);
        if (!mac.isEmpty()) ignoreDevice(mac);
        wantConnection = false;
        attempts = MAX_ATTEMPTS;
        pendingDevice = null;
        closeGatt();
        cb.onDisconnected();
        cb.onNotScale(name, mac);
    }

    /** 服务表里是否真的存在体重特征（不看服务 UUID，直接按特征 UUID 找） */
    private static boolean hasWeightChar(BluetoothGatt g) {
        try {
            for (BluetoothGattService s : g.getServices()) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if (isWeightChar(c.getUuid())) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 在所有服务里按特征 UUID 找（秤不一定把体重放在标准服务下） */
    private static BluetoothGattCharacteristic findCharAnywhere(BluetoothGatt g, UUID chr) {
        try {
            for (BluetoothGattService s : g.getServices()) {
                BluetoothGattCharacteristic c = s.getCharacteristic(chr);
                if (c != null) return c;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String describeGattError(int status, boolean wasReady) {
        if (status == 133) {
            return "连接被系统拒绝（133）：请关掉京东健康 App，或在系统蓝牙里忽略该设备后重试";
        }
        if (status == 8) return "连接超时（8）：请踩醒秤、靠近手机后重试";
        if (status == 62) return "连接被设备拒绝（62）：秤可能已被其他手机占用";
        return (wasReady ? "连接中断" : "连接失败") + "（状态码 " + status + "）";
    }

    /**
     * 小米体成分秤专用订阅：只开体重特征（ebe0ccc1）和备用体重特征（8edfffef）。
     * 以前把 10 个 notify 特征全订阅，等于同时收 10 路不同格式的数据，
     * 解析器会把历史记录包、配对包也当体重算，读数就乱跳。
     */
    private void subscribeWeightOnly(BluetoothGatt g) {
        int n = 0;
        BluetoothGattCharacteristic main = findChar(g, MI_BC_SERVICE, CHAR_WEIGHT);
        if (main == null) main = findCharAnywhere(g, CHAR_WEIGHT);
        if (main != null) { enqueueNotify(g, main); n++; }

        BluetoothGattCharacteristic alt = findChar(g,
                UUID.fromString("8edffff0-3d1b-9c37-4623-ad7265f14076"), CHAR_WEIGHT_ALT);
        if (alt == null) alt = findCharAnywhere(g, CHAR_WEIGHT_ALT);
        if (alt != null) { enqueueNotify(g, alt); n++; }

        if (n > 0) {
            weightSubscribed = true;
            cb.onState("已连接，请赤脚站上秤测量…");
            cb.onSniff("··· 已只订阅体重特征 " + n + " 个，其余噪声特征已忽略");
        } else {
            weightSubscribed = false;
            cb.onSniff("··· 没找到 ebe0ccc1，退回全量订阅");
            sniffAll(g);
        }
    }

    private static BluetoothGattCharacteristic findChar(BluetoothGatt g, UUID svc, UUID chr) {
        try {
            BluetoothGattService s = g.getService(svc);
            return s == null ? null : s.getCharacteristic(chr);
        } catch (Exception e) {
            return null;
        }
    }

    private void subscribe(BluetoothGatt g, ScaleProtocol protocol) {
        BluetoothGattService s = g.getService(protocol.serviceUuid());
        UUID notifyUuid = protocol.notifyCharacteristicUuid();
        if (s != null && notifyUuid != null) {
            BluetoothGattCharacteristic c = s.getCharacteristic(notifyUuid);
            if (c != null) {
                cb.onState("已连接，请赤脚站上秤测量…");
                enqueueNotify(g, c);
                // 部分协议需先写入初始化命令
                byte[] init = protocol.initPayload(parseContext);
                if (init != null && protocol.writeCharacteristicUuid() != null) {
                    BluetoothGattCharacteristic wc = s.getCharacteristic(protocol.writeCharacteristicUuid());
                    if (wc != null) {
                        try { wc.setValue(init); g.writeCharacteristic(wc); } catch (Exception ignored) {}
                    }
                }
                return;
            }
        }
        // 退化：订阅所有可通知特征
        sniffAll(g);
    }

    /** 系统通用服务（GAP/GATT/设备信息/电池）没有测量数据，跳过避免干扰 */
    private static boolean isSystemService(UUID u) {
        String s = u.toString().toUpperCase();
        return s.startsWith("00001800") || s.startsWith("00001801") || s.startsWith("0000180A")
                || s.startsWith("0000180F") || s.startsWith("00001805");
    }

    private void sniffAll(BluetoothGatt g) {
        int count = 0;
        for (BluetoothGattService s : g.getServices()) {
            if (isSystemService(s.getUuid())) continue;
            cb.onSniff("SERVICE " + s.getUuid());
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                int props = c.getProperties();
                cb.onSniff("  CHAR " + c.getUuid() + " props=" + props);
                if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                        (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                    enqueueNotify(g, c);
                    count++;
                }
            }
        }
        if (count == 0) {
            ready = true;
            everReady = true;
            cb.onState("已连接，但该设备没有可订阅的数据通道");
        } else {
            cb.onState("已连接（通用模式），请赤脚站上秤测量…");
        }
    }

    /** 订阅通知：同一时刻只允许一个描述符写入在途，连写必定失败 */
    private void enqueueNotify(BluetoothGatt g, BluetoothGattCharacteristic c) {
        if (!checkPermissions()) return;
        try { g.setCharacteristicNotification(c, true); } catch (Exception ignored) {}
        if (c.getDescriptor(CCCD) == null) return;
        descQueue.add(c);
        if (!descWriting) drainDescQueue();
    }

    private void drainDescQueue() {
        if (descWriting || gatt == null) return;
        BluetoothGattCharacteristic c = descQueue.poll();
        if (c == null) {
            if (!ready) {
                ready = true;
                everReady = true;
                cb.onState("已就绪，请赤脚站上秤测量…");
            }
            return;
        }
        if (!checkPermissions()) return;
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        if (d == null) { drainDescQueue(); return; }

        boolean indicate = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
        byte[] val = indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ok = gatt.writeDescriptor(d, val) == BluetoothStatusCodes.SUCCESS;
            } else {
                d.setValue(val);
                ok = gatt.writeDescriptor(d);
            }
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) { drainDescQueue(); return; }
        descWriting = true;
        handler.removeCallbacks(descWatchdog);
        handler.postDelayed(descWatchdog, DESC_TIMEOUT_MS);
    }

    private void handleNotification(UUID charUuid, byte[] value) {
        if (value == null || value.length == 0) return;
        String hex = toHex(value);
        cb.onSniff("NOTIFY " + charUuid + " len=" + value.length + " HEX=" + hex
                + " ASCII=" + printable(value));

        // 小米体成分服务系：只有 ebe0ccc1 是体重。
        // 其余 8 个 notify 特征值（设备信息、历史记录、配对状态…）字节布局完全不同，
        // 一旦被当成体重解析，就会出现「同一脚踩下去 41.6 / 135.3 / 156.2 乱跳」。
        if (weightSubscribed && !isWeightChar(charUuid)) {
            return;
        }

        Measurement m = null;
        String via = null;

        if (activeProtocol != null && (activeProtocol.notifyCharacteristicUuid() == null
                || activeProtocol.notifyCharacteristicUuid().equals(charUuid))) {
            m = activeProtocol.parse(value, parseContext);
            if (m != null && m.weight > 0) via = activeProtocol.name();
            else m = null;
        }

        // 未按服务识别出协议：本次连接只认定一个解析器，绝不跨协议混用
        if (m == null && activeProtocol == null) {
            if (stickyProtocol != null) {
                m = stickyProtocol.parse(value, parseContext);
                if (m != null && m.weight > 0) {
                    via = stickyProtocol.name() + "（已锁定）";
                    stickyMiss = 0;
                } else {
                    m = null;
                    stickyMiss++;
                    if (stickyMiss > 12) {              // 设备可能换了一种包型，重新认一次
                        stickyProtocol = null;
                        candidateProto = null;
                        candidateHits = 0;
                        stickyMiss = 0;
                        cb.onSniff("··· 连续解析失败，已解除协议锁定重新识别");
                    }
                }
            } else {
                ScaleProtocol hit = null;
                for (ScaleProtocol p : ProtocolRegistry.all()) {
                    Measurement cand = p.parse(value, parseContext);
                    if (cand != null && cand.weight > 0) { hit = p; m = cand; break; }
                }
                if (hit != null) {
                    if (hit == candidateProto) candidateHits++;
                    else { candidateProto = hit; candidateHits = 1; }
                    // 连续两个包都由同一解析器认出，才真正锁定，避免偶发误判
                    if (candidateHits >= 2) stickyProtocol = hit;
                    via = hit.name() + (candidateHits >= 2 ? "（已锁定）" : "");
                }
            }
        }

        // 自动探测兜底：所有已知解析器都不认这台设备时，靠跨包一致性找体重字段
        voter.feed(value);
        if (m == null && activeProtocol == null && stickyProtocol == null) {
            WeightVoter.Result r = voter.resolve(0);
            if (r != null && r.hits >= 4 && r.hits >= r.total * 0.6) {
                m = new Measurement();
                m.weight = r.kg;
                m.timestamp = System.currentTimeMillis();
                m.source = "ble_auto";
                m.note = "自动探测 " + r.describe();
                via = "自动探测";
            }
        }

        if (m != null && m.weight > 0) {
            // 最后一道物理闸门：不是秤的设备随便怎么解都可能「算出」一个数，
            // 但算出来 70.9kg 这种看着合理、其实来自温湿度计的读数，只能靠
            // 「必须先通过服务校验」来挡（见 rejectNotScale）。这里再兜一层范围。
            if (m.weight < MIN_KG || m.weight > MAX_KG) {
                cb.onSniff("✗ 解析出 " + String.format(java.util.Locale.US, "%.2f", m.weight)
                        + "kg，超出人体范围（" + (int) MIN_KG + "~" + (int) MAX_KG + "），已丢弃");
                return;
            }
            m.timestamp = System.currentTimeMillis();
            if (m.note == null || m.note.isEmpty()) m.note = via;
            cb.onMeasurement(m);
        }
    }

    /** 新连接开始时清空解析状态与投票样本 */
    private void resetParsing() {
        stickyProtocol = null;
        candidateProto = null;
        candidateHits = 0;
        stickyMiss = 0;
        discoveryRetries = 0;
        miScale = false;
        weightSubscribed = false;
        voter.reset();
    }

    /** 该特征值是否被认定为「携带体重」的那个 */
    private static boolean isWeightChar(UUID u) {
        return CHAR_WEIGHT.equals(u) || CHAR_WEIGHT_ALT.equals(u);
    }

    /**
     * 广播里直接解析体重。
     *
     * 实测这台秤的广播 AD 结构（type = 0xFF 厂商数据）：
     *   10 FF [C0] [cnt] [W_hi W_lo] [f1 f2] [hh mm ss] [MAC×6]
     * 校验方式很硬：末尾 6 字节必须等于设备自己的 MAC，[0] 必须是 0xC0。
     * 体重 = (W_hi &lt;&lt; 8 | W_lo) / 100.0 kg，空秤时为 0。
     * 这样即使附近有别的设备用同样的 0xFF 广播，也不会被误读成体重。
     */
    private void parseAdvWeight(BluetoothDevice d, String name,
                                android.bluetooth.le.ScanRecord rec) {
        if (rec == null || cb == null) return;
        byte[] raw = rec.getBytes();
        if (raw == null || raw.length < 19) return;

        final String mac = d.getAddress();
        byte[] addr;
        try {
            String[] p = mac.split(":");
            if (p.length != 6) return;
            addr = new byte[6];
            for (int i = 0; i < 6; i++) {
                addr[i] = (byte) Integer.parseInt(p[i], 16);
            }
        } catch (Exception e) {
            return;
        }

        // 自己拆 AD 结构，比 getManufacturerSpecificData 可靠：
        // 这台设备的「厂商 ID」两个字节本身就在变（它把数据直接写在那个位置），
        // 系统按 company id 解析会把它拆成无数个不同的厂商。
        int i = 0;
        while (i + 1 < raw.length) {
            int len = raw[i] & 0xFF;
            if (len == 0 || i + 1 + len > raw.length) break;
            int type = raw[i + 1] & 0xFF;
            if (type == 0xFF && len >= 16) {
                int p = i + 2;
                // 校验：标记字节 + 末尾 MAC
                boolean markerOk = (raw[p] & 0xFF) == ADV_SCALE_MARKER;
                boolean macOk = true;
                int macOff = p + 9;
                if (macOff + 6 > raw.length) {
                    macOk = false;
                } else {
                    for (int k = 0; k < 6; k++) {
                        if (raw[macOff + k] != addr[k]) { macOk = false; break; }
                    }
                }
                if (markerOk && macOk) {
                    int w = ((raw[p + 2] & 0xFF) << 8) | (raw[p + 3] & 0xFF);
                    double kg = w / 100.0;
                    if (kg > MAX_KG + 40) break;   // 明显不是体重，别当秤
                    int flags = ((raw[p + 4] & 0xFF) << 8) | (raw[p + 5] & 0xFF);
                    double res = 0;
                    // f1 f2 平时是 00 00，测量完成的一瞬间会变成 1770 这类值。
                    // 先按「阻抗 ×10」解释，范围不合理就当没有。
                    if (flags >= 500 && flags <= 20000) res = flags / 10.0;

                    if (advScaleMacs.add(mac)) {
                        Log.d("ScaleLinkSniff", "广播型秤 → 该设备不需要连接，直接读广播体重");
                        cb.onSniff("··· 这台秤把体重放在广播里，无需连接，直接读取");
                        rememberAdvScale(mac);
                    }

                    // 每包都往上报（限流 200ms），让上层能做「连续一致才算站稳」的判断；
                    // 只在变化时上报的话，稳住不动的那几秒一个样本都收不到。
                    long now = System.currentTimeMillis();
                    Long t = advLastTs.get(mac);
                    if (t != null && now - t < 200) return;
                    advLastTs.put(mac, now);
                    Double prev = advLastKg.get(mac);
                    advLastKg.put(mac, kg);
                    // 秤面一直是空的就别反复上报（否则空闲时每秒都要刷好几次 UI）
                    if (kg <= 0 && (prev == null || prev <= 0)) return;
                    cb.onAdvWeight(mac, name != null ? name : "体脂秤", kg, res);
                    return;
                }
            }
            i += len + 1;
        }
    }

    /** 该 MAC 是否是「广播自带体重」的秤（对它 connect 必然超时，别再试） */
    public boolean isAdvScale(String mac) {
        return mac != null && advScaleMacs.contains(mac);
    }

    private static String toHex(byte[] d) {
        if (d == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }

    /** 0000181D-… → 181D，日志里看着清楚 */
    private static String shortUuid(String uuid) {
        if (uuid == null) return "?";
        String u = uuid.toUpperCase();
        if (u.startsWith("0000") && u.length() >= 8) return u.substring(4, 8);
        return u.length() > 8 ? u.substring(0, 8) : u;
    }

    private static String printable(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            int v = b & 0xFF;
            sb.append((v >= 32 && v < 127) ? (char) v : '.');
        }
        return sb.toString();
    }

    /** 主动断开（用户操作）：同时关闭自动重连 */
    public void disconnect() {
        wantConnection = false;
        attempts = MAX_ATTEMPTS;
        resetParsing();
        closeGatt();
    }
}
