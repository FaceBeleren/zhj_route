#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
路线流水 vs 岗位规划 吻合度批处理脚本（直连 MySQL）。

用法示例（环境变量）：
  set LJSZY_DB_HOST=192.168.1.215
  set LJSZY_DB_PORT=3306
  set LJSZY_DB_USER=root
  set LJSZY_DB_PASSWORD=xxx
  set LJSZY_DB_NAME=ljszy_new
  python route_conformance.py --unit-ids id1,id2 --start-date 2026-04-01 --end-date 2026-04-14 --out-csv summary.csv

说明：
  - 规划路线：ljszy_route_fac_banding（route_id），按 order_num 取 fac_id 序列。
  - 实际流水：ljszy_route_record + 按月分表 ljszy_route_facility_record_yyyyMM。
  - 实际过滤口径（可调）：facility_match_type=0 且 is_route_facility=1。
  - 单条流水输出：precision / recall / F1、LCS 比率、加权 overall（默认 0.6*F1+0.4*LCS）。
  - 路线聚合：该 route_id 下所有流水的 mean(overall)、高分占比（overall>=0.8）、样本数。
"""

from __future__ import annotations

import argparse
import csv
import os
import sys
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Dict, List, Optional, Sequence, Set, Tuple

import pymysql


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

# Edit this block when running the file directly in Spyder.
# Command-line arguments still override these values.
RUN_CONFIG = {
    "unit_ids": "",
    "route_ids": "",
    "start_date": "2026-04-01",
    "end_date": "2026-04-30",
    "match_type": 0,
    "is_route_facility": 1,
    "w_f1": 0.6,
    "w_lcs": 0.4,
    "high_threshold": 0.8,
    "out_detail": os.path.join(SCRIPT_DIR, "route_conformance_detail.csv"),
    "out_summary": os.path.join(SCRIPT_DIR, "route_conformance_summary.csv"),
}


def env(key: str, default: Optional[str] = None) -> Optional[str]:
    v = os.environ.get(key)
    return v if v is not None and v != "" else default


def use_run_config() -> bool:
    return len(sys.argv) == 1 or "spyder_kernels" in sys.modules


def month_suffixes(d0: date, d1: date) -> List[str]:
    """闭区间 [d0, d1] 覆盖到的所有 yyyyMM。"""
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


def lcs_len(a: Sequence[int], b: Sequence[int]) -> int:
    """最长公共子序列长度（O(n*m)）。"""
    if not a or not b:
        return 0
    n, m = len(a), len(b)
    dp = [0] * (m + 1)
    for i in range(1, n + 1):
        prev = 0
        for j in range(1, m + 1):
            tmp = dp[j]
            if a[i - 1] == b[j - 1]:
                dp[j] = prev + 1
            else:
                dp[j] = max(dp[j], dp[j - 1])
            prev = tmp
    return dp[m]


def lcs_ratio(plan: Sequence[int], actual: Sequence[int]) -> float:
    if not plan and not actual:
        return 1.0
    if not plan or not actual:
        return 0.0
    l = lcs_len(plan, actual)
    return (2.0 * l) / (len(plan) + len(actual))


def set_metrics(plan: Set[int], actual: Set[int]) -> Tuple[float, float, float]:
    if not plan and not actual:
        return 1.0, 1.0, 1.0
    inter = len(plan & actual)
    if not plan:
        return 0.0, 1.0 if not actual else 0.0, 0.0
    if not actual:
        return 1.0, 0.0, 0.0
    recall = inter / len(plan)
    precision = inter / len(actual)
    if precision + recall == 0:
        f1 = 0.0
    else:
        f1 = 2 * precision * recall / (precision + recall)
    return precision, recall, f1


@dataclass
class RouteRecordRow:
    id: int
    route_id: Optional[int]
    route_name: Optional[str]
    unit_id: Optional[str]
    car_start_time: Optional[datetime]
    car_end_time: Optional[datetime]


def connect() -> pymysql.connections.Connection:
    host = env("LJSZY_DB_HOST", "zhhw-rds-xm.mysql.rds.aliyuncs.com")
    port = int(env("LJSZY_DB_PORT", "3306") or "3306")
    user = env("LJSZY_DB_USER", "cleanpro")
    password = env("LJSZY_DB_PASSWORD", "") or "zhj521%@!"
    database = env("LJSZY_DB_NAME", "ljszy_new")
    return pymysql.connect(
        host=host,
        port=port,
        user=user,
        password=password,
        database=database,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )


def fetch_plan_sequence(conn, route_id: int) -> List[int]:
    sql = """
        SELECT fac_id AS fid
        FROM ljszy_route_fac_banding
        WHERE been_deleted = 0 AND route_id = %s
        ORDER BY IFNULL(order_num, 999999), id
    """
    with conn.cursor() as cur:
        cur.execute(sql, (route_id,))
        rows = cur.fetchall()
    return [int(r["fid"]) for r in rows if r.get("fid") is not None]


def table_exists(conn, table: str) -> bool:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = %s LIMIT 1",
            (table,),
        )
        return cur.fetchone() is not None


def fetch_actual_sequence(
    conn,
    route_record_id: int,
    months: Sequence[str],
    match_type: int,
    is_route_facility: int,
) -> List[int]:
    """跨月 union 各分表；若无分表则回退主表 ljszy_route_facility_record。"""
    sub_parts: List[str] = []
    args2: List[object] = []
    for m in months:
        t = f"ljszy_route_facility_record_{m}"
        if not table_exists(conn, t):
            continue
        sub_parts.append(
            f"""
            SELECT facility_id AS fid,
                   COALESCE(entry_point_time, create_time) AS ts,
                   id AS rid
            FROM {t}
            WHERE been_deleted = 0
              AND route_record_id = %s
              AND facility_match_type = %s
              AND is_route_facility = %s
            """
        )
        args2.extend([route_record_id, match_type, is_route_facility])

    if not sub_parts:
        t = "ljszy_route_facility_record"
        if table_exists(conn, t):
            sql = f"""
                SELECT facility_id AS fid
                FROM {t}
                WHERE been_deleted = 0
                  AND route_record_id = %s
                  AND facility_match_type = %s
                  AND is_route_facility = %s
                ORDER BY COALESCE(entry_point_time, create_time), id
            """
            with conn.cursor() as cur:
                cur.execute(sql, (route_record_id, match_type, is_route_facility))
                rows = cur.fetchall()
            return [int(r["fid"]) for r in rows if r.get("fid") is not None]
        return []

    sql = "SELECT fid FROM (" + " UNION ALL ".join(sub_parts) + ") u ORDER BY ts, rid"
    with conn.cursor() as cur:
        cur.execute(sql, args2)
        rows = cur.fetchall()
    return [int(r["fid"]) for r in rows if r.get("fid") is not None]


def fetch_route_records(
    conn,
    unit_ids: Sequence[str],
    start: date,
    end: date,
    route_ids: Optional[Sequence[int]],
) -> List[RouteRecordRow]:
    cond = ["rr.been_deleted = 0", "rr.car_start_time >= %s", "rr.car_start_time < %s"]
    args: List[object] = [datetime.combine(start, datetime.min.time()), datetime.combine(end + timedelta(days=1), datetime.min.time())]
    if unit_ids:
        placeholders = ",".join(["%s"] * len(unit_ids))
        cond.append(f"rr.unit_id IN ({placeholders})")
        args.extend(unit_ids)
    if route_ids:
        placeholders = ",".join(["%s"] * len(route_ids))
        cond.append(f"rr.route_id IN ({placeholders})")
        args.extend(route_ids)
    where = " AND ".join(cond)
    sql = f"""
        SELECT rr.id, rr.route_id, rr.route_name, rr.unit_id, rr.car_start_time, rr.car_end_time
        FROM ljszy_route_record rr
        WHERE {where}
        ORDER BY rr.route_id, rr.car_start_time
    """
    with conn.cursor() as cur:
        cur.execute(sql, args)
        rows = cur.fetchall()
    out: List[RouteRecordRow] = []
    for r in rows:
        out.append(
            RouteRecordRow(
                id=int(r["id"]),
                route_id=int(r["route_id"]) if r.get("route_id") is not None else None,
                route_name=r.get("route_name"),
                unit_id=r.get("unit_id"),
                car_start_time=r.get("car_start_time"),
                car_end_time=r.get("car_end_time"),
            )
        )
    return out


def parse_args() -> argparse.Namespace:
    if use_run_config():
        return argparse.Namespace(
            unit_ids=RUN_CONFIG["unit_ids"],
            route_ids=RUN_CONFIG["route_ids"],
            start_date=RUN_CONFIG["start_date"],
            end_date=RUN_CONFIG["end_date"],
            match_type=RUN_CONFIG["match_type"],
            is_route_facility=RUN_CONFIG["is_route_facility"],
            w_f1=RUN_CONFIG["w_f1"],
            w_lcs=RUN_CONFIG["w_lcs"],
            high_threshold=RUN_CONFIG["high_threshold"],
            out_detail=RUN_CONFIG["out_detail"],
            out_summary=RUN_CONFIG["out_summary"],
        )
    p = argparse.ArgumentParser(description="路线规划 vs 实际流水 吻合度统计")
    p.add_argument("--unit-ids", type=str, default="", help="项目公司 unit_id，逗号分隔，空表示不限")
    p.add_argument("--route-ids", type=str, default="", help="限定 route_id，逗号分隔")
    p.add_argument("--start-date", type=str, required=True, help="YYYY-MM-DD")
    p.add_argument("--end-date", type=str, required=True, help="YYYY-MM-DD 含当日")
    p.add_argument("--match-type", type=int, default=0, help="facility_match_type，默认 0")
    p.add_argument("--is-route-facility", type=int, default=1, help="is_route_facility，默认 1")
    p.add_argument("--w-f1", type=float, default=0.6, help="overall 中 F1 权重")
    p.add_argument("--w-lcs", type=float, default=0.4, help="overall 中 LCS 比率权重")
    p.add_argument("--high-threshold", type=float, default=0.8, help="高分流水阈值（overall）")
    p.add_argument("--out-detail", type=str, default="", help="明细 CSV 路径")
    p.add_argument("--out-summary", type=str, default="route_conformance_summary.csv", help="汇总 CSV 路径")
    return p.parse_args()


def ensure_parent_dir(path: str) -> None:
    parent = os.path.dirname(os.path.abspath(path))
    if parent:
        os.makedirs(parent, exist_ok=True)


def main() -> int:
    args = parse_args()
    start = datetime.strptime(args.start_date, "%Y-%m-%d").date()
    end = datetime.strptime(args.end_date, "%Y-%m-%d").date()
    if end < start:
        print("end-date 不能早于 start-date", file=sys.stderr)
        return 2

    unit_ids = [x.strip() for x in args.unit_ids.split(",") if x.strip()]
    route_ids = [int(x.strip()) for x in args.route_ids.split(",") if x.strip()] or None

    months = month_suffixes(start, end)
    w1, w2 = args.w_f1, args.w_lcs
    if abs(w1 + w2) < 1e-12:
        print("w-f1 和 w-lcs 不能同时为 0", file=sys.stderr)
        return 2
    if abs(w1 + w2 - 1.0) > 1e-6:
        s = w1 + w2
        w1, w2 = w1 / s, w2 / s

    conn = connect()
    try:
        records = fetch_route_records(conn, unit_ids, start, end, route_ids)

        # route_id -> plan sequence cache
        plan_cache: Dict[int, List[int]] = {}
        detail_rows: List[Dict[str, object]] = []
        agg: Dict[int, Dict[str, object]] = defaultdict(
            lambda: {
                "n": 0,
                "sum_overall": 0.0,
                "high": 0,
                "route_name": "",
                "unit_id": "",
            }
        )

        for rr in records:
            if rr.route_id is None:
                continue
            rid = rr.route_id
            if rid not in plan_cache:
                plan_cache[rid] = fetch_plan_sequence(conn, rid)
            plan_seq = plan_cache[rid]
            plan_set = set(plan_seq)

            if rr.car_start_time:
                end_d = rr.car_end_time.date() if rr.car_end_time else rr.car_start_time.date()
                mlist = month_suffixes(rr.car_start_time.date(), end_d)
            else:
                mlist = months
            actual_seq = fetch_actual_sequence(
                conn, rr.id, mlist, args.match_type, args.is_route_facility
            )
            actual_set = set(actual_seq)

            prec, rec, f1 = set_metrics(plan_set, actual_set)
            lr = lcs_ratio(plan_seq, actual_seq)
            overall = w1 * f1 + w2 * lr

            g = agg[rid]
            g["n"] = int(g["n"]) + 1
            g["sum_overall"] = float(g["sum_overall"]) + overall
            if overall >= args.high_threshold:
                g["high"] = int(g["high"]) + 1
            if rr.route_name:
                g["route_name"] = rr.route_name
            if rr.unit_id:
                g["unit_id"] = rr.unit_id

            detail_rows.append(
                {
                    "route_record_id": rr.id,
                    "route_id": rid,
                    "route_name": rr.route_name or "",
                    "unit_id": rr.unit_id or "",
                    "car_start_time": rr.car_start_time.isoformat(sep=" ") if rr.car_start_time else "",
                    "plan_len": len(plan_seq),
                    "actual_len": len(actual_seq),
                    "precision": round(prec, 4),
                    "recall": round(rec, 4),
                    "f1": round(f1, 4),
                    "lcs_ratio": round(lr, 4),
                    "overall": round(overall, 4),
                }
            )

        summary_rows: List[Dict[str, object]] = []
        for rid, g in sorted(agg.items(), key=lambda x: x[0]):
            n = int(g["n"])
            mean_overall = float(g["sum_overall"]) / n if n else 0.0
            high_pct = (int(g["high"]) / n * 100.0) if n else 0.0
            summary_rows.append(
                {
                    "route_id": rid,
                    "route_name": g["route_name"],
                    "unit_id": g["unit_id"],
                    "sample_count": n,
                    "mean_overall": round(mean_overall, 4),
                    "mean_overall_pct": round(mean_overall * 100, 2),
                    "high_match_count": int(g["high"]),
                    "high_match_pct": round(high_pct, 2),
                }
        )

        if args.out_detail:
            ensure_parent_dir(args.out_detail)
            with open(args.out_detail, "w", newline="", encoding="utf-8-sig") as f:
                w = csv.DictWriter(f, fieldnames=list(detail_rows[0].keys()) if detail_rows else [])
                if detail_rows:
                    w.writeheader()
                    w.writerows(detail_rows)

        ensure_parent_dir(args.out_summary)
        with open(args.out_summary, "w", newline="", encoding="utf-8-sig") as f:
            fields = [
                "route_id",
                "route_name",
                "unit_id",
                "sample_count",
                "mean_overall",
                "mean_overall_pct",
                "high_match_count",
                "high_match_pct",
            ]
            w = csv.DictWriter(f, fieldnames=fields)
            w.writeheader()
            for row in summary_rows:
                w.writerow(row)

        print(f"写入汇总: {args.out_summary} 共 {len(summary_rows)} 条路线")
        if args.out_detail:
            print(f"写入明细: {args.out_detail} 共 {len(detail_rows)} 条流水")
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
