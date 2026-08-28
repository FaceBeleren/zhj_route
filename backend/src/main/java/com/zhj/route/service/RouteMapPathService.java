package com.zhj.route.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhj.route.algorithm.RoutePoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RouteMapPathService {
    private static final Logger log = LoggerFactory.getLogger(RouteMapPathService.class);
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

    public ResolvedPath resolveWithoutCache(RoutePoint from, RoutePoint to) {
        if (!onlineRouteEnabled || isBlank(baiduAk) || !from.hasCoordinate() || !to.hasCoordinate()) {
            return directPath(from, to);
        }
        ResolvedPath online = onlinePath(from, to);
        if (online != null) {
            return online;
        }
        return directPath(from, to);
    }

    public Map<String, ResolvedPath> preloadCachedPaths(List<RoutePoint> points) {
        long startedAt = System.currentTimeMillis();
        Map<String, ResolvedPath> cachedPaths = new HashMap<String, ResolvedPath>();
        List<String> facilityIds = cacheableFacilityIds(points);
        if (facilityIds.size() < 2) {
            log.info("OD preload skipped: cacheablePointCount={}", facilityIds.size());
            return cachedPaths;
        }

        String placeholders = placeholders(facilityIds.size());
        String sql = "SELECT /*+ MAX_EXECUTION_TIME(15000) */ start_code, end_code, distance, time_duration, msg_full, latitude_end, latitude_start, longitude_end, longitude_start " +
                "FROM ljszy_odpair_pool " +
                "WHERE been_deleted = 0 AND msg_full IS NOT NULL " +
                "AND start_code IN (" + placeholders + ") " +
                "AND end_code IN (" + placeholders + ") " +
                "ORDER BY create_time DESC";
        List<Object> args = new ArrayList<Object>();
        args.addAll(facilityIds);
        args.addAll(facilityIds);

        try {
            log.info("OD preload query starting: cacheablePoints={}, ids={}", facilityIds.size(), facilityIds.size());
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
            int invalidRows = 0;
            for (Map<String, Object> row : rows) {
                String startCode = String.valueOf(row.get("start_code"));
                String endCode = String.valueOf(row.get("end_code"));
                String key = pathKey(startCode, endCode);
                if (cachedPaths.containsKey(key)) {
                    continue;
                }
                List<Map<String, Object>> path = parseBaiduPath(String.valueOf(row.get("msg_full")));
            path = withEndpoints(path, toDouble(row.get("longitude_start")), toDouble(row.get("latitude_start")), toDouble(row.get("longitude_end")), toDouble(row.get("latitude_end")));
                if (path.size() < 2) {
                    invalidRows++;
                    continue;
                }
                cachedPaths.put(key, new ResolvedPath(path,
                        toDouble(row.get("distance")),
                        toDouble(row.get("time_duration")),
                        "OD_PRELOAD"));
            }
            log.info("OD preload finished: cacheablePoints={}, rows={}, validPairs={}, invalidRows={}, elapsed={}ms",
                    facilityIds.size(), rows.size(), cachedPaths.size(), invalidRows, System.currentTimeMillis() - startedAt);
        } catch (RuntimeException e) {
            log.warn("OD preload failed: cacheablePoints={}, elapsed={}ms, {}",
                    facilityIds.size(), System.currentTimeMillis() - startedAt, e.getMessage());
        }
        return cachedPaths;
    }

    public Map<String, Object> preview(Map<String, Object> request) {
        RoutePoint from = previewPoint(request, "from");
        RoutePoint to = previewPoint(request, "to");
        ResolvedPath resolvedPath = resolve(from, to);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("fromFacilityId", from.getFacilityId());
        result.put("toFacilityId", to.getFacilityId());
        result.put("pathSource", resolvedPath.getSource());
        result.put("distanceMeters", resolvedPath.getDistanceMeters());
        result.put("durationSeconds", resolvedPath.getDurationSeconds());
        result.put("pathPointCount", resolvedPath.getPath().size());
        result.put("pathSample", resolvedPath.getPath().subList(0, Math.min(5, resolvedPath.getPath().size())));
        return result;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new HashMap<String, Object>();
        status.put("onlineRouteEnabled", onlineRouteEnabled);
        status.put("baiduAkConfigured", !isBlank(baiduAk));
        status.put("baiduSkConfigured", !isBlank(baiduSk));
        status.put("onlineReady", onlineRouteEnabled && !isBlank(baiduAk));
        status.put("connectTimeoutMs", connectTimeoutMs);
        status.put("readTimeoutMs", readTimeoutMs);
        status.put("cacheTable", "ljszy_odpair_pool");
        status.putAll(cacheStatus());
        status.put("fallback", "DIRECT");
        return status;
    }

    private Map<String, Object> cacheStatus() {
        Map<String, Object> status = new HashMap<String, Object>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS latestCacheTime " +
                            "FROM ljszy_odpair_pool WHERE been_deleted = 0 ORDER BY create_time DESC LIMIT 1");
            status.put("cacheAvailable", true);
            status.put("cacheHasRows", !rows.isEmpty());
            status.put("latestCacheTime", rows.isEmpty() ? null : rows.get(0).get("latestCacheTime"));
            status.put("cacheCheckMessage", "OK");
        } catch (RuntimeException e) {
            status.put("cacheAvailable", false);
            status.put("cacheHasRows", false);
            status.put("latestCacheTime", null);
            status.put("cacheCheckMessage", e.getClass().getSimpleName());
        }
        return status;
    }

    private ResolvedPath cachedPath(RoutePoint from, RoutePoint to) {
        if (!isCacheableFacility(from) || !isCacheableFacility(to)) {
            log.debug("OD cache skipped: non-cacheable pair {} -> {}", pointLabel(from), pointLabel(to));
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
                log.debug("OD cache miss: {} -> {}", pointLabel(from), pointLabel(to));
                return null;
            }
            Map<String, Object> row = rows.get(0);
            List<Map<String, Object>> path = parseBaiduPath(String.valueOf(row.get("msg_full")));
            path = withEndpoints(path, from, to);
            if (path.size() < 2) {
                log.warn("OD cache invalid path: {} -> {}, distance={}", pointLabel(from), pointLabel(to), row.get("distance"));
                return null;
            }
            log.debug("OD cache hit: {} -> {}, distance={}m, duration={}s, pathPoints={}",
                    pointLabel(from), pointLabel(to), row.get("distance"), row.get("time_duration"), path.size());
            return new ResolvedPath(path, toDouble(row.get("distance")), toDouble(row.get("time_duration")), "OD_CACHE");
        } catch (RuntimeException e) {
            log.warn("OD cache query failed: {} -> {}, {}", pointLabel(from), pointLabel(to), e.getMessage());
            return null;
        }
    }

    private ResolvedPath onlinePath(RoutePoint from, RoutePoint to) {
        if (!onlineRouteEnabled) {
            log.info("Baidu route skipped: disabled, {} -> {}", pointLabel(from), pointLabel(to));
            return null;
        }
        if (isBlank(baiduAk)) {
            log.warn("Baidu route skipped: AK is blank, {} -> {}", pointLabel(from), pointLabel(to));
            return null;
        }
        if (!from.hasCoordinate() || !to.hasCoordinate()) {
            log.warn("Baidu route skipped: missing coordinate, {} -> {}", pointLabel(from), pointLabel(to));
            return null;
        }
        try {
            BaiduRouteResponse response = requestBaiduRoute(from, to);
            if (response == null || response.path.size() < 2) {
                log.warn("Baidu route returned no usable path: {} -> {}", pointLabel(from), pointLabel(to));
                return null;
            }
            cacheOnlinePath(from, to, response);
            return new ResolvedPath(withEndpoints(response.path, from, to), response.distanceMeters, response.durationSeconds, "BAIDU_ONLINE");
        } catch (RuntimeException e) {
            log.warn("Baidu route failed: {} -> {}, {}", pointLabel(from), pointLabel(to), e.getMessage());
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

        long startedAt = System.currentTimeMillis();
        try {
            URI uri = URI.create(BAIDU_DRIVING_URL + "?" + toQueryString(params));
            log.info("Calling Baidu route: {} -> {}, snConfigured={}, timeout={}/{}ms",
                    pointLabel(from), pointLabel(to), !isBlank(baiduSk), connectTimeoutMs, readTimeoutMs);
            String json = restTemplate.getForObject(uri, String.class);
            BaiduRouteResponse response = parseBaiduResponse(json);
            log.info("Baidu route finished: {} -> {}, elapsed={}ms, distance={}m, duration={}s, pathPoints={}",
                    pointLabel(from), pointLabel(to), System.currentTimeMillis() - startedAt,
                    response == null ? null : response.distanceMeters,
                    response == null ? null : response.durationSeconds,
                    response == null || response.path == null ? 0 : response.path.size());
            return response;
        } catch (RestClientException e) {
            log.warn("Baidu route HTTP failed: {} -> {}, elapsed={}ms, {}",
                    pointLabel(from), pointLabel(to), System.currentTimeMillis() - startedAt, e.getMessage());
            return null;
        }
    }

    private BaiduRouteResponse parseBaiduResponse(String json) {
        if (isBlank(json)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode status = root.path("status");
            JsonNode message = root.path("message");
            JsonNode routes = root.path("result").path("routes");
            if (!routes.isArray() || routes.size() == 0) {
                log.warn("Baidu route parse failed: status={}, message={}", status.asText(""), message.asText(""));
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
            log.warn("Baidu route parse exception: {}", e.getMessage());
            return null;
        }
    }

    private void cacheOnlinePath(RoutePoint from, RoutePoint to, BaiduRouteResponse response) {
        if (!isCacheableFacility(from) || !isCacheableFacility(to)) {
            log.debug("OD cache write skipped: non-cacheable pair {} -> {}", pointLabel(from), pointLabel(to));
            return;
        }
        String sql = "INSERT INTO ljszy_odpair_pool (" +
                "been_deleted, create_time, update_time, company_id, distance, end_code, " +
                "latitude_end, latitude_start, longitude_end, longitude_start, start_code, time_duration, msg_full" +
                ") VALUES (0, NOW(), NOW(), NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            int updated = jdbcTemplate.update(sql,
                    response.distanceMeters,
                    String.valueOf(to.getFacilityId()),
                    to.getLatitude(),
                    from.getLatitude(),
                    to.getLongitude(),
                    from.getLongitude(),
                    String.valueOf(from.getFacilityId()),
                    response.durationSeconds,
                    response.rawJson);
            log.info("OD cache write: {} -> {}, rows={}, distance={}m, duration={}s",
                    pointLabel(from), pointLabel(to), updated, response.distanceMeters, response.durationSeconds);
        } catch (RuntimeException e) {
            log.warn("OD cache write failed: {} -> {}, {}", pointLabel(from), pointLabel(to), e.getMessage());
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

    private List<Map<String, Object>> withEndpoints(List<Map<String, Object>> path, RoutePoint from, RoutePoint to) {
        return withEndpoints(path,
                from == null ? null : from.getLongitude(),
                from == null ? null : from.getLatitude(),
                to == null ? null : to.getLongitude(),
                to == null ? null : to.getLatitude());
    }

    private List<Map<String, Object>> withEndpoints(List<Map<String, Object>> path,
                                                     Double startLongitude,
                                                     Double startLatitude,
                                                     Double endLongitude,
                                                     Double endLatitude) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (path != null) {
            result.addAll(path);
        }
        if (startLongitude != null && startLatitude != null) {
            Map<String, Object> start = coordinate(startLongitude, startLatitude);
            if (result.isEmpty() || !sameCoordinate(start, result.get(0))) {
                result.add(0, start);
            }
        }
        if (endLongitude != null && endLatitude != null) {
            Map<String, Object> end = coordinate(endLongitude, endLatitude);
            if (result.isEmpty() || !sameCoordinate(end, result.get(result.size() - 1))) {
                result.add(end);
            }
        }
        return result;
    }

    private ResolvedPath directPath(RoutePoint from, RoutePoint to) {
        log.debug("Route path fallback to direct line: {} -> {}", pointLabel(from), pointLabel(to));
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
        coordinate.put("sourceFacilityId", point.getSourceFacilityId());
        return coordinate;
    }

    private Map<String, Object> coordinate(Double longitude, Double latitude) {
        Map<String, Object> coordinate = new HashMap<String, Object>();
        coordinate.put("longitude", longitude);
        coordinate.put("latitude", latitude);
        return coordinate;
    }

    public String pathKey(RoutePoint from, RoutePoint to) {
        return pathKey(pointCode(from), pointCode(to));
    }

    private String pathKey(String startCode, String endCode) {
        return startCode + "->" + endCode;
    }

    private List<String> cacheableFacilityIds(List<RoutePoint> points) {
        Set<String> ids = new LinkedHashSet<String>();
        if (points == null) {
            return new ArrayList<String>();
        }
        for (RoutePoint point : points) {
            if (isCacheableFacility(point)) {
                ids.add(pointCode(point));
            }
        }
        return new ArrayList<String>(ids);
    }

    private String placeholders(int size) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                builder.append(",");
            }
            builder.append("?");
        }
        return builder.toString();
    }

    private boolean isCacheableFacility(RoutePoint point) {
        return point != null && pointCode(point) != null && !pointCode(point).isEmpty();
    }

    private String pointCode(RoutePoint point) {
        if (point == null) return null;
        if (point.getSourceFacilityId() != null && !point.getSourceFacilityId().trim().isEmpty()) {
            String source = point.getSourceFacilityId().trim();
            try {
                return Long.parseLong(source) > 0 ? source : null;
            } catch (NumberFormatException ignored) {
                return source;
            }
        }
        return point.getFacilityId() != null && point.getFacilityId() > 0
                ? String.valueOf(point.getFacilityId()) : null;
    }

    private String pointLabel(RoutePoint point) {
        if (point == null) {
            return "null";
        }
        String name = point.getFacilityName() == null ? "" : "/" + point.getFacilityName();
        if (pointCode(point) != null) {
            return pointCode(point) + name;
        }
        return "XY(" + point.getLongitude() + "," + point.getLatitude() + ")" + name;
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

    private RoutePoint previewPoint(Map<String, Object> request, String prefix) {
        String sourceFacilityId = request.get(prefix + "SourceFacilityId") == null
                ? (request.get(prefix + "FacilityId") == null ? null : String.valueOf(request.get(prefix + "FacilityId")).trim())
                : String.valueOf(request.get(prefix + "SourceFacilityId")).trim();
        Long numericFacilityId = null;
        try {
            if (sourceFacilityId != null && !sourceFacilityId.isEmpty()) numericFacilityId = Long.valueOf(sourceFacilityId);
        } catch (NumberFormatException ignored) {
            // Parking-lot IDs may be UUIDs; keep them as sourceFacilityId.
        }
        return new RoutePoint(
                numericFacilityId,
                sourceFacilityId,
                null,
                toDouble(request.get(prefix + "Longitude")),
                toDouble(request.get(prefix + "Latitude")),
                null,
                null,
                null,
                null,
                null,
                null,
                "PREVIEW");
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return Long.valueOf(text);
    }

    private String toQueryString(Map<String, String> params) {
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
            return query.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String sign(Map<String, String> params) {
        try {
            String whole = BAIDU_DRIVING_PATH + toQueryString(params) + baiduSk;
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

