package com.zhj.route.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhj.route.algorithm.FacilityTimeWindow;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Exhaustive insertion of <=5 constrained points into a fixed unconstrained backbone. */
@Service
public class SmallWindowPlanningService {
    private final RouteOptimizeService optimizer;
    private final TripReorderService checker;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    public SmallWindowPlanningService(RouteOptimizeService optimizer, TripReorderService checker, ObjectMapper mapper) {
        this.optimizer = optimizer; this.checker = checker; this.mapper = mapper;
    }
    @javax.annotation.PreDestroy
    public void shutdown() { for(Job job:jobs.values()) job.cancelled=true;executor.shutdownNow(); }
    public synchronized Map<String,Object> start(Map<String,Object> request) {
        Map<String,Object> input = cloneMap(request);
        Set<String> disposalIds=new HashSet<>();
        for(Map<String,Object> point:maps(input.get("points")))if(PlanningPointFilter.isDisposal(point))disposalIds.add(String.valueOf(point.get("facilityId")));
        input.put("points",PlanningPointFilter.collections(maps(input.get("points"))));
        for(Map<String,Object> day:maps(input.get("scheduleDays"))) {
            if(day.get("facilityIds") instanceof List) {
                List<?> due=(List<?>)day.get("facilityIds");List<Object> kept=new ArrayList<>();
                for(Object id:due)if(!disposalIds.contains(String.valueOf(id)))kept.add(id);
                day.put("facilityIds",kept);
            }
        }
        List<Map<String,Object>> points = maps(input.get("points"));
        long count = points.stream().filter(SmallWindowPlanningService::hasWindow).count();
        if (count < 1 || count > 5) throw new IllegalArgumentException("仅允许1～5个时间窗点进行联合规划");
        if (maps(input.get("scheduleDays")).isEmpty()) throw new IllegalArgumentException("需要完整排班周期");
        if (maps(input.get("vehicles")).size() != 1) throw new IllegalArgumentException("当前联合规划仅支持岗位所选的一辆车");
        if(jobs.values().stream().anyMatch(j -> Arrays.asList("QUEUED","RUNNING","WAITING_INSERTION").contains(j.status))) throw new IllegalStateException("已有联合规划任务，请完成或取消后重试");
        if(jobs.size()>=20) jobs.entrySet().removeIf(e -> Arrays.asList("DONE","FAILED","CANCELLED").contains(e.getValue().status));
        Job job = new Job(); jobs.put(job.id,job);
        job.future = executor.submit(() -> {
            try {
                job.status="RUNNING"; job.prepared=prepare(input,job);
                job.basePreview=basePreview(job.prepared,job);
                synchronized(SmallWindowPlanningService.this) {
                    check(job); job.message="第一阶段完成：无时间窗点基础路线已生成，等待开始插入时间窗点";
                    job.status="WAITING_INSERTION";
                    job.phase("WAITING_INSERTION");job.finishedAt=System.currentTimeMillis();
                }
            }
            catch (CancellationException ex) { job.status="CANCELLED"; job.message="已取消，原方案未改变"; job.finish(); }
            catch (Exception ex) { job.status="FAILED"; job.message=ex.getMessage(); job.finish(); }
        });
        return job.view();
    }
    public synchronized Map<String,Object> insert(String id) {
        Job job=jobs.get(id);
        if(job==null) throw new IllegalArgumentException("任务不存在");
        if(!"WAITING_INSERTION".equals(job.status) || job.cancelled) throw new IllegalStateException("请先完成第一阶段；第二阶段不可重复启动");
        job.status="QUEUED"; job.message="等待开始插入时间窗点";
        job.startedAt=System.currentTimeMillis();job.finishedAt=0;job.phase("MATRIX");job.roadProgress=Collections.emptyMap();
        job.future=executor.submit(() -> {
            try {
                check(job); job.status="RUNNING"; job.response=searchPrepared(job.prepared,job);
                synchronized(SmallWindowPlanningService.this) { check(job); job.status="DONE"; job.finish(); }
            }
            catch(CancellationException ex) { job.status="CANCELLED"; job.message="已取消，原方案未改变"; job.finish(); }
            catch(Exception ex) { job.status="FAILED"; job.message=ex.getMessage(); job.finish(); }
        });
        return job.view();
    }
    public synchronized Map<String,Object> updateBaseRoutes(String id, Map<String,Object> request) {
        Job job=jobs.get(id);
        if(job==null) throw new IllegalArgumentException("任务不存在");
        if(!"WAITING_INSERTION".equals(job.status) || job.cancelled || job.prepared==null)
            throw new IllegalStateException("仅第一阶段完成、开始插入前允许调整基础路线");
        Prepared previous=job.prepared;
        List<Map<String,Object>> submitted=maps(request.get("routes"));
        List<Map<String,Object>> displayed=new ArrayList<>();
        for(Map<String,Object> route:previous.routes) if(maps(route.get("points")).size()>2) displayed.add(route);
        if(submitted.isEmpty() || submitted.size()!=displayed.size()) throw new IllegalArgumentException("基础路线趟次数不一致");
        List<Map<String,Object>> updated=new ArrayList<>();
        for(Map<String,Object> route:previous.routes) updated.add(cloneMap(route));
        for(int index=0;index<displayed.size();index++) {
            Map<String,Object> source=displayed.get(index), changed=submitted.get(index);
            if(!Objects.equals(String.valueOf(source.get("routeNo")),String.valueOf(changed.get("routeNo")))
                || !Objects.equals(String.valueOf(source.get("tripNo")),String.valueOf(changed.get("tripNo"))))
                throw new IllegalArgumentException("基础路线趟次归属不能变化");
            List<Map<String,Object>> sourcePoints=maps(source.get("points")), changedPoints=maps(changed.get("points"));
            if(sourcePoints.size()!=changedPoints.size()) throw new IllegalArgumentException("基础路线点位数量不能变化");
            Set<Integer> used=new HashSet<>();List<Map<String,Object>> ordered=new ArrayList<>();
            for(int position=0;position<changedPoints.size();position++) {
                Object token=changedPoints.get(position).get("manualToken");
                String prefix=index+":";
                if(!(token instanceof String) || !((String)token).startsWith(prefix)) throw new IllegalArgumentException("点位顺序标识无效");
                int original;
                try { original=Integer.parseInt(((String)token).substring(prefix.length())); }
                catch(NumberFormatException ex) { throw new IllegalArgumentException("点位顺序标识无效"); }
                if(original<0 || original>=sourcePoints.size() || !used.add(original)) throw new IllegalArgumentException("点位重复或超出基础路线");
                Map<String,Object> sourcePoint=sourcePoints.get(original);
                if(("START".equals(sourcePoint.get("role")) || "END".equals(sourcePoint.get("role")) || "ANCHOR".equals(sourcePoint.get("weightSource"))) && original!=position)
                    throw new IllegalArgumentException("固定锚点不能移动");
                for(int boundary=Math.min(original,position);boundary<=Math.max(original,position);boundary++) {
                    Map<String,Object> barrier=sourcePoints.get(boundary);
                    if(boundary!=original && ("START".equals(barrier.get("role")) || "END".equals(barrier.get("role")) || "ANCHOR".equals(barrier.get("weightSource"))))
                        throw new IllegalArgumentException("点位不能跨越固定锚点");
                }
                if("MIDDLE".equals(sourcePoint.get("role")) && hasWindow(sourcePoint)) throw new IllegalArgumentException("时间窗点不能进入第一阶段基础路线");
                Map<String,Object> copy=cloneMap(sourcePoint);copy.put("order",position+1);copy.put("orderNum",position+1);ordered.add(copy);
            }
            Map<String,Object> target=updated.stream().filter(route -> Objects.equals(String.valueOf(route.get("routeNo")),String.valueOf(source.get("routeNo")))).findFirst().orElseThrow(() -> new IllegalStateException("基础路线不存在"));
            target.put("points",ordered);
            target.remove("segments");target.remove("roadSegments");
        }
        Map<String,Object> base=cloneMap(previous.base);
        for(Map<String,Object> route:maps(base.get("routes"))) {
            for(Map<String,Object> candidate:updated) if(Objects.equals(String.valueOf(route.get("routeNo")),String.valueOf(candidate.get("routeNo")))) {
                route.put("points",cloneMap(candidate).get("points"));break;
            }
        }
        Prepared proposal=new Prepared(previous.request,base,previous.all,previous.timed,updated);
        try {
            Map<String,Object> preview=basePreview(proposal,job);
            job.prepared=proposal;job.basePreview=preview;
            job.status="WAITING_INSERTION";job.message="基础路线已更新，可开始插入时间窗点";job.phase("WAITING_INSERTION");
            return job.view();
        } catch(RuntimeException ex) { job.phase("WAITING_INSERTION");throw ex; }
    }
    public Map<String,Object> get(String id) { Job job=jobs.get(id); if(job==null) return Collections.singletonMap("status","NOT_FOUND"); return job.view(); }
    public synchronized Map<String,Object> cancel(String id) {
        Job job=jobs.get(id); if(job==null) return get(id);
        if (!Arrays.asList("DONE","FAILED","CANCELLED").contains(job.status)) { job.cancelled=true; job.status="CANCELLED"; job.message="已取消，原方案未改变"; job.finish(); if(job.future!=null) job.future.cancel(true); }
        return job.view();
    }
    private static void check(Job job) { if(job.cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException(); }
    static boolean hasWindow(Map<String,Object> p) {
        return FacilityTimeWindow.parseClockMinutes(p.get("allowTimeBegin")) != null
            || FacilityTimeWindow.parseClockMinutes(p.get("allowTimeEnd")) != null
            || (FacilityTimeWindow.parseClockMinutes(p.get("barredTimeBegin")) != null && FacilityTimeWindow.parseClockMinutes(p.get("barredTimeEnd")) != null);
    }
    Map<String,Object> plan(Map<String,Object> request, Job job) {
        return searchPrepared(prepare(cloneMap(request),job),job);
    }
    private Prepared prepare(Map<String,Object> request, Job job) {
        check(job); job.phase("BASE_PLANNING"); job.message="正在生成无时间窗点基础多趟路线";
        if(request.get("workHours")!=null && (!Double.isFinite(num(request.get("workHours"))) || num(request.get("workHours"))<=0)) throw new IllegalArgumentException("工作时长必须是正数");
        List<Map<String,Object>> all = optimizer.jointCollectionPoints(request,maps(request.get("points")));
        Set<String> ids=new HashSet<>();
        for(Map<String,Object> p:all) {
            if(p.get("facilityId")==null || !ids.add(String.valueOf(p.get("facilityId")))) throw new IllegalArgumentException("点位编号缺失或重复");
            if(p.get("longitude")==null || p.get("latitude")==null || !Double.isFinite(num(p.get("longitude"))) || !Double.isFinite(num(p.get("latitude")))) throw new IllegalArgumentException("点位坐标缺失或无效");
            if(!Double.isFinite(num(p.get("estimatedWeightKg"))) || num(p.get("estimatedWeightKg"))<0) throw new IllegalArgumentException("点位重量无效");
            if(p.get("operationDurationMinutes")==null || !Double.isFinite(num(p.get("operationDurationMinutes"))) || num(p.get("operationDurationMinutes"))<0) throw new IllegalArgumentException("点位作业时间无效");
        }
        List<Map<String,Object>> timed=new ArrayList<>(), normal=new ArrayList<>();
        for(Map<String,Object> p:all) (hasWindow(p)?timed:normal).add(p);
        if(timed.isEmpty() || timed.size()>5) throw new IllegalArgumentException("仅允许1～5个时间窗点");
        Map<String,Object> baseRequest=new LinkedHashMap<>(request);
        baseRequest.remove("scheduleDays"); baseRequest.remove("facilityIds");
        // No time-window point affects the unconstrained backbone construction.
        baseRequest.put("points",normal);
        Map<String,Object> base=normal.isEmpty()?optimizer.jointEmptyBackbone(baseRequest):optimizer.optimizeJointBackbone(baseRequest,progress -> {
            check(job);job.backboneProgress=progress;job.message=String.valueOf(progress.get("message"));job.touch();
        });
        if(num(base.get("unassignedPointCount"))>0) throw new IllegalStateException("基础规划存在未分配点，请调整载重、趟数或工作时长后重试");
        List<Map<String,Object>> routes=maps(base.get("routes"));
        if(routes.isEmpty()) throw new IllegalStateException("未生成基础趟次");
        for(Map<String,Object> route:routes) {
            if(!Double.isFinite(num(route.get("maxCapacityKg"))) || num(route.get("maxCapacityKg"))<=0) throw new IllegalArgumentException("车辆最大载重必须是正数");
            List<Map<String,Object>> kept=new ArrayList<>();
            for(Map<String,Object> p:maps(route.get("points"))) if(!"MIDDLE".equals(p.get("role")) || !hasWindow(p)) kept.add(p);
            route.put("points",kept);
        }
        Map<String,Object> vehicle=maps(request.get("vehicles")).get(0);
        int maxTrips=Math.max(1,(int)num(vehicle.get("tripCount")));
        if(routes.size()>maxTrips) throw new IllegalStateException("基础趟次超过配置上限");
        Map<String,Object> last=routes.get(routes.size()-1);
        List<Map<String,Object>> lastPoints=maps(last.get("points"));
        Map<String,Object> end=lastPoints.get(lastPoints.size()-1);
        // Configured unused trips remain available; no implicit unlimited vehicle/capacity.
        while(routes.size()<maxTrips) {
            Map<String,Object> extra=new LinkedHashMap<>(last);
            Map<String,Object> startPoint=new LinkedHashMap<>(end); startPoint.put("role","START"); startPoint.put("operationDurationMinutes",0D);
            extra.put("points",new ArrayList<>(Arrays.asList(startPoint,new LinkedHashMap<>(end))));
            extra.put("routeNo",routes.size()+1); extra.put("tripNo",routes.size()+1); routes.add(extra);
        }
        return new Prepared(request,base,all,timed,routes);
    }
    private Map<String,Object> basePreview(Prepared prepared, Job job) {
        job.phase("BASE_ROADS");job.message="正在准备基础路线道路数据";
        List<Map<String,Object>> displayed=new ArrayList<>();
        for(Map<String,Object> route:prepared.routes) if(maps(route.get("points")).size()>2) displayed.add(cloneMap(route));
        List<Map<String,Object>> pool=new ArrayList<>();
        for(Map<String,Object> route:displayed) pool.addAll(maps(route.get("points")));
        if(!pool.isEmpty()) {
            Function<List<Map<String,Object>>,Map<String,Object>> roads=optimizer.prepareJointRoads(prepared.request,pool,()->check(job),job::roads);
            for(Map<String,Object> route:displayed) {
                job.message="正在绘制基础路线第"+route.get("tripNo")+"趟";job.touch();
                List<Map<String,Object>> points=maps(route.get("points"));
                Map<String,Object> road=roads.apply(points);
                double operation=0D;for(Map<String,Object> point:points)operation+=num(point.get("operationDurationMinutes"));
                route.put("segments",road.get("segments")); route.put("roadSegments",road.get("segments"));
                route.put("distance",road.get("distance")); route.put("durationMinutes",num(road.get("durationMinutes"))+operation);
            }
        }
        Map<String,Object> preview=new LinkedHashMap<>(); preview.put("routes",displayed); preview.put("pendingPoints",prepared.timed);
        preview.put("message",displayed.isEmpty()?"没有无时间窗收运点，第二阶段从配置起终点开始插入":"蓝色为基础路线，紫色为待插入时间窗点（不参与连线和基础统计）");
        return preview;
    }
    private Map<String,Object> searchPrepared(Prepared prepared, Job job) {
        Map<String,Object> request=prepared.request,base=prepared.base;
        List<Map<String,Object>> all=prepared.all,timed=prepared.timed,routes=prepared.routes;
        check(job);
        List<Map<String,Object>> matrixPoints=new ArrayList<>(all);
        for(Map<String,Object> route:routes) for(Map<String,Object> p:maps(route.get("points"))) {
            if(matrixPoints.stream().noneMatch(q -> Objects.equals(q.get("facilityId"),p.get("facilityId")) && Objects.equals(q.get("longitude"),p.get("longitude")) && Objects.equals(q.get("latitude"),p.get("latitude")))) matrixPoints.add(p);
        }
        job.message="正在准备有向道路矩阵（缺失OD按现有策略补算）";
        job.phase("MATRIX");
        Function<List<Map<String,Object>>,Map<String,Object>> roads=optimizer.prepareJointRoads(request,matrixPoints,()->check(job),job::roads);
        // Coverage metadata must include all timed points before daily validation.
        Map<String,Object> coverage=cloneMap(base); coverage.put("distanceMode","ROAD");
        List<Map<String,Object>> coveredRoutes=maps(coverage.get("routes"));
        List<Map<String,Object>> covered=maps(coveredRoutes.get(0).get("points")); covered.addAll(1,timed); coveredRoutes.get(0).put("points",covered);
        Map<String,Object> evalRequest=new LinkedHashMap<>(request); evalRequest.put("result",coverage);
        Function<List<Map<String,Object>>,Map<String,Object>> evaluate=checker.jointScenarioChecker(evalRequest,roads);
        Search search=new Search(request,routes,timed,roads,evaluate,job);
        job.message="正在联合枚举插入位置和顺序；校验完整模板及每日跳点";
        job.phase("SEARCH");
        int slots=0;for(Map<String,Object> route:routes) slots+=maps(route.get("points")).size()-1;
        java.math.BigInteger bound=java.math.BigInteger.ONE;
        for(int i=0;i<timed.size();i++) bound=bound.multiply(java.math.BigInteger.valueOf(slots+i));
        job.candidateUpperBound=bound.toString();
        search.visit(0);
        Map<String,Object> response=new LinkedHashMap<>();
        response.put("evaluations",job.evaluations); response.put("feasibleCandidates",job.feasible);
        response.put("timeWindowPointCount",timed.size()); response.put("allScenariosPassed",search.best!=null);
        response.put("scope","无时间窗点的基础顺序和归属固定；时间窗点可在配置趟次内插入；不代表全局最优");
        if(search.best==null) { response.put("message","未找到完整模板及每日均满足时间窗、载重和工作时长的插入方案；原方案未改变"); return response; }
        Map<String,Object> result=new LinkedHashMap<>(base); result.put("routes",search.best); result.put("distanceMode","ROAD");
        result.put("routeCount",search.best.size()); result.put("assignedPointCount",all.size()); result.put("unassignedPointCount",0); result.put("unassignedPoints",Collections.emptyList());
        result.put("smallWindowJointPlanning",true);
        result.put("status","DONE");result.put("message",response.get("scope"));result.put("displayMode","ROAD");
        double weight=0D;for(Map<String,Object> p:all)weight+=num(p.get("estimatedWeightKg"));
        result.put("sourcePointCount",all.size());result.put("candidatePointCount",all.size());
        result.put("estimatedWeightKg",weight);result.put("assignedWeightKg",weight);result.put("unassignedWeightKg",0D);
        result.put("dispatchTripCount",search.best.size());
        double capacity=0D;for(Map<String,Object> route:search.best)capacity+=num(route.get("maxCapacityKg"));
        result.put("totalPlannedCapacityKg",capacity);
        response.put("result",result); response.put("checks",search.bestChecks);
        response.put("distance",search.bestDistance); response.put("durationMinutes",search.bestDuration);
        response.put("message",search.bestChecks.get("message")+" 已完成全部插入候选比较，按完整模板里程优先选取。");
        return response;
    }
    private static class Search {
        final Map<String,Object> request; final List<Map<String,Object>> routes,timed;
        final Function<List<Map<String,Object>>,Map<String,Object>> roads,evaluate; final Job job;
        List<Map<String,Object>> best; Map<String,Object> bestChecks; double bestDistance=Double.POSITIVE_INFINITY,bestDuration=Double.POSITIVE_INFINITY;
        Search(Map<String,Object> request,List<Map<String,Object>> routes,List<Map<String,Object>> timed,Function<List<Map<String,Object>>,Map<String,Object>> roads,Function<List<Map<String,Object>>,Map<String,Object>> evaluate,Job job) {
            this.request=request;this.routes=routes;this.timed=timed;this.roads=roads;this.evaluate=evaluate;this.job=job;
        }
        void visit(int depth) {
            check(job);
            if(depth==timed.size()) { leaf(); return; }
            Map<String,Object> p=timed.get(depth);
            for(Map<String,Object> route:routes) {
                List<Map<String,Object>> points=maps(route.get("points"));
                double weight=0D; for(Map<String,Object> q:points) if("MIDDLE".equals(q.get("role"))) weight+=num(q.get("estimatedWeightKg"));
                if(weight+num(p.get("estimatedWeightKg"))>num(route.get("maxCapacityKg"))+1e-6) { if((++job.capacityPruned & 255)==1)job.touch();continue; }
                // No partial time-window pruning: later insertions can delay an early point.
                for(int at=1;at<points.size();at++) {
                    points.add(at,p); route.put("points",points); visit(depth+1); points.remove(at);
                }
            }
        }
        void leaf() {
            job.evaluations++;
            if((job.evaluations & 255)==1) job.touch();
            List<Map<String,Object>> built=new ArrayList<>(); double distance=0D,duration=0D;
            for(Map<String,Object> route:routes) {
                List<Map<String,Object>> input=maps(route.get("points"));
                if(input.size()==2) continue; // Unused configured trips do not incur fictitious unloads.
                Map<String,Object> out=new LinkedHashMap<>(route); List<Map<String,Object>> points=new ArrayList<>();
                double weight=0D,volume=0D,operation=0D;
                for(Map<String,Object> p:input) {
                    Map<String,Object> q=new LinkedHashMap<>(p); q.put("order",points.size()+1); q.put("orderNum",points.size()+1); points.add(q);
                    operation+=num(q.get("operationDurationMinutes"));
                    if("MIDDLE".equals(q.get("role"))) { weight+=num(q.get("estimatedWeightKg")); volume+=num(q.get("estimatedVolumeLiter")); }
                }
                Map<String,Object> actualStart=new LinkedHashMap<>(built.isEmpty()
                    ? maps(routes.get(0).get("points")).get(0)
                    : maps(built.get(built.size()-1).get("points")).get(maps(built.get(built.size()-1).get("points")).size()-1));
                actualStart.put("role","START");actualStart.put("operationDurationMinutes",0D);actualStart.put("order",1);actualStart.put("orderNum",1);
                points.set(0,actualStart);
                Map<String,Object> road=roads.apply(points); double d=num(road.get("distance")),travel=num(road.get("durationMinutes")),total=travel+operation;
                out.put("points",points); out.put("segments",road.get("segments")); out.put("roadSegments",road.get("segments"));
                for(String k:Arrays.asList("distance","planningDistance","displayDistance")) out.put(k,d);
                for(String k:Arrays.asList("travelDurationMinutes","planningTravelDurationMinutes","displayTravelDurationMinutes")) out.put(k,travel);
                for(String k:Arrays.asList("durationMinutes","totalDurationMinutes","planningTotalDurationMinutes","displayTotalDurationMinutes")) out.put(k,total);
                out.put("operationDurationMinutes",operation); out.put("estimatedWeightKg",weight); out.put("estimatedVolumeLiter",volume);
                out.put("loadRate",num(out.get("ratedCapacityKg"))>0?weight/num(out.get("ratedCapacityKg")):0D);
                out.put("pointCount",points.size()-2); out.put("routeNo",built.size()+1); out.put("tripNo",built.size()+1);
                out.put("timeExceeded",false); out.put("overdueMinutes",0D); out.remove("polyline");
                List<Object> sequence=new ArrayList<>();for(Map<String,Object> point:points)sequence.add(point.get("facilityId"));out.put("sequence",sequence);
                out.put("roadDistance",d);out.put("roadDurationMinutes",travel);
                built.add(out);distance+=d;duration+=total;
                out.put("vehicleWorkedMinutes",duration);
                double workLimit=num(request.get("workHours"))*60D;if(workLimit<=0)workLimit=480D;
                out.put("vehicleRemainingMinutes",Math.max(0D,workLimit-duration));
            }
            double limit=num(request.get("workHours"))*60D;
            if(limit<=0) limit=480D;
            if(duration>limit+1e-6) return;
            Map<String,Object> checks=evaluate.apply(built);
            if(!Boolean.TRUE.equals(checks.get("allScenariosPassed"))) return;
            for(Map<String,Object> day:maps(checks.get("dailyChecks"))) if(num(day.get("completionMinutes"))>limit+1e-6) return;
            job.feasible++;
            if(distance>bestDistance+1e-6 || (Math.abs(distance-bestDistance)<1e-6 && duration>=bestDuration)) return;
            best=built;bestChecks=checks;bestDistance=distance;bestDuration=duration;
            Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("distance",distance);snapshot.put("durationMinutes",duration);snapshot.put("routeCount",built.size());snapshot.put("allScenariosPassed",true);
            job.bestCandidate=snapshot;job.touch();
        }
    }
    private static class Prepared {
        final Map<String,Object> request,base;
        final List<Map<String,Object>> all,timed,routes;
        Prepared(Map<String,Object> request,Map<String,Object> base,List<Map<String,Object>> all,List<Map<String,Object>> timed,List<Map<String,Object>> routes) {
            this.request=request;this.base=base;this.all=all;this.timed=timed;this.routes=routes;
        }
    }
    static class Job {
        final String id=UUID.randomUUID().toString(); volatile String status="QUEUED",message="等待联合规划";
        volatile long evaluations=0,feasible=0; volatile boolean cancelled=false; volatile Future<?> future; volatile Map<String,Object> response;
        volatile String candidateUpperBound="";
        volatile Prepared prepared; volatile Map<String,Object> basePreview;
        volatile String phase="QUEUED";volatile long startedAt=System.currentTimeMillis(),updatedAt=startedAt,finishedAt=0,capacityPruned=0;
        volatile Map<String,Object> roadProgress=Collections.emptyMap(),backboneProgress=Collections.emptyMap(),bestCandidate;
        void touch() { updatedAt=System.currentTimeMillis(); }
        void phase(String value) { phase=value;touch(); }
        void finish() { finishedAt=System.currentTimeMillis();phase(status); }
        void roads(Map<String,Object> value) { roadProgress=value;touch(); }
        Map<String,Object> view() {
            Map<String,Object> out=new LinkedHashMap<>();out.put("taskId",id);out.put("status",status);out.put("message",message);out.put("evaluations",evaluations);out.put("feasibleCandidates",feasible);out.put("candidateUpperBound",candidateUpperBound);
            Map<String,Object> progress=new LinkedHashMap<>();long now=System.currentTimeMillis();
            progress.put("phase",phase);progress.put("elapsedMs",Math.max(0,(finishedAt>0?finishedAt:now)-startedAt));progress.put("updatedAgoMs",Math.max(0,now-updatedAt));
            progress.put("roads",roadProgress);progress.put("capacityPruned",capacityPruned);progress.put("bestCandidate",bestCandidate);out.put("progress",progress);
            progress.put("backbone",backboneProgress);
            if(basePreview!=null)out.put("basePreview",basePreview);if("DONE".equals(status)) out.put("response",response);return out;
        }
    }
    private Map<String,Object> cloneMap(Map<String,Object> map) { return mapper.convertValue(map,new TypeReference<Map<String,Object>>(){}); }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> maps(Object value) { return value instanceof List ? (List<Map<String,Object>>)value : new ArrayList<>(); }
    static double num(Object v) { return v==null?0D:Double.parseDouble(String.valueOf(v)); }
}
