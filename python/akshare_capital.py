#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AKShare 股本 / 股东数据抓取脚本

为什么要这份数据：
    K线库(T_D_Day)只有成交量和成交额，没有「流通股」，
    所以算不出换手率 = 成交量 / 流通A股。
    这份脚本把「股本」和「大股东持股」抓下来，
    由 Java 端写进 data/finance.db 的 T_D_ShareCapital 表，
    K线图据此显示换手率与实际换手率。

两个数据源(都以 akshare 为入口)：
    1. 巨潮资讯 stock_share_change_cninfo —— 股本变动明细，
       每个变动日期一行：总股本 / 已流通股份 / 流通受限股份 / 变动原因。
       单位是「万股」，这里统一乘 10000 换成「股」。
       注意：该接口的数据只到 2024 年前后，更新的季度由本地财报字段补齐。
    2. 东方财富 stock_gdfx_free_top_10_em —— 指定报告期的十大流通股东，
       取「合计持股数 / 第一大持股数 / 合计占流通股比例」。

输出 CSV(UTF-8，逗号分隔，表头见 OUT_COLUMNS)：
    code, report_date, total_shares, float_a_shares, restricted_a_shares,
    top10_holder_shares, top10_holder_ratio, top1_holder_shares,
    holder_count, change_reason, source, ann_date
    一笔数据只填自己有值的列，Java 端按 (code, report_date) 合并。

stdout 约定(Java 端按 KEY=VALUE 行解析)：
    PYTHON_EXE=<实际解释器路径>
    AKSHARE_VERSION=<版本号>
    OUT_FILE=<csv 绝对路径>
    ROW_COUNT=<写出行数>
    CODE_COUNT=<覆盖股票数>
    ERROR_COUNT=<抓取失败的请求数>
    SUCCESS:<csv 绝对路径>

用法：
    # 只检测环境
    python akshare_capital.py --check

    # 抓一批股票(代码来自文件，每行一个，可带 sh/sz 前缀)
    python akshare_capital.py --codes-file data/capital/codes.txt \
        --report-date 20260930 --out data/capital/akshare_capital.csv --threads 6

    # 断点续抓：已在 out 文件里的股票跳过
    python akshare_capital.py --codes-file ... --out ... --resume

    # 单只股票，结果直接打到 stdout(调试用)
    python akshare_capital.py --single 600000 --report-date 20260930 --print
"""
import argparse
import csv
import datetime
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

OUT_COLUMNS = [
    "code", "report_date", "total_shares", "float_a_shares", "restricted_a_shares",
    "top10_holder_shares", "top10_holder_ratio", "top1_holder_shares",
    "holder_count", "change_reason", "source", "ann_date",
]

# 万元 -> 元
WAN = 10000.0

_print_lock = threading.Lock()


def log(msg):
    with _print_lock:
        sys.stderr.write(str(msg) + "\n")
        sys.stderr.flush()


def emit(key, value):
    with _print_lock:
        sys.stdout.write("%s=%s\n" % (key, value))
        sys.stdout.flush()


def to_float(value):
    """把 akshare 返回的各种脏值(空串/NaN/--/'')统一成 float 或 None"""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        if isinstance(value, float) and (value != value):
            return None
        return float(value)
    text = str(value).strip().replace(",", "")
    if text in ("", "-", "--", "None", "nan", "NaN"):
        return None
    try:
        return float(text)
    except ValueError:
        return None


def norm_date(value):
    """变动日期 / 公告日期 -> yyyyMMdd 字符串"""
    if value is None:
        return ""
    if isinstance(value, (datetime.date, datetime.datetime)):
        return value.strftime("%Y%m%d")
    text = str(value).strip()
    if not text:
        return ""
    text = text.replace("-", "").replace("/", "").replace(".", "")
    digits = "".join(ch for ch in text if ch.isdigit())
    if len(digits) >= 8:
        return digits[:8]
    return ""


def plain_code(code):
    """sh600000 / 600000.SH / 600000 -> 600000"""
    text = str(code).strip().upper()
    for prefix in ("SH", "SZ", "BJ"):
        if text.startswith(prefix):
            text = text[len(prefix):]
    if "." in text:
        text = text.split(".")[0]
    digits = "".join(ch for ch in text if ch.isdigit())
    return digits[-6:].rjust(6, "0") if digits else ""


def market_prefix(code6):
    """6位代码 -> sh / sz / bj（东财接口需要带市场前缀）"""
    if code6.startswith(("60", "68", "90", "58", "51", "11")):
        return "sh"
    if code6.startswith(("00", "30", "20", "15", "16", "12", "39")):
        return "sz"
    if code6.startswith(("43", "83", "87", "88", "92")):
        return "bj"
    return "sh"


def db_code(code6, prefix):
    """统一成项目里的库内代码形态：sh600000"""
    return (prefix or market_prefix(code6)) + code6


# 季度末：3/31、6/30、9/30、12/31
QUARTER_ENDS = ((3, 31), (6, 30), (9, 30), (12, 31))


def prev_quarter(report_date, steps=1):
    """报告期往前推 steps 个季度：20260930 -> 20260630 -> 20260331 -> 20251231"""
    text = norm_date(report_date)
    if not text:
        return report_date
    year = int(text[:4])
    month = int(text[4:6])
    months = [q[0] for q in QUARTER_ENDS]
    index = months.index(month) if month in months else len(months) - 1
    for _ in range(max(1, steps)):
        index -= 1
        if index < 0:
            index = len(months) - 1
            year -= 1
    mo, day = QUARTER_ENDS[index]
    return "%04d%02d%02d" % (year, mo, day)


def fetch_share_change(ak, code6):
    """巨潮股本变动 -> {报告期: 行dict}，单位已换算成股"""
    rows = {}
    try:
        df = ak.stock_share_change_cninfo(symbol=code6)
    except KeyError as e:
        # 巨潮那边没有这只股票的股本变动记录时，akshare 取「公告日期」列会 KeyError，
        # 这是「没数据」而不是「请求失败」，不记进失败数，否则新股会一直报红
        log("  · 无股本变动记录 %s (%s)" % (code6, e))
        return rows
    if df is None or len(df) == 0:
        return rows
    cols = set(df.columns)

    def pick(*names):
        for name in names:
            if name in cols:
                return name
        return None

    c_date = pick("变动日期", "公告日期")
    c_total = pick("总股本")
    c_float = pick("已流通股份")
    c_limit = pick("流通受限股份")
    c_reason = pick("变动原因")
    c_ann = pick("公告日期")
    if c_date is None:
        return rows

    for _, row in df.iterrows():
        date = norm_date(row.get(c_date))
        if not date:
            continue
        total = to_float(row.get(c_total)) if c_total else None
        float_a = to_float(row.get(c_float)) if c_float else None
        limit = to_float(row.get(c_limit)) if c_limit else None
        if total is None and float_a is None:
            continue
        item = {
            "report_date": date,
            "total_shares": None if total is None else total * WAN,
            "float_a_shares": None if float_a is None else float_a * WAN,
            "restricted_a_shares": None if limit is None else limit * WAN,
            "change_reason": "" if c_reason is None else str(row.get(c_reason) or "").strip(),
            "ann_date": norm_date(row.get(c_ann)) if c_ann else "",
            "source": "CNINFO",
        }
        rows[date] = item
    return rows


def fetch_top10_holder(ak, code6, report_date):
    """东财十大流通股东 -> 一行dict；报告期没数据就往前找最多 4 个季度"""
    symbol = market_prefix(code6) + code6
    date = report_date
    for _ in range(5):
        try:
            df = ak.stock_gdfx_free_top_10_em(symbol=symbol, date=date)
        except Exception:
            df = None
        if df is not None and len(df) > 0:
            cols = set(df.columns)
            c_shares = "持股数" if "持股数" in cols else None
            c_ratio = "占总流通股本持股比例" if "占总流通股本持股比例" in cols else None
            total_shares = 0.0
            total_ratio = 0.0
            top1 = None
            has_share = False
            has_ratio = False
            for _, row in df.iterrows():
                shares = to_float(row.get(c_shares)) if c_shares else None
                ratio = to_float(row.get(c_ratio)) if c_ratio else None
                if shares is not None:
                    total_shares += shares
                    has_share = True
                    if top1 is None:
                        top1 = shares
                if ratio is not None:
                    total_ratio += ratio
                    has_ratio = True
            return {
                "report_date": date,
                "top10_holder_shares": total_shares if has_share else None,
                "top10_holder_ratio": total_ratio if has_ratio else None,
                "top1_holder_shares": top1,
                "source": "EM",
            }
        date = prev_quarter(date)
    return None


def fetch_one(ak, code6, report_date):
    """抓一只股票：先股本变动(历年)，再十大流通股东(最近报告期)"""
    rows = {}
    errors = 0
    try:
        rows.update(fetch_share_change(ak, code6))
    except Exception as e:
        errors += 1
        log("  ! 股本变动失败 %s: %s" % (code6, str(e)[:100]))
    try:
        holder = fetch_top10_holder(ak, code6, report_date)
        if holder is not None:
            key = holder["report_date"]
            if key in rows:
                rows[key].update({k: v for k, v in holder.items()
                                  if k not in ("report_date",)})
                # 两个源都落在同一报告期：标记成合并来源，便于排查
                rows[key]["source"] = "CNINFO+EM" if rows[key].get("source") else "EM"
            else:
                rows[key] = holder
    except KeyError as e:
        log("  · 无十大流通股东数据 %s (%s)" % (code6, e))
    except Exception as e:
        errors += 1
        log("  ! 十大流通股东失败 %s: %s" % (code6, str(e)[:100]))
    return rows, errors


def read_codes(args):
    """取待抓取的股票代码列表"""
    codes = []
    if args.single:
        codes.extend(args.single.split(","))
    if args.codes:
        codes.extend(args.codes.split(","))
    if args.codes_file:
        with open(args.codes_file, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#"):
                    codes.append(line)
    result = []
    seen = set()
    for item in codes:
        code6 = plain_code(item)
        if not code6 or code6 in seen:
            continue
        seen.add(code6)
        result.append(code6)
    return result


def read_done_codes(path):
    """--resume：已经出现在结果文件里的股票不再重抓"""
    done = set()
    if not os.path.isfile(path):
        return done
    try:
        with open(path, "r", encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f):
                code = plain_code(row.get("code", ""))
                if code:
                    done.add(code)
    except Exception:
        pass
    return done


def write_csv(path, rows, append=False):
    directory = os.path.dirname(os.path.abspath(path))
    if directory and not os.path.isdir(directory):
        os.makedirs(directory)
    exists = os.path.isfile(path) and os.path.getsize(path) > 0
    mode = "a" if (append and exists) else "w"
    with open(path, mode, encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=OUT_COLUMNS, extrasaction="ignore")
        if mode == "w":
            writer.writeheader()
        for row in rows:
            writer.writerow(row)


def main():
    parser = argparse.ArgumentParser(description="AKShare 股本/股东数据抓取")
    parser.add_argument("--codes", help="逗号分隔的股票代码")
    parser.add_argument("--codes-file", help="股票代码文件，每行一个")
    parser.add_argument("--single", help="单只股票(调试)")
    parser.add_argument("--report-date", default=None, help="股东数据报告期 yyyyMMdd")
    parser.add_argument("--out", default="data/capital/akshare_capital.csv", help="输出 CSV")
    parser.add_argument("--threads", type=int, default=6, help="并发线程数")
    parser.add_argument("--resume", action="store_true", help="跳过结果文件里已有的股票")
    parser.add_argument("--check", action="store_true", help="只检测 akshare 环境")
    parser.add_argument("--print", dest="print_rows", action="store_true", help="结果打到 stdout")
    parser.add_argument("--max-errors", type=int, default=0, help="失败请求超过该数就中止，0 表示不限制")
    args = parser.parse_args()

    emit("PYTHON_EXE", sys.executable)

    try:
        import akshare as ak
    except Exception as e:
        log("导入 akshare 失败: %s" % e)
        log("请先安装: %s -m pip install akshare" % sys.executable)
        return 2
    emit("AKSHARE_VERSION", getattr(ak, "__version__", "unknown"))

    if args.check:
        log("akshare %s 可用，解释器 %s" % (getattr(ak, "__version__", "?"), sys.executable))
        return 0

    report_date = args.report_date or datetime.date.today().strftime("%Y%m%d")
    codes = read_codes(args)
    if not codes:
        log("没有可抓取的股票代码")
        return 3

    done = read_done_codes(args.out) if args.resume else set()
    pending = [c for c in codes if c not in done]
    log("待抓取 %d 只(已跳过 %d 只)，报告期 %s" % (len(pending), len(codes) - len(pending), report_date))

    lock = threading.Lock()
    all_rows = []
    error_count = [0]
    finished = [0]
    started = time.time()

    def worker(code6):
        rows, errors = fetch_one(ak, code6, report_date)
        with lock:
            for row in rows.values():
                row["code"] = db_code(code6, None)
                all_rows.append(row)
            error_count[0] += errors
            finished[0] += 1
            done_num = finished[0]
        if done_num % 20 == 0 or done_num == len(pending):
            spent = time.time() - started
            speed = done_num / spent if spent > 0 else 0
            log("PROGRESS %d/%d  %.1f 只/秒  已用时 %.0f 秒" % (
                done_num, len(pending), speed, spent))
        return errors

    with ThreadPoolExecutor(max_workers=max(1, args.threads)) as pool:
        futures = {pool.submit(worker, code): code for code in pending}
        for future in as_completed(futures):
            try:
                future.result()
            except Exception as e:
                log("  ! 线程异常 %s: %s" % (futures[future], str(e)[:100]))
                with lock:
                    error_count[0] += 1
            if args.max_errors and error_count[0] > args.max_errors:
                log("失败请求过多(%d)，提前结束" % error_count[0])
                break

    all_rows.sort(key=lambda r: (r.get("code", ""), r.get("report_date", "")))

    if args.print_rows:
        writer = csv.DictWriter(sys.stdout, fieldnames=OUT_COLUMNS, extrasaction="ignore")
        writer.writeheader()
        for row in all_rows:
            writer.writerow(row)

    if args.out:
        write_csv(args.out, all_rows, append=args.resume)
        emit("OUT_FILE", os.path.abspath(args.out))

    codes_written = len({r.get("code") for r in all_rows})
    emit("ROW_COUNT", len(all_rows))
    emit("CODE_COUNT", codes_written)
    emit("ERROR_COUNT", error_count[0])
    log("完成：%d 行 / %d 只股票 / %d 个失败请求，用时 %.0f 秒" % (
        len(all_rows), codes_written, error_count[0], time.time() - started))
    if args.out:
        emit("SUCCESS", os.path.abspath(args.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
