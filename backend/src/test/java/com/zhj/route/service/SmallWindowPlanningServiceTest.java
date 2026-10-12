package com.zhj.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SmallWindowPlanningServiceTest {
    private static Map<String,Object> map(Object... pairs) { Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m; }
    private static Map<String,Object> point(int id,String window) { return map("facilityId",id,"facilityName","p"+id,"longitude",118D+id/1000D,"latitude",36D,"role","MIDDLE","estimatedWeightKg",1D,"estimatedVolumeLiter",2D,"operationDurationMinutes",1D,"allowTimeBegin",window); }
    private static class Fixture {
        RouteOptimizeService optimizer=mock(RouteOptimizeService.class);
        TripReorderService checker=new TripReorderService(optimizer);
        SmallWindowPlanningService service=new SmallWindowPlanningService(optimizer,checker,new ObjectMapper());
        Map<String,Object> request; SmallWindowPlanningService.Job job=new SmallWindowPlanningService.Job();
        List<List<Integer>> seen=new ArrayList<>();
        Fixture(List<Map<String,Object>> input,int trips) {
            request=map("points",input,"vehicles",Arrays.asList(map("tripCount",trips)),"scheduleDays",Arrays.asList(map("day",1,"facilityIds",Arrays.asList(1,2,3))),"plannedStartTime","03:00","workHours",8);
            when(optimizer.jointCollectionPoints(anyMap(),anyList())).thenAnswer(a -> a.getArgument(1));
            when(optimizer.optimizeMultiPreview(anyMap())).thenAnswer(a -> {
                List<Map<String,Object>> points=new ArrayList<>();Map<String,Object> start=point(-1,null);start.put("role","START");start.put("operationDurationMinutes",0D);points.add(start);
                points.addAll(SmallWindowPlanningService.maps(((Map<String,Object>)a.getArgument(0)).get("points")));
                Map<String,Object> end=point(-2,null);end.put("role","END");end.put("operationDurationMinutes",0D);points.add(end);
                return map("routes",new ArrayList<>(Arrays.asList(map("points",points,"maxCapacityKg",10D,"ratedCapacityKg",10D,"vehicleId","v","tripNo",1,"routeNo",1,"workLimitMinutes",480D))),"unassignedPointCount",0);
            });
            when(optimizer.prepareJointRoads(anyMap(),anyList(),any())).thenAnswer(a -> (Function<List<Map<String,Object>>,Map<String,Object>>) points -> {
                List<Integer> ids=new ArrayList<>();for(Map<String,Object> p:points)ids.add(((Number)p.get("facilityId")).intValue());seen.add(ids);
                List<Map<String,Object>> segments=new ArrayList<>();double d=0;
                for(int i=1;i<points.size();i++) { double cost=ids.get(i-1)==2 && ids.get(i)==3?1D:10D;d+=cost;segments.add(map("distance",cost,"durationMinutes",1D,"pathSource","OD_CACHE")); }
                return map("segments",segments,"distance",d,"durationMinutes",points.size()-1);
            });
        }
    }
    @Test void enumeratesAllTwoPointOrdersAndChoosesDistanceFirstWithoutChangingInput() {
        List<Map<String,Object>> input=Arrays.asList(point(1,null),point(2,"00:00"),point(3,"00:00"));
        Fixture f=new Fixture(input,1);
        Map<String,Object> result=f.service.plan(f.request,f.job);
        assertTrue((Boolean)result.get("allScenariosPassed"));
        assertEquals(6L,f.job.evaluations);assertEquals(6L,f.job.feasible);
        List<Map<String,Object>> routes=SmallWindowPlanningService.maps(((Map<String,Object>)result.get("result")).get("routes"));
        List<Map<String,Object>> points=SmallWindowPlanningService.maps(routes.get(0).get("points"));
        assertTrue(f.seen.stream().anyMatch(ids->ids.indexOf(3)<ids.indexOf(2)));
        assertEquals(1,input.get(0).get("facilityId"));assertFalse(input.get(0).containsKey("order"));
        assertEquals(2,points.get(points.stream().map(p->p.get("facilityId")).collect(java.util.stream.Collectors.toList()).indexOf(3)-1).get("facilityId"));
    }
    @Test void dailyBypassCanRejectEveryFullTemplateFeasibleCandidate() {
        Fixture f=new Fixture(Arrays.asList(point(1,null),point(2,"03:04"),point(3,null)),1);
        f.request.put("scheduleDays",Arrays.asList(map("day",1,"facilityIds",Arrays.asList(2))));
        Map<String,Object> result=f.service.plan(f.request,f.job);
        assertFalse((Boolean)result.get("allScenariosPassed"));assertFalse(result.containsKey("result"));
        assertEquals(3L,f.job.evaluations);
    }
    @Test void laterInsertionsMayFixEarlyArrivalAndMustNotBePruned() {
        Fixture f=new Fixture(Arrays.asList(point(1,null),point(2,"03:04"),point(3,"00:00")),1);
        Map<String,Object> result=f.service.plan(f.request,f.job);
        assertTrue((Boolean)result.get("allScenariosPassed"));assertEquals(6L,f.job.evaluations);
    }
    @Test void rejectsMoreThanFiveWindowsAndRecognizesBarredWindows() {
        SmallWindowPlanningService service=new SmallWindowPlanningService(null,null,new ObjectMapper());
        List<Map<String,Object>> points=new ArrayList<>();for(int i=0;i<6;i++)points.add(point(i,"04:00"));
        assertThrows(IllegalArgumentException.class,()->service.start(map("points",points)));
        assertTrue(SmallWindowPlanningService.hasWindow(map("barredTimeBegin","04:00","barredTimeEnd","05:00")));
        assertFalse(SmallWindowPlanningService.hasWindow(map("allowTimeBegin","","barredTimeBegin","04:00")));
    }
    @Test void cancelledTaskDoesNotReturnPartialBestAsComplete() {
        Fixture f=new Fixture(Arrays.asList(point(1,null),point(2,"00:00"),point(3,null)),1);f.job.cancelled=true;
        assertThrows(java.util.concurrent.CancellationException.class,()->f.service.plan(f.request,f.job));
    }
    @Test void reservesConfiguredExtraTripAndRespectsCapacity() {
        Fixture f=new Fixture(Arrays.asList(point(1,null),point(2,"00:00"),point(3,null)),2);
        f.request.put("scheduleDays",Arrays.asList(map("day",1,"facilityIds",Arrays.asList(1,2,3))));
        when(f.optimizer.optimizeMultiPreview(anyMap())).thenAnswer(a -> {
            Map<String,Object> start=point(-1,null),end=point(-2,null);start.put("role","START");end.put("role","END");start.put("operationDurationMinutes",0D);end.put("operationDurationMinutes",0D);
            return map("routes",new ArrayList<>(Arrays.asList(map("points",new ArrayList<>(Arrays.asList(start,point(1,null),point(3,null),end)),"maxCapacityKg",2D,"ratedCapacityKg",2D,"vehicleId","v","tripNo",1,"routeNo",1))),"unassignedPointCount",0);
        });
        Map<String,Object> result=f.service.plan(f.request,f.job);
        assertTrue((Boolean)result.get("allScenariosPassed"));
        assertEquals(2,SmallWindowPlanningService.maps(((Map<String,Object>)result.get("result")).get("routes")).size());
    }

    @Test void localRoadMatrixMatchesNormalSegmentSpeedAndDoesNotQueryPerCandidate() {
        RouteMapPathService paths=mock(RouteMapPathService.class);
        when(paths.preloadCachedDistances(anyList())).thenReturn(Collections.emptyMap());
        when(paths.resolveWithoutCache(any(),any())).thenAnswer(a -> {
            com.zhj.route.algorithm.RoutePoint from=a.getArgument(0),to=a.getArgument(1);
            return new RouteMapPathService.ResolvedPath(Arrays.asList(map("longitude",from.getLongitude(),"latitude",from.getLatitude()),map("longitude",to.getLongitude(),"latitude",to.getLatitude())),1000D,999D,"OD_CACHE");
        });
        RouteOptimizeService optimizer=new RouteOptimizeService(null,paths,mock(RouteOptimizeTraceService.class));
        List<Map<String,Object>> pool=Arrays.asList(point(-1,null),point(1,null),point(2,null),point(-2,null));
        Function<List<Map<String,Object>>,Map<String,Object>> roads=optimizer.prepareJointRoads(map("denseSpeedKmh",15,"normalSpeedKmh",25,"transferSpeedKmh",40),pool,()->{});
        assertEquals(5.4D,((Number)roads.apply(pool).get("durationMinutes")).doubleValue(),0.001);
        roads.apply(Arrays.asList(pool.get(0),pool.get(2),pool.get(1),pool.get(3)));
        verify(paths,times(12)).resolveWithoutCache(any(),any());
        assertEquals(0D,((Number)roads.apply(Arrays.asList(pool.get(0),pool.get(0))).get("distance")).doubleValue());
        verify(paths,times(12)).resolveWithoutCache(any(),any());
    }

    @Test void matrixRejectsDirectFallbackRatherThanClaimingFeasibility() {
        RouteMapPathService paths=mock(RouteMapPathService.class);
        when(paths.preloadCachedDistances(anyList())).thenReturn(Collections.emptyMap());
        when(paths.resolveWithoutCache(any(),any())).thenReturn(new RouteMapPathService.ResolvedPath(Collections.emptyList(),null,null,"DIRECT"));
        RouteOptimizeService optimizer=new RouteOptimizeService(null,paths,mock(RouteOptimizeTraceService.class));
        assertThrows(IllegalStateException.class,()->optimizer.prepareJointRoads(map(),Arrays.asList(point(1,null),point(2,null)),()->{}));
    }

    @Test void collectionOperationUsesCurrentDensityAndHandlingParametersNotOldPointTiming() {
        RouteOptimizeService optimizer=new RouteOptimizeService(null,null,null);
        Map<String,Object> p=point(1,"04:00");p.put("containerInfo","240/87");p.put("containerCount",87);p.put("operationDurationMinutes",999D);
        Map<String,Object> result=optimizer.jointCollectionPoints(map("secondsPerContainer",20,"minutesPerPoint",1),Arrays.asList(p)).get(0);
        assertEquals(15.67D,((Number)result.get("operationDurationMinutes")).doubleValue());
        assertEquals("MIDDLE",result.get("role"));
    }

    @Test void fiveWindowPointsAndNoUnconstrainedPointsEnumerateAll120Orders() {
        List<Map<String,Object>> input=new ArrayList<>();for(int i=1;i<=5;i++)input.add(point(i,"00:00"));
        Fixture f=new Fixture(input,1);
        f.request.put("scheduleDays",Arrays.asList(map("day",1,"facilityIds",Arrays.asList(1,2,3,4,5))));
        Map<String,Object> result=f.service.plan(f.request,f.job);
        assertTrue((Boolean)result.get("allScenariosPassed"));assertEquals(120L,f.job.evaluations);assertEquals(120L,f.job.feasible);
        assertEquals("120",f.job.candidateUpperBound);
    }

    @Test void dailyWorkHoursAreCheckedEvenWhenFullTemplateFitsAndWindowsPass() {
        Fixture f=new Fixture(Arrays.asList(point(1,null),point(2,"00:00"),point(3,null)),1);
        f.request.put("workHours",1D/6D);f.request.put("scheduleDays",Arrays.asList(map("day",1,"facilityIds",Arrays.asList(2))));
        when(f.optimizer.prepareJointRoads(anyMap(),anyList(),any())).thenAnswer(a -> (Function<List<Map<String,Object>>,Map<String,Object>>) points -> {
            List<Map<String,Object>> segments=new ArrayList<>();double duration=points.size()==3?30D:1D;
            for(int i=1;i<points.size();i++)segments.add(map("distance",10D,"durationMinutes",duration,"pathSource","OD_CACHE"));
            return map("segments",segments,"distance",10D*(points.size()-1),"durationMinutes",duration*(points.size()-1));
        });
        Map<String,Object> result=f.service.plan(f.request,f.job);
        assertFalse((Boolean)result.get("allScenariosPassed"));assertFalse(result.containsKey("result"));
    }
}
