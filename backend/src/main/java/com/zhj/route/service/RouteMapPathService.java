package com.zhj.route.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhj.route.algorithm.RoutePoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RouteMapPathService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RouteMapPathService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ResolvedPath resolve(RoutePoint from, RoutePoint to) {
        ResolvedPath cached = cachedPath(from, to);
        if (cached != null) {
            return cached;
        }
        return directPath(from, to);
    }

    private ResolvedPath cachedPath(RoutePoint from, RoutePoint to) {
        if (from.getFacilityId() == null || to.getFacilityId() == null) {
            return null;
        }
        String sql = "SELECT distance, time_duration, msg_full " +
                "FROM ljszy_odpair_pool " +
                "WHERE been_deleted = 0 AND start_code = ? AND end_code = ? AND msg_full IS NOT NULL " +
                "ORDER BY create_time DESC LIMIT 1";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    sql,
                    String.valueOf(from.getFacilityId()),
                    String.valueOf(to.getFacilityId()));
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            List<Map<String, Object>> path = parseBaiduPath(String.valueOf(row.get("msg_full")));
            if (path.size() < 2) {
                return null;
            }
            return new ResolvedPath(path, toDouble(row.get("distance")), toDouble(row.get("time_duration")), "OD_CACHE");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<Map<String, Object>> parseBaiduPath(String json) {
        List<Map<String, Object>> path = new ArrayList<Map<String, Object>>();
        if (json == null || json.trim().isEmpty() || "null".equals(json)) {
            return path;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode routes = root.path("result").path("routes");
            if (!routes.isArray()) {
                return path;
            }
            for (JsonNode route : routes) {
                JsonNode steps = route.path("steps");
                if (!steps.isArray()) {
                    continue;
                }
                for (JsonNode step : steps) {
                    appendPath(path, step.path("path").asText(""));
                }
                if (path.size() >= 2) {
                    break;
                }
            }
        } catch (Exception e) {
            path.clear();
        }
        return path;
    }

    private void appendPath(List<Map<String, Object>> path, String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return;
        }
        String[] coordinates = rawPath.split(";");
        for (String coordinate : coordinates) {
            String[] parts = coordinate.split(",");
            if (parts.length != 2) {
                continue;
            }
            Double longitude = toDouble(parts[0]);
            Double latitude = toDouble(parts[1]);
            if (longitude == null || latitude == null) {
                continue;
            }
            Map<String, Object> point = coordinate(longitude, latitude);
            if (!path.isEmpty() && sameCoordinate(path.get(path.size() - 1), point)) {
                continue;
            }
            path.add(point);
        }
    }

    private ResolvedPath directPath(RoutePoint from, RoutePoint to) {
        List<Map<String, Object>> path = new ArrayList<Map<String, Object>>();
        if (from.hasCoordinate()) {
            path.add(coordinate(from));
        }
        if (to.hasCoordinate()) {
            path.add(coordinate(to));
        }
        return new ResolvedPath(path, null, null, "DIRECT");
    }

    private Map<String, Object> coordinate(RoutePoint point) {
        Map<String, Object> coordinate = coordinate(point.getLongitude(), point.getLatitude());
        coordinate.put("facilityId", point.getFacilityId());
        return coordinate;
    }

    private Map<String, Object> coordinate(Double longitude, Double latitude) {
        Map<String, Object> coordinate = new HashMap<String, Object>();
        coordinate.put("longitude", longitude);
        coordinate.put("latitude", latitude);
        return coordinate;
    }

    private boolean sameCoordinate(Map<String, Object> a, Map<String, Object> b) {
        Double aLongitude = toDouble(a.get("longitude"));
        Double aLatitude = toDouble(a.get("latitude"));
        Double bLongitude = toDouble(b.get("longitude"));
        Double bLatitude = toDouble(b.get("latitude"));
        if (aLongitude == null || aLatitude == null || bLongitude == null || bLatitude == null) {
            return false;
        }
        return Math.abs(aLongitude - bLongitude) < 0.000001 && Math.abs(aLatitude - bLatitude) < 0.000001;
    }

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return Double.valueOf(text);
    }

    public static class ResolvedPath {
        private final List<Map<String, Object>> path;
        private final Double distanceMeters;
        private final Double durationSeconds;
        private final String source;

        public ResolvedPath(List<Map<String, Object>> path, Double distanceMeters, Double durationSeconds, String source) {
            this.path = path;
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
            this.source = source;
        }

        public List<Map<String, Object>> getPath() {
            return path;
        }

        public Double getDistanceMeters() {
            return distanceMeters;
        }

        public Double getDurationSeconds() {
            return durationSeconds;
        }

        public String getSource() {
            return source;
        }
    }
}
