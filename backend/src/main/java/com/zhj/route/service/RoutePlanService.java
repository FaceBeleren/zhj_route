package com.zhj.route.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class RoutePlanService {
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RoutePlanService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> saveGroup(Map<String, Object> request) {
        List<Map<String, Object>> routes = listOfMaps(request.get("routes"));
        String sourceType = text(request.get("sourceType"), "MULTI");
        String groupName = text(request.get("groupName"), defaultGroupName(sourceType));
        Long originRouteId = toLong(request.get("originRouteId"));
        String defaultDisplayMode = text(request.get("defaultDisplayMode"), "DIRECT");
        Object summary = request.containsKey("summary") ? request.get("summary") : request.get("summaryJson");
        Object requestPayload = request.containsKey("request") ? request.get("request") : request;

        String sql = "INSERT INTO zhj_route_plan_group "
                + "(group_name, source_type, unit_id, unit_name, origin_route_id, origin_route_name, planning_strategy, "
                + "default_display_mode, route_count, summary_json, request_json, remark) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        Long groupId = insert(sql,
                groupName,
                sourceType,
                textOrNull(request.get("unitId")),
                textOrNull(request.get("unitName")),
                originRouteId,
                textOrNull(request.get("originRouteName")),
                textOrNull(request.get("planningStrategy")),
                defaultDisplayMode,
                routes.size(),
                json(summary),
                json(requestPayload),
                textOrNull(request.get("remark")));

        int routeNo = 1;
        for (Map<String, Object> route : routes) {
            saveRouteInternal(groupId, route, routeNo++);
        }
        refreshGroupRouteCount(groupId);
        return group(groupId);
    }

    @Transactional
    public Map<String, Object> saveRoute(Map<String, Object> request) {
        Map<String, Object> route = mapValue(request.get("route"));
        if (route.isEmpty()) {
            route = new LinkedHashMap<>(request);
        }
        String sourceType = text(request.get("sourceType"), "SINGLE_OPTIMIZE");
        String groupName = text(request.get("groupName"), defaultGroupName(sourceType));
        String routeName = text(request.get("routeName"), groupName);
        route.putIfAbsent("routeName", routeName);
        Long originRouteId = toLong(request.get("originRouteId"));

        Long groupId = insert("INSERT INTO zhj_route_plan_group "
                        + "(group_name, source_type, unit_id, unit_name, origin_route_id, origin_route_name, planning_strategy, "
                        + "default_display_mode, route_count, summary_json, request_json, remark) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                groupName,
                sourceType,
                textOrNull(request.get("unitId")),
                textOrNull(request.get("unitName")),
                originRouteId,
                textOrNull(request.get("originRouteName")),
                textOrNull(request.get("planningStrategy")),
                text(request.get("defaultDisplayMode"), "DIRECT"),
                1,
                json(request.get("summary")),
                json(request),
                textOrNull(request.get("remark")));
        Long routeId = saveRouteInternal(groupId, route, toInt(route.get("routeNo"), 1));
        refreshGroupRouteCount(groupId);
        return route(routeId);
    }

    public List<Map<String, Object>> groups(String unitId, String sourceType, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT * FROM zhj_route_plan_group WHERE been_deleted = 0");
        List<Object> args = new ArrayList<>();
        if (hasText(unitId)) {
            sql.append(" AND unit_id = ?");
            args.add(unitId);
        }
        if (hasText(sourceType)) {
            sql.append(" AND source_type = ?");
            args.add(sourceType);
        }
        if (hasText(keyword)) {
            sql.append(" AND (group_name LIKE ? OR unit_name LIKE ? OR origin_route_name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY create_time DESC LIMIT 200");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray()).stream()
                .map(this::groupRow)
                .collect(Collectors.toList());
    }

    public Map<String, Object> group(Long id) {
        Map<String, Object> group = groupRow(jdbcTemplate.queryForMap(
                "SELECT * FROM zhj_route_plan_group WHERE id = ? AND been_deleted = 0", id));
        List<Map<String, Object>> routes = jdbcTemplate.queryForList(
                        "SELECT * FROM zhj_route_plan_route WHERE group_id = ? AND been_deleted = 0 ORDER BY route_no, id", id)
                .stream()
                .map(this::routeWithChildren)
                .collect(Collectors.toList());
        group.put("routes", routes);
        return group;
    }

    public Map<String, Object> route(Long id) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM zhj_route_plan_route WHERE id = ? AND been_deleted = 0", id);
        return routeWithChildren(row);
    }

    @Transactional
    public Map<String, Object> deleteGroup(Long id) {
        jdbcTemplate.update("UPDATE zhj_route_plan_group SET been_deleted = 1 WHERE id = ?", id);
        jdbcTemplate.update("UPDATE zhj_route_plan_route SET been_deleted = 1 WHERE group_id = ?", id);
        Map<String, Object> result = new HashMap<>();
        result.put("deleted", true);
        result.put("id", id);
        return result;
    }

    @Transactional
    public Map<String, Object> deleteRoute(Long id) {
        Long groupId = jdbcTemplate.queryForObject("SELECT group_id FROM zhj_route_plan_route WHERE id = ?", Long.class, id);
        jdbcTemplate.update("UPDATE zhj_route_plan_route SET been_deleted = 1 WHERE id = ?", id);
        refreshGroupRouteCount(groupId);
        Map<String, Object> result = new HashMap<>();
        result.put("deleted", true);
        result.put("id", id);
        result.put("groupId", groupId);
        return result;
    }

    private Long saveRouteInternal(Long groupId, Map<String, Object> route, int fallbackRouteNo) {
        List<Map<String, Object>> points = routePoints(route);
        List<Map<String, Object>> segments = routeSegments(route);
        int routeNo = toInt(route.get("routeNo"), fallbackRouteNo);
        String routeName = text(route.get("routeName"), "第" + routeNo + "趟");
        Double distance = firstDouble(route, "distance", "optimizedDistance", "displayDistance");
        Double roadDistance = firstDouble(route, "roadDistance", "roadDistanceM");
        Double travelDuration = firstDouble(route, "travelDurationMinutes", "pathDurationMinutes", "roadDurationMinutes", "durationMinutes");
        Double operationDuration = firstDouble(route, "operationDurationMinutes", "operationDurationMin");
        Double totalDuration = firstDouble(route, "totalDurationMinutes", "durationMinutes");
        if (totalDuration == null && (travelDuration != null || operationDuration != null)) {
            totalDuration = number(travelDuration) + number(operationDuration);
        }
        Long routeId = insert("INSERT INTO zhj_route_plan_route "
                        + "(group_id, route_name, route_no, vehicle_id, vehicle_name, vehicle_type, trip_no, point_count, "
                        + "distance_m, road_distance_m, travel_duration_min, operation_duration_min, total_duration_min, "
                        + "estimated_weight_kg, estimated_volume_liter, load_rate, path_source_summary, route_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                groupId,
                routeName,
                routeNo,
                textOrNull(route.get("vehicleId")),
                textOrNull(route.get("vehicleName")),
                textOrNull(route.get("vehicleType")),
                toInteger(route.get("tripNo")),
                Math.max(0, points.size() - 2),
                decimal(distance),
                decimal(roadDistance),
                decimal(travelDuration),
                decimal(operationDuration),
                decimal(totalDuration),
                decimal(firstDouble(route, "estimatedWeightKg", "weightKg")),
                decimal(firstDouble(route, "estimatedVolumeLiter", "estimatedVolumeL", "volumeLiter")),
                decimal(firstDouble(route, "loadRate")),
                text(route.get("pathSourceSummary"), pathSourceSummary(segments)),
                json(route));
        savePoints(routeId, points);
        saveSegments(routeId, segments);
        return routeId;
    }

    private void savePoints(Long routeId, List<Map<String, Object>> points) {
        for (int i = 0; i < points.size(); i++) {
            Map<String, Object> point = points.get(i);
            int order = toInt(point.get("order"), toInt(point.get("pointOrder"), i + 1));
            String role = text(point.get("role"), i == 0 ? "START" : (i == points.size() - 1 ? "END" : "MIDDLE"));
            jdbcTemplate.update("INSERT INTO zhj_route_plan_point "
                            + "(route_id, point_order, role, facility_id, facility_name, longitude, latitude, estimated_weight_kg, "
                            + "estimated_volume_liter, container_info, container_count, operation_duration_min, point_json) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    routeId,
                    order,
                    role,
                    toLong(point.get("facilityId")),
                    textOrNull(point.get("facilityName")),
                    decimal(firstDouble(point, "longitude", "lng")),
                    decimal(firstDouble(point, "latitude", "lat")),
                    decimal(firstDouble(point, "estimatedWeightKg", "weightKg")),
                    decimal(firstDouble(point, "estimatedVolumeLiter", "estimatedVolumeL", "volumeLiter")),
                    textOrNull(point.get("containerInfo")),
                    decimal(firstDouble(point, "containerCount")),
                    decimal(firstDouble(point, "operationDurationMinutes", "operationDurationMin")),
                    json(point));
        }
    }

    private void saveSegments(Long routeId, List<Map<String, Object>> segments) {
        for (int i = 0; i < segments.size(); i++) {
            Map<String, Object> segment = segments.get(i);
            int order = toInt(segment.get("order"), toInt(segment.get("segmentOrder"), i + 1));
            jdbcTemplate.update("INSERT INTO zhj_route_plan_segment "
                            + "(route_id, segment_order, from_facility_id, from_facility_name, to_facility_id, to_facility_name, "
                            + "distance_m, duration_min, path_source, path_json, segment_json) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    routeId,
                    order,
                    toLong(segment.get("fromFacilityId")),
                    textOrNull(segment.get("fromFacilityName")),
                    toLong(segment.get("toFacilityId")),
                    textOrNull(segment.get("toFacilityName")),
                    decimal(firstDouble(segment, "distance", "distanceM")),
                    decimal(firstDouble(segment, "durationMinutes", "durationMin")),
                    textOrNull(segment.get("pathSource")),
                    json(segment.get("path")),
                    json(segment));
        }
    }

    private Map<String, Object> routeWithChildren(Map<String, Object> row) {
        Map<String, Object> route = routeRow(row);
        Long routeId = toLong(row.get("id"));
        List<Map<String, Object>> points = jdbcTemplate.queryForList(
                        "SELECT * FROM zhj_route_plan_point WHERE route_id = ? ORDER BY point_order, id", routeId)
                .stream()
                .map(this::pointRow)
                .collect(Collectors.toList());
        List<Map<String, Object>> segments = jdbcTemplate.queryForList(
                        "SELECT * FROM zhj_route_plan_segment WHERE route_id = ? ORDER BY segment_order, id", routeId)
                .stream()
                .map(this::segmentRow)
                .collect(Collectors.toList());
        route.put("points", points);
        route.put("segments", segments);
        List<Map<String, Object>> roadSegments = segments.stream()
                .filter(segment -> isRoadPathSource(textOrNull(segment.get("pathSource"))))
                .collect(Collectors.toList());
        if (!roadSegments.isEmpty()) {
            route.put("roadSegments", roadSegments);
            if (route.get("roadDurationMinutes") == null) {
                route.put("roadDurationMinutes", route.get("travelDurationMinutes"));
            }
        }
        return route;
    }

    private Map<String, Object> groupRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("groupName", row.get("group_name"));
        result.put("sourceType", row.get("source_type"));
        result.put("unitId", row.get("unit_id"));
        result.put("unitName", row.get("unit_name"));
        result.put("originRouteId", row.get("origin_route_id"));
        result.put("originRouteName", row.get("origin_route_name"));
        result.put("planningStrategy", row.get("planning_strategy"));
        result.put("defaultDisplayMode", row.get("default_display_mode"));
        result.put("routeCount", row.get("route_count"));
        result.put("summary", parseJson(row.get("summary_json")));
        result.put("request", parseJson(row.get("request_json")));
        result.put("remark", row.get("remark"));
        result.put("createTime", row.get("create_time"));
        result.put("updateTime", row.get("update_time"));
        return result;
    }

    private Map<String, Object> routeRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("groupId", row.get("group_id"));
        result.put("routeName", row.get("route_name"));
        result.put("routeNo", row.get("route_no"));
        result.put("vehicleId", row.get("vehicle_id"));
        result.put("vehicleName", row.get("vehicle_name"));
        result.put("vehicleType", row.get("vehicle_type"));
        result.put("tripNo", row.get("trip_no"));
        result.put("pointCount", row.get("point_count"));
        result.put("distance", row.get("distance_m"));
        result.put("roadDistance", row.get("road_distance_m"));
        result.put("travelDurationMinutes", row.get("travel_duration_min"));
        result.put("operationDurationMinutes", row.get("operation_duration_min"));
        result.put("totalDurationMinutes", row.get("total_duration_min"));
        result.put("estimatedWeightKg", row.get("estimated_weight_kg"));
        result.put("estimatedVolumeLiter", row.get("estimated_volume_liter"));
        result.put("loadRate", row.get("load_rate"));
        result.put("pathSourceSummary", row.get("path_source_summary"));
        result.put("routeJson", parseJson(row.get("route_json")));
        result.put("createTime", row.get("create_time"));
        result.put("updateTime", row.get("update_time"));
        return result;
    }

    private Map<String, Object> pointRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("routeId", row.get("route_id"));
        result.put("order", row.get("point_order"));
        result.put("pointOrder", row.get("point_order"));
        result.put("role", row.get("role"));
        result.put("facilityId", row.get("facility_id"));
        result.put("facilityName", row.get("facility_name"));
        result.put("longitude", row.get("longitude"));
        result.put("latitude", row.get("latitude"));
        result.put("estimatedWeightKg", row.get("estimated_weight_kg"));
        result.put("estimatedVolumeLiter", row.get("estimated_volume_liter"));
        result.put("containerInfo", row.get("container_info"));
        result.put("containerCount", row.get("container_count"));
        result.put("operationDurationMinutes", row.get("operation_duration_min"));
        result.put("pointJson", parseJson(row.get("point_json")));
        return result;
    }

    private Map<String, Object> segmentRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("routeId", row.get("route_id"));
        result.put("order", row.get("segment_order"));
        result.put("segmentOrder", row.get("segment_order"));
        result.put("fromFacilityId", row.get("from_facility_id"));
        result.put("fromFacilityName", row.get("from_facility_name"));
        result.put("toFacilityId", row.get("to_facility_id"));
        result.put("toFacilityName", row.get("to_facility_name"));
        result.put("distance", row.get("distance_m"));
        result.put("durationMinutes", row.get("duration_min"));
        result.put("pathSource", row.get("path_source"));
        result.put("path", parseJson(row.get("path_json")));
        result.put("segmentJson", parseJson(row.get("segment_json")));
        return result;
    }

    private Long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    private void refreshGroupRouteCount(Long groupId) {
        if (groupId == null) return;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM zhj_route_plan_route WHERE group_id = ? AND been_deleted = 0", Integer.class, groupId);
        jdbcTemplate.update("UPDATE zhj_route_plan_group SET route_count = ? WHERE id = ?", count, groupId);
    }

    private List<Map<String, Object>> routePoints(Map<String, Object> route) {
        List<Map<String, Object>> points = listOfMaps(route.get("points"));
        if (points.isEmpty()) points = listOfMaps(route.get("optimizedPoints"));
        return points;
    }

    private List<Map<String, Object>> routeSegments(Map<String, Object> route) {
        List<Map<String, Object>> segments = listOfMaps(route.get("roadSegments"));
        if (segments.isEmpty()) segments = listOfMaps(route.get("segments"));
        return segments;
    }

    private String defaultGroupName(String sourceType) {
        String suffix;
        if ("SINGLE_OPTIMIZE".equals(sourceType)) suffix = "路线优化";
        else if ("SPLIT".equals(sourceType)) suffix = "路线拆分方案";
        else suffix = "多路线方案";
        return LocalDateTime.now().format(NAME_TIME) + "_" + suffix;
    }

    private String pathSourceSummary(List<Map<String, Object>> segments) {
        if (segments == null || segments.isEmpty()) return null;
        return segments.stream()
                .map(segment -> textOrNull(segment.get("pathSource")))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("/"));
    }

    private boolean isRoadPathSource(String source) {
        return "OD_CACHE".equals(source) || "OD_PRELOAD".equals(source) || "BAIDU_ONLINE".equals(source);
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List)) return Collections.emptyList();
        List<?> raw = (List<?>) value;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : raw) {
            Map<String, Object> map = mapValue(item);
            if (!map.isEmpty()) result.add(map);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) value);
        }
        return new LinkedHashMap<>();
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Object parseJson(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        if (!hasText(text)) return null;
        try {
            return objectMapper.readValue(text, new TypeReference<Object>() {});
        } catch (Exception e) {
            return text;
        }
    }

    private Double firstDouble(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Double value = toDouble(map.get(key));
            if (value != null) return value;
        }
        return null;
    }

    private BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private double number(Double value) {
        return value == null ? 0 : value;
    }

    private Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) return null;
            return Double.parseDouble(text);
        } catch (Exception e) {
            return null;
        }
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) return null;
            return Long.parseLong(text);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer toInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) return null;
            return Integer.parseInt(text);
        } catch (Exception e) {
            return null;
        }
    }

    private int toInt(Object value, int fallback) {
        Integer result = toInteger(value);
        return result == null ? fallback : result;
    }

    private String text(Object value, String fallback) {
        String text = textOrNull(value);
        return text == null ? fallback : text;
    }

    private String textOrNull(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
