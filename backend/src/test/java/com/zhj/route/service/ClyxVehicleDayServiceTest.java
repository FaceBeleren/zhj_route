package com.zhj.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ClyxVehicleDayServiceTest {
    private final FlowAnalysisService flow = mock(FlowAnalysisService.class);
    private final ClyxVehicleDayService service = new ClyxVehicleDayService(flow, new ObjectMapper(),
            "http://example.test/cloud/clyx", "unit", "tenant", "user");

    private void vehicle() {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("carCode", "鲁CL0725");
        row.put("vehicleId", "car-id");
        when(flow.vehicles("unit", "534", "2026-09-23", "2026-09-23"))
                .thenReturn(Collections.singletonList(row));
    }

    @Test
    void reportReadsStringMileageAndMatchesCar() {
        vehicle();
        RestTemplate http = (RestTemplate) ReflectionTestUtils.getField(service, "http");
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        server.expect(requestTo("http://example.test/cloud/clyx/carDailyReport/pageList.smvc?tenantId=tenant&userId=user&carId=car-id&carCode=%E9%B2%81CL0725&startTime=2026-09-23&endTime=2026-09-23&page=1&rows=10"))
                .andRespond(withSuccess("{\"rows\":[{\"carId\":\"car-id\",\"carCode\":\"鲁CL0725\",\"mileage\":\"166.34\",\"hasGps\":\"有\"}]}", MediaType.APPLICATION_JSON));
        Map<String, Object> result = service.report("unit", "534", "鲁CL0725", "2026-09-23");
        assertEquals("166.34", result.get("mileageKm").toString());
        server.verify();
    }

    @Test
    void trackUsesChinaDayAndDropsInvalidCoordinates() {
        vehicle();
        RestTemplate http = (RestTemplate) ReflectionTestUtils.getField(service, "http");
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        server.expect(requestTo("http://example.test/cloud/clyx/gpsPosition/historyPosition.smvc?carId=car-id&startTime=1790092800000&endTime=1790179199999&stopTime=10&tenantId=tenant&userId=user"))
                .andRespond(withSuccess("{\"result\":0,\"data\":{\"hisPositions\":[{\"gpsLongitude\":118.1,\"gpsLatitude\":36.8,\"equipmentTime\":\"03:41:09\"},{\"gpsLongitude\":0,\"gpsLatitude\":0}]}}", MediaType.APPLICATION_JSON));
        Map<String, Object> result = service.track("unit", "534", "鲁CL0725", "2026-09-23");
        assertEquals(1, ((java.util.List<?>) result.get("points")).size());
        assertEquals("BD09", result.get("coordinateSystem"));
        server.verify();
    }

    @Test
    void otherCompanyCannotUseZiboTenant() {
        assertThrows(IllegalArgumentException.class, () -> service.report("other", "534", "鲁CL0725", "2026-09-23"));
    }
}
