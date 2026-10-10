package com.zhj.route.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class GarbageDensityTest {
    private final RouteOptimizeService service = new RouteOptimizeService(mock(RouteQueryService.class), mock(RouteMapPathService.class), mock(RouteOptimizeTraceService.class));
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalize(Map<String, Object> point, Map<String, Object> request) {
        return ReflectionTestUtils.invokeMethod(service, "normalizeInputAnchorIds", Collections.singletonList(point), request);
    }
    @Test void overridesWeightFromVolumeWithoutMutatingSource() {
        Map<String, Object> point = new HashMap<>();
        point.put("estimatedVolumeLiter", 3960D); point.put("estimatedWeightKg", 495D); point.put("litersPerTon", 8000D);
        Map<String, Object> result = normalize(point, Collections.singletonMap("garbageDensityKgM3", 200D)).get(0);
        assertEquals(792D, result.get("estimatedWeightKg"));
        assertEquals(5000D, result.get("litersPerTon"));
        assertEquals(495D, point.get("estimatedWeightKg"));
    }
    @Test void blankKeepsDatabaseWeightAndAnchorIsNotReweighted() {
        Map<String, Object> point = new HashMap<>(); point.put("estimatedWeightKg", 495D); point.put("estimatedVolumeLiter", 3960D);
        assertEquals(495D, normalize(point, Collections.singletonMap("garbageDensityKgM3", "")).get(0).get("estimatedWeightKg"));
        point.put("weightSource", "ANCHOR");
        assertEquals(495D, normalize(point, Collections.singletonMap("garbageDensityKgM3", 200D)).get(0).get("estimatedWeightKg"));
    }
    @Test void rejectsInvalidDensity() {
        for (Object value : Arrays.asList(0D, -1D, Double.NaN, Double.POSITIVE_INFINITY, "abc")) {
            assertThrows(RuntimeException.class, () -> normalize(Collections.emptyMap(), Collections.singletonMap("garbageDensityKgM3", value)));
        }
    }
}
