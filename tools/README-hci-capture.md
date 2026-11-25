# 抓包还原体脂秤协议

给手上另一台秤补协议时，先把它的通讯过程录下来。有两条路：**A 更快**（只抓广播，不用 root），**B 更全**（能拿到 GATT 交互全文）。

已有的实测结论见 [PROTOCOL-captured.md](PROTOCOL-captured.md)。

---

## A. 广播抓包（不用连接设备）

相当一部分体脂秤会把体重直接放进广播的厂商数据里，**这种型号根本不需要建立 GATT 连接**——扫描即测量。本项目的实测机型就是这一类。

### A1. 用现成 App 抓

装一个 nRF Connect（或任何能看原始广播字节的工具），扫描时把某台设备的 `Manufacturer data` 原样记下来。踩上秤、站住 5 秒、再下来，前后对比哪几个字节在变。

### A2. 用本项目的 sniff 模式抓

`BleManager.setSniffMode(true)` 会把每条广播按下面这种格式打进 logcat：

```
ADV ? AA:BB:CC:DD:EE:FF rssi=-57 connectable=false hex=10 FF C0 05 19 41 00 00 0A 11 24 AA BB CC DD EE FF 00 ...
```

存成 `adv.log`，然后用脚本按字段切出来看：

```bash
python tools/parse_adv.py adv.log AA:BB:CC:DD:EE:FF
```

**怎么判断是不是体重字段**：踩上去时这组数连续变化、下秤后归零，数值接近真实体重，且换算系数是 100 或 200 这种整数。

---

## B. 系统层 HCI 抓包（能拿到协议全文）

这条路录的是厂商 App 自己的通讯过程。它是能连上的，把它发的每条指令、收到的每条通知原样记下来，照着实现就行——这是最权威的协议参考。

### B1. 打开抓包开关

1. 设置 → 关于手机 → 连点「版本号」7 次，开启开发者选项
2. 设置 → 系统 → 开发者选项 → 打开 **「启用蓝牙 HCI 信息收集日志」**
3. **把手机蓝牙关掉，再打开**（必须，否则不会开始记录）

### B2. 用厂商 App 称一次

4. 打开厂商 App，连接这台秤，正常称一次（站上去等到出结果）
5. 关掉厂商 App

### B3. 取出日志文件

不用电脑的办法：

- 打开手机自带的「文件管理」，进**手机存储根目录**，找 `btsnoop_hci.log`

用 adb（不需要 root）：

```bash
adb pull /sdcard/btsnoop_hci.log
# 部分机型在：
adb pull /data/misc/bluetooth/logs/btsnoop_hci.log
```

> 小米 / 红米：通常在 `/sdcard/btsnoop_hci.log`，文件管理器能直接看到。
> 华为 / 荣耀：可能需要 `adb pull /data/log/bt/btsnoop_hci.log`。

### B4. 解析

```bash
# 全文（含广播噪音）
python tools/parse_btsnoop.py btsnoop_hci.log --out proto.txt

# 只看 ATT 交互，这就是协议本体
python tools/parse_btsnoop.py btsnoop_hci.log --att

# 只看某台设备
python tools/parse_btsnoop.py btsnoop_hci.log --mac AA:BB:CC:DD:EE:FF
```

输出长这样：

```
ATT 交互（这里就是协议本体）
h=0x021 秤→手机 NOTIFY   21 00 2E 04 3F 1B 0B
h=0x021 手机→秤 WRITE_CMD 22 00 01

疑似体重字段（按出现次数排序，仅供参考）
  value[1..2]  ÷200 = 80.7   kg   出现 3/3 次
```

最后一段是脚本自动猜的体重字段，只能当线索。确认方法：**站上去时这组数连续变化、下秤后固定，且数值接近真实体重**。

---

## 补一个新协议要写什么

1. 在 `protocol/` 下新建 `XxxProtocol.java` 实现 `ScaleProtocol` 接口：服务 UUID、notify 特征 UUID、包解析
2. 在 `ProtocolRegistry` 的静态块里注册（顺序讲究：状态字节特征明显的放前面，避免被通用解析器先抢走）
3. 如果它只发广播，在 `BleManager.parseAdvWeight()` 里加分支：按 AD 结构校验厂商标记字节和末尾 MAC，别信 `getManufacturerSpecificData()`（它会把 `C0 XX` 当成 company id）
4. 把抓包原文按 `PROTOCOL-captured.md` 的格式整理进 `docs`，方便别人复核

提 PR 时请把真实设备地址打码成 `XX:XX`。
