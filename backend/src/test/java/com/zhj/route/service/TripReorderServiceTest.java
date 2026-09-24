package com.zhj.route.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TripReorderServiceTest {
    private TripReorderService service;

    @BeforeEach
    void setUp() {
        RouteOptimizeService optimizeService = mock(RouteOptimizeService.class);
        when(optimizeService.routeSegmentsPreview(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> request = invocation.getArgument(0);
            List<Map<String, Object>> points = maps(request.get("points"));
            List<Map<String, Object>> segments = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < points.size() - 1; i++) {
                Map<String, Object> segment = new HashMap<String, Object>();
                segment.put("order", i + 1);
                segment.put("distance", 1000D);
                segment.put("durationMinutes", 5D);
                segment.put("pathSource", "DIRECT");
                segments.add(segment);
            }
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("segments", segments);
            result.put("distance", segments.size() * 1000D);
            result.put("durationMinutes", segments.size() * 5D);
            return result;
        });
        service = new TripReorderService(optimizeService);
    }

    @Test
    void swapsTwoTripsAndKeepsParkingAsFirstStart() {
        Map<String, Object> result = service.reorder(request(Arrays.asList(
                route(1, "v1", "普通点", null, null),
                route(2, "v1", "限时点", "02:00", "02:30")
        )));
        assertEquals(true, result.get("improved"));
        assertEquals(1, ((Number) result.get("beforeViolationCount")).intValue());
        assertEquals(0, ((Number) result.get("afterViolationCount")).intValue());
        List<Map<String, Object>> routes = resultRoutes(result);
        assertEquals("限时点", maps(routes.get(0).get("points")).get(1).get("facilityName"));
        assertEquals("停车场", maps(routes.get(0).get("points")).get(0).get("facilityName"));
        assertEquals("处理厂", maps(routes.get(1).get("points")).get(0).get("facilityName"));
    }

    @Test
    void keepsOriginalWhenNoOrderCanImprove() {
        Map<String, Object> first = route(1, "v1", "点1", null, null);
        Map<String, Object> second = route(2, "v1", "点2", null, null);
        middle(first).put("barredTimeBegin", "00:00");
        middle(first).put("barredTimeEnd", "23:59");
        middle(second).put("barredTimeBegin", "00:00");
        middle(second).put("barredTimeEnd", "23:59");
        Map<String, Object> result = service.reorder(request(Arrays.asList(first, second)));
        assertEquals(false, result.get("improved"));
        assertEquals("调整趟序无法改善时间窗", result.get("message"));
    }

    @Test
    void movesUrgentTripToFrontAmongThreeTrips() {
        Map<String, Object> result = service.reorder(request(Arrays.asList(
                route(1, "v1", "普通点1", null, null),
                route(2, "v1", "普通点2", null, null),
                route(3, "v1", "限时点", "02:00", "02:30")
        )));
        assertEquals(true, result.get("improved"));
        assertEquals("限时点", maps(resultRoutes(result).get(0).get("points")).get(1).get("facilityName"));
    }

    @Test
    void reordersEachVehicleIndependently() {
        Map<String, Object> result = service.reorder(request(Arrays.asList(
                route(1, "v1", "V1普通", null, null),
                route(2, "v2", "V2普通", null, null),
                route(3, "v1", "V1限时", "02:00", "02:30"),
                route(4, "v2", "V2后续", null, null)
        )));
        assertEquals(true, result.get("improved"));
        List<Map<String, Object>> routes = resultRoutes(result);
        assertEquals("V1限时", maps(routes.get(0).get("points")).get(1).get("facilityName"));
        assertEquals("V2普通", maps(routes.get(1).get("points")).get(1).get("facilityName"));
        assertEquals("V1普通", maps(routes.get(2).get("points")).get(1).get("facilityName"));
    }

    @Test
    void appliesPartialImprovementAndKeepsRemainingConflict() {
        Map<String, Object> impossible = route(1, "v1", "全天禁止点", null, null);
        middle(impossible).put("barredTimeBegin", "00:00");
        middle(impossible).put("barredTimeEnd", "23:59");
        Map<String, Object> result = service.reorder(request(Arrays.asList(
                impossible,
                route(2, "v1", "普通点", null, null),
                route(3, "v1", "限时点", "02:00", "02:30")
        )));
        assertEquals(true, result.get("improved"));
        assertEquals(2, ((Number) result.get("beforeViolationCount")).intValue());
        assertEquals(1, ((Number) result.get("afterViolationCount")).intValue());
    }

    @Test
    void acceptsOvernightWindowAtTwoAm() {
        Map<String, Object> overnight = route(1, "v1", "跨午夜点", "23:00", "03:00");
        Map<String, Object> result = service.reorder(request(Arrays.asList(
                overnight,
                route(2, "v1", "普通点", null, null)
        )));
        assertEquals(false, result.get("improved"));
        assertEquals(0, ((Number) result.get("beforeViolationCount")).intValue());
        assertEquals("当前方案时间窗均已通过", result.get("message"));
    }
    private Map<String, Object> request(List<Map<String, Object>> routes) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("distanceMode", "DIRECT");
        result.put("routes", routes);
        Map<String, Object> request = new HashMap<String, Object>();
        request.put("plannedStartTime", "02:00");
        request.put("result", result);
        return request;
    }

    private Map<String, Object> route(int routeNo, String vehicleId, String pointName, String allowBegin, String allowEnd) {
        Map<String, Object> route = new HashMap<String, Object>();
        route.put("routeNo", routeNo);
        route.put("tripNo", routeNo);
        route.put("vehicleId", vehicleId);
        route.put("vehicleName", vehicleId);
        route.put("durationMinutes", 60D);
        route.put("points", Arrays.asList(
                point(-1L, routeNo == 1 ? "停车场" : "处理厂", "START", null, null, 0D),
                point((long) (100 + routeNo), pointName, "MIDDLE", allowBegin, allowEnd, 20D),
                point(-2L, "处理厂", "END", null, null, 15D)
        ));
        route.put("segments", Arrays.asList(segment(1), segment(2)));
        return route;
    }

    private Map<String, Object> point(Long id, String name, String role, String allowBegin, String allowEnd, double operation) {
        Map<String, Object> point = new HashMap<String, Object>();
        point.put("facilityId", id);
        point.put("facilityName", name);
        point.put("role", role);
        point.put("longitude", 117D + id / 10000D);
        point.put("latitude", 36.8D);
        point.put("operationDurationMinutes", operation);
        if (allowBegin != null) point.put("allowTimeBegin", allowBegin);
        if (allowEnd != null) point.put("allowTimeEnd", allowEnd);
        return point;
    }

    private Map<String, Object> segment(int order) {
        Map<String, Object> segment = new HashMap<String, Object>();
        segment.put("order", order);
        segment.put("distance", 1000D);
        segment.put("durationMinutes", 10D);
        segment.put("pathSource", "DIRECT");
        return segment;
    }

    private Map<String, Object> middle(Map<String, Object> route) { return maps(route.get("points")).get(1); }
    private List<Map<String, Object>> resultRoutes(Map<String, Object> response) { return maps(map(response.get("result")).get("routes")); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) { return (List<Map<String, Object>>) value; }
}