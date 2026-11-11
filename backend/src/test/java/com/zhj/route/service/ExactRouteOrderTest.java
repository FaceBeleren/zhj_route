package com.zhj.route.service;

import com.zhj.route.algorithm.ExactRouteOptimizer;
import com.zhj.route.algorithm.RoutePoint;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ExactRouteOrderTest {
    @Test
    void enumeratesDirectedOrdersAndReturnsMatchingRoads() {
        RouteMapPathService paths = mock(RouteMapPathService.class);
        when(paths.preloadCachedDistances(anyList())).thenReturn(Collections.emptyMap());
        when(paths.resolveWithoutCache(any(), any())).thenAnswer(call -> {
            RoutePoint from = call.getArgument(0), to = call.getArgument(1);
            Set<String> shortEdges = new HashSet<>(Arrays.asList("0:3", "3:1", "1:2", "2:9"));
            double distance = shortEdges.contains(from.getFacilityId() + ":" + to.getFacilityId()) ? 1D : 100D;
            return new RouteMapPathService.ResolvedPath(Arrays.asList(
                    map("longitude", from.getLongitude(), "latitude", from.getLatitude()),
                    map("longitude", to.getLongitude(), "latitude", to.getLatitude())), distance, 1D, "OD_CACHE");
        });
        RouteOptimizeService service = new RouteOptimizeService(null, paths, mock(RouteOptimizeTraceService.class));
        List<Map<String, Object>> points = Arrays.asList(point(0, "START"), point(1, "MIDDLE"),
                point(2, "MIDDLE"), point(3, "MIDDLE"), point(9, "END"));
        points.get(1).put("allowTimeEnd", "06:01");
        Map<String, Object> request = map("points", points, "tripStartMinutes", 360D);
        Map<String, Object> result = service.exactRouteOrder(request);
        Map<?, ?> feasible = (Map<?, ?>) result.get("timeWindowCandidate");
        assertNotNull(feasible);
        List<Map<String, Object>> windowPoints = SmallWindowPlanningService.maps(feasible.get("points"));
        assertEquals("p1", windowPoints.get(1).get("manualToken"));
        assertEquals(301D, SmallWindowPlanningService.maps(feasible.get("adjustedSegments")).stream()
                .mapToDouble(segment -> ((Number) segment.get("distance")).doubleValue()).sum());
        List<Map<String, Object>> adjusted = SmallWindowPlanningService.maps(result.get("points"));
        assertEquals(Arrays.asList("p0", "p3", "p1", "p2", "p9"),
                adjusted.stream().map(p -> p.get("manualToken")).collect(Collectors.toList()));
        assertEquals(6L, result.get("evaluated"));
        assertEquals(4D, result.get("distance"));
        assertEquals(4D, SmallWindowPlanningService.maps(result.get("adjustedSegments")).stream()
                .mapToDouble(segment -> ((Number) segment.get("distance")).doubleValue()).sum());
        assertEquals("p1", points.get(1).get("manualToken"));
        verify(paths, times(1)).preloadCachedDistances(anyList());
        verify(paths, times(12)).resolveWithoutCache(any(), any());

        request.put("downstreamWindows", Collections.singletonList(map("role", "MIDDLE", "allowTimeBegin", "07:00",
                "arrivalOffsetMinutes", 0D, "operationDurationMinutes", 0D)));
        assertNull(service.exactRouteOrder(request).get("timeWindowCandidate"));
    }

    @Test
    void fixedAnchorsKeepGroupsSeparateAndEqualRoutesKeepOriginalOrder() {
        double[][] costs = new double[7][7];
        for (double[] row : costs) Arrays.fill(row, 1D);
        boolean[] fixed = {true, false, false, true, false, false, true};
        ExactRouteOptimizer.Result result = new ExactRouteOptimizer().optimize(costs, fixed);
        assertEquals(4L, result.getEvaluated());
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6}, result.getOrder());
    }

    @Test
    void tenPointsEnumerateAllOrdersAndElevenAreRejected() {
        boolean[] fixed = new boolean[12];
        fixed[0] = fixed[11] = true;
        assertEquals(3628800L, new ExactRouteOptimizer().optimize(new double[12][12], fixed).getEvaluated());
        List<Map<String, Object>> points = new ArrayList<>();
        points.add(point(0, "START"));
        for (int i = 1; i <= 11; i++) points.add(point(i, "MIDDLE"));
        points.add(point(12, "END"));
        RouteMapPathService paths = mock(RouteMapPathService.class);
        RouteOptimizeService service = new RouteOptimizeService(null, paths, mock(RouteOptimizeTraceService.class));
        assertThrows(IllegalArgumentException.class, () -> service.exactRouteOrder(map("points", points)));
        verifyNoInteractions(paths);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static Map<String, Object> point(int id, String role) {
        return map("facilityId", id, "manualToken", "p" + id, "facilityName", "p" + id,
                "role", role, "longitude", 118D + id / 1000D, "latitude", 36D, "operationDurationMinutes", 1D);
    }
}
