package com.zhj.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SmallWindowPlanningServiceTest {
    @Test void disposalWindowsDoNotCountTowardLimitAndDisposalIsRemovedFromDailyDue() throws Exception {
        List<Map<String,Object>> input=new ArrayList<>();for(int i=1;i<=5;i++)input.add(point(i,"00:00"));
        Map<String,Object> plant=point(99,"04:00");plant.put("facilityType",5);input.add(plant);
        Fixture f=new Fixture(input,1);f.request.put("scheduleDays",Arrays.asList(map("day",1,"facilityIds",Arrays.asList(1,2,3,4,5,99))));
        try {
            String id=String.valueOf(f.service.start(f.request).get("taskId"));
            Map<String,Object> preview=(Map<String,Object>)awaitStage(f.service,id,"WAITING_INSERTION").get("basePreview");
            assertEquals(5,SmallWindowPlanningService.maps(preview.get("pendingPoints")).size());
            f.service.insert(id);
            Map<String,Object> result=(Map<String,Object>)awaitStage(f.service,id,"DONE").get("response");
            assertTrue((Boolean)result.get("allScenariosPassed"));
            assertEquals(6,input.size());assertTrue(((List<?>)SmallWindowPlanningService.maps(f.request.get("scheduleDays")).get(0).get("facilityIds")).contains(99));
        } finally { f.service.shutdown(); }
    }
    @Test void roadProgressReportsActualUniquePairsAndPublishesPairBeforeLookup() {
        RouteMapPathService paths=mock(RouteMapPathService.class);
        when(paths.preloadCachedDistances(anyList())).thenReturn(Collections.emptyMap());
        List<Map<String,Object>> updates=new ArrayList<>();
        when(paths.resolveWithoutCache(any(),any())).thenAnswer(a -> {
            com.zhj.route.algorithm.RoutePoint from=a.getArgument(0),to=a.getArgument(1);
            Map<String,Object> latest=updates.get(updates.size()-1);
            assertEquals("RESOLVING",latest.get("state"));
            assertEquals(from.getFacilityName()+" → "+to.getFacilityName(),latest.get("currentPair"));
            return new RouteMapPathService.ResolvedPath(Arrays.asList(map("longitude",from.getLongitude(),"latitude",from.getLatitude()),map("longitude",to.getLongitude(),"latitude",to.getLatitude())),1000D,60D,from.getFacilityId()==1L?"BAIDU_ONLINE":"OD_CACHE");
        });
        RouteOptimizeService optimizer=new RouteOptimizeService(null,paths,mock(RouteOptimizeTraceService.class));
        optimizer.prepareJointRoads(map(),Arrays.asList(point(1,null),point(2,null),point(2,null)),()->{},updates::add);
        Map<String,Object> ready=updates.get(updates.size()-1);
        assertEquals("READY",ready.get("state"));assertEquals(2L,ready.get("total"));assertEquals(2L,ready.get("completed"));
        assertEquals(1L,ready.get("online"));assertEquals(1L,ready.get("cached"));
        assertEquals(0L,updates.get(0).get("completed"));verify(paths,times(2)).resolveWithoutCache(any(),any());
    }
    @Test void searchProgressIncludesProvisionalBestAndCountsPrunedBranches() {
        Fixture f=new Fixture(Arrays.asList(point(1,null),point(2,"00:00"),point(3,null)),1);
        f.service.plan(f.request,f.job);
        Map<String,Object> progress=(Map<String,Object>)f.job.view().get("progress");
        assertEquals("SEARCH",progress.get("phase"));
        Map<String,Object> best=(Map<String,Object>)progress.get("bestCandidate");
        assertTrue((Boolean)best.get("allScenariosPassed"));assertTrue(((Number)best.get("distance")).doubleValue()>0);
        f.request.put("points",Arrays.asList(point(1,null),point(2,"00:00"),point(3,null)));
        SmallWindowPlanningService.Job noFit=new SmallWindowPlanningService.Job();
        when(f.optimizer.optimizeMultiPreview(anyMap())).thenAnswer(a -> {
            Map<String,Object> start=point(-1,null),end=point(-2,null);start.put("role","START");end.put("role","END");
            return map("routes",new ArrayList<>(Arrays.asList(map("points",new ArrayList<>(Arrays.asList(start,point(1,null),point(3,null),end)),"maxCapacityKg",2D,"ratedCapacityKg",2D,"vehicleId","v","tripNo",1,"routeNo",1))),"unassignedPointCount",0);
        });
        f.service.plan(f.request,noFit);
        assertEquals(1L,noFit.capacityPruned);assertNull(noFit.bestCandidate);assertEquals(0L,noFit.evaluations);
    }
    @Test void terminalElapsedStopsIncreasing() {
        SmallWindowPlanningService.Job job=new SmallWindowPlanningService.Job();job.startedAt=System.currentTimeMillis()-2000;
        job.status="CANCELLED";job.finish();
        Map<String,Object> progress=(Map<String,Object>)job.view().get("progress");
        assertEquals("CANCELLED",progress.get("phase"));assertTrue(((Number)progress.get("elapsedMs")).longValue()>=2000);
        assertEquals(progress.get("elapsedMs"),((Map<String,Object>)job.view().get("progress")).get("elapsedMs"));
        assertFalse(job.view().containsKey("response"));
    }
    private static Map<String,Object> awaitStage(SmallWindowPlanningService service,String id,String expected) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        Map<String,Object> task;
        do {
            task=service.get(id);
            if(expected.equals(task.get("status"))) return task;
            if("FAILED".equals(task.get("status"))) fail(String.valueOf(task.get("message")));
            Thread.sleep(10);
        } while(System.nanoTime()<deadline);
        fail("等待任务阶段超时："+task);return task;
    }
    @Test void twoStagesWaitForUserAndReuseFrozenBackbone() throws Exception {
        Fixture f=new Fixture(Arrays.asList(point(1,null),point(2,"00:00"),point(3,null)),1);
        try {
            String id=String.valueOf(f.service.start(f.request).get("taskId"));
            Map<String,Object> waiting=awaitStage(f.service,id,"WAITING_INSERTION");
            assertEquals(0L,waiting.get("evaluations"));assertFalse(waiting.containsKey("response"));
            Map<String,Object> preview=(Map<String,Object>)waiting.get("basePreview");
            assertEquals(2,SmallWindowPlanningService.maps(preview.get("pendingPoints")).get(0).get("facilityId"));
            List<Map<String,Object>> points=SmallWindowPlanningService.maps(SmallWindowPlanningService.maps(preview.get("routes")).get(0).get("points"));
            assertFalse(points.stream().anyMatch(p->Objects.equals(2,p.get("facilityId"))));
            verify(f.optimizer,times(1)).prepareJointRoads(anyMap(),argThat(rows -> rows.stream().noneMatch(p->Objects.equals(2,p.get("facilityId")))),any(),any());
            f.request.put("points",Collections.emptyList());
            f.service.insert(id);
            Map<String,Object> done=awaitStage(f.service,id,"DONE");
            assertEquals(3L,done.get("evaluations"));
            assertTrue((Boolean)((Map<String,Object>)done.get("response")).get("allScenariosPassed"));
            verify(f.optimizer,times(1)).optimizeMultiPreview(anyMap());
            assertThrows(IllegalStateException.class,()->f.service.insert(id));
        } finally { f.service.shutdown(); }
    }
    @Test void closingWaitingStageCancelsWithoutSearching() throws Exception {
        Fixture f=new Fixture(Arrays.asList(point(1,null),point(2,"00:00"),point(3,null)),1);
        try {
            String id=String.valueOf(f.service.start(f.request).get("taskId"));
            awaitStage(f.service,id,"WAITING_INSERTION");
            assertThrows(IllegalStateException.class,()->f.service.start(f.request));
            assertEquals("CANCELLED",f.service.cancel(id).get("status"));
            assertThrows(IllegalStateException.class,()->f.service.insert(id));
            assertEquals(0L,f.service.get(id).get("evaluations"));
            verify(f.optimizer,times(1)).prepareJointRoads(anyMap(),anyList(),any(),any());
        } finally { f.service.shutdown(); }
    }
    @Test void allTimedFirstStageShowsOnlyScatterAndDoesNotBuildCollectionRoute() throws Exception {
        Fixture f=new Fixture(Arrays.asList(point(1,"00:00"),point(2,"00:00")),1);
        try {
            String id=String.valueOf(f.service.start(f.request).get("taskId"));
            Map<String,Object> preview=(Map<String,Object>)awaitStage(f.service,id,"WAITING_INSERTION").get("basePreview");
            assertTrue(SmallWindowPlanningService.maps(preview.get("routes")).isEmpty());
            assertEquals(2,SmallWindowPlanningService.maps(preview.get("pendingPoints")).size());
            verify(f.optimizer,never()).optimizeMultiPreview(anyMap());
            verify(f.optimizer,never()).prepareJointRoads(anyMap(),anyList(),any(),any());
        } finally { f.service.shutdown(); }
    }
    @Test void emptyBackboneUsesConfiguredAnchorsAndCapacityWithoutBaiduCalls() {
        RouteMapPathService paths=mock(RouteMapPathService.class);
        RouteOptimizeService optimizer=new RouteOptimizeService(null,paths,mock(RouteOptimizeTraceService.class));
        Map<String,Object> request=map("startLongitude",118D,"startLatitude",36D,"endLongitude",119D,"endLatitude",37D,
            "startFacilityName","停车场","endFacilityName","处理厂","vehicles",Arrays.asList(map("vehicleId","v","tripCount",2,"ratedCapacityKg",10000D,"maxCapacityKg",11000D)));
        Map<String,Object> route=SmallWindowPlanningService.maps(optimizer.jointEmptyBackbone(request).get("routes")).get(0);
        List<Map<String,Object>> points=SmallWindowPlanningService.maps(route.get("points"));
        assertEquals(2,points.size());assertEquals("START",points.get(0).get("role"));assertEquals("END",points.get(1).get("role"));
        assertEquals(11000D,((Number)route.get("maxCapacityKg")).doubleValue());
        verifyNoInteractions(paths);
        assertThrows(IllegalArgumentException.class,()->optimizer.jointEmptyBackbone(map("vehicles",request.get("vehicles"))));
    }
    @Test void oneNormalPointStillGeneratesBackboneWithExplicitAnchors() {
        RouteOptimizeService optimizer=new RouteOptimizeService(null,mock(RouteMapPathService.class),mock(RouteOptimizeTraceService.class));
        Map<String,Object> request=map("points",Arrays.asList(point(1,null)),"startLongitude",118D,"startLatitude",36D,"endLongitude",119D,"endLatitude",37D,
            "vehicles",Arrays.asList(map("vehicleId","v","tripCount",1,"ratedCapacityKg",10000D,"maxCapacityKg",11000D)),"workHours",24D);
        List<Map<String,Object>> updates=new ArrayList<>();
        Map<String,Object> result=optimizer.optimizeJointBackbone(request,updates::add);
        assertTrue(updates.stream().anyMatch(p -> ((Number)p.get("currentTripNo")).intValue()==1));
        assertEquals(0,((Number)result.get("unassignedPointCount")).intValue());
        List<Map<String,Object>> routes=SmallWindowPlanningService.maps(result.get("routes"));
        assertEquals(1,routes.size());
        assertEquals(3,SmallWindowPlanningService.maps(routes.get(0).get("points")).size());
    }
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
            when(optimizer.optimizeJointBackbone(anyMap(),any())).thenAnswer(a -> optimizer.optimizeMultiPreview(a.getArgument(0)));
            when(optimizer.jointEmptyBackbone(anyMap())).thenAnswer(a -> {
                Map<String,Object> start=point(-1,null),end=point(-2,null);
                start.put("role","START");end.put("role","END");start.put("operationDurationMinutes",0D);end.put("operationDurationMinutes",0D);
                return map("routes",new ArrayList<>(Arrays.asList(map("points",new ArrayList<>(Arrays.asList(start,end)),"maxCapacityKg",10D,"ratedCapacityKg",10D,"vehicleId","v","tripNo",1,"routeNo",1))),"unassignedPointCount",0);
            });
            when(optimizer.optimizeMultiPreview(anyMap())).thenAnswer(a -> {
                List<Map<String,Object>> points=new ArrayList<>();Map<String,Object> start=point(-1,null);start.put("role","START");start.put("operationDurationMinutes",0D);points.add(start);
                points.addAll(SmallWindowPlanningService.maps(((Map<String,Object>)a.getArgument(0)).get("points")));
                Map<String,Object> end=point(-2,null);end.put("role","END");end.put("operationDurationMinutes",0D);points.add(end);
                return map("routes",new ArrayList<>(Arrays.asList(map("points",points,"maxCapacityKg",10D,"ratedCapacityKg",10D,"vehicleId","v","tripNo",1,"routeNo",1,"workLimitMinutes",480D))),"unassignedPointCount",0);
            });
            when(optimizer.prepareJointRoads(anyMap(),anyList(),any(),any())).thenAnswer(a -> (Function<List<Map<String,Object>>,Map<String,Object>>) points -> {
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
        when(f.optimizer.prepareJointRoads(anyMap(),anyList(),any(),any())).thenAnswer(a -> (Function<List<Map<String,Object>>,Map<String,Object>>) points -> {
            List<Map<String,Object>> segments=new ArrayList<>();double duration=points.size()==3?30D:1D;
            for(int i=1;i<points.size();i++)segments.add(map("distance",10D,"durationMinutes",duration,"pathSource","OD_CACHE"));
            return map("segments",segments,"distance",10D*(points.size()-1),"durationMinutes",duration*(points.size()-1));
        });
        Map<String,Object> result=f.service.plan(f.request,f.job);
        assertFalse((Boolean)result.get("allScenariosPassed"));assertFalse(result.containsKey("result"));
    }
}
