package com.zhj.route.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RouteQueryService {
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.default-days:30}")
    private int defaultDays;

    public RouteQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> companies() {
        String sql = "SELECT id, depName, depCode " +
                "FROM cloud_management.cloud_department " +
                "WHERE beenDeleted = 0 ORDER BY depName, id";
        return jdbcTemplate.queryForList(sql);
    }

    public List<Map<String, Object>> routes(String unitId, Integer dataType) {
        int queryDataType = dataType == null ? 0 : dataType;
        if (queryDataType != 0 && queryDataType != 1) {
            throw new IllegalArgumentException("dataType must be 0 or 1");
        }
        String dataTypeCondition = queryDataType == 1
                ? "data_type = 1"
                : "(data_type = 0 OR data_type IS NULL)";
        String sql = "SELECT id, name AS routeName, department_id AS unitId, department_name AS unitName, " +
                "frequency_name AS frequencyName, work_begin_time AS workBeginTime, work_end_time AS workEndTime, " +
                "CASE WHEN data_type = 1 THEN 1 ELSE 0 END AS dataType, " +
                "CASE WHEN data_type = 1 THEN '岗位' ELSE '路线' END AS dataTypeName " +
                "FROM ljszy_route_info " +
                "WHERE been_deleted = 0 AND department_id = ? AND " + dataTypeCondition + " " +
                "ORDER BY name, id";
        return jdbcTemplate.queryForList(sql, unitId);
    }

    public List<Map<String, Object>> routePlanPoints(Long routeId) {
        String sql = "SELECT b.route_id AS routeId, b.fac_id AS facilityId, b.order_num AS orderNum, " +
                "f.name AS facilityName, f.facility_type_name AS facilityTypeName, " +
                "f.longitude_done AS longitude, f.latitude_done AS latitude " +
                "FROM ljszy_route_fac_banding b " +
                "LEFT JOIN ljszy_facility_info f ON f.id = b.fac_id " +
                "WHERE b.been_deleted = 0 AND b.route_id = ? " +
                "ORDER BY IFNULL(b.order_num, 999999), b.id";
        return jdbcTemplate.queryForList(sql, routeId);
    }

    public List<Map<String, Object>> routeRecords(String unitId, Long routeId, String startDate, String endDate) {
        LocalDate start = parseOrDefaultStart(startDate);
        LocalDate end = parseOrDefaultEnd(endDate);
        String sql = "SELECT id, unit_id AS unitId, route_id AS routeId, route_name AS routeName, " +
                "car_code AS carCode, car_start_time AS carStartTime, car_end_time AS carEndTime, " +
                "route_status AS routeStatus, terminal_frequency AS terminalFrequency, " +
                "working_velocity AS workingVelocity, start_velocity AS startVelocity, back_velocity AS backVelocity " +
                "FROM ljszy_route_record " +
                "WHERE been_deleted = 0 AND unit_id = ? AND route_id = ? " +
                "AND car_start_time >= ? AND car_start_time < ? " +
                "ORDER BY car_start_time DESC, id DESC";
        return jdbcTemplate.queryForList(sql, unitId, routeId, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
    }

    public List<Map<String, Object>> recordPoints(Long recordId) {
        Map<String, Object> record = findRouteRecord(recordId);
        if (record == null || record.get("car_start_time") == null) {
            return new ArrayList<Map<String, Object>>();
        }
        LocalDateTime startTime = toLocalDateTime(record.get("car_start_time"));
        String table = "ljszy_route_facility_record_" + startTime.format(MONTH_FMT);
        if (!tableExists(table)) {
            return new ArrayList<Map<String, Object>>();
        }
        String sql = "SELECT fr.id, fr.route_record_id AS routeRecordId, fr.facility_id AS facilityId, " +
                "f.name AS facilityName, fr.facility_work_type AS facilityWorkType, " +
                "fr.create_time AS createTime, fr.entry_point_time AS entryPointTime, fr.leave_point_time AS leavePointTime, " +
                "fr.operation_time_length AS operationTimeLength, fr.facility_match_type AS facilityMatchType, " +
                "fr.is_route_facility AS routeFacility " +
                "FROM " + table + " fr " +
                "LEFT JOIN ljszy_facility_info f ON f.id = fr.facility_id " +
                "WHERE fr.been_deleted = 0 AND fr.route_record_id = ? " +
                "ORDER BY COALESCE(fr.entry_point_time, fr.create_time), fr.id";
        return jdbcTemplate.queryForList(sql, recordId);
    }

    public Map<String, Object> optimizePreview(Map<String, Object> request) {
        Map<String, Object> result = new HashMap<String, Object>();
        Object routeId = request.get("routeId");
        List<Map<String, Object>> planPoints = routePlanPoints(Long.valueOf(String.valueOf(routeId)));
        List<Object> sequence = new ArrayList<Object>();
        for (Map<String, Object> point : planPoints) {
            sequence.add(point.get("facilityId"));
        }
        result.put("routeId", routeId);
        result.put("status", "DEMO");
        result.put("message", "当前为占位优化结果：先返回原规划点位顺序，后续在这里接入真实算法。");
        result.put("optimizedSequence", sequence);
        result.put("pointCount", sequence.size());
        return result;
    }

    private Map<String, Object> findRouteRecord(Long recordId) {
        String sql = "SELECT id, car_start_time FROM ljszy_route_record WHERE been_deleted = 0 AND id = ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, recordId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean tableExists(String tableName) {
        String sql = "SELECT 1 FROM information_schema.tables " +
                "WHERE table_schema = DATABASE() AND table_name = ? LIMIT 1";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, tableName);
        return !rows.isEmpty();
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

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toLocalDateTime();
        }
        if (value instanceof java.util.Date) {
            return new java.sql.Timestamp(((java.util.Date) value).getTime()).toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value).replace(" ", "T"));
    }
}
