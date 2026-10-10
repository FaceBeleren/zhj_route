package com.zhj.route.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class JointTimeWindowRepairTest {
    private Map<String, Object> point(long id, double operation, String begin, String end) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("facilityId", id);
        point.put("facilityName", "点" + id);
        point.put("role", id < 0 ? (id == -1 ? "START" : "END") : "MIDDLE");
        point.put("longitude", 118D + id / 1000D);
        point.put("latitude", 36.8D);
        point.put("operationDurationMinutes", operation);
        if (begin != null) point.put("allowTimeBegin", begin);
        if (end != null) point.put("allowTimeEnd", end);
        return point;
    }

    private Map<String, Object> road(List<Map<String, Object>> points) {
        List<Map<String, Object>> segments = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("durationMinutes", 5D);
            segment.put("distance", 1000D);
            segment.put("pathSource", "OD_CACHE");
            segments.add(segment);
        }
        Map<String, Object> road = new LinkedHashMap<>();
        road.put("segments", segments);
        road.put("durationMinutes", segments.size() * 5D);
        road.put("distance", segments.size() * 1000D);
        return road;
    }

    private Map<String, Object> route(int no, Map<String, Object>... middle) {
        List<Map<String, Object>> points = new ArrayList<>();
        Map<String, Object> start = point(no == 1 ? -1 : -2, 0, null, null);
        start.put("role", "START");
        points.add(start);
        points.addAll(Arrays.asList(middle));
        points.add(point(-2, 15, null, null));
        Map<String, Object> route = road(points);
        route.put("points", points);
        route.put("routeNo", no);
        route.put("tripNo", no);
        route.put("vehicleId", "v1");
        route.put("durationMinutes", (points.size() - 1) * 5D + points.stream().mapToDouble(p -> ((Number) p.get("operationDurationMinutes")).doubleValue()).sum());
        return route;
    }

    private Map<String, Object> day(int day, Long... ids) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("day", day);
        result.put("facilityIds", Arrays.asList(ids));
        return result;
    }

    private Map<String, Object> request(List<Map<String, Object>> routes, Map<String, Object>... days) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("distanceMode", "ROAD");
        result.put("routes", routes);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("result", result);
        request.put("plannedStartTime", "02:00");
        request.put("scheduleDays", Arrays.asList(days));
        return request;
    }

    private RouteOptimizeService optimizer() {
        RouteOptimizeService optimizer = mock(RouteOptimizeService.class);
        when(optimizer.routeSegmentsPreview(anyMap())).thenAnswer(invocation -> road(maps(((Map<?, ?>) invocation.getArgument(0)).get("points"))));
        return optimizer;
    }

    @Test
    void fixesDailyEarlyArrivalEvenWhenFullTemplateAlreadyPasses() {
        Map<String, Object> route = route(1, point(1, 30, null, null), point(2, 5, "02:30", "04:00"), point(3, 30, null, null));
        String original = route.toString();
        Map<String, Object> response = new TripReorderService(optimizer()).repairPoints(request(Arrays.asList(route), day(1, 1L, 2L, 3L), day(2, 2L, 3L)));
        assertEquals(true, response.get("jointScenarioValidation"));
        assertEquals(0, response.get("templateBeforeViolationCount"));
        assertEquals(1, response.get("beforeViolationCount"));
        assertEquals(0, response.get("afterViolationCount"));
        assertEquals(true, response.get("allScenariosPassed"));
        assertEquals(2L, maps(maps(map(response.get("result")).get("routes")).get(0).get("points")).get(3).get("facilityId"));
        assertEquals(original, route.toString());
        assertEquals(2, maps(response.get("dailyAfter")).size());
    }

    @Test
    void countsSamePointSeparatelyOnEachDayAndReportsImpossibleDay() {
        Map<String, Object> route = route(1, point(1, 30, null, null), point(2, 5, "02:30", "04:00"), point(3, 30, null, null));
        Map<String, Object> response = new TripReorderService(optimizer()).repairPoints(request(Arrays.asList(route), day(1, 2L, 3L), day(2, 2L)));
        assertEquals(2, response.get("beforeViolationCount"));
        assertEquals(1, response.get("afterViolationCount"));
        assertEquals(false, response.get("allScenariosPassed"));
        assertEquals(0, maps(response.get("dailyAfter")).get(0).get("violationCount"));
        assertEquals(1, maps(response.get("dailyAfter")).get(1).get("violationCount"));
        assertTrue(response.get("message").toString().contains("第2天1处冲突"));
        assertTrue(response.get("message").toString().contains("未找到全部场景"));
    }

    @Test
    void checksLaterTripUsingEarlierTripsActualBypassFinishTime() {
        Map<String, Object> first = route(1, point(1, 60, null, null));
        Map<String, Object> second = route(2, point(2, 5, "02:55", "04:00"));
        Map<String, Object> response = new TripReorderService(optimizer()).repairPoints(request(Arrays.asList(first, second), day(1, 1L, 2L), day(2, 2L)));
        assertEquals(0, response.get("templateBeforeViolationCount"));
        assertEquals(1, maps(response.get("dailyAfter")).get(1).get("violationCount"));
        assertEquals(false, response.get("improved"));
        assertEquals(false, response.get("allScenariosPassed"));
    }

    @Test
    void conflictingDirectionsAcrossScenariosSearchBothWays() {
        Map<String, Object> route = route(1, point(1, 60, null, null), point(2, 5, "02:30", "03:00"), point(3, 40, null, null));
        Map<String, Object> response = new TripReorderService(optimizer()).repairPoints(request(Arrays.asList(route), day(1, 2L, 3L)));
        assertEquals(2, response.get("beforeViolationCount"));
        assertEquals(1, response.get("afterViolationCount"));
        assertEquals(0, maps(response.get("dailyAfter")).get(0).get("violationCount"));
        assertEquals(1, response.get("templateAfterViolationCount"));
        assertEquals(false, response.get("allScenariosPassed"));
    }

    @Test
    void repeatedDayCombinationsReuseRoadButCountEveryDay() {
        RouteOptimizeService optimizer = optimizer();
        Map<String, Object> route = route(1, point(1, 30, null, null), point(2, 5, null, null));
        Map<String, Object> response = new TripReorderService(optimizer).repairPoints(request(Arrays.asList(route), day(1, 2L), day(2, 2L), day(3)));
        verify(optimizer, times(2)).routeSegmentsPreview(anyMap());
        assertEquals(3, maps(response.get("dailyAfter")).size());
        assertEquals(true, response.get("allScenariosPassed"));
        assertEquals(80D, response.get("afterCompletionMinutes"));
    }

    @Test
    void rejectsRoadFallbackAndInvalidSchedulesRatherThanClaimingPass() {
        RouteOptimizeService optimizer = optimizer();
        doAnswer(invocation -> {
            Map<String, Object> road = road(maps(((Map<?, ?>) invocation.getArgument(0)).get("points")));
            maps(road.get("segments")).forEach(segment -> segment.put("pathSource", "DIRECT"));
            return road;
        }).when(optimizer).routeSegmentsPreview(anyMap());
        Map<String, Object> route = route(1, point(1, 30, null, null), point(2, 5, null, null));
        TripReorderService service = new TripReorderService(optimizer);
        assertThrows(IllegalStateException.class, () -> service.repairPoints(request(Arrays.asList(route), day(1, 2L))));
        assertThrows(IllegalArgumentException.class, () -> service.repairPoints(request(Arrays.asList(route))));
        assertThrows(IllegalArgumentException.class, () -> service.repairPoints(request(Arrays.asList(route), day(1, 1L), day(1, 2L))));
    }

    @Test
    void missingDuePointsNeverClaimAllScenariosPass() {
        Map<String, Object> response = new TripReorderService(optimizer()).repairPoints(request(Arrays.asList(route(1, point(1, 5, null, null))), day(1, 1L, 99L)));
        assertEquals(0, response.get("afterViolationCount"));
        assertEquals(false, response.get("allScenariosPassed"));
        assertEquals(1L, maps(response.get("dailyAfter")).get(0).get("dueUnassignedCount"));
        assertTrue(response.get("message").toString().contains("未分配"));
    }

    @Test
    void dailyArrivalUsesNewBypassOdRatherThanSubtractingSkippedServiceOnly() {
        RouteOptimizeService optimizer = optimizer();
        doAnswer(invocation -> {
            List<Map<String, Object>> points = maps(((Map<?, ?>) invocation.getArgument(0)).get("points"));
            Map<String, Object> road = road(points);
            if (points.size() == 3 && points.get(1).get("facilityId").equals(2L)) {
                maps(road.get("segments")).get(0).put("durationMinutes", 50D);
                maps(road.get("segments")).get(0).put("distance", 10000D);
            }
            return road;
        }).when(optimizer).routeSegmentsPreview(anyMap());
        Map<String, Object> route = route(1, point(1, 0, null, null), point(2, 5, "02:00", "02:20"));
        Map<String, Object> response = new TripReorderService(optimizer).repairPoints(request(Arrays.asList(route), day(1, 2L)));
        assertEquals(0, response.get("templateBeforeViolationCount"));
        assertEquals(1, maps(response.get("dailyAfter")).get(0).get("violationCount"));
        assertEquals(75D, maps(response.get("dailyAfter")).get(0).get("completionMinutes"));
        assertEquals(11000D, maps(response.get("dailyAfter")).get(0).get("distance"));
    }

    @Test
    void emptyLaterTripKeepsUnloadingButHasZeroTravelAtSameCoordinates() {
        RouteOptimizeService optimizer = optimizer();
        Map<String, Object> response = new TripReorderService(optimizer).repairPoints(request(Arrays.asList(route(1, point(1, 5, null, null)), route(2, point(2, 5, null, null))), day(1, 1L)));
        assertEquals(45D, maps(response.get("dailyAfter")).get(0).get("completionMinutes"));
        assertEquals(true, response.get("allScenariosPassed"));
        verifyNoInteractions(optimizer);
    }

    @Test
    void separateVehiclesStartIndependentlyInEveryScenario() {
        Map<String, Object> first = route(1, point(1, 60, null, null));
        Map<String, Object> second = route(2, point(2, 5, "02:00", "02:20"));
        second.put("vehicleId", "v2");
        Map<String, Object> response = new TripReorderService(optimizer()).repairPoints(request(Arrays.asList(first, second), day(1, 1L, 2L)));
        assertEquals(true, response.get("allScenariosPassed"));
        assertEquals(0, response.get("afterViolationCount"));
    }

    @Test
    void continuesPastFiveHundredCandidatesAndFindsLaterImprovement() {
        RouteOptimizeService optimizer = optimizer();
        doAnswer(invocation -> {
            List<Map<String, Object>> points = maps(((Map<?, ?>) invocation.getArgument(0)).get("points"));
            Map<String, Object> road = road(points);
            // Only a move generated late in the round improves efficiency; the window is impossible.
            if (points.get(1).get("facilityId").equals(25L)) {
                maps(road.get("segments")).forEach(segment -> segment.put("durationMinutes", 1D));
                road.put("durationMinutes", points.size() - 1D);
            }
            return road;
        }).when(optimizer).routeSegmentsPreview(anyMap());
        List<Map<String, Object>> points = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (long id = 1; id <= 25; id++) { points.add(point(id, 0, null, null)); ids.add(id); }
        points.get(0).put("barredTimeBegin", "00:00");
        points.get(0).put("barredTimeEnd", "23:59");
        Map<String, Object> original = route(1, points.toArray(new Map[0]));
        String snapshot = original.toString();
        Map<String, Object> request = request(Arrays.asList(original), day(1, ids.toArray(new Long[0])));
        request.put("searchMode", "EXPANDED");
        Map<String, Object> response = new TripReorderService(optimizer).repairPoints(request);
        assertTrue(((Number) response.get("evaluations")).intValue() > 500);
        assertEquals(false, response.get("limited"));
        assertEquals(true, response.get("improved"));
        assertEquals("EFFICIENCY_ONLY", response.get("improvementType"));
        assertEquals(25L, maps(maps(map(response.get("result")).get("routes")).get(0).get("points")).get(1).get("facilityId"));
        assertEquals(snapshot, original.toString());
        assertFalse(response.get("message").toString().contains("计算上限"));
    }

    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> maps(Object value) { return (List<Map<String, Object>>) value; }
}
