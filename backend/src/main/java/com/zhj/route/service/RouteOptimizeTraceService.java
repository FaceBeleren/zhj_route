package com.zhj.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Optional insertion trace. A missing trace table must never block route generation. */
@Service
public class RouteOptimizeTraceService {
    private static final Logger log = LoggerFactory.getLogger(RouteOptimizeTraceService.class);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private volatile boolean tableUnavailableLogged;

    public RouteOptimizeTraceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public TraceHandle start(Map<String, Object> request, List<Map<String, Object>> points, String unitId, Long routeId, String strategy, String dispatchMode) {
        if (!enabled(request)) return null;
        String traceId = UUID.randomUUID().toString();
        try {
            jdbcTemplate.update("INSERT INTO zhj_route_optimize_trace_run (trace_id, task_id, source_type, route_id, unit_id, strategy, distance_mode, dispatch_mode, point_count, request_json, point_snapshot_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    traceId, text(request.get("taskId")), text(request.get("sourceType")), routeId, unitId, strategy,
                    strategyUsesRoad(strategy) ? "ROAD" : "DIRECT", dispatchMode, points.size(), json(request), json(points));
            return new TraceHandle(traceId);
        } catch (RuntimeException ex) { warnUnavailable(ex); return null; }
    }

    public void step(TraceHandle trace, int routeNo, String vehicleId, int tripNo, int iteration, List<Map<String, Object>> routeSnapshot, Map<String, Object> selected, List<Map<String, Object>> topCandidates, int remainingCount, int candidateCount, int eligibleCount, double routeDistance, double travelMinutes, double operationMinutes, double totalMinutes, double loadKg, String distanceSource) {
        if (trace == null) return;
        try {
            jdbcTemplate.update("INSERT INTO zhj_route_optimize_trace_step (trace_id, route_no, vehicle_id, trip_no, iteration_no, before_route_size, after_route_size, selected_facility_id, selected_facility_name, insert_index, previous_facility_id, previous_facility_name, next_facility_id, next_facility_name, increase_distance_m, route_distance_m, travel_duration_min, operation_duration_min, total_duration_min, current_load_kg, remaining_count, candidate_count, eligible_candidate_count, distance_source, decision_reason, top_candidates_json, route_snapshot_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    trace.traceId, routeNo, vehicleId, tripNo, iteration, routeSnapshot.size() - 1, routeSnapshot.size(), selected.get("facilityId"), selected.get("facilityName"), selected.get("insertIndex"), selected.get("previousFacilityId"), selected.get("previousFacilityName"), selected.get("nextFacilityId"), selected.get("nextFacilityName"), selected.get("increaseDistanceMeters"), routeDistance, travelMinutes, operationMinutes, totalMinutes, loadKg, remainingCount, candidateCount, eligibleCount, distanceSource, "MIN_INSERTION", json(topCandidates), json(routeSnapshot));
        } catch (RuntimeException ex) { warnUnavailable(ex); }
    }

    public void finish(TraceHandle trace, String status, long elapsedMs) {
        if (trace == null) return;
        try { jdbcTemplate.update("UPDATE zhj_route_optimize_trace_run SET status=?, elapsed_ms=?, finish_time=NOW() WHERE trace_id=?", status, elapsedMs, trace.traceId); }
        catch (RuntimeException ex) { warnUnavailable(ex); }
    }

    public static Map<String, Object> candidate(long id, String name, int insertIndex, double increase) {
        Map<String, Object> row = new HashMap<String, Object>(); row.put("facilityId", id); row.put("facilityName", name); row.put("insertIndex", insertIndex); row.put("increaseMeters", increase); return row;
    }

    public static class TraceHandle { private final String traceId; private TraceHandle(String traceId) { this.traceId = traceId; } }
    private boolean enabled(Map<String, Object> request) { Object value = request.get("traceEnabled"); return value != null && Boolean.parseBoolean(String.valueOf(value)); }
    private boolean strategyUsesRoad(String strategy) { return "ROAD_GLOBAL".equals(strategy); }
    private String text(Object value) { return value == null ? null : String.valueOf(value); }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception ex) { return "{}"; } }
    private void warnUnavailable(RuntimeException ex) { if (!tableUnavailableLogged) { tableUnavailableLogged = true; log.warn("Route optimize trace disabled for this run; trace tables may not exist: {}", ex.getMessage()); } }
}
