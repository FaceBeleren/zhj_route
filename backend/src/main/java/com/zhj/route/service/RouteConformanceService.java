package com.zhj.route.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RouteConformanceService {
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final double W_F1 = 0.6D;
    private static final double W_LCS = 0.4D;
    private static final int MATCH_TYPE = 0;
    private static final int IS_ROUTE_FACILITY = 1;

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.default-days:30}")
    private int defaultDays;

    public RouteConformanceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> companyScores(String unitIds, String startDate, String endDate) {
        LocalDate start = parseOrDefaultStart(startDate);
        LocalDate end = parseOrDefaultEnd(endDate);
        List<String> ids = splitIds(unitIds);
        List<Map<String, Object>> companies = ids.isEmpty() ? allCompanies() : companiesByIds(ids);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> company : companies) {
            rows.add(scoreCompany(String.valueOf(company.get("id")), company, start, end));
        }
        Collections.sort(rows, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Double.compare(asDouble(b.get("companyOverallScore")), asDouble(a.get("companyOverallScore")));
            }
        });
        return rows;
    }

    public List<Map<String, Object>> routeScores(String unitId, String startDate, String endDate) {
        LocalDate start = parseOrDefaultStart(startDate);
        LocalDate end = parseOrDefaultEnd(endDate);
        ScoreContext context = buildContext(unitId, start, end, null);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Long routeId : context.allRouteIds) {
            rows.add(toRouteScore(unitId, routeId, context));
        }
        Collections.sort(rows, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Double.compare(asDouble(b.get("avgOverall")), asDouble(a.get("avgOverall")));
            }
        });
        return rows;
    }

    public List<Map<String, Object>> tripScores(String unitId, Long routeId, String startDate, String endDate) {
        LocalDate start = parseOrDefaultStart(startDate);
        LocalDate end = parseOrDefaultEnd(endDate);
        ScoreContext context = buildContext(unitId, start, end, routeId);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (RouteRecord record : context.records) {
            if (record.routeId == null || !record.routeId.equals(routeId)) {
                continue;
            }
            List<Long> plan = context.planSequences.containsKey(routeId)
                    ? context.planSequences.get(routeId)
                    : new ArrayList<Long>();
            List<Long> actual = context.actualSequences.containsKey(record.id)
                    ? context.actualSequences.get(record.id)
                    : new ArrayList<Long>();
            ScoreMetrics metrics = scorePair(plan, actual);
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("routeRecordId", record.id);
            row.put("routeId", routeId);
            row.put("routeName", record.routeName);
            row.put("unitId", record.unitId);
            row.put("carCode", record.carCode);
            row.put("carStartTime", record.carStartTime);
            row.put("carEndTime", record.carEndTime);
            row.put("planPointCount", plan.size());
            row.put("actualPointCount", actual.size());
            row.put("precision", round(metrics.precision));
            row.put("recall", round(metrics.recall));
            row.put("f1", round(metrics.f1));
            row.put("lcsLen", metrics.lcsLen);
            row.put("lcsRatio", round(metrics.lcsRatio));
            row.put("overall", round(metrics.overall));
            row.put("planFingerprint", fingerprint(plan));
            row.put("actualFingerprint", fingerprint(actual));
            rows.add(row);
        }
        Collections.sort(rows, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return String.valueOf(b.get("carStartTime")).compareTo(String.valueOf(a.get("carStartTime")));
            }
        });
        return rows;
    }

    private Map<String, Object> scoreCompany(String unitId, Map<String, Object> company, LocalDate start, LocalDate end) {
        ScoreContext context = buildContext(unitId, start, end, null);
        List<Double> f1Values = new ArrayList<Double>();
        List<Double> lcsValues = new ArrayList<Double>();
        List<Double> overallValues = new ArrayList<Double>();
        Set<Long> routesWithTrip = new HashSet<Long>();
        Set<String> fingerprints = new HashSet<String>();

        for (RouteRecord record : context.records) {
            if (record.routeId == null) {
                continue;
            }
            routesWithTrip.add(record.routeId);
            List<Long> plan = context.planSequences.containsKey(record.routeId)
                    ? context.planSequences.get(record.routeId)
                    : new ArrayList<Long>();
            List<Long> actual = context.actualSequences.containsKey(record.id)
                    ? context.actualSequences.get(record.id)
                    : new ArrayList<Long>();
            if (!actual.isEmpty()) {
                fingerprints.add(fingerprint(actual));
            }
            ScoreMetrics metrics = scorePair(plan, actual);
            f1Values.add(metrics.f1);
            lcsValues.add(metrics.lcsRatio);
            overallValues.add(metrics.overall);
        }

        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("unitId", unitId);
        row.put("unitName", company.get("depName"));
        row.put("depCode", company.get("depCode"));
        row.put("routeCount", context.routeNames.size());
        row.put("routeWithTripCount", routesWithTrip.size());
        row.put("tripCount", context.records.size());
        row.put("distinctFingerprintCount", fingerprints.size());
        row.put("avgF1", round(avg(f1Values)));
        row.put("avgLcsRatio", round(avg(lcsValues)));
        row.put("companyOverallScore", round(avg(overallValues)));
        row.put("maxOverall", round(max(overallValues)));
        row.put("startDate", start.toString());
        row.put("endDate", end.toString());
        return row;
    }

    private Map<String, Object> toRouteScore(String unitId, Long routeId, ScoreContext context) {
        List<Double> f1Values = new ArrayList<Double>();
        List<Double> lcsValues = new ArrayList<Double>();
        List<Double> overallValues = new ArrayList<Double>();
        Set<String> fingerprints = new HashSet<String>();
        int tripCount = 0;
        for (RouteRecord record : context.records) {
            if (record.routeId == null || !record.routeId.equals(routeId)) {
                continue;
            }
            tripCount++;
            List<Long> plan = context.planSequences.containsKey(routeId)
                    ? context.planSequences.get(routeId)
                    : new ArrayList<Long>();
            List<Long> actual = context.actualSequences.containsKey(record.id)
                    ? context.actualSequences.get(record.id)
                    : new ArrayList<Long>();
            if (!actual.isEmpty()) {
                fingerprints.add(fingerprint(actual));
            }
            ScoreMetrics metrics = scorePair(plan, actual);
            f1Values.add(metrics.f1);
            lcsValues.add(metrics.lcsRatio);
            overallValues.add(metrics.overall);
        }
        List<Long> plan = context.planSequences.containsKey(routeId)
                ? context.planSequences.get(routeId)
                : new ArrayList<Long>();
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("unitId", unitId);
        row.put("routeId", routeId);
        row.put("routeName", context.routeNames.get(routeId));
        row.put("planPointCount", plan.size());
        row.put("tripCount", tripCount);
        row.put("distinctFingerprintCount", fingerprints.size());
        row.put("avgF1", round(avg(f1Values)));
        row.put("avgLcsRatio", round(avg(lcsValues)));
        row.put("avgOverall", round(avg(overallValues)));
        row.put("maxOverall", round(max(overallValues)));
        return row;
    }

    private ScoreContext buildContext(String unitId, LocalDate start, LocalDate end, Long routeId) {
        ScoreContext context = new ScoreContext();
        context.routeNames.putAll(fetchCompanyRoutes(unitId, routeId));
        context.records.addAll(fetchRouteRecords(unitId, routeId, start, end));
        for (RouteRecord record : context.records) {
            if (record.routeId != null && !context.routeNames.containsKey(record.routeId)) {
                context.routeNames.put(record.routeId, record.routeName);
            }
        }
        context.allRouteIds.addAll(context.routeNames.keySet());
        for (RouteRecord record : context.records) {
            if (record.routeId != null) {
                context.allRouteIds.add(record.routeId);
            }
        }
        context.planSequences.putAll(fetchPlanSequences(new ArrayList<Long>(context.allRouteIds)));
        List<Long> recordIds = new ArrayList<Long>();
        for (RouteRecord record : context.records) {
            recordIds.add(record.id);
        }
        context.actualSequences.putAll(fetchActualSequences(recordIds, monthSuffixes(start, end)));
        return context;
    }

    private List<Map<String, Object>> allCompanies() {
        String sql = "SELECT id, depName, depCode FROM cloud_management.cloud_department " +
                "WHERE beenDeleted = 0 ORDER BY depName, id";
        return jdbcTemplate.queryForList(sql);
    }

    private List<Map<String, Object>> companiesByIds(List<String> ids) {
        if (ids.isEmpty()) {
            return new ArrayList<Map<String, Object>>();
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                placeholders.append(",");
            }
            placeholders.append("?");
        }
        String sql = "SELECT id, depName, depCode FROM cloud_management.cloud_department " +
                "WHERE beenDeleted = 0 AND id IN (" + placeholders + ") ORDER BY depName, id";
        return jdbcTemplate.queryForList(sql, ids.toArray());
    }

    private Map<Long, String> fetchCompanyRoutes(String unitId, Long routeId) {
        List<Object> args = new ArrayList<Object>();
        args.add(unitId);
        String sql = "SELECT id, name AS routeName FROM ljszy_route_info " +
                "WHERE been_deleted = 0 AND department_id = ? " +
                "AND (data_type = 0 OR data_type IS NULL)";
        if (routeId != null) {
            sql += " AND id = ?";
            args.add(routeId);
        }
        sql += " ORDER BY id";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
        Map<Long, String> out = new LinkedHashMap<Long, String>();
        for (Map<String, Object> row : rows) {
            if (row.get("id") != null) {
                out.put(asLong(row.get("id")), String.valueOf(row.get("routeName")));
            }
        }
        return out;
    }

    private List<RouteRecord> fetchRouteRecords(String unitId, Long routeId, LocalDate start, LocalDate end) {
        List<Object> args = new ArrayList<Object>();
        args.add(unitId);
        args.add(Timestamp.valueOf(start.atStartOfDay()));
        args.add(Timestamp.valueOf(end.plusDays(1).atStartOfDay()));
        String sql = "SELECT id, route_id AS routeId, route_name AS routeName, unit_id AS unitId, " +
                "car_code AS carCode, car_start_time AS carStartTime, car_end_time AS carEndTime " +
                "FROM ljszy_route_record " +
                "WHERE been_deleted = 0 AND unit_id = ? AND car_start_time >= ? AND car_start_time < ?";
        if (routeId != null) {
            sql += " AND route_id = ?";
            args.add(routeId);
        }
        sql += " ORDER BY route_id, car_start_time, id";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
        List<RouteRecord> out = new ArrayList<RouteRecord>();
        for (Map<String, Object> row : rows) {
            RouteRecord record = new RouteRecord();
            record.id = asLong(row.get("id"));
            record.routeId = row.get("routeId") == null ? null : asLong(row.get("routeId"));
            record.routeName = row.get("routeName") == null ? "" : String.valueOf(row.get("routeName"));
            record.unitId = row.get("unitId") == null ? "" : String.valueOf(row.get("unitId"));
            record.carCode = row.get("carCode") == null ? "" : String.valueOf(row.get("carCode"));
            record.carStartTime = row.get("carStartTime");
            record.carEndTime = row.get("carEndTime");
            out.add(record);
        }
        return out;
    }

    private Map<Long, List<Long>> fetchPlanSequences(List<Long> routeIds) {
        Map<Long, List<Long>> out = new HashMap<Long, List<Long>>();
        if (routeIds.isEmpty()) {
            return out;
        }
        for (List<Long> part : chunks(routeIds, 800)) {
            String placeholders = placeholders(part.size());
            String sql = "SELECT route_id AS routeId, fac_id AS facilityId FROM ljszy_route_fac_banding " +
                    "WHERE been_deleted = 0 AND route_id IN (" + placeholders + ") " +
                    "ORDER BY route_id, IFNULL(order_num, 999999), id";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, part.toArray());
            for (Map<String, Object> row : rows) {
                Long rid = asLong(row.get("routeId"));
                if (!out.containsKey(rid)) {
                    out.put(rid, new ArrayList<Long>());
                }
                if (row.get("facilityId") != null) {
                    out.get(rid).add(asLong(row.get("facilityId")));
                }
            }
        }
        return out;
    }

    private Map<Long, List<Long>> fetchActualSequences(List<Long> recordIds, List<String> months) {
        Map<Long, List<ActualPoint>> rowsByRecord = new HashMap<Long, List<ActualPoint>>();
        if (recordIds.isEmpty()) {
            return new HashMap<Long, List<Long>>();
        }
        List<String> tables = new ArrayList<String>();
        for (String month : months) {
            String table = "ljszy_route_facility_record_" + month;
            if (tableExists(table)) {
                tables.add(table);
            }
        }
        if (tables.isEmpty() && tableExists("ljszy_route_facility_record")) {
            tables.add("ljszy_route_facility_record");
        }
        for (String table : tables) {
            for (List<Long> part : chunks(recordIds, 800)) {
                List<Object> args = new ArrayList<Object>();
                args.addAll(part);
                args.add(MATCH_TYPE);
                args.add(IS_ROUTE_FACILITY);
                String sql = "SELECT route_record_id AS recordId, facility_id AS facilityId, " +
                        "COALESCE(entry_point_time, create_time) AS eventTime, id " +
                        "FROM " + table + " WHERE been_deleted = 0 " +
                        "AND route_record_id IN (" + placeholders(part.size()) + ") " +
                        "AND facility_match_type = ? AND is_route_facility = ?";
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
                for (Map<String, Object> row : rows) {
                    if (row.get("recordId") == null || row.get("facilityId") == null) {
                        continue;
                    }
                    Long recordId = asLong(row.get("recordId"));
                    if (!rowsByRecord.containsKey(recordId)) {
                        rowsByRecord.put(recordId, new ArrayList<ActualPoint>());
                    }
                    ActualPoint point = new ActualPoint();
                    point.facilityId = asLong(row.get("facilityId"));
                    point.eventTime = row.get("eventTime");
                    point.rowId = row.get("id") == null ? 0L : asLong(row.get("id"));
                    rowsByRecord.get(recordId).add(point);
                }
            }
        }

        Map<Long, List<Long>> out = new HashMap<Long, List<Long>>();
        for (Map.Entry<Long, List<ActualPoint>> entry : rowsByRecord.entrySet()) {
            Collections.sort(entry.getValue(), new Comparator<ActualPoint>() {
                @Override
                public int compare(ActualPoint a, ActualPoint b) {
                    int c = String.valueOf(a.eventTime).compareTo(String.valueOf(b.eventTime));
                    if (c != 0) {
                        return c;
                    }
                    return Long.compare(a.rowId, b.rowId);
                }
            });
            List<Long> sequence = new ArrayList<Long>();
            for (ActualPoint point : entry.getValue()) {
                sequence.add(point.facilityId);
            }
            out.put(entry.getKey(), sequence);
        }
        return out;
    }

    private ScoreMetrics scorePair(List<Long> plan, List<Long> actual) {
        Set<Long> planSet = new HashSet<Long>(plan);
        Set<Long> actualSet = new HashSet<Long>(actual);
        Set<Long> hit = new HashSet<Long>(planSet);
        hit.retainAll(actualSet);

        double precision;
        double recall;
        double f1;
        if (planSet.isEmpty() && actualSet.isEmpty()) {
            precision = 1.0D;
            recall = 1.0D;
            f1 = 1.0D;
        } else {
            precision = actualSet.isEmpty() ? 0.0D : (double) hit.size() / actualSet.size();
            recall = planSet.isEmpty() ? 0.0D : (double) hit.size() / planSet.size();
            f1 = precision + recall == 0.0D ? 0.0D : 2.0D * precision * recall / (precision + recall);
        }

        int lcsLen = lcsLen(plan, actual);
        double lcsRatio;
        if (plan.isEmpty() && actual.isEmpty()) {
            lcsRatio = 1.0D;
        } else if (plan.isEmpty() || actual.isEmpty()) {
            lcsRatio = 0.0D;
        } else {
            lcsRatio = 2.0D * lcsLen / (plan.size() + actual.size());
        }

        ScoreMetrics metrics = new ScoreMetrics();
        metrics.precision = precision;
        metrics.recall = recall;
        metrics.f1 = f1;
        metrics.lcsLen = lcsLen;
        metrics.lcsRatio = lcsRatio;
        metrics.overall = W_F1 * f1 + W_LCS * lcsRatio;
        return metrics;
    }

    private int lcsLen(List<Long> a, List<Long> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int[] dp = new int[b.size() + 1];
        for (Long ai : a) {
            int prev = 0;
            for (int j = 1; j <= b.size(); j++) {
                int old = dp[j];
                if (ai.equals(b.get(j - 1))) {
                    dp[j] = prev + 1;
                } else if (dp[j - 1] > dp[j]) {
                    dp[j] = dp[j - 1];
                }
                prev = old;
            }
        }
        return dp[b.size()];
    }

    private boolean tableExists(String tableName) {
        String sql = "SELECT 1 FROM information_schema.tables " +
                "WHERE table_schema = DATABASE() AND table_name = ? LIMIT 1";
        return !jdbcTemplate.queryForList(sql, tableName).isEmpty();
    }

    private LocalDate parseOrDefaultStart(String value) {
        if (StringUtils.hasText(value)) {
            return LocalDate.parse(value);
        }
        return LocalDate.now().minusDays(defaultDays - 1L);
    }

    private LocalDate parseOrDefaultEnd(String value) {
        if (StringUtils.hasText(value)) {
            return LocalDate.parse(value);
        }
        return LocalDate.now();
    }

    private List<String> splitIds(String unitIds) {
        if (!StringUtils.hasText(unitIds)) {
            return new ArrayList<String>();
        }
        List<String> out = new ArrayList<String>();
        for (String id : Arrays.asList(unitIds.split(","))) {
            if (StringUtils.hasText(id)) {
                out.add(id.trim());
            }
        }
        return out;
    }

    private List<String> monthSuffixes(LocalDate start, LocalDate end) {
        List<String> out = new ArrayList<String>();
        LocalDate cursor = LocalDate.of(start.getYear(), start.getMonth(), 1);
        LocalDate endMonth = LocalDate.of(end.getYear(), end.getMonth(), 1);
        while (!cursor.isAfter(endMonth)) {
            out.add(cursor.format(MONTH_FMT));
            cursor = cursor.plusMonths(1);
        }
        return out;
    }

    private <T> List<List<T>> chunks(List<T> items, int size) {
        List<List<T>> out = new ArrayList<List<T>>();
        for (int i = 0; i < items.size(); i += size) {
            out.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return out;
    }

    private String placeholders(int size) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                out.append(",");
            }
            out.append("?");
        }
        return out.toString();
    }

    private String fingerprint(List<Long> sequence) {
        StringBuilder out = new StringBuilder();
        for (Long item : sequence) {
            if (out.length() > 0) {
                out.append("-");
            }
            out.append(item);
        }
        return out.toString();
    }

    private double avg(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (Double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private double max(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        double max = values.get(0);
        for (Double value : values) {
            if (value > max) {
                max = value;
            }
        }
        return max;
    }

    private double round(double value) {
        return Math.round(value * 1000000D) / 1000000D;
    }

    private double asDouble(Object value) {
        if (value == null) {
            return 0.0D;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private Long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private static class ScoreContext {
        private final Map<Long, String> routeNames = new LinkedHashMap<Long, String>();
        private final Set<Long> allRouteIds = new HashSet<Long>();
        private final List<RouteRecord> records = new ArrayList<RouteRecord>();
        private final Map<Long, List<Long>> planSequences = new HashMap<Long, List<Long>>();
        private final Map<Long, List<Long>> actualSequences = new HashMap<Long, List<Long>>();
    }

    private static class RouteRecord {
        private Long id;
        private Long routeId;
        private String routeName;
        private String unitId;
        private String carCode;
        private Object carStartTime;
        private Object carEndTime;
    }

    private static class ActualPoint {
        private Long facilityId;
        private Object eventTime;
        private Long rowId;
    }

    private static class ScoreMetrics {
        private double precision;
        private double recall;
        private double f1;
        private int lcsLen;
        private double lcsRatio;
        private double overall;
    }
}
