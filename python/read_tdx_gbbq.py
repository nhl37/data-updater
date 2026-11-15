# File: python/read_tdx_gbbq.py
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
使用 pytdx 读取本地通达信 gbbq 股本变迁文件，导出除权除息数据为 CSV。

依赖: pytdx (pip install pytdx) + pandas

pytdx GbbqReader 原始列:
    market, code, datetime, category,
    hongli_panqianliutong,   -> 每10股派现(元)
    peigujia_qianzongguben,  -> 每股配股价(元)
    songgu_qianzongguben,    -> 每10股送转股
    peigu_houzongguben       -> 每10股配股
注意: 非 category=1 的记录里，上述四个字段含义不同(前流通股本/后总股本)，
      数值可能极大，因此"厘/元"单位判断必须逐条判断，不能取全文件最大值。

输出 CSV(UTF-8) 列:
    code,date,category,fenhong,peigujia,songgu,peigu
    code      : 带市场前缀 sz/sh/bj，如 sh600000
    date      : yyyyMMdd
    category  : 1=除权除息
    fenhong   : 每10股派现(元)
    peigujia  : 每股配股价(元)
    songgu    : 每10股送转股
    peigu     : 每10股配股

用法:
    python read_tdx_gbbq.py --tdx-dir <通达信目录> --output-file <输出CSV>

可选:
    --gbbq-file PATH        直接指定 gbbq 文件
    --codes-file PATH       本地 db 股票代码列表(用于校正前缀/可选过滤)
    --filter-codes          只导出 codes-file 中的股票
    --include-all-category  导出全部 category(默认只导出 category=1)
    --debug                 打印列名/分布/样例
"""
import argparse
import math
import os
import sys

CSV_HEADER = "code,date,category,fenhong,peigujia,songgu,peigu\n"

CATEGORY_XDXR = 1
MIN_DATE = 19000101
MAX_DATE = 21001231
EPS = 1e-10

# 逐条单位判断：每10股派现 > 100 元 / 每股配股价 > 1000 元，视为以"厘"存储
FENHONG_CENT_LIMIT = 100.0
PEIGUJIA_CENT_LIMIT = 1000.0
UNIT_CENT = 1000.0
# 换算后依然离谱的，视为脏数据丢弃
FENHONG_INVALID_LIMIT = 1000.0
PEIGUJIA_INVALID_LIMIT = 10000.0

MARKET_PREFIX = {0: "sz", 1: "sh", 2: "bj"}

CANDIDATE_REL_PATHS = [
    os.path.join("T0002", "hq_cache", "gbbq"),
    os.path.join("T0002", "special", "gbbq"),
    os.path.join("T0002", "hq_cache", "gbbq.dat"),
    os.path.join("T0002", "special", "gbbq.dat"),
]

# pytdx 列名 -> 内部列名
COLUMN_ALIASES = [
    ("date", ["datetime", "date"]),
    ("fenhong", ["hongli_panqianliutong", "fenhong", "hongli"]),
    ("peigujia", ["peigujia_qianzongguben", "peigujia"]),
    ("songgu", ["songgu_qianzongguben", "songgu"]),
    ("peigu", ["peigu_houzongguben", "peigu"]),
]


# ---------------------------------------------------------------- 文件查找
def find_gbbq_file(tdx_dir, gbbq_file=None):
    if gbbq_file:
        return gbbq_file if os.path.isfile(gbbq_file) else None
    for rel in CANDIDATE_REL_PATHS:
        path = os.path.join(tdx_dir, rel)
        if os.path.isfile(path):
            return path
    for root, _dirs, files in os.walk(tdx_dir):
        for name in files:
            if name == "gbbq" or name.startswith("gbbq."):
                return os.path.join(root, name)
    return None


# ---------------------------------------------------------------- 工具函数
def to_int(value, default=0):
    try:
        if value is None:
            return default
        f = float(value)
        if math.isnan(f) or math.isinf(f):
            return default
        return int(f)
    except (TypeError, ValueError):
        return default


def to_float(value, default=0.0):
    try:
        if value is None:
            return default
        f = float(value)
        if math.isnan(f) or math.isinf(f):
            return default
        return f
    except (TypeError, ValueError):
        return default


def guess_prefix(code6):
    """按 6 位代码推断市场前缀"""
    head = code6[:1]
    if head in ("6", "9", "5"):
        return "sh"
    if head in ("0", "2", "3"):
        return "sz"
    if head in ("4", "8"):
        return "bj"
    return ""


def normal_code(raw):
    """
    把 pytdx 返回的 code 规范为 6 位数字字符串。
    注意: 这里不做任何"逆序纠正"，pytdx 已经处理好 gbbq 的存储顺序，
          否则 600000 会被错误改写为 000006（该逻辑已删除）。
    """
    value = raw
    if isinstance(value, (bytes, bytearray)):
        value = value.decode("ascii", "ignore")
    text = str(value).strip().replace("\x00", "")
    if text.endswith(".0"):
        text = text[:-2]
    if not text.isdigit():
        return ""
    text = text.zfill(6)
    if len(text) > 6:
        text = text[-6:]
    return text


def market_prefix(market, code6):
    """市场前缀：优先用通达信市场号(0=sz,1=sh,2=bj)，未知时按代码规则兜底"""
    return MARKET_PREFIX.get(to_int(market, -1), guess_prefix(code6))


def load_codes(codes_file):
    """返回 (codes_set, code_map)；code_map: 6位 -> 带前缀代码"""
    codes_set = set()
    code_map = {}
    if not codes_file or not os.path.isfile(codes_file):
        return codes_set, code_map
    with open(codes_file, "r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            text = line.strip()
            if not text:
                continue
            codes_set.add(text)
            if len(text) > 6:
                code_map[text[-6:]] = text
    return codes_set, code_map


def normalize_record_unit(row):
    """
    逐条判断价格单位(厘/元)。
    返回 True 表示该条做过换算。
    """
    need = (abs(row["fenhong"]) > FENHONG_CENT_LIMIT
            or abs(row["peigujia"]) > PEIGUJIA_CENT_LIMIT)
    if need:
        row["fenhong"] = row["fenhong"] / UNIT_CENT
        row["peigujia"] = row["peigujia"] / UNIT_CENT
    return need


def resolve_full_code(row, codes_set, code_map):
    """
    生成带市场前缀的完整代码；返回 None 表示该记录应跳过。

    规则:
      1) 优先 市场号 + 代码；
      2) 若市场号与代码规则冲突(典型是沪市指数 000001 这类)，且本地没有该 db，则跳过；
      3) 若本地 db 里的前缀与市场号不同(如北交所代码被放进 sz 目录)，用 db 里的名字。
    """
    code6 = row["code6"]
    market_p = MARKET_PREFIX.get(row["market"], "")
    rule_p = guess_prefix(code6)
    market_full = (market_p or rule_p) + code6

    if not codes_set:
        # 没有 db 列表时不做清洗，按市场号输出
        if market_p and rule_p and market_p != rule_p:
            return None
        return market_full

    if market_full in codes_set:
        return market_full

    # 市场号与代码规则冲突(指数/异类记录)，本地也没有对应 db -> 跳过
    if market_p and rule_p and market_p != rule_p:
        return None

    # db 前缀与市场号不同，用 db 中的名字（要求与代码规则一致，避免污染）
    candidate = code_map.get(code6)
    if candidate and rule_p and candidate.startswith(rule_p):
        return candidate
    return market_full


# ---------------------------------------------------------------- 主逻辑
def map_columns(df):
    rename = {}
    columns = list(df.columns)
    for target, aliases in COLUMN_ALIASES:
        if target in columns:
            continue
        for alias in aliases:
            if alias in columns:
                rename[alias] = target
                break
    return df.rename(columns=rename) if rename else df


def load_df(gbbq_file):
    from pytdx.reader.gbbq_reader import GbbqReader
    reader = GbbqReader()
    df = reader.get_df(gbbq_file)
    return map_columns(df)


def build_rows(df, debug):
    required = ["market", "code", "date", "category",
                "fenhong", "peigujia", "songgu", "peigu"]
    missing = [c for c in required if c not in df.columns]
    if missing:
        print("ERROR: pytdx 返回的数据缺少列: %s" % missing)
        print("       实际列名: %s" % list(df.columns))
        return None

    cat_count = {}
    rows = []
    for record in df.to_dict("records"):
        code6 = normal_code(record.get("code"))
        if not code6:
            continue
        date = to_int(record.get("date"), 0)
        category = to_int(record.get("category"), 0)
        cat_count[category] = cat_count.get(category, 0) + 1
        rows.append({
            "market": to_int(record.get("market"), -1),
            "code6": code6,
            "date": date,
            "category": category,
            "fenhong": to_float(record.get("fenhong")),
            "peigujia": to_float(record.get("peigujia")),
            "songgu": to_float(record.get("songgu")),
            "peigu": to_float(record.get("peigu")),
        })

    print("parsed rows:", len(rows))
    if debug:
        print("category distribution:", dict(sorted(cat_count.items())))
        if rows:
            sample = rows[0]
            print("sample record:", sample)
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description="pytdx 读取本地通达信 gbbq 并导出 CSV")
    parser.add_argument("--tdx-dir", "-d", required=True, help="通达信安装目录")
    parser.add_argument("--output-file", "-o", required=True, help="输出 CSV 文件")
    parser.add_argument("--gbbq-file", "-f", default=None, help="直接指定 gbbq 文件(可选)")
    parser.add_argument("--codes-file", "-c", default=None,
                        help="本地 db 股票代码列表(用于校正前缀/可选过滤)")
    parser.add_argument("--filter-codes", action="store_true",
                        help="只导出 codes-file 中的股票")
    parser.add_argument("--include-all-category", action="store_true",
                        help="导出全部类别(默认只导出 category=1)")
    parser.add_argument("--debug", action="store_true", help="打印诊断信息")
    args = parser.parse_args(argv)

    print("python:", sys.version.replace("\n", " "))
    try:
        import pandas
        print("pandas:", pandas.__version__)
    except Exception as exc:  # noqa: BLE001
        print("ERROR: 未能导入 pandas(%s)" % exc)
        return 7
    try:
        import pytdx
        print("pytdx:", getattr(pytdx, "__version__", "unknown"))
        from pytdx.reader.gbbq_reader import GbbqReader  # noqa: F401
    except Exception as exc:  # noqa: BLE001
        print("ERROR: 未能导入 pytdx(%s)，请先 pip install pytdx" % exc)
        return 7

    tdx_dir = os.path.abspath(os.path.expanduser(args.tdx_dir))
    if not os.path.isdir(tdx_dir):
        print("ERROR: 本地数据来源目录不存在: %s" % tdx_dir)
        return 2

    gbbq_file = find_gbbq_file(tdx_dir, args.gbbq_file)
    if not gbbq_file:
        print("ERROR: 未找到 gbbq 文件，目录: %s" % tdx_dir)
        print("       这个文件由通达信客户端下发，没有单独的下载地址。补的办法：打开通达信并登录 →")
        print("       菜单「系统 → 盘后数据下载」（Ctrl+F1）→ 勾「除权除息数据」→ 时间选「所有」→ 开始下载，")
        print("       下完就在 <通达信安装目录>/T0002/hq_cache/gbbq；--tdx-dir 要指到安装目录这一层。")
        print("       不想装通达信：把除权任务的来源换成「东方财富」，那条线路不需要 gbbq。")
        return 3
    print("gbbq file:", gbbq_file, "size:", os.path.getsize(gbbq_file))

    try:
        df = load_df(gbbq_file)
    except Exception as exc:  # noqa: BLE001
        print("ERROR: pytdx 读取 gbbq 失败: %s: %s" % (type(exc).__name__, exc))
        return 4
    if df is None or len(df) == 0:
        print("ERROR: gbbq 解析结果为空")
        return 5
    print("gbbq rows:", len(df))
    if args.debug:
        print("gbbq columns:", list(df.columns))
        print(df.head(5).to_string())

    rows = build_rows(df, args.debug)
    if rows is None:
        return 6
    if not rows:
        print("ERROR: gbbq 中未解析出任何记录")
        return 6

    # 1. 类别过滤
    stage = rows if args.include_all_category else \
        [r for r in rows if r["category"] == CATEGORY_XDXR]
    print("after category filter:", len(stage))

    # 2. 日期过滤
    stage = [r for r in stage if MIN_DATE <= r["date"] <= MAX_DATE]
    print("after date filter:", len(stage))

    # 3. 逐条单位换算(仅对 category=1 的除权记录有意义)
    unit_fixed = 0
    if not args.include_all_category:
        for r in stage:
            if normalize_record_unit(r):
                unit_fixed += 1
    print("after unit normalize:", len(stage), "unit fixed rows:", unit_fixed)

    # 4. 数值有效性过滤(全为0 / 换算后仍离谱的丢弃)
    valid = []
    zero_skipped = 0
    invalid_skipped = 0
    for r in stage:
        if (abs(r["fenhong"]) < EPS
                and abs(r["songgu"]) < EPS
                and abs(r["peigu"]) < EPS):
            zero_skipped += 1
            continue
        if (abs(r["fenhong"]) > FENHONG_INVALID_LIMIT
                or abs(r["peigujia"]) > PEIGUJIA_INVALID_LIMIT):
            invalid_skipped += 1
            continue
        valid.append(r)
    print("after value filter:", len(valid),
          "zero skipped:", zero_skipped, "invalid skipped:", invalid_skipped)
    if not valid and stage:
        print("WARN: 数值过滤后为空，回退为不做数值过滤")
        valid = stage
    stage = valid

    # 5. 生成完整代码(校正前缀) / 可选过滤
    codes_set, code_map = load_codes(args.codes_file)
    if codes_set:
        print("codes file size:", len(codes_set))
    else:
        print("codes file not used")

    out_rows = []
    conflict_skipped = 0
    for r in stage:
        full_code = resolve_full_code(r, codes_set, code_map)
        if not full_code:
            conflict_skipped += 1
            continue
        if args.filter_codes and codes_set and full_code not in codes_set:
            continue
        out_rows.append((full_code, r["date"], r["category"],
                         r["fenhong"], r["peigujia"], r["songgu"], r["peigu"]))
    print("after code resolve:", len(out_rows), "conflict skipped:", conflict_skipped)

    if not out_rows:
        print("ERROR: 没有任何记录可输出")
        for r in rows[:5]:
            print("  sample: market=%d code=%s date=%d category=%d "
                  "fenhong=%.4f peigujia=%.4f songgu=%.4f peigu=%.4f"
                  % (r["market"], r["code6"], r["date"], r["category"],
                     r["fenhong"], r["peigujia"], r["songgu"], r["peigu"]))
        return 6

    # 6. 写 CSV
    out_file = args.output_file
    parent = os.path.dirname(os.path.abspath(out_file))
    if parent and not os.path.isdir(parent):
        os.makedirs(parent, exist_ok=True)

    with open(out_file, "w", encoding="utf-8", newline="") as handle:
        handle.write(CSV_HEADER)
        for (code, date, category, fenhong, peigujia, songgu, peigu) in out_rows:
            handle.write("%s,%d,%d,%.6f,%.6f,%.6f,%.6f\n"
                         % (code, date, category, fenhong, peigujia, songgu, peigu))

    # 便于核对: 打印指定代码(如 sh600000)的前几条
    if args.debug:
        for target in ("sh600000", "sz000001"):
            hits = [row for row in out_rows if row[0] == target]
            print("%s rows: %d" % (target, len(hits)))
            for hit in hits[:3]:
                print("  %s" % (hit,))

    print("rows to write:", len(out_rows))
    print("SUCCESS:%s" % out_file)
    return 0


if __name__ == "__main__":
    sys.exit(main())
