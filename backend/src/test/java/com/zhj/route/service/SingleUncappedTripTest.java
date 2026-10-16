package com.zhj.route.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SingleUncappedTripTest {
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    @Test void optInSingleTripCoversAllPointsWithoutChangingOrdinaryCapacityPlanning() {
        RouteOptimizeService service = new RouteOptimizeService(null, mock(RouteMapPathService.class), mock(RouteOptimizeTraceService.class));
        List<Map<String, Object>> points = Arrays.asList(
                map("facilityId", 1, "facilityType", 6, "facilityName", "A", "longitude", 118.01D, "latitude", 36D, "estimatedWeightKg", 60D),
                map("facilityId", 2, "facilityType", 6, "facilityName", "B", "longitude", 118.02D, "latitude", 36D, "estimatedWeightKg", 60D),
                map("facilityId", 3, "facilityType", 6, "facilityName", "C", "longitude", 118.03D, "latitude", 36D, "estimatedWeightKg", 60D));
        Map<String, Object> request = map("points", points, "startLongitude", 118D, "startLatitude", 36D,
                "endLongitude", 118.04D, "endLatitude", 36D, "ratedCapacityKg", 100D,
                "targetLoadRate", 0.9D, "maxRoutes", 1, "dispatchMode", "USER_ORDER");
        Map<String, Object> ordinary = service.optimizeMultiPreview(request);
        assertTrue(((Number) ordinary.get("assignedPointCount")).intValue() < 3);
        request.put("singleUncappedTrip", true);
        Map<String, Object> uncapped = service.optimizeMultiPreview(request);
        assertEquals(1, ((Number) uncapped.get("routeCount")).intValue());
        assertEquals(3, ((Number) uncapped.get("assignedPointCount")).intValue());
        assertEquals(0, ((Number) uncapped.get("unassignedPointCount")).intValue());
        request.put("maxRoutes", 2);
        assertThrows(IllegalArgumentException.class, () -> service.optimizeMultiPreview(request));
    }
}
