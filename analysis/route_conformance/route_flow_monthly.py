#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
单月「流水点位表」提取与整理统计（第二步对比/优化稍后可接）。

设计要点：
  - 主数据来自分表：ljszy_route_facility_record_yyyyMM（必选 --month=yyyyMM）。
  - 为拿到项目公司、路线、作业日，最小 JOIN：ljszy_route_record（仅维度字段）。
  - 一条流水 = 同一个 route_record_id 的多行点位记录；脚本按时间排序拼成「流水线路」指纹。
  - 指纹：该流水下去重前的 facility_id 序列。
  - 默认过滤口径与 route_conformance.py 一致：facility_match_type=0 且 is_route_facility=1。
  - 若需与 SELECT * 完全一致（不过滤），请加 --all-rows。

输出 CSV（utf-8-sig）：
  1) detail_route_records.csv（可选）：每条流水一行，含指纹、点数、作业日等。
  2) daily_unit_route.csv：公司 + 作业日 + 路线，当日流水条数、当日不同指纹数。
  3) monthly_unit_route.csv：公司 + 路线，本月有流水的天数、流水总趟数、本月不同指纹数。
  4) cumulative_unique_fingerprints.csv：按公司、路线，按日期排序做「跨日累计唯一指纹」
     （解释：若把每日出现的指纹集合不断并入全集，累计规模即「到该日为止出现过多少种不同流水线路」）。

用法示例：
  set LJSZY_DB_HOST=...
  set LJSZY_DB_USER=...
  set LJSZY_DB_PASSWORD=...
  set LJSZY_DB_NAME=ljszy_new

  python route_flow_monthly.py --month 202604 --unit-ids id1,id2
  python route_flow_monthly.py --month 202604 --all-rows
  python route_flow_monthly.py --month 202604 --out-dir ./out_apr
"""

from __future__ import annotations

import argparse
import csv
import os
import sys
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime
from typing import DefaultDict, Dict, List, Optional, Sequence, Set, Tuple

import pymysql


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

# Edit this block when running the file directly in Spyder.
# Command-line arguments still override these values.
RUN_CONFIG = {
    "month": "202604",
    "unit_ids": "",
    "route_ids": "",
    "all_rows": False,
    "match_type": 0,
    "is_route_facility": 1,
    "out_dir": os.path.join(SCRIPT_DIR, "out_flow_202604"),
    "no_detail": False,
}


def env(key: str, default: Optional[str] = None) -> Optional[str]:
    v = os.environ.get(key)
    return v if v is not None and v != "" else default


def use_run_config() -> bool:
    return len(sys.argv) == 1 or "spyder_kernels" in sys.modules


def connect() -> pymysql.connections.Connection:
    # 默认使用本机开发曾配置的库；生产可用环境变量覆盖
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


def table_exists(conn, table: str) -> bool:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = %s LIMIT 1",
            (table,),
        )
        return cur.fetchone() is not None


@dataclass
class RawRow:
    route_record_id: int
    facility_id: int
    ts: datetime
    rid: int
    unit_id: Optional[str]
    route_id: Optional[int]
    route_name: Optional[str]
    work_date: Optional[date]


def fetch_raw_rows(
    conn,
    table: str,
    unit_ids: Sequence[str],
    route_ids: Optional[Sequence[int]],
    match_type: Optional[int],
    is_route_facility: Optional[int],
) -> List[RawRow]:
    cond = ["fr.been_deleted = 0", "rr.been_deleted = 0"]
    args: List[object] = []
    if unit_ids:
        ph = ",".join(["%s"] * len(unit_ids))
        cond.append(f"rr.unit_id IN ({ph})")
        args.extend(unit_ids)
    if route_ids:
        ph = ",".join(["%s"] * len(route_ids))
        cond.append(f"rr.route_id IN ({ph})")
        args.extend(route_ids)
    if match_type is not None:
        cond.append("fr.facility_match_type = %s")
        args.append(match_type)
    if is_route_facility is not None:
        cond.append("fr.is_route_facility = %s")
        args.append(is_route_facility)
    where = " AND ".join(cond)
    sql = f"""
        SELECT
            fr.route_record_id,
            fr.facility_id,
            COALESCE(fr.entry_point_time, fr.create_time) AS ts,
            fr.id AS rid,
            rr.unit_id,
            rr.route_id,
            rr.route_name,
            DATE(rr.car_start_time) AS work_date
        FROM {table} fr
        INNER JOIN ljszy_route_record rr ON rr.id = fr.route_record_id
        WHERE {where}
        ORDER BY fr.route_record_id, ts, fr.id
    """
    with conn.cursor() as cur:
        cur.execute(sql, args)
        rows = cur.fetchall()
    out: List[RawRow] = []
    for r in rows:
        wd = r.get("work_date")
        if isinstance(wd, datetime):
            wd = wd.date()
        out.append(
            RawRow(
                route_record_id=int(r["route_record_id"]),
                facility_id=int(r["facility_id"]),
                ts=r["ts"],
                rid=int(r["rid"]),
                unit_id=r.get("unit_id"),
                route_id=int(r["route_id"]) if r.get("route_id") is not None else None,
                route_name=r.get("route_name"),
                work_date=wd if isinstance(wd, date) else None,
            )
        )
    return out


def build_fingerprints(rows: List[RawRow]) -> Dict[int, Tuple[str, Optional[str], Optional[int], Optional[str], Optional[date], int]]:
    """
    route_record_id -> (fingerprint, unit_id, route_id, route_name, work_date, point_count)
    fingerprint = facility_id 用 '-' 连接（保持出现顺序，含重复；便于区分「多停一次同点」）。
    """
    by_rr: DefaultDict[int, List[RawRow]] = defaultdict(list)
    for r in rows:
        by_rr[r.route_record_id].append(r)
    result: Dict[int, Tuple[str, Optional[str], Optional[int], Optional[str], Optional[date], int]] = {}
    for rid, lst in by_rr.items():
        lst.sort(key=lambda x: (x.ts, x.rid))
        seq = [str(x.facility_id) for x in lst]
        fp = "-".join(seq) if seq else ""
        unit = lst[0].unit_id
        route_id = lst[0].route_id
        route_name = lst[0].route_name
        work_date = lst[0].work_date
        result[rid] = (fp, unit, route_id, route_name, work_date, len(seq))
    return result


def parse_args() -> argparse.Namespace:
    if use_run_config():
        return argparse.Namespace(
            month=RUN_CONFIG["month"],
            unit_ids=RUN_CONFIG["unit_ids"],
            route_ids=RUN_CONFIG["route_ids"],
            all_rows=RUN_CONFIG["all_rows"],
            match_type=RUN_CONFIG["match_type"],
            is_route_facility=RUN_CONFIG["is_route_facility"],
            out_dir=RUN_CONFIG["out_dir"],
            no_detail=RUN_CONFIG["no_detail"],
        )
    p = argparse.ArgumentParser(description="单月流水点位表提取与路线/日统计")
    p.add_argument("--month", type=str, required=True, help="yyyyMM，例如 202604")
    p.add_argument("--unit-ids", type=str, default="", help="项目公司 unit_id，逗号分隔，空表示不限")
    p.add_argument("--route-ids", type=str, default="", help="限定 route_id，逗号分隔")
    p.add_argument(
        "--all-rows",
        action="store_true",
        help="不按 facility_match_type / is_route_facility 过滤（与 SELECT * 行数一致）",
    )
    p.add_argument(
        "--match-type",
        type=int,
        default=0,
        help="过滤 facility_match_type（默认 0；与 route_conformance 一致；--all-rows 时忽略）",
    )
    p.add_argument(
        "--is-route-facility",
        type=int,
        default=1,
        help="过滤 is_route_facility（默认 1；--all-rows 时忽略）",
    )
    p.add_argument("--out-dir", type=str, default=".", help="输出目录")
    p.add_argument("--no-detail", action="store_true", help="不写 detail_route_records.csv")
    return p.parse_args()


def ensure_dir(path: str) -> None:
    if path and path != ".":
        os.makedirs(path, exist_ok=True)


def main() -> int:
    args = parse_args()
    month = args.month.strip()
    if len(month) != 6 or not month.isdigit():
        print("--month 须为 6 位 yyyyMM", file=sys.stderr)
        return 2

    unit_ids = [x.strip() for x in args.unit_ids.split(",") if x.strip()]
    route_ids = [int(x.strip()) for x in args.route_ids.split(",") if x.strip()] or None
    if args.all_rows:
        mt: Optional[int] = None
        irf: Optional[int] = None
    else:
        mt = args.match_type
        irf = args.is_route_facility

    table = f"ljszy_route_facility_record_{month}"
    out_dir = args.out_dir.rstrip("\\/") or "."
    ensure_dir(out_dir)

    conn = connect()
    try:
        if not table_exists(conn, table):
            print(f"表不存在: {table}", file=sys.stderr)
            return 3

        raw = fetch_raw_rows(conn, table, unit_ids, route_ids, mt, irf)
        fp_map = build_fingerprints(raw)

        # --- detail: one row per route_record ---
        detail_path = os.path.join(out_dir, "detail_route_records.csv")
        if not args.no_detail:
            ensure_dir(out_dir)
            with open(detail_path, "w", newline="", encoding="utf-8-sig") as f:
                fields = [
                    "route_record_id",
                    "unit_id",
                    "route_id",
                    "route_name",
                    "work_date",
                    "point_count",
                    "fingerprint",
                ]
                w = csv.DictWriter(f, fieldnames=fields)
                w.writeheader()
                for rr_id in sorted(fp_map.keys()):
                    fp, uid, rid, rname, wd, pc = fp_map[rr_id]
                    w.writerow(
                        {
                            "route_record_id": rr_id,
                            "unit_id": uid or "",
                            "route_id": rid if rid is not None else "",
                            "route_name": rname or "",
                            "work_date": wd.isoformat() if wd else "",
                            "point_count": pc,
                            "fingerprint": fp,
                        }
                    )

        # --- daily: unit + work_date + route_id ---
        daily: DefaultDict[Tuple[str, date, int], Set[int]] = defaultdict(set)
        daily_fp: DefaultDict[Tuple[str, date, int], Set[str]] = defaultdict(set)
        for rr_id, (fp, uid, rid, _rname, wd, _pc) in fp_map.items():
            if uid is None or wd is None or rid is None:
                continue
            key = (uid, wd, rid)
            daily[key].add(rr_id)
            if fp:
                daily_fp[key].add(fp)

        daily_path = os.path.join(out_dir, "daily_unit_route.csv")
        with open(daily_path, "w", newline="", encoding="utf-8-sig") as f:
            w = csv.DictWriter(
                f,
                fieldnames=[
                    "unit_id",
                    "work_date",
                    "route_id",
                    "trip_count",
                    "distinct_fingerprint_count",
                ],
            )
            w.writeheader()
            for (uid, wd, rid) in sorted(daily.keys(), key=lambda x: (x[0], x[1], x[2])):
                w.writerow(
                    {
                        "unit_id": uid,
                        "work_date": wd.isoformat(),
                        "route_id": rid,
                        "trip_count": len(daily[(uid, wd, rid)]),
                        "distinct_fingerprint_count": len(daily_fp[(uid, wd, rid)]),
                    }
                )

        # --- monthly per (unit, route) ---
        monthly_trips: DefaultDict[Tuple[str, int], Set[int]] = defaultdict(set)
        monthly_days: DefaultDict[Tuple[str, int], Set[date]] = defaultdict(set)
        monthly_fp: DefaultDict[Tuple[str, int], Set[str]] = defaultdict(set)
        for rr_id, (fp, uid, rid, _rname, wd, _pc) in fp_map.items():
            if uid is None or wd is None or rid is None:
                continue
            k = (uid, rid)
            monthly_trips[k].add(rr_id)
            monthly_days[k].add(wd)
            if fp:
                monthly_fp[k].add(fp)

        monthly_path = os.path.join(out_dir, "monthly_unit_route.csv")
        with open(monthly_path, "w", newline="", encoding="utf-8-sig") as f:
            w = csv.DictWriter(
                f,
                fieldnames=[
                    "unit_id",
                    "route_id",
                    "active_day_count",
                    "trip_count",
                    "distinct_fingerprint_count",
                ],
            )
            w.writeheader()
            for (uid, rid) in sorted(monthly_trips.keys(), key=lambda x: (x[0], x[1])):
                w.writerow(
                    {
                        "unit_id": uid,
                        "route_id": rid,
                        "active_day_count": len(monthly_days[(uid, rid)]),
                        "trip_count": len(monthly_trips[(uid, rid)]),
                        "distinct_fingerprint_count": len(monthly_fp[(uid, rid)]),
                    }
                )

        # --- cumulative unique fingerprints by date (per unit, route) ---
        dates_by_unit_route: DefaultDict[Tuple[str, int], Set[date]] = defaultdict(set)
        fp_by_day: DefaultDict[Tuple[str, int, date], Set[str]] = defaultdict(set)
        for (uid, wd, rid) in daily.keys():
            dates_by_unit_route[(uid, rid)].add(wd)
            for fp in daily_fp.get((uid, wd, rid), set()):
                fp_by_day[(uid, rid, wd)].add(fp)

        cum_path = os.path.join(out_dir, "cumulative_unique_fingerprints.csv")
        with open(cum_path, "w", newline="", encoding="utf-8-sig") as f:
            w = csv.DictWriter(
                f,
                fieldnames=["unit_id", "route_id", "work_date", "cumulative_unique_fingerprint_count"],
            )
            w.writeheader()
            for (uid, rid) in sorted(monthly_trips.keys(), key=lambda x: (x[0], x[1])):
                seen: Set[str] = set()
                for d0 in sorted(dates_by_unit_route[(uid, rid)]):
                    seen |= fp_by_day.get((uid, rid, d0), set())
                    w.writerow(
                        {
                            "unit_id": uid,
                            "route_id": rid,
                            "work_date": d0.isoformat(),
                            "cumulative_unique_fingerprint_count": len(seen),
                        }
                    )

        filt_desc = "无行级过滤(--all-rows)" if args.all_rows else f"match_type={mt}, is_route_facility={irf}"
        print(f"表: {table} 点位过滤: {filt_desc}")
        print(f"原始行数: {len(raw)}  不同流水(route_record): {len(fp_map)}")
        print(f"日汇总: {daily_path}")
        print(f"月汇总: {monthly_path}")
        print(f"跨日累计唯一指纹: {cum_path}")
        if not args.no_detail:
            print(f"流水明细: {detail_path}")
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
