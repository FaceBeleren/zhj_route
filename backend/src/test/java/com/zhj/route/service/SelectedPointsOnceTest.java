package com.zhj.route.service;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SelectedPointsOnceTest {
    @Test void coincidentOccurrencesRemainDistinctAndNeedNoRoadQuery() {
        RouteMapPathService paths=mock(RouteMapPathService.class);when(paths.preloadCachedDistances(anyList())).thenReturn(Collections.emptyMap());
        RouteOptimizeService service=new RouteOptimizeService(null,paths,mock(RouteOptimizeTraceService.class));
        Map<String,Object> start=point(0,"START"),collection=point(1,"MIDDLE"),end=point(9,"END");
        collection.put("longitude",118D);end.put("longitude",118D);
        Map<String,Object> response=service.adjustSelectedOnce(map("routes",Arrays.asList(map("points",Arrays.asList(start,collection,end))),"selectedTokens",Arrays.asList("p1")));
        List<Map<String,Object>> segments=SmallWindowPlanningService.maps(SmallWindowPlanningService.maps(response.get("adjustedRoads")).get(0).get("segments"));
        assertEquals(2,segments.size());assertEquals("SAME_POINT",segments.get(0).get("pathSource"));assertEquals(0D,((Number)segments.get(0).get("distance")).doubleValue());
        verify(paths,never()).resolveWithoutCache(any(),any());
    }
    static Map<String,Object> map(Object... pairs){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    static Map<String,Object> point(int id,String role){return map("facilityId",id,"manualToken","p"+id,"facilityName","p"+id,"role",role,"longitude",118D+id/1000D,"latitude",36D,"operationDurationMinutes",1D);}
    @Test void onceReinsertionPreservesMembershipAndUsesOneOdPreload() {
        RouteMapPathService paths=mock(RouteMapPathService.class);
        when(paths.preloadCachedDistances(anyList())).thenReturn(Collections.emptyMap());
        when(paths.resolveWithoutCache(any(),any())).thenAnswer(a->{
            com.zhj.route.algorithm.RoutePoint from=a.getArgument(0),to=a.getArgument(1);
            double distance=(from.getFacilityId()==1L && to.getFacilityId()==3L)||(from.getFacilityId()==3L && to.getFacilityId()==2L)||(from.getFacilityId()==2L && to.getFacilityId()==9L)?1D:10D;
            return new RouteMapPathService.ResolvedPath(Arrays.asList(map("longitude",from.getLongitude(),"latitude",from.getLatitude()),map("longitude",to.getLongitude(),"latitude",to.getLatitude())),distance,1D,"OD_CACHE");
        });
        RouteOptimizeService service=new RouteOptimizeService(null,paths,mock(RouteOptimizeTraceService.class));
        List<Map<String,Object>> points=Arrays.asList(point(0,"START"),point(1,"MIDDLE"),point(2,"MIDDLE"),point(3,"MIDDLE"),point(9,"END"));
        Map<String,Object> request=map("routes",Arrays.asList(map("routeNo",1,"points",points)),"selectedTokens",Arrays.asList("p2"));
        Map<String,Object> response=service.adjustSelectedOnce(request);
        List<Map<String,Object>> adjusted=SmallWindowPlanningService.maps(SmallWindowPlanningService.maps(response.get("routes")).get(0).get("points"));
        assertEquals(Arrays.asList("p0","p1","p3","p2","p9"),adjusted.stream().map(p->p.get("manualToken")).collect(java.util.stream.Collectors.toList()));
        assertEquals("p2",points.get(2).get("manualToken"));
        verify(paths,times(1)).preloadCachedDistances(anyList());
        assertEquals(1,SmallWindowPlanningService.maps(response.get("originalRoads")).size());
        assertEquals(1,SmallWindowPlanningService.maps(response.get("adjustedRoads")).size());
        request.put("selectedTokens",Arrays.asList("p0"));assertThrows(IllegalArgumentException.class,()->service.adjustSelectedOnce(request));
    }
    @Test void selectedPointCannotCrossInternalUnloadAnchor() {
        RouteMapPathService paths=mock(RouteMapPathService.class);
        when(paths.preloadCachedDistances(anyList())).thenReturn(Collections.emptyMap());
        when(paths.resolveWithoutCache(any(),any())).thenAnswer(a->{
            com.zhj.route.algorithm.RoutePoint from=a.getArgument(0),to=a.getArgument(1);
            return new RouteMapPathService.ResolvedPath(Arrays.asList(map("longitude",from.getLongitude(),"latitude",from.getLatitude()),map("longitude",to.getLongitude(),"latitude",to.getLatitude())),1D,1D,"OD_CACHE");
        });
        RouteOptimizeService service=new RouteOptimizeService(null,paths,mock(RouteOptimizeTraceService.class));
        List<Map<String,Object>> points=Arrays.asList(point(0,"START"),point(1,"MIDDLE"),point(5,"END"),point(2,"MIDDLE"),point(9,"END"));
        Map<String,Object> request=map("routes",Arrays.asList(map("routeNo",1,"points",points)),"selectedTokens",Arrays.asList("p1","p2"));
        List<Map<String,Object>> adjusted=SmallWindowPlanningService.maps(SmallWindowPlanningService.maps(service.adjustSelectedOnce(request).get("routes")).get(0).get("points"));
        assertEquals("p5",adjusted.get(2).get("manualToken"));assertEquals("p1",adjusted.get(1).get("manualToken"));assertEquals("p2",adjusted.get(3).get("manualToken"));
    }
    @Test void worseGreedyReinsertionReturnsOriginalRouteAndReportsRejectedAttempt() {
        RouteMapPathService paths=mock(RouteMapPathService.class);
        when(paths.preloadCachedDistances(anyList())).thenReturn(Collections.emptyMap());
        when(paths.resolveWithoutCache(any(),any())).thenAnswer(a->{
            com.zhj.route.algorithm.RoutePoint from=a.getArgument(0),to=a.getArgument(1);
            int x=from.getFacilityId().intValue(),y=to.getFacilityId().intValue();
            double distance=(x==0&&y==1)||(x==1&&y==2)||(x==2&&y==3)||(x==3&&y==9)
                    ||(x==0&&y==3)||(x==3&&y==2)||(x==2&&y==9)?1D
                    :(x==1&&y==3?2D:10D);
            return new RouteMapPathService.ResolvedPath(Arrays.asList(map("longitude",from.getLongitude(),"latitude",from.getLatitude()),map("longitude",to.getLongitude(),"latitude",to.getLatitude())),distance,1D,"OD_CACHE");
        });
        RouteOptimizeService service=new RouteOptimizeService(null,paths,mock(RouteOptimizeTraceService.class));
        List<Map<String,Object>> points=Arrays.asList(point(0,"START"),point(1,"MIDDLE"),point(2,"MIDDLE"),point(3,"MIDDLE"),point(9,"END"));
        Map<String,Object> response=service.adjustSelectedOnce(map("routes",Arrays.asList(map("routeNo",1,"points",points)),"selectedTokens",Arrays.asList("p1","p2")));
        assertEquals(false,response.get("improved"));
        assertTrue(((Number)response.get("attemptedDistance")).doubleValue()>((Number)response.get("originalDistance")).doubleValue());
        List<Map<String,Object>> retained=SmallWindowPlanningService.maps(SmallWindowPlanningService.maps(response.get("routes")).get(0).get("points"));
        assertEquals(Arrays.asList("p0","p1","p2","p3","p9"),retained.stream().map(p->p.get("manualToken")).collect(java.util.stream.Collectors.toList()));
    }
}
