#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
通达信财报(gpcw)数据下载与解析脚本

流程:
    1. 通过 mootdx.Affair 获取服务器财报文件列表；
    2. 按文件名倒序，跳过大小 < 阈值的占位符文件；
    3. 指定 --report-date 时只处理该报告期；未指定时取最新有效文件；
    4. --check-only 只输出报告期信息(不下载)，否则下载 + 解压 + 解析；
    5. 解析结果写 CSV，同时写 meta(key=value)，供 Java 端动态建表/导入。

stdout 约定(Java 端按 KEY=VALUE 行解析):
    PYTHON_EXE=<实际解释器路径>
    AVAILABLE_DATES=<服务器可用报告期，逗号分隔，倒序>
    LATEST_FILE=<文件名>         如 gpcw20260930.zip
    LATEST_SIZE=<字节数>
    REPORT_DATE=<报告期>         如 20260930
    ROW_COUNT=<记录数>
    DOWNLOAD_SKIPPED=<0|1>
    CSV_FILE=<csv 绝对路径>
    SUCCESS:<csv 绝对路径>

用法:
    python read_tdx_finance.py --out-dir data/finance_download \
        --csv-file data/finance/tdx_finance.csv \
        --meta-file data/finance/tdx_finance.meta \
        [--report-date 202603 | --report-date 20260331] \
        [--check-only] [--list-dates] [--min-size 1024] [--debug]
"""
import argparse
import calendar
import csv
import datetime
import math
import os
import re
import sys
import zipfile
from dataclasses import asdict

MIN_VALID_SIZE_DEFAULT = 1024
CODE_CANDIDATES = ("code", "stock_code", "stockcode", "symbol", "sec_code")
REPORT_CANDIDATES = ("report_date", "reportdate", "report_period", "period", "date")
COL_CODE = "code"
COL_REPORT_DATE = "report_date"


# ---------------------------------------------------------------- 基础工具
def parse_report_date(name):
    """从 gpcwYYYYMMDD.zip 中取出报告期"""
    match = re.search(r"(\d{8})", name or "")
    return match.group(1) if match else ""


def normalize_report_arg(text):
    """把用户输入(yyyyMM / yyyyMMdd / yyyy-MM-dd)归一成报告期 yyyyMMdd"""
    digits = re.sub(r"\D", "", text or "")
    if len(digits) == 6:
        year = int(digits[:4])
        month = int(digits[4:6])
        if not (1 <= month <= 12):
            return ""
        if month <= 3:
            month = 3
        elif month <= 6:
            month = 6
        elif month <= 9:
            month = 9
        else:
            month = 12
        last_day = calendar.monthrange(year, month)[1]
        return "%04d%02d%02d" % (year, month, last_day)
    if len(digits) == 8:
        year = int(digits[:4])
        month = int(digits[4:6])
        day = int(digits[6:8])
        try:
            datetime.date(year, month, day)
        except ValueError:
            return ""
        return digits
    return ""


def pick_field(columns, candidates):
    for candidate in candidates:
        if candidate in columns:
            return candidate
    return None


def normalize_text(value):
    if value is None:
        return ""
    if isinstance(value, float) and (math.isnan(value) or math.isinf(value)):
        return ""
    text = str(value).strip().replace("\x00", "")
    if text.endswith(".0"):
        text = text[:-2]
    return text


def normalize_report_date(value):
    text = normalize_text(value).replace("-", "").replace("/", "")
    return text if text.isdigit() else ""


def record_to_dict(record):
    """兼容 dataclass / dict / 普通对象"""
    if isinstance(record, dict):
        return dict(record)
    try:
        return asdict(record)
    except TypeError:
        return dict(vars(record))


def write_meta(path, values):
    parent = os.path.dirname(os.path.abspath(path))
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        for key, value in values.items():
            handle.write("%s=%s\n" % (key, value))


def write_csv(path, columns, rows):
    parent = os.path.dirname(os.path.abspath(path))
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(columns)
        for row in rows:
            out = []
            for value in row:
                if value is None:
                    out.append("")
                elif isinstance(value, float):
                    if math.isnan(value) or math.isinf(value):
                        out.append("")
                    else:
                        out.append("%.6f" % value)
                else:
                    out.append(value)
            writer.writerow(out)


# ---------------------------------------------------------------- 文件定位
def find_latest_local(out_dir, min_size):
    """网络不可用时，用本地已下载的最新财报文件兜底"""
    latest = None
    if not os.path.isdir(out_dir):
        return None
    for name in os.listdir(out_dir):
        lower = name.lower()
        if not lower.endswith(".zip") or not lower.startswith("gpcw"):
            continue
        path = os.path.join(out_dir, name)
        try:
            size = os.path.getsize(path)
        except OSError:
            continue
        if size <= min_size:
            continue
        if latest is None or name > latest[0]:
            latest = (name, size, path)
    return latest


# ---------------------------------------------------------------- 解析结果整理
def build_output(records, report_date):
    """返回 (out_columns, out_rows, error)"""
    rows_dict = [record_to_dict(record) for record in records]
    if not rows_dict:
        return None, None, "解析结果为空"

    columns = []
    for record in rows_dict:
        for key in record.keys():
            if key not in columns:
                columns.append(key)

    code_field = pick_field(columns, CODE_CANDIDATES)
    report_field = pick_field(columns, REPORT_CANDIDATES)
    if code_field is None:
        return None, None, "解析结果中没有股票代码字段，实际字段: %s" % columns

    other_columns = [c for c in columns if c not in (code_field, report_field)]
    out_columns = [COL_CODE, COL_REPORT_DATE] + other_columns

    out_rows = []
    for record in rows_dict:
        code = normalize_text(record.get(code_field))
        if not code:
            continue
        date_text = normalize_report_date(record.get(report_field)) if report_field else ""
        if not date_text:
            date_text = report_date
        out_rows.append([code, date_text] + [record.get(c) for c in other_columns])

    return out_columns, out_rows, None


def detect_types(columns, rows):
    types = []
    for index, column in enumerate(columns):
        if column in (COL_CODE, COL_REPORT_DATE):
            types.append("TEXT")
            continue
        sample = []
        for row in rows[:300]:
            value = row[index]
            if value is None:
                continue
            if isinstance(value, str) and value.strip() == "":
                continue
            sample.append(value)
            if len(sample) >= 32:
                break
        if not sample:
            types.append("TEXT")
            continue
        numeric = True
        for value in sample:
            if isinstance(value, bool):
                numeric = False
                break
            try:
                float(value)
            except (TypeError, ValueError):
                numeric = False
                break
        types.append("REAL" if numeric else "TEXT")
    return types


# ---------------------------------------------------------------- 主流程
def main(argv=None):
    parser = argparse.ArgumentParser(description="通达信财报(gpcw)下载解析并导出 CSV")
    parser.add_argument("--out-dir", "-d", required=True, help="财报 zip 下载目录")
    parser.add_argument("--csv-file", "-o", required=True, help="输出 CSV 文件")
    parser.add_argument("--meta-file", "-m", required=True, help="输出 meta 元数据文件")
    parser.add_argument("--report-date", "-r", default=None,
                        help="指定报告期(yyyyMM 或 yyyyMMdd)，不指定则取最新")
    parser.add_argument("--check-only", action="store_true", help="只探测报告期，不下载不解析")
    parser.add_argument("--list-dates", action="store_true", help="只列出服务器可用报告期")
    parser.add_argument("--min-size", type=int, default=MIN_VALID_SIZE_DEFAULT,
                        help="有效文件最小字节数，低于该值视为占位符(默认 1024)")
    parser.add_argument("--debug", action="store_true", help="打印诊断信息")
    args = parser.parse_args(argv)

    out_dir = os.path.abspath(os.path.expanduser(args.out_dir))
    csv_file = os.path.abspath(os.path.expanduser(args.csv_file))
    meta_file = os.path.abspath(os.path.expanduser(args.meta_file))
    os.makedirs(out_dir, exist_ok=True)

    print("python:", sys.version.replace("\n", " "))
    print("PYTHON_EXE=%s" % sys.executable)

    target_date = ""
    if args.report_date:
        target_date = normalize_report_arg(args.report_date)
        if not target_date:
            print("ERROR: 财报季度格式不正确: %s (应为 yyyyMM 或 yyyyMMdd)" % args.report_date)
            return 8
        print("REQUEST_DATE=%s" % target_date)

    try:
        from mootdx.affair import Affair
    except Exception as exc:  # noqa: BLE001
        print("ERROR: 未能导入 mootdx(%s)，请先 pip install mootdx" % exc)
        return 7

    # ===== 1. 服务器文件列表 =====
    print("正在获取服务器财务文件列表...")
    files = None
    try:
        files = Affair.files()
    except Exception as exc:  # noqa: BLE001
        print("WARN: 获取服务器文件列表失败(%s)，尝试使用本地已下载文件" % exc)

    valid_files = []
    if files:
        ordered = sorted(files, key=lambda x: x.get("filename", ""), reverse=True)
        for item in ordered:
            name = item.get("filename", "")
            size = item.get("filesize", 0) or 0
            if size > args.min_size:
                valid_files.append((name, size))
        date_list = [parse_report_date(n) for n, _ in valid_files]
        date_list = [d for d in date_list if d]
        if date_list:
            print("AVAILABLE_DATES=%s" % ",".join(date_list))
        print("服务器有效文件数: %d" % len(valid_files))
        skipped = [item.get("filename", "") for item in ordered
                   if (item.get("filesize", 0) or 0) <= args.min_size]
        if skipped:
            print("已跳过 %d 个占位符文件: %s%s"
                  % (len(skipped), skipped[:3], "..." if len(skipped) > 3 else ""))

    # ===== 2. 仅列出报告期 =====
    if args.list_dates:
        if not valid_files:
            print("ERROR: 服务器上没有可用财报文件")
            return 3
        print("服务器可用报告期数: %d" % len(valid_files))
        return 0

    # ===== 3. 选择目标文件 =====
    latest_name = ""
    latest_size = 0

    if target_date:
        for name, size in valid_files:
            if parse_report_date(name) == target_date:
                latest_name, latest_size = name, size
                break
        if not latest_name:
            local_try = os.path.join(out_dir, "gpcw%s.zip" % target_date)
            if os.path.isfile(local_try) and os.path.getsize(local_try) > args.min_size:
                latest_name = "gpcw%s.zip" % target_date
                latest_size = os.path.getsize(local_try)
                print("LOCAL_FILE=%s" % latest_name)
            else:
                print("ERROR: 服务器上没有报告期 %s 的财报文件" % target_date)
                if valid_files:
                    print("提示: 服务器可用报告期=%s"
                          % ",".join([parse_report_date(n) for n, _ in valid_files
                                      if parse_report_date(n)][:12]))
                return 3
    elif valid_files:
        latest_name, latest_size = valid_files[0]
    else:
        local_latest = find_latest_local(out_dir, args.min_size)
        if not local_latest:
            print("ERROR: 无法获取财报文件列表，本地也没有可用的财报 zip")
            return 3
        latest_name, latest_size, _local_path = local_latest
        print("LOCAL_FILE=%s" % latest_name)

    report_date = parse_report_date(latest_name)
    if not report_date:
        print("ERROR: 无法从文件名中识别报告期: %s" % latest_name)
        return 3

    print("LATEST_FILE=%s" % latest_name)
    print("LATEST_SIZE=%d" % latest_size)
    print("REPORT_DATE=%s" % report_date)
    print("目标财报文件: %s (%d bytes)" % (latest_name, latest_size))

    meta_values = {
        "REPORT_DATE": report_date,
        "SOURCE_FILE": latest_name,
        "FILE_SIZE": latest_size,
        "ROW_COUNT": 0,
        "DOWNLOAD_SKIPPED": 0,
        "CHECK_ONLY": 1 if args.check_only else 0,
    }

    # ===== 4. 只探测 =====
    if args.check_only:
        write_meta(meta_file, meta_values)
        print("探测完成，报告期: %s" % report_date)
        return 0

    # ===== 5. 下载 =====
    local_path = os.path.join(out_dir, latest_name)
    need_download = True
    if os.path.exists(local_path):
        local_size = os.path.getsize(local_path)
        if latest_size > 0 and local_size == latest_size:
            need_download = False
            print("本地已存在且大小一致，跳过下载")
        elif latest_size <= 0 and local_size > args.min_size:
            need_download = False
            print("服务器未返回大小信息，本地文件可用，跳过下载")
        else:
            print("大小不一致(本地 %d vs 服务器 %d)，重新下载" % (local_size, latest_size))

    if need_download:
        print("正在下载 %s ..." % latest_name)
        Affair.fetch(downdir=out_dir, filename=latest_name)
        print("下载完成: %s" % local_path)
    meta_values["DOWNLOAD_SKIPPED"] = 0 if need_download else 1

    if not os.path.isfile(local_path):
        print("ERROR: 下载后仍找不到文件: %s" % local_path)
        return 4

    # ===== 6. 解压 + 解析 =====
    print("正在解析...")
    try:
        with zipfile.ZipFile(local_path) as archive:
            dat_name = next((n for n in archive.namelist() if n.lower().endswith(".dat")), None)
            if not dat_name:
                print("ERROR: 压缩包中未找到 .dat 文件: %s" % local_path)
                return 4
            dat_bytes = archive.read(dat_name)
    except zipfile.BadZipFile as exc:
        print("ERROR: 财报压缩包损坏: %s" % exc)
        return 4

    if len(dat_bytes) < args.min_size:
        print("ERROR: .dat 文件过小(%d 字节)，可能仍是占位符" % len(dat_bytes))
        return 5

    try:
        from pytdxdata.codec import parse_financial_dat
    except Exception as exc:  # noqa: BLE001
        print("ERROR: 未能导入 pytdxdata(%s)，请先安装 pytdxdata" % exc)
        return 7

    try:
        records = parse_financial_dat(dat_bytes)
    except Exception as exc:  # noqa: BLE001
        print("ERROR: 解析财报 .dat 失败: %s: %s" % (type(exc).__name__, exc))
        return 6

    if not records:
        print("ERROR: 解析结果为空，请确认 .dat 格式是否已变化")
        return 6

    out_columns, out_rows, error = build_output(records, report_date)
    if error:
        print("ERROR: %s" % error)
        return 6
    if not out_rows:
        print("ERROR: 解析结果中没有有效记录")
        return 6

    types = detect_types(out_columns, out_rows)

    # ===== 7. 写 CSV + meta =====
    write_csv(csv_file, out_columns, out_rows)
    meta_values.update({
        "ROW_COUNT": len(out_rows),
        "COLUMNS": ",".join(out_columns),
        "COLUMN_TYPES": ",".join(types),
        "CSV_FILE": csv_file,
    })
    write_meta(meta_file, meta_values)

    print("ROW_COUNT=%d" % len(out_rows))
    print("CSV_FILE=%s" % csv_file)
    print("解析成功: %d 条记录, %d 个字段" % (len(out_rows), len(out_columns)))
    if args.debug:
        print("COLUMNS:", out_columns[:20])
        print("COLUMN_TYPES:", types[:20])
        for row in out_rows[:3]:
            print("sample:", row[:10])
    print("SUCCESS:%s" % csv_file)
    return 0


if __name__ == "__main__":
    sys.exit(main())
