#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Score multiple project companies by route conformance.

For now, edit fetch_target_companies() after the company source is confirmed.
The script outputs one Excel-friendly CSV:
  - multi_company_scores.csv
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

RUN_CONFIG = {
    "start_date": "",
    "end_date": "",
    "match_type": 0,
    "is_route_facility": 1,
    "w_f1": 0.6,
    "w_lcs": 0.4,
    "out_dir": os.path.join(SCRIPT_DIR, "multi_company_scores_out"),
}

DB_CONFIG = {
    "host": "zhhw-rds-xm.mysql.rds.aliyuncs.com",
    "port": 3306,
    "user": "cleanpro",
    "password": "zhj521%@!",
    "database": "ljszy_new",
}


@dataclass
class CompanyRow:
    unit_id: str
    unit_name: str = ""
    dep_code: str = ""


@dataclass
class RouteRecordRow:
    id: int
    route_id: Optional[int]
    route_name: str
    unit_id: str


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


def avg(values: Sequence[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def lcs_len(a: Sequence[int], b: Sequence[int]) -> int:
    if not a or not b:
        return 0
    m = len(b)
    dp = [0] * (m + 1)
    for ai in a:
        prev = 0
        for j in range(1, m + 1):
            old = dp[j]
            if ai == b[j - 1]:
                dp[j] = prev + 1
            elif dp[j - 1] > dp[j]:
                dp[j] = dp[j - 1]
            prev = old
    return dp[m]


def score_pair(plan_seq: Sequence[int], actual_seq: Sequence[int], w_f1: float, w_lcs: float) -> Tuple[float, float, float]:
    plan_set = set(plan_seq)
    actual_set = set(actual_seq)
    if not plan_set and not actual_set:
        f1 = 1.0
    else:
        hit = len(plan_set & actual_set)
        precision = hit / len(actual_set) if actual_set else 0.0
        recall = hit / len(plan_set) if plan_set else 0.0
        f1 = 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)

    if not plan_seq and not actual_seq:
        lcs_ratio = 1.0
    elif not plan_seq or not actual_seq:
        lcs_ratio = 0.0
    else:
        lcs_ratio = 2.0 * lcs_len(plan_seq, actual_seq) / (len(plan_seq) + len(actual_seq))

    return f1, lcs_ratio, w_f1 * f1 + w_lcs * lcs_ratio


def fetch_target_companies(conn) -> List[CompanyRow]:
    sql = """
        SELECT id, depName, depCode
        FROM cloud_management.cloud_department
        WHERE beenDeleted = 0
        ORDER BY depName, id
    """
    with conn.cursor() as cur:
        cur.execute(sql)
        rows = cur.fetchall()
    return [
        CompanyRow(
            unit_id=str(r.get("id") or ""),
            unit_name=str(r.get("depName") or ""),
            dep_code=str(r.get("depCode") or ""),
        )
        for r in rows
        if r.get("id")
    ]


def fetch_company_routes(conn, unit_id: str, start: date, end: date) -> Tuple[Dict[int, str], str]:
    route_map: Dict[int, str] = {}
    if table_exists(conn, "ljszy_route_info"):
        sql = (
            "SELECT id, name AS route_name FROM ljszy_route_info "
            "WHERE been_deleted = 0 AND department_id = %s "
            "AND (data_type = 0 OR data_type IS NULL) ORDER BY id"
        )
        try:
            with conn.cursor() as cur:
                cur.execute(sql, (unit_id,))
                for r in cur.fetchall():
                    if r.get("id") is not None:
                        route_map[int(r["id"])] = r.get("route_name") or ""
            return route_map, "ljszy_route_info.department_id"
        except pymysql.MySQLError:
            route_map = {}

    sql2 = (
        "SELECT route_id AS id, MAX(route_name) AS route_name "
        "FROM ljszy_route_record "
        "WHERE been_deleted = 0 AND unit_id = %s AND car_start_time >= %s AND car_start_time < %s "
        "AND route_id IS NOT NULL GROUP BY route_id ORDER BY route_id"
    )
    with conn.cursor() as cur:
        cur.execute(
            sql2,
            (
                unit_id,
                datetime.combine(start, datetime.min.time()),
                datetime.combine(end + timedelta(days=1), datetime.min.time()),
            ),
        )
        for r in cur.fetchall():
            if r.get("id") is not None:
                route_map[int(r["id"])] = r.get("route_name") or ""
    return route_map, "ljszy_route_record in selected date range"


def fetch_route_records(conn, unit_id: str, start: date, end: date) -> List[RouteRecordRow]:
    sql = (
        "SELECT id, route_id, route_name, unit_id "
        "FROM ljszy_route_record "
        "WHERE been_deleted = 0 AND unit_id = %s AND car_start_time >= %s AND car_start_time < %s "
        "ORDER BY route_id, car_start_time, id"
    )
    with conn.cursor() as cur:
        cur.execute(
            sql,
            (
                unit_id,
                datetime.combine(start, datetime.min.time()),
                datetime.combine(end + timedelta(days=1), datetime.min.time()),
            ),
        )
        rows = cur.fetchall()
    return [
        RouteRecordRow(
            id=int(r["id"]),
            route_id=int(r["route_id"]) if r.get("route_id") is not None else None,
            route_name=r.get("route_name") or "",
            unit_id=r.get("unit_id") or "",
        )
        for r in rows
    ]


def fetch_plan_sequences(conn, route_ids: Sequence[int]) -> Dict[int, List[int]]:
    if not route_ids:
        return {}
    out: DefaultDict[int, List[int]] = defaultdict(list)
    for part in chunks(list(route_ids)):
        ph = ",".join(["%s"] * len(part))
        sql = (
            "SELECT route_id, fac_id AS fid FROM ljszy_route_fac_banding "
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

    tables = [f"ljszy_route_facility_record_{m}" for m in months if table_exists(conn, f"ljszy_route_facility_record_{m}")]
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
                    out_rows[int(r["route_record_id"])].append(
                        (r.get("ts") or datetime.min, int(r["rid"]), int(r["fid"]))
                    )

    result: Dict[int, List[int]] = {}
    for rr_id, rows in out_rows.items():
        rows.sort(key=lambda x: (x[0], x[1]))
        result[rr_id] = [fid for _ts, _rid, fid in rows]
    return result


def score_company(
    conn,
    company: CompanyRow,
    start: date,
    end: date,
    match_type: int,
    is_route_facility: int,
    w_f1: float,
    w_lcs: float,
) -> Dict[str, object]:
    company_routes, route_count_source = fetch_company_routes(conn, company.unit_id, start, end)
    records = fetch_route_records(conn, company.unit_id, start, end)
    record_route_ids = sorted({r.route_id for r in records if r.route_id is not None})
    all_route_ids = sorted(set(company_routes.keys()) | set(record_route_ids))
    plan_map = fetch_plan_sequences(conn, all_route_ids)
    actual_map = fetch_actual_sequences(
        conn,
        [r.id for r in records],
        month_suffixes(start, end),
        match_type,
        is_route_facility,
    )

    f1_values: List[float] = []
    lcs_values: List[float] = []
    overall_values: List[float] = []
    route_ids_with_trip: Set[int] = set()
    fingerprints: Set[str] = set()

    for rr in records:
        if rr.route_id is None:
            continue
        route_ids_with_trip.add(rr.route_id)
        actual_seq = actual_map.get(rr.id, [])
        if actual_seq:
            fingerprints.add("-".join(str(x) for x in actual_seq))
        f1, lcs_ratio, overall = score_pair(plan_map.get(rr.route_id, []), actual_seq, w_f1, w_lcs)
        f1_values.append(f1)
        lcs_values.append(lcs_ratio)
        overall_values.append(overall)

    return {
        "unit_id": company.unit_id,
        "unit_name": company.unit_name,
        "dep_code": company.dep_code,
        "start_date": start.isoformat(),
        "end_date": end.isoformat(),
        "route_count": len(company_routes),
        "route_count_source": route_count_source,
        "route_with_trip_count": len(route_ids_with_trip),
        "trip_count": len(records),
        "distinct_fingerprint_count": len(fingerprints),
        "avg_f1": round(avg(f1_values), 6),
        "avg_lcs_ratio": round(avg(lcs_values), 6),
        "company_overall_score": round(avg(overall_values), 6),
        "max_overall": round(max(overall_values), 6) if overall_values else 0.0,
    }


def parse_args() -> argparse.Namespace:
    if use_run_config():
        return argparse.Namespace(**RUN_CONFIG)
    start_default, end_default = default_date_range()
    p = argparse.ArgumentParser(description="score multiple project companies")
    p.add_argument("--start-date", default=start_default.isoformat(), help="YYYY-MM-DD, default: today - 29 days")
    p.add_argument("--end-date", default=end_default.isoformat(), help="YYYY-MM-DD, default: today")
    p.add_argument("--match-type", type=int, default=0)
    p.add_argument("--is-route-facility", type=int, default=1)
    p.add_argument("--w-f1", type=float, default=0.6)
    p.add_argument("--w-lcs", type=float, default=0.4)
    p.add_argument("--out-dir", default=os.path.join(SCRIPT_DIR, "multi_company_scores_out"))
    return p.parse_args()


def write_csv(path: str, rows: List[Dict[str, object]], fields: Sequence[str]) -> None:
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=list(fields))
        w.writeheader()
        w.writerows(rows)


def main() -> int:
    args = parse_args()
    if args.start_date and args.end_date:
        start = parse_date(args.start_date)
        end = parse_date(args.end_date)
    else:
        start, end = default_date_range()
    if end < start:
        print("end-date cannot be earlier than start-date", file=sys.stderr)
        return 2

    w_sum = float(args.w_f1) + float(args.w_lcs)
    if abs(w_sum) < 1e-12:
        print("w_f1 and w_lcs cannot both be zero", file=sys.stderr)
        return 2
    w_f1 = float(args.w_f1) / w_sum
    w_lcs = float(args.w_lcs) / w_sum

    out_dir = os.path.abspath(args.out_dir)
    os.makedirs(out_dir, exist_ok=True)

    conn = connect()
    try:
        companies = fetch_target_companies(conn)
        if not companies:
            print("No target companies found from cloud_management.cloud_department.", file=sys.stderr)
            return 2

        rows: List[Dict[str, object]] = []
        total = len(companies)
        for idx, company in enumerate(companies, start=1):
            label = f"{company.unit_name or '-'}({company.unit_id})"
            print(f"[{idx}/{total}] 开始统计公司: {label}", flush=True)
            try:
                row = score_company(
                    conn,
                    company,
                    start,
                    end,
                    int(args.match_type),
                    int(args.is_route_facility),
                    w_f1,
                    w_lcs,
                )
                rows.append(row)
                print(
                    f"[{idx}/{total}] 完成统计公司: {label}, "
                    f"路线数={row['route_count']}, 流水数={row['trip_count']}, "
                    f"综合得分={row['company_overall_score']}",
                    flush=True,
                )
            except Exception as exc:
                print(f"[{idx}/{total}] 统计失败公司: {label}, 原因: {exc}", file=sys.stderr, flush=True)
                raise
        rows.sort(key=lambda r: float(r["company_overall_score"]), reverse=True)

        headers = {
            "unit_id": "项目公司ID",
            "unit_name": "项目公司名称",
            "dep_code": "项目公司编码",
            "start_date": "开始日期",
            "end_date": "结束日期",
            "route_count": "路线总数",
            "route_count_source": "路线数来源",
            "route_with_trip_count": "有流水路线数",
            "trip_count": "流水总数",
            "distinct_fingerprint_count": "不同流水指纹数",
            "avg_f1": "平均F1",
            "avg_lcs_ratio": "平均LCS比例",
            "company_overall_score": "公司综合得分",
            "max_overall": "最高综合得分",
        }
        out_rows = [{headers.get(k, k): v for k, v in row.items()} for row in rows]
        out_path = os.path.join(out_dir, "multi_company_scores.csv")
        write_csv(out_path, out_rows, headers.values())
        print(f"multi-company scores: {out_path}")
        print(f"companies={len(rows)}, start={start.isoformat()}, end={end.isoformat()}")
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
