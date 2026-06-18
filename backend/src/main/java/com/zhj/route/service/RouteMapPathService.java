package com.zhj.route.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhj.route.algorithm.RoutePoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RouteMapPathService {
    private static final String BAIDU_DRIVING_URL = "http://api.map.baidu.com/directionlite/v1/driving";
    private static final String BAIDU_DRIVING_PATH = "/directionlite/v1/driving?";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final boolean onlineRouteEnabled;
    private final String baiduAk;
    private final String baiduSk;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public RouteMapPathService(JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper,
                               @Value("${app.baidu-route.enabled:false}") boolean onlineRouteEnabled,
                               @Value("${app.baidu-route.ak:}") String baiduAk,
                               @Value("${app.baidu-route.sk:}") String baiduSk,
                               @Value("${app.baidu-route.connect-timeout-ms:2000}") int connectTimeoutMs,
                               @Value("${app.baidu-route.read-timeout-ms:5000}") int readTimeoutMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.onlineRouteEnabled = onlineRouteEnabled;
        this.baiduAk = baiduAk;
        this.baiduSk = baiduSk;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(requestFactory);
    }

    public ResolvedPath resolve(RoutePoint from, RoutePoint to) {
        ResolvedPath cached = cachedPath(from, to);
        if (cached != null) {
            return cached;
        }
        ResolvedPath online = onlinePath(from, to);
        if (online != null) {
            return online;
        }
        return directPath(from, to);
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new HashMap<String, Object>();
        status.put("onlineRouteEnabled", onlineRouteEnabled);
        status.put("baiduAkConfigured", !isBlank(baiduAk));
        status.put("baiduSkConfigured", !isBlank(baiduSk));
        status.put("connectTimeoutMs", connectTimeoutMs);
        status.put("readTimeoutMs", readTimeoutMs);
        status.put("cacheTable", "ljszy_odpair_pool");
        status.put("fallback", "DIRECT");
        return status;
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

    private ResolvedPath onlinePath(RoutePoint from, RoutePoint to) {
        if (!onlineRouteEnabled || isBlank(baiduAk) || !from.hasCoordinate() || !to.hasCoordinate()) {
            return null;
        }
        try {
            BaiduRouteResponse response = requestBaiduRoute(from, to);
            if (response == null || response.path.size() < 2) {
                return null;
            }
            cacheOnlinePath(from, to, response);
            return new ResolvedPath(response.path, response.distanceMeters, response.durationSeconds, "BAIDU_ONLINE");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private BaiduRouteResponse requestBaiduRoute(RoutePoint from, RoutePoint to) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("ak", baiduAk);
        params.put("origin", from.getLatitude() + "," + from.getLongitude());
        params.put("destination", to.getLatitude() + "," + to.getLongitude());
        params.put("tactics", "0");
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        if (!isBlank(baiduSk)) {
            String sn = sign(params);
            if (!isBlank(sn)) {
                params.put("sn", sn);
            }
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(BAIDU_DRIVING_URL);
        for (Map.Entry<String, String> entry : params.entrySet()) {
            builder.queryParam(entry.getKey(), entry.getValue());
        }

        try {
            String json = restTemplate.getForObject(builder.build().encode().toUri(), String.class);
            return parseBaiduResponse(json);
        } catch (RestClientException e) {
            return null;
        }
    }

    private BaiduRouteResponse parseBaiduResponse(String json) {
        if (isBlank(json)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode routes = root.path("result").path("routes");
            if (!routes.isArray() || routes.size() == 0) {
                return null;
            }
            JsonNode firstRoute = routes.get(0);
            List<Map<String, Object>> path = parseBaiduPath(json);
            if (path.size() < 2) {
                return null;
            }
            return new BaiduRouteResponse(
                    path,
                    firstRoute.path("distance").isNumber() ? firstRoute.path("distance").asDouble() : null,
                    firstRoute.path("duration").isNumber() ? firstRoute.path("duration").asDouble() : null,
                    json);
        } catch (Exception e) {
            return null;
        }
    }

    private void cacheOnlinePath(RoutePoint from, RoutePoint to, BaiduRouteResponse response) {
        if (from.getFacilityId() == null || to.getFacilityId() == null) {
            return;
        }
        String sql = "INSERT INTO ljszy_odpair_pool (" +
                "been_deleted, create_time, update_time, company_id, distance, end_code, " +
                "latitude_end, latitude_start, longitude_end, longitude_start, start_code, time_duration, msg_full" +
                ") VALUES (0, NOW(), NOW(), NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            jdbcTemplate.update(sql,
                    response.distanceMeters,
                    String.valueOf(to.getFacilityId()),
                    to.getLatitude(),
                    from.getLatitude(),
                    to.getLongitude(),
                    from.getLongitude(),
                    String.valueOf(from.getFacilityId()),
                    response.durationSeconds,
                    response.rawJson);
        } catch (RuntimeException e) {
            // 缓存写入失败不影响本次路线展示。
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

    private String sign(Map<String, String> params) {
        try {
            StringBuilder query = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (query.length() > 0) {
                    query.append("&");
                }
                query.append(entry.getKey())
                        .append("=")
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8.name()));
            }
            String whole = BAIDU_DRIVING_PATH + query + baiduSk;
            String encoded = URLEncoder.encode(whole, StandardCharsets.UTF_8.name());
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(encoded.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(Integer.toHexString((b & 0xFF) | 0x100), 1, 3);
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class BaiduRouteResponse {
        private final List<Map<String, Object>> path;
        private final Double distanceMeters;
        private final Double durationSeconds;
        private final String rawJson;

        private BaiduRouteResponse(List<Map<String, Object>> path, Double distanceMeters,
                                   Double durationSeconds, String rawJson) {
            this.path = path;
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
            this.rawJson = rawJson;
        }
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
