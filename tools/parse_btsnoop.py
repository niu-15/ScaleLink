#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
解析 Android 的蓝牙 HCI 抓包日志（btsnoop_hci.log），还原体脂秤的通讯协议。

为什么用这个而不是只看 App 内日志：
  App 只能在「自己连上了」之后记录通知数据；如果连接本身就失败，什么都拿不到。
  HCI 日志是系统层抓的，能看到：
    · 广播包（谁在广播、广播内容是什么、踩秤时怎么变）
    · GATT 交互全过程（发现服务、订阅、写指令、收通知）
    · 京东健康这类「能连上的 App」到底发了什么指令 —— 这是最权威的协议参考

用法：
    python parse_btsnoop.py btsnoop_hci.log
    python parse_btsnoop.py btsnoop_hci.log --mac F3:0B:xx:xx:xx:xx   # 只看某台设备
    python parse_btsnoop.py btsnoop_hci.log --att                    # 只看 ATT 数据（协议本体）
    python parse_btsnoop.py btsnoop_hci.log --out proto.txt

抓日志的步骤见同目录 README-hci-capture.md。
"""

import argparse
import struct
import sys
from collections import Counter, defaultdict

# ---- btsnoop 文件头 ----
BTSNOOP_MAGIC = b"btsnoop\x00"


class Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def read(self, n):
        b = self.data[self.pos:self.pos + n]
        self.pos += n
        return b

    def u8(self):
        return self.read(1)[0]

    def u16(self):
        return struct.unpack("<H", self.read(2))[0]

    def u32(self):
        return struct.unpack("<I", self.read(4))[0]

    def u64(self):
        return struct.unpack("<Q", self.read(8))[0]

    def eof(self):
        return self.pos >= len(self.data)


def parse_btsnoop(path):
    """返回 [(ts_us, direction, packet_bytes)]，direction: 'H2C' 主机->控制器 / 'C2H' 反之"""
    with open(path, "rb") as f:
        data = f.read()
    if not data.startswith(BTSNOOP_MAGIC):
        raise SystemExit("不是 btsnoop 文件（文件头不匹配）。请确认抓的是 btsnoop_hci.log")
    r = Reader(data)
    r.read(8)              # magic
    r.u32()                # version
    r.u32()                # datalink type
    out = []
    epoch = None
    while not r.eof() and r.pos + 24 <= len(data):
        orig_len = r.u32()
        incl_len = r.u32()
        flags = r.u32()
        drops = r.u32()
        ts = r.u64()
        if incl_len == 0 or incl_len > 0x10000:
            break
        pkt = r.read(incl_len)
        if len(pkt) < incl_len:
            break
        if epoch is None:
            epoch = ts
        # flags bit0: 0 = 发送(host->controller), 1 = 接收
        direction = "C2H" if (flags & 1) else "H2C"
        out.append((ts, direction, pkt))
    return out


def handle_of(acl):
    """ACL 包里的连接句柄"""
    if len(acl) < 2:
        return -1
    return struct.unpack("<H", acl[:2])[0] & 0x0FFF


def l2cap_att(acl):
    """
    从 HCI ACL 数据里剥出 ATT 负载。
    结构：handle(2) len(2) | l2cap_len(2) cid(2) | att...
    """
    if len(acl) < 8:
        return None
    cid = struct.unpack("<H", acl[6:8])[0]
    if cid != 0x0004:      # 0x0004 = ATT
        return None
    return acl[8:]


ATT_OP = {
    0x01: "ERROR_RSP", 0x02: "MTU_REQ", 0x03: "MTU_RSP",
    0x04: "FIND_INFO_REQ", 0x05: "FIND_INFO_RSP",
    0x06: "READ_BY_TYPE_REQ", 0x07: "READ_BY_TYPE_RSP",
    0x08: "READ_REQ", 0x09: "READ_RSP",
    0x0A: "READ_BLOB_REQ", 0x0B: "READ_BLOB_RSP",
    0x0C: "READ_MULTI_REQ", 0x0D: "READ_MULTI_RSP",
    0x10: "READ_BY_GRP_TYPE_REQ", 0x11: "READ_BY_GRP_TYPE_RSP",
    0x12: "WRITE_REQ", 0x13: "WRITE_RSP",
    0x16: "PREPARE_WRITE_REQ", 0x17: "PREPARE_WRITE_RSP",
    0x18: "EXEC_WRITE_REQ", 0x19: "EXEC_WRITE_RSP",
    0x1B: "NOTIFY", 0x1D: "INDICATE", 0x1E: "SIGNED_WRITE_CMD",
    0x52: "WRITE_CMD",
}


def hexs(b):
    return " ".join("%02X" % x for x in b)


def u16at(b, i):
    if i + 1 >= len(b):
        return None
    return b[i] | (b[i + 1] << 8)


# ---- HCI 事件：LE 广播报告 ----
def parse_le_advertising(pkt):
    """
    返回 [(addr, addr_type, rssi, data_bytes)]。
    支持 HCI LE Advertising Report (0x3E subevent 0x02) 与
    Extended Advertising Report (subevent 0x0D)。
    """
    out = []
    if len(pkt) < 3 or pkt[0] != 0x04:      # 0x04 = HCI Event
        return out
    if pkt[1] != 0x3E:                       # LE Meta Event
        return out
    sub = pkt[3] if len(pkt) > 3 else -1
    body = pkt[4:]

    if sub == 0x02 and len(body) >= 10:      # Advertising Report
        try:
            n = body[0]
            p = 1
            for _ in range(n):
                if p + 9 > len(body):
                    break
                evt_type = body[p]
                atype = body[p + 1]
                addr = ":".join("%02X" % x for x in reversed(body[p + 2:p + 8]))
                dlen = body[p + 8]
                adv = body[p + 9:p + 9 + dlen]
                p += 9 + dlen
                if p >= len(body):
                    break
                rssi = struct.unpack("b", bytes([body[p]]))[0]
                p += 1
                out.append((addr, atype, rssi, adv))
        except Exception:
            pass

    elif sub == 0x0D and len(body) >= 2:     # Extended Advertising Report
        try:
            n = body[0]
            p = 2
            for _ in range(n):
                if p + 24 > len(body):
                    break
                atype = body[p + 1]
                addr = ":".join("%02X" % x for x in reversed(body[p + 2:p + 8]))
                dlen = body[p + 23]
                adv = body[p + 24:p + 24 + dlen]
                p += 24 + dlen
                if p >= len(body):
                    break
                rssi = struct.unpack("b", bytes([body[p]]))[0]
                p += 1
                out.append((addr, atype, rssi, adv))
        except Exception:
            pass

    return out


# ---- AD 结构拆解 ----
def split_ad(adv):
    """把广播原始数据拆成 (type, payload) 列表"""
    out = []
    i = 0
    while i < len(adv):
        ln = adv[i]
        if ln == 0 or i + 1 + ln > len(adv):
            break
        t = adv[i + 1]
        out.append((t, adv[i + 2:i + 1 + ln]))
        i += 1 + ln
    return out


AD_NAMES = {
    0x01: "Flags", 0x02: "16bitUUID(不完整)", 0x03: "16bitUUID(完整)",
    0x04: "32bitUUID", 0x05: "32bitUUID", 0x06: "128bitUUID", 0x07: "128bitUUID",
    0x08: "ShortName", 0x09: "Name",
    0x0A: "TxPower", 0x16: "ServiceData16", 0x20: "ServiceData32", 0x21: "ServiceData128",
    0xFF: "Manufacturer",
}


def describe_ad(adv):
    parts = []
    for t, v in split_ad(adv):
        name = AD_NAMES.get(t, "AD[0x%02X]" % t)
        if t in (0x08, 0x09):
            try:
                parts.append("%s=%s" % (name, v.decode("utf-8", "replace")))
            except Exception:
                parts.append("%s=%s" % (name, hexs(v)))
        elif t in (0x02, 0x03):
            uu = [hexs(v[i:i + 2][::-1]) for i in range(0, len(v) - 1, 2)]
            parts.append("%s=%s" % (name, ",".join(uu)))
        elif t == 0x16 and len(v) >= 2:
            parts.append("%s=UUID %s | %s" % (name, hexs(v[:2][::-1]), hexs(v[2:])))
        elif t == 0xFF and len(v) >= 2:
            parts.append("%s=0x%02X%02X | %s" % (name, v[1], v[0], hexs(v[2:])))
        else:
            parts.append("%s=%s" % (name, hexs(v)))
    return " ; ".join(parts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--mac", help="只看这台设备（不分大小写）")
    ap.add_argument("--att", action="store_true", help="只看 ATT 交互（协议本体）")
    ap.add_argument("--adv", action="store_true", help="只看广播")
    ap.add_argument("--out", help="输出到文件")
    args = ap.parse_args()

    packets = parse_btsnoop(args.log)
    if not packets:
        raise SystemExit("日志里没有数据包")

    lines = []
    mac = args.mac.upper() if args.mac else None

    adv_seen = defaultdict(list)     # addr -> [(rssi, adv)]
    att_lines = []
    notify_values = []               # 通知里的「值部分」（已去掉句柄），用于猜体重字段
    all_values = []                  # 所有带值的方向，统计用

    for ts, direction, pkt in packets:
        if not pkt:
            continue
        # --- HCI 事件（含 LE 广播） ---
        if pkt[0] == 0x04:
            for addr, atype, rssi, adv in parse_le_advertising(pkt):
                if mac and addr.upper() != mac:
                    continue
                adv_seen[addr].append((rssi, adv))
            continue

        # --- ACL 数据（ATT 交互） ---
        if pkt[0] == 0x02 and len(pkt) >= 5:
            acl = pkt[1:]
            att = l2cap_att(acl)
            if att is None or not att:
                continue
            op = att[0]
            name = ATT_OP.get(op, "OP[0x%02X]" % op)
            h = handle_of(acl)
            arrow = "手机→秤" if direction == "H2C" else "秤→手机"
            att_lines.append("h=0x%03X %s %-16s %s" % (h, arrow, name, hexs(att[1:])))
            # 带值的操作码：值是「操作码+2字节句柄」之后的内容
            if op in (0x1B, 0x1D, 0x52, 0x12) and len(att) > 3:
                val = att[3:]
                if op in (0x1B, 0x1D):
                    notify_values.append(val)
                all_values.append(val)

    if not args.adv:
        # 广播汇总：同一地址只打一次，附带「变过几次」
        lines.append("=" * 70)
        lines.append("广播（广告）汇总")
        lines.append("=" * 70)
        for addr, items in sorted(adv_seen.items(), key=lambda kv: -len(kv[1])):
            uniq = []
            for rssi, adv in items:
                if not uniq or uniq[-1][1] != adv:
                    uniq.append((rssi, adv))
            lines.append("")
            lines.append("%s   共 %d 包，内容变化 %d 次，最近 RSSI %d"
                         % (addr, len(items), max(0, len(uniq) - 1), items[-1][0]))
            for rssi, adv in uniq[-6:]:
                lines.append("   rssi=%-4d len=%-3d %s" % (rssi, len(adv), hexs(adv)))
                lines.append("        └ %s" % describe_ad(adv))
        lines.append("")

    if not args.adv:
        lines.append("=" * 70)
        lines.append("ATT 交互（这里就是协议本体）")
        lines.append("=" * 70)
        lines.extend(att_lines)
        lines.append("")

    # 自动猜体重：在通知的「值」里找 2 字节小端，÷200 / ÷100 落在合理体重区间。
    # 偏移从值的第 0 字节算起（已去掉 ATT 操作码与 2 字节句柄），和 App 里 bytes[1..2] 这类写法可直接对应。
    cand = Counter()
    for bs in notify_values:
        for i in range(len(bs) - 1):
            v = bs[i] | (bs[i + 1] << 8)
            for div in (200.0, 100.0):
                kg = v / div
                if 20 <= kg <= 250:
                    cand[(i, div, round(kg, 1))] += 1

    if cand:
        total = max(1, len(notify_values))
        lines.append("=" * 70)
        lines.append("疑似体重字段（按出现次数排序，仅供参考）")
        lines.append("=" * 70)
        for (off, div, kg), n in cand.most_common(12):
            lines.append("  value[%d..%d]  ÷%-3d = %-6s kg   出现 %d/%d 次"
                         % (off, off + 1, int(div), kg, n, total))
        lines.append("")
        lines.append("怎么确认：站上去读数会连续变化，下秤后固定。上面出现次数最多、")
        lines.append("且数值在「你的真实体重」附近的那一组，就是体重字段。")

    text = "\n".join(lines)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
        print("已写入", args.out)
    else:
        print(text)


if __name__ == "__main__":
    main()
