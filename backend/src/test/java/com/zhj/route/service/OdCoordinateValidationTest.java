package com.zhj.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhj.route.algorithm.RoutePoint;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OdCoordinateValidationTest {
    @Test
    void rejectsOldLocationWhileRetainingCurrentLocationInKeysAndDistances() {
        java.util.concurrent.atomic.AtomicReference<List<Map<String, Object>>> rows = new java.util.concurrent.atomic.AtomicReference<>();
        JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> invocation.getMethod().getName().equals("queryForList") ? rows.get() : null);
        RouteMapPathService service = new RouteMapPathService(jdbc, new ObjectMapper(), false, "", "", 2000, 5000);
        RoutePoint from = point(22575L, 118.042581684, 36.87130045);
        RoutePoint to = point(278L, 118.308445, 36.962722);
        Map<String, Object> old = row(118.045337, 36.872796);
        rows.set(Arrays.asList(old));
        assertTrue(service.preloadCachedKeys(Arrays.asList(from, to)).isEmpty());
        assertTrue(service.preloadCachedDistances(Arrays.asList(from, to)).isEmpty());
        Map<String, Object> valid = row(118.042581684, 36.87130045);
        rows.set(Arrays.asList(old, valid));
        assertEquals(1, service.preloadCachedKeys(Arrays.asList(from, to)).size());
        assertEquals(1, service.preloadCachedDistances(Arrays.asList(from, to)).size());
    }

    private RoutePoint point(Long id, double longitude, double latitude) {
        return new RoutePoint(id, "点位", longitude, latitude, 1, null, null, null, null, null, null);
    }

    private Map<String, Object> row(double longitude, double latitude) {
        Map<String, Object> row = new HashMap<>();
        row.put("start_code", "22575"); row.put("end_code", "278");
        row.put("longitude_start", longitude); row.put("latitude_start", latitude);
        row.put("longitude_end", 118.308445); row.put("latitude_end", 36.962722);
        row.put("distance", 1000D); row.put("time_duration", 60D);
        return row;
    }
}
