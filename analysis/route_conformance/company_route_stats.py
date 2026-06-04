#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Company route conformance statistics.

Spyder usage:
  1. Edit RUN_CONFIG below.
  2. Run this file directly.

Command line usage:
  python company_route_stats.py --unit-id 45c945e950bc4da8a8f84bf23b208656
  python company_route_stats.py --unit-id xxx --start-date 2026-04-01 --end-date 2026-04-30

Outputs three Excel-friendly CSV files:
  - company_summary.csv
  - route_summary.csv
  - trip_detail.csv
"""

from __future__ import annotations

import argparse
import csv
import os
import sys
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import DefaultDict, Dict, Iterable, List, Optional, Sequence, Set, Tuple

import pymysql


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

# Edit this block when running directly in Spyder.
# DB connection is already filled in below; normally you only edit unit_id/date/out_dir.
RUN_CONFIG = {
    # "unit_id": "45c945e950bc4da8a8f84bf23b208656",
    # "unit_id": "0c01317c46614f368405a22051bc4d50",
    "unit_id": "a24ddbe0aaa547658f4d9624e56499bf",

    "start_date": "",
    "end_date": "",
    "route_ids": "",
    "match_type": 0,
    "is_route_facility": 1,
    "w_f1": 0.6,
    "w_lcs": 0.4,
    "out_dir": os.path.join(SCRIPT_DIR, "company_route_stats_out"),
}

DB_CONFIG = {
    "host": "zhhw-rds-xm.mysql.rds.aliyuncs.com",
    "port": 3306,
    "user": "cleanpro",
    "password": "zhj521%@!",
    "database": "ljszy_new",
}


@dataclass
class RouteRecordRow:
    id: int
    route_id: Optional[int]
    route_name: str
    unit_id: str
    car_start_time: Optional[datetime]
    car_end_time: Optional[datetime]


def use_run_config() -> bool:
    return len(sys.argv) == 1 or "spyder_kernels" in sys.modules


def parse_date(s: str) -> date:
    return datetime.strptime(s, "%Y-%m-%d").date()


def default_date_range() -> Tuple[date, date]:
    end = date.today()
    start = end - timedelta(days=29)
    return start, end


def month_suffixes(d0: date, d1: date) -> List[str]:
    cur = date(d0.year, d0.month, 1)
    end = date(d1.year, d1.month, 1)
    out: List[str] = []
    while cur <= end:
        out.append(f"{cur.year:04d}{cur.month:02d}")
        if cur.month == 12:
            cur = date(cur.year + 1, 1, 1)
        else:
            cur = date(cur.year, cur.month + 1, 1)
    return out


def split_ints(s: str) -> Optional[List[int]]:
    vals = [x.strip() for x in (s or "").split(",") if x.strip()]
    return [int(x) for x in vals] if vals else None


def connect() -> pymysql.connections.Connection:
    return pymysql.connect(
        host=DB_CONFIG["host"],
        port=int(DB_CONFIG["port"]),
        user=DB_CONFIG["user"],
        password=DB_CONFIG["password"],
        database=DB_CONFIG["database"],
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )


def table_exists(conn, table: str) -> bool:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT 1 FROM information_schema.tables "
            "WHERE table_schema = DATABASE() AND table_name = %s LIMIT 1",
            (table,),
        )
        return cur.fetchone() is not None


def chunks(items: Sequence[int], size: int = 800) -> Iterable[Sequence[int]]:
    for i in range(0, len(items), size):
        yield items[i : i + size]


def fingerprint(seq: Sequence[int]) -> str:
    return "-".join(str(x) for x in seq)


def lcs_sequence(a: Sequence[int], b: Sequence[int]) -> List[int]:
    if not a or not b:
        return []
    n, m = len(a), len(b)
    dp = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        ai = a[i - 1]
        for j in range(1, m + 1):
            if ai == b[j - 1]:
                dp[i][j] = dp[i - 1][j - 1] + 1
            else:
                dp[i][j] = dp[i - 1][j] if dp[i - 1][j] >= dp[i][j - 1] else dp[i][j - 1]

    i, j = n, m
    out: List[int] = []
    while i > 0 and j > 0:
        if a[i - 1] == b[j - 1]:
            out.append(a[i - 1])
            i -= 1
            j -= 1
        elif dp[i - 1][j] >= dp[i][j - 1]:
            i -= 1
        else:
            j -= 1
    out.reverse()
    return out


def score_metrics(
    plan_seq: Sequence[int],
    actual_seq: Sequence[int],
    w_f1: float,
    w_lcs: float,
) -> Dict[str, object]:
    plan_set = set(plan_seq)
    actual_set = set(actual_seq)
    hit_set = plan_set & actual_set
    actual_hit_count = len(hit_set)
    plan_covered_count = len(hit_set)

    if not plan_set and not actual_set:
        precision = recall = f1 = 1.0
    else:
        precision = actual_hit_count / len(actual_set) if actual_set else 0.0
        recall = plan_covered_count / len(plan_set) if plan_set else 0.0
        f1 = 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)

    lcs_seq = lcs_sequence(plan_seq, actual_seq)
    lcs_len = len(lcs_seq)
    if not plan_seq and not actual_seq:
        lcs_ratio = 1.0
    elif not plan_seq or not actual_seq:
        lcs_ratio = 0.0
    else:
        lcs_ratio = (2.0 * lcs_len) / (len(plan_seq) + len(actual_seq))

    overall = w_f1 * f1 + w_lcs * lcs_ratio
    return {
        "actual_hit_plan_count": actual_hit_count,
        "plan_covered_by_actual_count": plan_covered_count,
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "lcs_len": lcs_len,
        "lcs_ratio": lcs_ratio,
        "lcs_fingerprint": fingerprint(lcs_seq),
        "overall": overall,
    }


def fetch_company_routes(
    conn,
    unit_id: str,
    start: date,
    end: date,
    route_ids: Optional[Sequence[int]],
) -> Tuple[Dict[int, str], str]:
    route_map: Dict[int, str] = {}
    if table_exists(conn, "ljszy_route_info"):
        cond = ["been_deleted = 0", "department_id = %s", "(data_type = 0 OR data_type IS NULL)"]
        args: List[object] = [unit_id]
        if route_ids:
            ph = ",".join(["%s"] * len(route_ids))
            cond.append(f"id IN ({ph})")
            args.extend(route_ids)
        sql = (
            "SELECT id, name AS route_name FROM ljszy_route_info "
            f"WHERE {' AND '.join(cond)} ORDER BY id"
        )
        try:
            with conn.cursor() as cur:
                cur.execute(sql, args)
                for r in cur.fetchall():
                    if r.get("id") is not None:
                        route_map[int(r["id"])] = r.get("route_name") or ""
            return route_map, "ljszy_route_info.department_id"
        except pymysql.MySQLError:
            route_map = {}

    cond = ["been_deleted = 0", "unit_id = %s", "car_start_time >= %s", "car_start_time < %s"]
    args2: List[object] = [
        unit_id,
        datetime.combine(start, datetime.min.time()),
        datetime.combine(end + timedelta(days=1), datetime.min.time()),
    ]
    if route_ids:
        ph = ",".join(["%s"] * len(route_ids))
        cond.append(f"route_id IN ({ph})")
        args2.extend(route_ids)
    sql2 = (
        "SELECT route_id AS id, MAX(route_name) AS route_name "
        "FROM ljszy_route_record "
        f"WHERE {' AND '.join(cond)} AND route_id IS NOT NULL GROUP BY route_id ORDER BY route_id"
    )
    with conn.cursor() as cur:
        cur.execute(sql2, args2)
        for r in cur.fetchall():
            if r.get("id") is not None:
                route_map[int(r["id"])] = r.get("route_name") or ""
    return route_map, "ljszy_route_record in selected date range"


def fetch_route_records(
    conn,
    unit_id: str,
    start: date,
    end: date,
    route_ids: Optional[Sequence[int]],
) -> List[RouteRecordRow]:
    cond = ["been_deleted = 0", "unit_id = %s", "car_start_time >= %s", "car_start_time < %s"]
    args: List[object] = [
        unit_id,
        datetime.combine(start, datetime.min.time()),
        datetime.combine(end + timedelta(days=1), datetime.min.time()),
    ]
    if route_ids:
        ph = ",".join(["%s"] * len(route_ids))
        cond.append(f"route_id IN ({ph})")
        args.extend(route_ids)
    sql = (
        "SELECT id, route_id, route_name, unit_id, car_start_time, car_end_time "
        "FROM ljszy_route_record "
        f"WHERE {' AND '.join(cond)} ORDER BY route_id, car_start_time, id"
    )
    with conn.cursor() as cur:
        cur.execute(sql, args)
        rows = cur.fetchall()
    return [
        RouteRecordRow(
            id=int(r["id"]),
            route_id=int(r["route_id"]) if r.get("route_id") is not None else None,
            route_name=r.get("route_name") or "",
            unit_id=r.get("unit_id") or "",
            car_start_time=r.get("car_start_time"),
            car_end_time=r.get("car_end_time"),
        )
        for r in rows
    ]


def fetch_plan_sequences(conn, route_ids: Sequence[int]) -> Dict[int, List[int]]:
    if not route_ids:
        return {}
    out: Dict[int, List[int]] = defaultdict(list)
    for part in chunks(list(route_ids)):
        ph = ",".join(["%s"] * len(part))
        sql = (
            "SELECT route_id, fac_id AS fid "
            "FROM ljszy_route_fac_banding "
            f"WHERE been_deleted = 0 AND route_id IN ({ph}) "
            "ORDER BY route_id, IFNULL(order_num, 999999), id"
        )
        with conn.cursor() as cur:
            cur.execute(sql, list(part))
            for r in cur.fetchall():
                if r.get("route_id") is not None and r.get("fid") is not None:
                    out[int(r["route_id"])].append(int(r["fid"]))
    return dict(out)


def fetch_actual_sequences(
    conn,
    route_record_ids: Sequence[int],
    months: Sequence[str],
    match_type: int,
    is_route_facility: int,
) -> Dict[int, List[int]]:
    out_rows: DefaultDict[int, List[Tuple[datetime, int, int]]] = defaultdict(list)
    if not route_record_ids:
        return {}

    existing_month_tables = [f"ljszy_route_facility_record_{m}" for m in months if table_exists(conn, f"ljszy_route_facility_record_{m}")]
    tables = existing_month_tables
    if not tables and table_exists(conn, "ljszy_route_facility_record"):
        tables = ["ljszy_route_facility_record"]

    for table in tables:
        for part in chunks(list(route_record_ids)):
            ph = ",".join(["%s"] * len(part))
            sql = (
                "SELECT route_record_id, facility_id AS fid, "
                "COALESCE(entry_point_time, create_time) AS ts, id AS rid "
                f"FROM {table} "
                f"WHERE been_deleted = 0 AND route_record_id IN ({ph}) "
                "AND facility_match_type = %s AND is_route_facility = %s"
            )
            args: List[object] = list(part) + [match_type, is_route_facility]
            with conn.cursor() as cur:
                cur.execute(sql, args)
                for r in cur.fetchall():
                    if r.get("route_record_id") is None or r.get("fid") is None:
                        continue
                    ts = r.get("ts") or datetime.min
                    out_rows[int(r["route_record_id"])].append((ts, int(r["rid"]), int(r["fid"])))

    result: Dict[int, List[int]] = {}
    for rr_id, rows in out_rows.items():
        rows.sort(key=lambda x: (x[0], x[1]))
        result[rr_id] = [fid for _ts, _rid, fid in rows]
    return result


def parse_args() -> argparse.Namespace:
    if use_run_config():
        return argparse.Namespace(**RUN_CONFIG)

    start_default, end_default = default_date_range()
    p = argparse.ArgumentParser(description="company route conformance statistics")
    p.add_argument("--unit-id", required=True, help="target project company unit_id")
    p.add_argument("--start-date", default=start_default.isoformat(), help="YYYY-MM-DD, default: today - 29 days")
    p.add_argument("--end-date", default=end_default.isoformat(), help="YYYY-MM-DD, default: today")
    p.add_argument("--route-ids", default="", help="optional route_id list, comma separated")
    p.add_argument("--match-type", type=int, default=0)
    p.add_argument("--is-route-facility", type=int, default=1)
    p.add_argument("--w-f1", type=float, default=0.6)
    p.add_argument("--w-lcs", type=float, default=0.4)
    p.add_argument("--out-dir", default=os.path.join(SCRIPT_DIR, "company_route_stats_out"))
    return p.parse_args()


def ensure_dir(path: str) -> None:
    os.makedirs(path, exist_ok=True)


def write_csv(path: str, rows: List[Dict[str, object]], fields: Sequence[str]) -> None:
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=list(fields))
        w.writeheader()
        w.writerows(rows)


def with_chinese_headers(rows: List[Dict[str, object]], header_map: Dict[str, str]) -> List[Dict[str, object]]:
    return [{header_map.get(k, k): v for k, v in row.items()} for row in rows]


def avg(values: Sequence[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def main() -> int:
    args = parse_args()
    unit_id = (args.unit_id or "").strip()
    if not unit_id:
        print("unit_id is required", file=sys.stderr)
        return 2

    if args.start_date and args.end_date:
        start = parse_date(args.start_date)
        end = parse_date(args.end_date)
    else:
        start, end = default_date_range()
    if end < start:
        print("end-date cannot be earlier than start-date", file=sys.stderr)
        return 2

    route_ids = split_ints(args.route_ids)
    w_sum = float(args.w_f1) + float(args.w_lcs)
    if abs(w_sum) < 1e-12:
        print("w_f1 and w_lcs cannot both be zero", file=sys.stderr)
        return 2
    w_f1 = float(args.w_f1) / w_sum
    w_lcs = float(args.w_lcs) / w_sum
    out_dir = os.path.abspath(args.out_dir)
    ensure_dir(out_dir)

    conn = connect()
    try:
        company_routes, route_count_source = fetch_company_routes(conn, unit_id, start, end, route_ids)
        records = fetch_route_records(conn, unit_id, start, end, route_ids)
        record_route_ids = sorted({r.route_id for r in records if r.route_id is not None})
        all_route_ids = sorted(set(company_routes.keys()) | set(record_route_ids))
        plan_map = fetch_plan_sequences(conn, all_route_ids)
        actual_map = fetch_actual_sequences(
            conn,
            [r.id for r in records],
            month_suffixes(start, end),
            int(args.match_type),
            int(args.is_route_facility),
        )

        trip_rows: List[Dict[str, object]] = []
        route_metric_values: DefaultDict[int, Dict[str, List[float]]] = defaultdict(
            lambda: {"f1": [], "lcs_ratio": [], "overall": []}
        )
        route_fingerprints: DefaultDict[int, Set[str]] = defaultdict(set)
        route_names: Dict[int, str] = dict(company_routes)

        for rr in records:
            if rr.route_id is None:
                continue
            rid = rr.route_id
            if rr.route_name:
                route_names[rid] = rr.route_name

            plan_seq = plan_map.get(rid, [])
            actual_seq = actual_map.get(rr.id, [])
            actual_fp = fingerprint(actual_seq)
            metrics = score_metrics(plan_seq, actual_seq, w_f1, w_lcs)
            route_metric_values[rid]["f1"].append(float(metrics["f1"]))
            route_metric_values[rid]["lcs_ratio"].append(float(metrics["lcs_ratio"]))
            route_metric_values[rid]["overall"].append(float(metrics["overall"]))
            if actual_fp:
                route_fingerprints[rid].add(actual_fp)

            trip_rows.append(
                {
                    "unit_id": unit_id,
                    "route_record_id": rr.id,
                    "route_id": rid,
                    "route_name": route_names.get(rid, ""),
                    "car_start_time": rr.car_start_time.isoformat(sep=" ") if rr.car_start_time else "",
                    "car_end_time": rr.car_end_time.isoformat(sep=" ") if rr.car_end_time else "",
                    "plan_point_count": len(plan_seq),
                    "actual_point_count": len(actual_seq),
                    "actual_hit_plan_count": metrics["actual_hit_plan_count"],
                    "plan_covered_by_actual_count": metrics["plan_covered_by_actual_count"],
                    "precision": round(float(metrics["precision"]), 6),
                    "recall": round(float(metrics["recall"]), 6),
                    "f1": round(float(metrics["f1"]), 6),
                    "lcs_len": metrics["lcs_len"],
                    "lcs_ratio": round(float(metrics["lcs_ratio"]), 6),
                    "overall": round(float(metrics["overall"]), 6),
                    "plan_fingerprint": fingerprint(plan_seq),
                    "actual_fingerprint": actual_fp,
                    "lcs_fingerprint": metrics["lcs_fingerprint"],
                }
            )

        route_rows: List[Dict[str, object]] = []
        for rid in all_route_ids:
            vals = route_metric_values[rid]
            f1_vals = vals["f1"]
            lcs_vals = vals["lcs_ratio"]
            overall_vals = vals["overall"]
            route_rows.append(
                {
                    "unit_id": unit_id,
                    "route_id": rid,
                    "route_name": route_names.get(rid, ""),
                    "plan_point_count": len(plan_map.get(rid, [])),
                    "trip_count": len(overall_vals),
                    "distinct_fingerprint_count": len(route_fingerprints[rid]),
                    "avg_f1": round(avg(f1_vals), 6),
                    "max_f1": round(max(f1_vals), 6) if f1_vals else 0.0,
                    "avg_lcs_ratio": round(avg(lcs_vals), 6),
                    "max_lcs_ratio": round(max(lcs_vals), 6) if lcs_vals else 0.0,
                    "avg_overall": round(avg(overall_vals), 6),
                    "max_overall": round(max(overall_vals), 6) if overall_vals else 0.0,
                }
            )

        all_f1 = [float(r["f1"]) for r in trip_rows]
        all_lcs = [float(r["lcs_ratio"]) for r in trip_rows]
        all_overall = [float(r["overall"]) for r in trip_rows]
        all_fps = {str(r["actual_fingerprint"]) for r in trip_rows if r["actual_fingerprint"]}
        company_rows = [
            {
                "unit_id": unit_id,
                "start_date": start.isoformat(),
                "end_date": end.isoformat(),
                "route_count": len(company_routes),
                "route_count_source": route_count_source,
                "route_with_trip_count": len({r["route_id"] for r in trip_rows}),
                "trip_count": len(trip_rows),
                "distinct_fingerprint_count": len(all_fps),
                "avg_f1": round(avg(all_f1), 6),
                "max_f1": round(max(all_f1), 6) if all_f1 else 0.0,
                "avg_lcs_ratio": round(avg(all_lcs), 6),
                "max_lcs_ratio": round(max(all_lcs), 6) if all_lcs else 0.0,
                "company_overall_score": round(avg(all_overall), 6),
                "max_overall": round(max(all_overall), 6) if all_overall else 0.0,
                "w_f1": round(w_f1, 6),
                "w_lcs": round(w_lcs, 6),
                "match_type": int(args.match_type),
                "is_route_facility": int(args.is_route_facility),
            }
        ]

        company_path = os.path.join(out_dir, "company_summary.csv")
        route_path = os.path.join(out_dir, "route_summary.csv")
        trip_path = os.path.join(out_dir, "trip_detail.csv")

        company_headers = {
            "unit_id": "项目公司ID",
            "start_date": "开始日期",
            "end_date": "结束日期",
            "route_count": "路线总数",
            "route_count_source": "路线数来源",
            "route_with_trip_count": "有流水路线数",
            "trip_count": "流水总数",
            "distinct_fingerprint_count": "不同流水指纹数",
            "avg_f1": "公司平均F1",
            "max_f1": "公司最高F1",
            "avg_lcs_ratio": "公司平均LCS比例",
            "max_lcs_ratio": "公司最高LCS比例",
            "company_overall_score": "公司综合得分",
            "max_overall": "公司最高综合得分",
            "w_f1": "F1权重",
            "w_lcs": "LCS权重",
            "match_type": "点位匹配类型",
            "is_route_facility": "是否路线点位",
        }
        route_headers = {
            "unit_id": "项目公司ID",
            "route_id": "路线ID",
            "route_name": "路线名称",
            "plan_point_count": "规划点位数",
            "trip_count": "流水数",
            "distinct_fingerprint_count": "不同流水指纹数",
            "avg_f1": "平均F1",
            "max_f1": "最高F1",
            "avg_lcs_ratio": "平均LCS比例",
            "max_lcs_ratio": "最高LCS比例",
            "avg_overall": "平均综合得分",
            "max_overall": "最高综合得分",
        }
        trip_headers = {
            "unit_id": "项目公司ID",
            "route_record_id": "流水记录ID",
            "route_id": "路线ID",
            "route_name": "路线名称",
            "car_start_time": "作业开始时间",
            "car_end_time": "作业结束时间",
            "plan_point_count": "规划点位数",
            "actual_point_count": "实际点位数",
            "actual_hit_plan_count": "实际点位命中规划数",
            "plan_covered_by_actual_count": "规划点位被实际覆盖数",
            "precision": "准确率",
            "recall": "召回率",
            "f1": "F1",
            "lcs_len": "最长重合长度",
            "lcs_ratio": "LCS比例",
            "overall": "综合得分",
            "plan_fingerprint": "规划点位指纹",
            "actual_fingerprint": "实际点位指纹",
            "lcs_fingerprint": "最长重合点位指纹",
        }

        write_csv(company_path, with_chinese_headers(company_rows, company_headers), company_headers.values())
        write_csv(
            route_path,
            with_chinese_headers(route_rows, route_headers),
            route_headers.values(),
        )
        write_csv(
            trip_path,
            with_chinese_headers(trip_rows, trip_headers),
            trip_headers.values(),
        )

        print(f"company summary: {company_path}")
        print(f"route summary:   {route_path}")
        print(f"trip detail:     {trip_path}")
        print(f"routes={len(company_routes)}, trips={len(trip_rows)}, distinct_fingerprints={len(all_fps)}")
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
