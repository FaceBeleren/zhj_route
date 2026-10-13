package com.zhj.route.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanningPointFilterTest {
    private static Map<String,Object> row(Object... pairs) { Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)map.put((String)pairs[i],pairs[i+1]);return map; }
    @Test void explicitTypeWinsAndFacilityNamesAreNeverGuessed() {
        assertTrue(PlanningPointFilter.isDisposal(row("facilityType",5)));
        assertTrue(PlanningPointFilter.isDisposal(row("facilityType","5")));
        assertTrue(PlanningPointFilter.isDisposal(row("facilityTypeName","处置场")));
        assertFalse(PlanningPointFilter.isDisposal(row("facilityType",6,"facilityTypeName","处置场")));
        assertFalse(PlanningPointFilter.isDisposal(row("facilityType",2,"facilityTypeName","中转站")));
        assertFalse(PlanningPointFilter.isDisposal(row("facilityName","生活垃圾处理厂旁收集点")));
    }
    @Test void multiPlanningExcludesDisposalButKeepsConfiguredEndAnchor() {
        RouteOptimizeService service=new RouteOptimizeService(null,mock(RouteMapPathService.class),mock(RouteOptimizeTraceService.class));
        Map<String,Object> collect=row("facilityId",1,"facilityType",6,"longitude",118.01D,"latitude",36D,"estimatedWeightKg",1D);
        Map<String,Object> plant=row("facilityId",99,"facilityType",5,"facilityName","处理厂","longitude",118.02D,"latitude",36D,"estimatedWeightKg",40D);
        List<Map<String,Object>> input=Arrays.asList(collect,plant);
        Map<String,Object> request=row("points",input,"startLongitude",118D,"startLatitude",36D,"endLongitude",118.02D,"endLatitude",36D,"endFacilityId",99,"endFacilityName","处理厂","workHours",24D);
        Map<String,Object> result=service.optimizeMultiPreview(request);
        assertEquals(1,((Number)result.get("candidatePointCount")).intValue());
        List<Map<String,Object>> points=SmallWindowPlanningService.maps(SmallWindowPlanningService.maps(result.get("routes")).get(0).get("points"));
        assertEquals(3,points.size());assertEquals(99L,points.get(2).get("facilityId"));assertEquals("END",points.get(2).get("role"));
        assertFalse(points.stream().anyMatch(p -> "MIDDLE".equals(p.get("role")) && Objects.equals(99L,p.get("facilityId"))));
        assertEquals(1,service.jointCollectionPoints(request,input).size());assertEquals(2,input.size());
    }
}
