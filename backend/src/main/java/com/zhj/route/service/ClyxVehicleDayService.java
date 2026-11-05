package com.zhj.route.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only adapter for the Clyx daily report and actual Baidu-coordinate GPS track. */
@Service
public class ClyxVehicleDayService {
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private final FlowAnalysisService flowAnalysisService;
    private final ObjectMapper objectMapper;
    private final RestTemplate http;
    private final String baseUrl;
    private final String unitId;
    private final String tenantId;
    private final String userId;

    public ClyxVehicleDayService(FlowAnalysisService flowAnalysisService, ObjectMapper objectMapper,
            @Value("${app.clyx.base-url:http://39.106.23.248:18080/cloud/clyx}") String baseUrl,
            @Value("${app.clyx.zibo-unit-id:69746e1213194138859f734fca25cc1a}") String unitId,
            @Value("${app.clyx.zibo-tenant-id:64a121438d1e440ba9a0bbde10439c25}") String tenantId,
            @Value("${app.clyx.zibo-user-id:90186cfd942b4fe098c0df4e05754e44}") String userId) {
        this.flowAnalysisService = flowAnalysisService;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.unitId = unitId;
        this.tenantId = tenantId;
        this.userId = userId;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(20000);
        this.http = new RestTemplate(factory);
    }

    public Map<String, Object> report(String requestedUnitId, String routeId, String carCode, String day) {
        String carId = verifiedCarId(requestedUnitId, routeId, carCode, day);
        JsonNode root = get("/carDailyReport/pageList.smvc?tenantId=" + enc(tenantId) + "&userId=" + enc(userId)
                + "&carId=" + enc(carId) + "&carCode=" + enc(carCode) + "&startTime=" + enc(day)
                + "&endTime=" + enc(day) + "&page=1&rows=10");
        JsonNode rows = root.path("rows");
        if (!rows.isArray()) throw new IllegalStateException("车辆日报返回格式不正确");
        for (JsonNode row : rows) {
            if (!carId.equals(row.path("carId").asText()) || !carCode.equals(row.path("carCode").asText())) continue;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("carCode", carCode);
            result.put("carId", carId);
            result.put("day", day);
            String mileage = row.path("mileage").asText("").trim();
            result.put("mileageKm", mileage.isEmpty() ? null : new BigDecimal(mileage));
            result.put("hasGps", row.path("hasGps").asText(""));
            result.put("gpsStatus", row.path("gpsStatus").asText(""));
            return result;
        }
        throw new IllegalStateException("该车辆当天没有匹配的车辆日报");
    }

    public Map<String, Object> track(String requestedUnitId, String routeId, String carCode, String day) {
        String carId = verifiedCarId(requestedUnitId, routeId, carCode, day);
        LocalDate date = LocalDate.parse(day);
        long start = date.atStartOfDay(CHINA).toInstant().toEpochMilli();
        long end = date.plusDays(1).atStartOfDay(CHINA).toInstant().toEpochMilli() - 1;
        JsonNode root = get("/gpsPosition/historyPosition.smvc?carId=" + enc(carId)
                + "&startTime=" + start + "&endTime=" + end + "&stopTime=10&tenantId=" + enc(tenantId)
                + "&userId=" + enc(userId));
        if (root.path("result").asInt(-1) != 0) throw new IllegalStateException("车辆轨迹查询失败：" + root.path("msg").asText("未知错误"));
        JsonNode positions = root.path("data").path("hisPositions");
        if (!positions.isArray()) throw new IllegalStateException("车辆轨迹返回格式不正确");
        List<Map<String, Object>> points = new ArrayList<Map<String, Object>>();
        for (JsonNode point : positions) {
            double lng = point.path("gpsLongitude").asDouble(Double.NaN);
            double lat = point.path("gpsLatitude").asDouble(Double.NaN);
            if (!Double.isFinite(lng) || !Double.isFinite(lat) || lng == 0 || lat == 0
                    || Math.abs(lng) > 180 || Math.abs(lat) > 90) continue;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("longitude", lng);
            item.put("latitude", lat);
            item.put("equipmentTime", point.path("equipmentTime").asText(""));
            points.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("carCode", carCode);
        result.put("carId", carId);
        result.put("day", day);
        result.put("coordinateSystem", "BD09");
        result.put("points", points);
        return result;
    }

    private String verifiedCarId(String requestedUnitId, String routeId, String carCode, String day) {
        LocalDate.parse(day);
        if (!unitId.equals(requestedUnitId)) throw new IllegalArgumentException("当前仅配置了淄博公司的车辆运行接口");
        for (Map<String, Object> vehicle : flowAnalysisService.vehicles(requestedUnitId, routeId, day, day)) {
            if (carCode.equals(String.valueOf(vehicle.get("carCode")))) {
                String carId = String.valueOf(vehicle.get("vehicleId"));
                if (!carId.isEmpty() && !"null".equals(carId)) return carId;
            }
        }
        throw new IllegalArgumentException("该岗位当天没有对应车辆 ID，无法查询车辆运行数据");
    }

    private JsonNode get(String path) {
        try {
            String body = http.getForObject(URI.create(baseUrl + path), String.class);
            return objectMapper.readTree(body);
        } catch (RestClientException ex) {
            throw new IllegalStateException("车辆运行服务不可用，请稍后重试", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("车辆运行服务返回内容无法解析", ex);
        }
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
