# -*- coding: utf-8 -*-
"""解析 ScaleLink 抓到的广播日志，定位体重字段。

用法: python parse_adv.py <adv.log> <MAC>

日志行形如:
11-25 16:07:26.226 D/ScaleLinkSniff(24561): ADV ? AA:BB:CC:DD:EE:FF rssi=-57 \
    connectable=false hex=10 FF C0 05 19 41 00 00 0A 11 24 AA BB CC DD EE FF 00 ...

厂商数据布局（实测）:
    [0]=0x10 长度  [1]=0xFF 类型  [2..3]=厂商 ID  [4..5]=重量 BE ×100
    [6..7]=状态/填充  [8..10]=秤内部时钟 H/M/S  [11..16]=自身 MAC
"""
import sys
import re

LINE = re.compile(r"(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}).*?ADV (\S+) (\S+) rssi=(-?\d+) "
                  r"connectable=(\w+) hex=([0-9A-Fa-f ]+)")


def main():
    if len(sys.argv) < 3:
        print("用法: python parse_adv.py <adv.log> <MAC>")
        print("例:   python parse_adv.py adv.log AA:BB:CC:DD:EE:FF")
        sys.exit(1)
    path = sys.argv[1]
    mac = sys.argv[2].upper()

    rows = []
    for ln in open(path, encoding="utf-8", errors="ignore"):
        m = LINE.search(ln)
        if not m:
            continue
        ts, name, addr, rssi, conn, hexs = m.groups()
        if addr.upper() != mac:
            continue
        b = hexs.split()
        if len(b) < 17:
            continue
        raw = int(b[4], 16) << 8 | int(b[5], 16)
        rows.append((ts[6:], b, rssi, conn, raw / 100.0))

    if not rows:
        print("没有匹配到 %s 的广播" % mac)
        return

    print("共 %d 条广播（%s ~ %s）" % (len(rows), rows[0][0], rows[-1][0]))
    print("%-13s %-4s %-4s %-4s %-4s %-4s %-3s %-3s %-3s %8s %s"
          % ("时间", "b3", "b4", "b5", "b6", "b7", "H", "M", "S", "重量kg", "连接性"))
    for ts, b, rssi, conn, kg in rows:
        print("%-13s %-4s %-4s %-4s %-4s %-4s %-3s %-3s %-3s %8.2f %s"
              % (ts, b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], kg, conn))

    vals = [r[4] for r in rows if r[4] > 0]
    if vals:
        print("\n重量范围: %.2f ~ %.2f kg   （非零样本 %d 条）"
              % (min(vals), max(vals), len(vals)))
    zero = sum(1 for r in rows if r[4] == 0)
    print("零值样本: %d 条（秤面无人时应为 0）" % zero)


if __name__ == "__main__":
    main()
