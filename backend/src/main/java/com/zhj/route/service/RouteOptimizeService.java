package com.zhj.route.service;

import com.zhj.route.algorithm.RouteOptimizationResult;
import com.zhj.route.algorithm.RoutePoint;
import com.zhj.route.algorithm.SingleRouteOptimizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class RouteOptimizeService {
    private static final Logger log = LoggerFactory.getLogger(RouteOptimizeService.class);
    private static final String STRATEGY_DIRECT_GROUP = "DIRECT_GROUP";
    private static final String STRATEGY_DIRECT_GROUP_ROAD_REFINE = "DIRECT_GROUP_ROAD_REFINE";
    private static final String STRATEGY_ROAD_GLOBAL = "ROAD_GLOBAL";

    private final RouteQueryService routeQueryService;
    private final RouteMapPathService routeMapPathService;
    private final RouteOptimizeTraceService traceService;
    private final SingleRouteOptimizer singleRouteOptimizer = new SingleRouteOptimizer();
    private final ExecutorService multiRouteExecutor = Executors.newFixedThreadPool(2);
    private final Map<String, MultiRouteTask> multiRouteTasks = new ConcurrentHashMap<String, MultiRouteTask>();

    public RouteOptimizeService(RouteQueryService routeQueryService, RouteMapPathService routeMapPathService, RouteOptimizeTraceService traceService) {
        this.routeQueryService = routeQueryService;
        this.routeMapPathService = routeMapPathService;
        this.traceService = traceService;
    }

    public Map<String, Object> optimizePreview(Map<String, Object> request) {
        long startedAt = System.currentTimeMillis();
        Object routeIdValue = request.get("routeId");
        if (routeIdValue == null) {
            throw new IllegalArgumentException("routeId is required");
        }

        Long routeId = Long.valueOf(String.valueOf(routeIdValue));
        boolean useRoadPath = useRoadPath(request);
        boolean displayRoadPath = displayRoadPath(request, useRoadPath);
        DistanceContext distanceContext = new DistanceContext(useRoadPath, useRoadPath);
        DistanceContext displayContext = displayRoadPath == useRoadPath ? distanceContext : new DistanceContext(displayRoadPath, false);
        List<Map<String, Object>> planRows = routeQueryService.routePlanPoints(routeId);
        List<RoutePoint> points = toRoutePoints(planRows);
        distanceContext.preload(points);
        if (displayContext != distanceContext) {
            displayContext.preload(points);
        }
        log.info("Single route optimize started: routeId={}, points={}, mode={}, displayMode={}, ratedKg={}, targetRate={}",
                routeId, points.size(), useRoadPath ? "ROAD" : "DIRECT", displayRoadPath ? "ROAD" : "DIRECT", ratedCapacityKg(request), targetLoadRate(request));
        RouteOptimizationResult optimization = singleRouteOptimizer.optimize(points, distanceContext);
        List<Map<String, Object>> originalSegments = segmentViews(optimization.getOriginalPoints(), speedProfile(request), displayContext);
        List<Map<String, Object>> segments = segmentViews(optimization.getOptimizedPoints(), speedProfile(request), displayContext);
        Double roadOriginalDistance = displayRoadPath ? round(sumSegmentDistance(originalSegments)) : null;
        Double roadOptimizedDistance = displayRoadPath ? round(sumSegmentDistance(segments)) : null;

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("routeId", routeId);
        result.put("status", points.size() < 3 ? "UNCHANGED" : "DONE");
        result.put("message", buildMessage(points, useRoadPath));
        result.put("pointCount", points.size());
        result.put("originalSequence", sequence(optimization.getOriginalPoints()));
        result.put("optimizedSequence", sequence(optimization.getOptimizedPoints()));
        result.put("distanceMode", useRoadPath ? "ROAD" : "DIRECT");
        result.put("displayMode", displayRoadPath ? "ROAD" : "DIRECT");
        result.put("optimizerCostSource", useRoadPath ? "OD_OR_BAIDU" : "DIRECT");
        result.put("displayPathSource", displayRoadPath ? "OD_OR_BAIDU" : "DIRECT");
        result.put("originalDistance", round(optimization.getOriginalDistance()));
        result.put("optimizedDistance", round(optimization.getOptimizedDistance()));
        result.put("distanceDelta", round(optimization.getOriginalDistance() - optimization.getOptimizedDistance()));
        result.put("savedDistance", round(optimization.getSavedDistance()));
        result.put("savedRate", round(optimization.getSavedRate()));
        result.put("directOriginalDistance", round(singleRouteOptimizer.totalDistance(optimization.getOriginalPoints())));
        result.put("directOptimizedDistance", round(singleRouteOptimizer.totalDistance(optimization.getOptimizedPoints())));
        result.put("roadOriginalDistance", roadOriginalDistance);
        result.put("roadOptimizedDistance", roadOptimizedDistance);
        result.put("originalPathDistance", round(sumSegmentDistance(originalSegments)));
        result.put("originalPathDurationMinutes", round(sumSegmentDuration(originalSegments)));
        result.put("estimatedWeightKg", round(sumEstimatedWeight(points)));
        result.put("estimatedVolumeLiter", round(sumEstimatedVolume(points)));
        result.put("ratedCapacityKg", round(ratedCapacityKg(request)));
        result.put("targetLoadRate", round(targetLoadRate(request)));
        result.put("targetLoadWeightKg", round(ratedCapacityKg(request) * targetLoadRate(request)));
        result.put("loadRate", loadRate(points, request));
        result.put("pathDistance", round(sumSegmentDistance(segments)));
        result.put("pathDurationMinutes", round(sumSegmentDuration(segments)));
        result.put("points", pointViews(optimization.getOptimizedPoints()));
        result.put("originalSegments", originalSegments);
        result.put("segments", segments);
        result.put("polyline", polyline(optimization.getOptimizedPoints()));
        log.info("Single route optimize finished: routeId={}, status={}, elapsed={}ms, original={}m, optimized={}m, delta={}m, odStats={}, displayStats={}",
                routeId, result.get("status"), System.currentTimeMillis() - startedAt,
                result.get("originalDistance"), result.get("optimizedDistance"), result.get("distanceDelta"), distanceContext.summary(), displayContext.summary());
        return result;
    }

    public Map<String, Object> startMultiPreviewTask(Map<String, Object> request) {
        final MultiRouteTask task = new MultiRouteTask(UUID.randomUUID().toString());
        final Map<String, Object> taskRequest = new HashMap<String, Object>(request);
        multiRouteTasks.put(task.taskId, task);
        task.future = multiRouteExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    task.update("RUNNING", "PREPARE", "正在整理本批点位");
                    Map<String, Object> result = optimizeMultiPreview(taskRequest, task);
                    if (task.cancelled) {
                        task.cancelled();
                        return;
                    }
                    task.complete(result);
                } catch (CancellationException e) {
                    task.cancelled();
                } catch (Exception e) {
                    task.fail(e);
                    log.warn("Multi route task failed: taskId={}, {}", task.taskId, e.getMessage(), e);
                }
            }
        });
        return task.view(false);
    }

    public Map<String, Object> multiPreviewTask(String taskId) {
        MultiRouteTask task = multiRouteTasks.get(taskId);
        if (task == null) {
            Map<String, Object> missing = new HashMap<String, Object>();
            missing.put("taskId", taskId);
            missing.put("status", "NOT_FOUND");
            missing.put("message", "任务不存在或已被清理");
            return missing;
        }
        return task.view(true);
    }

    public Map<String, Object> cancelMultiPreviewTask(String taskId) {
        MultiRouteTask task = multiRouteTasks.get(taskId);
        if (task == null) {
            Map<String, Object> missing = new HashMap<String, Object>();
            missing.put("taskId", taskId);
            missing.put("status", "NOT_FOUND");
            missing.put("message", "任务不存在或已被清理");
            return missing;
        }
        task.cancel();
        return task.view(false);
    }

    public Map<String, Object> optimizeMultiPreview(Map<String, Object> request) {
        return optimizeMultiPreview(request, null);
    }

    private Map<String, Object> optimizeMultiPreview(Map<String, Object> request, MultiRouteTask task) {
        long startedAt = System.currentTimeMillis();
        Object routeIdValue = request.get("routeId");
        Object unitIdValue = request.get("unitId");
        Object pointsValue = request.get("points");
        boolean inlinePointMode = pointsValue instanceof List;
        if (routeIdValue == null && unitIdValue == null && !inlinePointMode) {
            throw new IllegalArgumentException("routeId, unitId or points is required");
        }

        Long routeId = routeIdValue == null ? null : Long.valueOf(String.valueOf(routeIdValue));
        String unitId = unitIdValue == null ? null : String.valueOf(unitIdValue);
        boolean companyMode = routeId == null && !inlinePointMode;
        List<RoutePoint> sourcePoints;
        if (inlinePointMode) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) pointsValue;
            sourcePoints = toRoutePoints(rows);
        } else {
            sourcePoints = toRoutePoints(companyMode
                    ? routeQueryService.companyFacilityPoints(unitId)
                    : routeQueryService.routePlanPoints(routeId));
        }
        sourcePoints = filterByRequestedFacilities(sourcePoints, request);
        boolean explicitAnchors = hasAnchorCoordinate(request, "start") || hasAnchorCoordinate(request, "end");
        if (explicitAnchors) {
            sourcePoints = withoutInputAnchors(sourcePoints);
        }
        List<Map<String, Object>> routes = new ArrayList<Map<String, Object>>();
        List<RoutePoint> unassigned = new ArrayList<RoutePoint>();
        double ratedCapacityKg = defaultedRatedCapacityKg(request);
        double targetLoadRate = targetLoadRate(request);
        double targetLoadWeightKg = ratedCapacityKg * targetLoadRate;
        double maxCapacityKg = maxCapacityKg(request, ratedCapacityKg);

        if (sourcePoints.isEmpty()) {
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("routeId", routeId);
            result.put("unitId", unitId);
            result.put("status", "UNCHANGED");
            result.put("message", "没有可用于多路线生成的点位。");
            result.put("routes", routes);
            result.put("unassignedPoints", pointViews(sourcePoints));
            result.put("unassignedPointCount", sourcePoints.size());
            return result;
        }

        List<RoutePoint> remaining = new ArrayList<RoutePoint>();
        RoutePoint start;
        RoutePoint end;
        if (companyMode) {
            start = anchorPoint(request, sourcePoints, "start", -1L, "临时起点");
            end = anchorPoint(request, sourcePoints, "end", -2L, "临时终点");
            remaining.addAll(sourcePoints);
        } else if (sourcePoints.size() < 3) {
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("routeId", routeId);
            result.put("unitId", unitId);
            result.put("status", "UNCHANGED");
            result.put("message", "该路线点位不足 3 个，无法拆分多路线。");
            result.put("routes", routes);
            result.put("unassignedPoints", pointViews(sourcePoints));
            result.put("unassignedPointCount", sourcePoints.size());
            return result;
        } else {
            boolean requestAnchors = hasAnchorCoordinate(request, "start") || hasAnchorCoordinate(request, "end");
            if (requestAnchors) {
                start = anchorPoint(request, sourcePoints, "start", -1L, "拆分起点");
                end = anchorPoint(request, sourcePoints, "end", -2L, "拆分终点");
                remaining.addAll(sourcePoints);
            } else {
                start = sourcePoints.get(0);
                end = sourcePoints.get(sourcePoints.size() - 1);
                remaining.addAll(sourcePoints.subList(1, sourcePoints.size() - 1));
            }
        }

        String planningStrategy = multiRouteStrategy(request);
        boolean useRoadPath = multiRouteUsesRoadPath(planningStrategy, request);
        boolean refineWithRoad = multiRouteRefinesWithRoad(planningStrategy);
        RouteOptimizeTraceService.TraceHandle trace = traceService.start(request, pointViews(sourcePoints), unitId, routeId, planningStrategy, dispatchMode(request));
        boolean displayRoadPath = displayRoadPath(request, false);
        DistanceContext distanceContext = new DistanceContext(useRoadPath, useRoadPath);
        DistanceContext refineContext = refineWithRoad ? new DistanceContext(true, true) : distanceContext;
        DistanceContext displayContext = displayRoadPath
                ? (refineWithRoad ? refineContext : (useRoadPath ? distanceContext : new DistanceContext(true, false)))
                : new DistanceContext(false, false);
        List<DispatchTrip> dispatchPlan = buildDispatchPlan(request, start, end, ratedCapacityKg, maxCapacityKg, targetLoadRate);
        if (task != null) {
            task.prepare(sourcePoints.size(), dispatchPlan.size(), planningStrategy);
        }
        List<RoutePoint> endCandidates = endCandidates(request, end);
        List<RoutePoint> matrixPoints = distancePoints(sourcePoints, dispatchPlan, endCandidates);
        if (task != null) {
            task.update("RUNNING", useRoadPath ? "OD_PRELOAD" : "DIRECT_DISTANCE",
                    useRoadPath ? "正在批量读取 OD 缓存" : "正在构建直线距离矩阵");
        }
        distanceContext.preload(matrixPoints);
        if (task != null) {
            task.update("RUNNING", useRoadPath ? "PAIR_RESOLVE" : "DIRECT_DISTANCE",
                    useRoadPath ? "正在补齐全部有向道路 OD 点对，完成后开始路线计算" : "正在构建直线距离矩阵");
        }
        if (useRoadPath) {
            distanceContext.ensureRoadPaths(matrixPoints);
        }
        MatrixDistanceContext routeDistanceContext = new MatrixDistanceContext(matrixPoints, distanceContext);
        if (displayContext != distanceContext && displayContext != refineContext) {
            displayContext.preload(matrixPoints);
        }
        log.info("Multi route optimize started: routeId={}, unitId={}, companyMode={}, candidatePoints={}, dispatchTrips={}, strategy={}, mode={}, displayMode={}, targetLoadKg={}, maxKg={}",
                routeId, unitId, companyMode, sourcePoints.size(), dispatchPlan.size(), planningStrategy, useRoadPath ? "ROAD" : "DIRECT", displayRoadPath ? "ROAD" : "DIRECT", targetLoadWeightKg, maxCapacityKg);
        int routeNo = 1;
        boolean workHoursDispatch = "WORK_HOURS".equals(dispatchMode(request));
        double workLimitMinutes = positiveOrDefault(request, "workHours", 8D) * 60D;
        Map<String, RoutePoint> lastEndByVehicle = new HashMap<String, RoutePoint>();
        Map<String, Double> workedMinutesByVehicle = new HashMap<String, Double>();
        for (DispatchTrip trip : dispatchPlan) {
            assertNotCancelled(task);
            if (remaining.isEmpty()) {
                break;
            }
            if (task != null) {
                task.route(routeNo, dispatchPlan.size(), trip.vehicleName, trip.tripNo, "ROUTE_BUILD",
                        "正在生成第 " + routeNo + " 趟路线", 0, remaining.size());
            }
            RoutePoint effectiveStart = trip.tripNo == 1 ? trip.start : lastEndByVehicle.get(trip.vehicleId);
            if (effectiveStart == null) {
                effectiveStart = trip.start;
            }
            double workedMinutes = valueOrZero(workedMinutesByVehicle.get(trip.vehicleId));
            double timeBudgetMinutes = workHoursDispatch ? workLimitMinutes - workedMinutes : -1D;
            if (workHoursDispatch && timeBudgetMinutes <= 0D) {
                continue;
            }
            long routeStartedAt = System.currentTimeMillis();
            routeDistanceContext.resetRouteStats();
            RouteChoice routeChoice = chooseBestEndRoute(trip, effectiveStart, endCandidates, remaining,
                    request, timeBudgetMinutes, routeDistanceContext, trace, routeNo);
            List<RoutePoint> capacityRoute = routeChoice.route;
            List<RoutePoint> route = capacityRoute;
            DispatchTrip effectiveTrip = trip.withStartAndEnd(effectiveStart, routeChoice.end);
            if (refineWithRoad) {
                if (task != null) {
                    task.route(routeNo, dispatchPlan.size(), trip.vehicleName, trip.tripNo, "ROUTE_REFINE",
                            "正在道路精排第 " + routeNo + " 趟路线", collectedPoints(route).size(), remaining.size());
                }
                route = refineRouteWithRoad(route, refineContext);
                if (workHoursDispatch && estimatedRouteDuration(route, request, refineContext) > timeBudgetMinutes) {
                    route = capacityRoute;
                }
            }
            List<RoutePoint> collected = collectedPoints(route);
            if (collected.isEmpty()) {
                if (workHoursDispatch) {
                    continue;
                }
                break;
            }
            remaining.removeAll(collected);
            if (task != null) {
                task.route(routeNo, dispatchPlan.size(), trip.vehicleName, trip.tripNo, "SEGMENT_BUILD",
                        "正在整理第 " + routeNo + " 趟地图数据", collected.size(), remaining.size());
            }
            DistanceContext planningContext = refineWithRoad ? refineContext : distanceContext;
            Map<String, Object> routeView = multiRouteView(routeNo, route, request, effectiveTrip,
                    planningContext, displayContext, useRoadPath || refineWithRoad);
            lastEndByVehicle.put(effectiveTrip.vehicleId, effectiveTrip.end);
            if (workHoursDispatch) {
                Double routeMinutes = toDouble(routeView.get("totalDurationMinutes"));
                double currentWorkedMinutes = workedMinutes + (routeMinutes == null ? 0D : routeMinutes);
                workedMinutesByVehicle.put(effectiveTrip.vehicleId, currentWorkedMinutes);
                routeView.put("vehicleWorkedMinutes", round(currentWorkedMinutes));
                routeView.put("vehicleRemainingMinutes", round(Math.max(0D, workLimitMinutes - currentWorkedMinutes)));
            }
            routes.add(routeView);
            if (task != null) {
                task.routeDone(routeNo, routes.size(), pointsInRoutes(routes), remaining.size());
            }
            log.info("Multi route built: routeNo={}, vehicle={}, tripNo={}, points={}, weightKg={}, distance={}, remaining={}, routeElapsed={}ms, matrixStats={}",
                    routeNo, trip.vehicleName, trip.tripNo, routeView.get("pointCount"), routeView.get("estimatedWeightKg"),
                    routeView.get("distance"), remaining.size(), System.currentTimeMillis() - routeStartedAt, routeDistanceContext.routeSummary());
            routeNo++;
        }

        unassigned.addAll(remaining);

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("routeId", routeId);
        result.put("unitId", unitId);
        result.put("status", unassigned.isEmpty() ? "DONE" : "PARTIAL");
        result.put("message", buildMultiMessage(companyMode, planningStrategy));
        result.put("sourcePointCount", sourcePoints.size());
        result.put("candidatePointCount", remaining.size() + pointsInRoutes(routes));
        result.put("routeCount", routes.size());
        result.put("assignedPointCount", pointsInRoutes(routes));
        result.put("unassignedPointCount", unassigned.size());
        result.put("estimatedWeightKg", round(sumEstimatedWeight(sourcePoints)));
        result.put("assignedWeightKg", round(sumAssignedWeight(routes)));
        result.put("unassignedWeightKg", round(sumEstimatedWeight(unassigned)));
        result.put("ratedCapacityKg", round(ratedCapacityKg));
        result.put("targetLoadRate", round(targetLoadRate));
        result.put("targetLoadWeightKg", round(targetLoadWeightKg));
        result.put("maxCapacityKg", round(maxCapacityKg));
        result.put("planningStrategy", planningStrategy);
        result.put("distanceMode", useRoadPath ? "ROAD" : "DIRECT");
        result.put("refineMode", refineWithRoad ? "ROAD" : "NONE");
        result.put("displayMode", displayRoadPath ? "ROAD" : "DIRECT");
        result.put("optimizerCostSource", useRoadPath ? "OD_OR_BAIDU" : (refineWithRoad ? "DIRECT_THEN_OD_REFINE" : "DIRECT"));
        result.put("displayPathSource", displayRoadPath ? "OD_OR_BAIDU" : "DIRECT");
        result.put("dispatchMode", dispatchMode(request));
        result.put("dispatchTripCount", workHoursDispatch ? routes.size() : dispatchPlan.size());
        result.put("totalPlannedCapacityKg", round(workHoursDispatch
                ? totalRouteCapacity(routes) : totalPlannedCapacity(dispatchPlan)));
        result.put("routes", routes);
        result.put("unassignedPoints", pointViews(unassigned));
        traceService.finish(trace, String.valueOf(result.get("status")), System.currentTimeMillis() - startedAt);
        log.info("Multi route optimize finished: routeId={}, unitId={}, status={}, elapsed={}ms, routes={}, assigned={}, unassigned={}, strategy={}, odStats={}, refineStats={}, displayStats={}",
                routeId, unitId, result.get("status"), System.currentTimeMillis() - startedAt,
                routes.size(), result.get("assignedPointCount"), unassigned.size(), planningStrategy, distanceContext.summary(), refineContext.summary(), displayContext.summary());
        return result;
    }

    private void assertNotCancelled(MultiRouteTask task) {
        if (task != null && (task.cancelled || Thread.currentThread().isInterrupted())) {
            throw new CancellationException("多路线任务已取消");
        }
    }

    public Map<String, Object> routeSegmentsPreview(Map<String, Object> request) {
        Object pointsValue = request.get("points");
        if (!(pointsValue instanceof List)) {
            throw new IllegalArgumentException("points is required");
        }
        boolean displayRoadPath = displayRoadPath(request, false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) pointsValue;
        List<RoutePoint> points = toRoutePoints(rows);
        DistanceContext context = new DistanceContext(displayRoadPath, false);
        context.preload(points);
        List<Map<String, Object>> segments = segmentViews(points, speedProfile(request), context);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("displayMode", displayRoadPath ? "ROAD" : "DIRECT");
        result.put("segments", segments);
        result.put("distance", round(sumSegmentDistance(segments)));
        result.put("durationMinutes", round(sumSegmentDuration(segments)));
        result.put("pathSource", displayRoadPath ? "OD_OR_BAIDU" : "DIRECT");
        result.put("stats", context.summary());
        return result;
    }

    private List<RoutePoint> toRoutePoints(List<Map<String, Object>> rows) {
        List<RoutePoint> points = new ArrayList<RoutePoint>();
        for (Map<String, Object> row : rows) {
            points.add(new RoutePoint(
                    toLong(row.get("facilityId")),
                    row.get("facilityName") == null ? null : String.valueOf(row.get("facilityName")),
                    toDouble(row.get("longitude")),
                    toDouble(row.get("latitude")),
                    toInteger(row.get("orderNum")),
                    toDouble(row.get("estimatedVolumeLiter")),
                    toDouble(row.get("estimatedWeightKg")),
                    row.get("containerInfo") == null ? null : String.valueOf(row.get("containerInfo")),
                    toDouble(row.get("containerCount")),
                    toDouble(row.get("litersPerTon")),
                    row.get("weightSource") == null ? null : String.valueOf(row.get("weightSource"))));
        }
        return points;
    }

    private List<RoutePoint> withoutInputAnchors(List<RoutePoint> points) {
        List<RoutePoint> result = new ArrayList<RoutePoint>();
        for (RoutePoint point : points) {
            if (point.getFacilityId() != null && (point.getFacilityId() == -1L || point.getFacilityId() == -2L)) {
                continue;
            }
            if ("ANCHOR".equalsIgnoreCase(point.getWeightSource())) {
                continue;
            }
            result.add(point);
        }
        return result;
    }

    private List<RoutePoint> filterByRequestedFacilities(List<RoutePoint> points, Map<String, Object> request) {
        if (!request.containsKey("facilityIds")) {
            return points;
        }
        Set<Long> facilityIds = toLongSet(request.get("facilityIds"));
        if (facilityIds.isEmpty()) {
            return new ArrayList<RoutePoint>();
        }
        List<RoutePoint> filtered = new ArrayList<RoutePoint>();
        for (RoutePoint point : points) {
            if (point.getFacilityId() != null && facilityIds.contains(point.getFacilityId())) {
                filtered.add(point);
            }
        }
        return filtered;
    }

    private String buildMessage(List<RoutePoint> points, boolean useRoadPath) {
        if (points.isEmpty()) {
            return "该路线没有规划点位，无法生成优化预览。";
        }
        if (points.size() < 3) {
            return "该路线点位不足 3 个，保持原规划顺序。";
        }
        if (useRoadPath) {
            return "已按 OD 缓存/百度补算道路距离执行单路线全路径插入算法；缓存缺失会实时补算，失败后按直线回退。";
        }
        return "已使用单路线全路径插入算法生成优化预览。当前使用点位直线距离，未开启真实道路算路。";
    }

    private List<Long> sequence(List<RoutePoint> points) {
        List<Long> sequence = new ArrayList<Long>();
        for (RoutePoint point : points) {
            sequence.add(point.getFacilityId());
        }
        return sequence;
    }

    private List<Map<String, Object>> pointViews(List<RoutePoint> points) {
        return pointViews(points, null);
    }

    private List<Map<String, Object>> pointViews(List<RoutePoint> points, Map<String, Object> request) {
        List<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < points.size(); i++) {
            RoutePoint point = points.get(i);
            Map<String, Object> view = new HashMap<String, Object>();
            view.put("order", i + 1);
            view.put("facilityId", point.getFacilityId());
            view.put("facilityName", point.getFacilityName());
            view.put("longitude", point.getLongitude());
            view.put("latitude", point.getLatitude());
            view.put("originalOrder", point.getOriginalOrder());
            view.put("estimatedVolumeLiter", round(valueOrZero(point.getEstimatedVolumeLiter())));
            view.put("estimatedWeightKg", round(valueOrZero(point.getEstimatedWeightKg())));
            view.put("containerInfo", point.getContainerInfo());
            view.put("containerCount", round(valueOrZero(point.getContainerCount())));
            view.put("operationDurationMinutes", round(operationDuration(point, request, i == 0 || i == points.size() - 1)));
            view.put("litersPerTon", point.getLitersPerTon());
            view.put("weightSource", point.getWeightSource());
            view.put("role", i == 0 ? "START" : (i == points.size() - 1 ? "END" : "MIDDLE"));
            views.add(view);
        }
        return views;
    }

    private List<RoutePoint> buildCapacityRoute(RoutePoint start, RoutePoint end, List<RoutePoint> remaining,
                                                double targetLoadWeightKg, double maxCapacityKg,
                                                Map<String, Object> request, double timeBudgetMinutes,
                                                MatrixDistanceContext distanceCalculator,
                                                RouteOptimizeTraceService.TraceHandle trace, int routeNo, DispatchTrip trip) {
        List<RoutePoint> route = new ArrayList<RoutePoint>();
        route.add(start);
        route.add(end);
        List<RoutePoint> available = new ArrayList<RoutePoint>(remaining);
        double load = 0D;

        int iteration = 0;
        while (!available.isEmpty()) {
            InsertChoice best = null;
            List<Map<String, Object>> candidateEvaluations = new ArrayList<Map<String, Object>>();
            for (RoutePoint candidate : available) {
                double nextLoad = load + valueOrZero(candidate.getEstimatedWeightKg());
                if (nextLoad > maxCapacityKg && load > 0D) {
                    continue;
                }
                for (int segment = 0; segment < route.size() - 1; segment++) {
                    RoutePoint previous = route.get(segment);
                    RoutePoint next = route.get(segment + 1);
                    double increase = distanceCalculator.distance(previous, candidate)
                            + distanceCalculator.distance(candidate, next)
                            - distanceCalculator.distance(previous, next);
                    if (timeBudgetMinutes > 0D) {
                        route.add(segment + 1, candidate);
                        double projectedMinutes = estimatedRouteDuration(route, request, distanceCalculator);
                        route.remove(segment + 1);
                        if (projectedMinutes > timeBudgetMinutes) {
                            continue;
                        }
                    }
                    candidateEvaluations.add(RouteOptimizeTraceService.candidate(candidate.getFacilityId(), candidate.getFacilityName(), segment + 1, increase));
                    if (best == null || increase < best.increase) {
                        best = new InsertChoice(candidate, segment + 1, increase);
                    }
                }
            }
            if (best == null) {
                break;
            }
            RoutePoint previous = route.get(best.insertIndex - 1);
            RoutePoint next = route.get(best.insertIndex);
            route.add(best.insertIndex, best.point);
            available.remove(best.point);
            load += valueOrZero(best.point.getEstimatedWeightKg());
            iteration++;
            Collections.sort(candidateEvaluations, new Comparator<Map<String, Object>>() {
                @Override public int compare(Map<String, Object> left, Map<String, Object> right) { return Double.compare(((Number) left.get("increaseMeters")).doubleValue(), ((Number) right.get("increaseMeters")).doubleValue()); }
            });
            List<Map<String, Object>> topCandidates = candidateEvaluations.subList(0, Math.min(5, candidateEvaluations.size()));
            Map<String, Object> selected = new HashMap<String, Object>();
            selected.put("facilityId", best.point.getFacilityId()); selected.put("facilityName", best.point.getFacilityName()); selected.put("insertIndex", best.insertIndex);
            selected.put("previousFacilityId", previous.getFacilityId()); selected.put("previousFacilityName", previous.getFacilityName());
            selected.put("nextFacilityId", next.getFacilityId()); selected.put("nextFacilityName", next.getFacilityName()); selected.put("increaseDistanceMeters", best.increase);
            double traceTotalMinutes = estimatedRouteDuration(route, request, distanceCalculator);
            traceService.step(trace, routeNo, trip.vehicleId, trip.tripNo, iteration, pointViews(route, request), selected, topCandidates, available.size(), available.size() + 1, candidateEvaluations.size(), routeDistance(route, distanceCalculator), traceTotalMinutes, sumOperationDuration(collectedPoints(route), request), traceTotalMinutes, load, distanceCalculator.delegate.useRoadPath ? "OD_PRELOAD" : "DIRECT");
            if (targetLoadWeightKg > 0D && load >= targetLoadWeightKg) {
                break;
            }
        }

        return route;
    }

    private double estimatedRouteDuration(List<RoutePoint> route, Map<String, Object> request,
                                           SingleRouteOptimizer.DistanceCalculator distanceCalculator) {
        if (route.size() < 2) {
            return 0D;
        }
        SpeedProfile profile = speedProfile(request);
        double total = 0D;
        for (int i = 0; i < route.size() - 1; i++) {
            double distance = distanceCalculator.distance(route.get(i), route.get(i + 1));
            SpeedDecision speed = profile.forSegment(route, i, distance);
            total += minutes(distance, speed.kmh);
        }
        total += sumOperationDuration(collectedPoints(route), request);
        return total;
    }

    private List<RoutePoint> refineRouteWithRoad(List<RoutePoint> route, DistanceContext refineContext) {
        if (route.size() < 4) {
            return route;
        }
        refineContext.preload(route);
        refineContext.ensureRoadPaths(route);
        RouteOptimizationResult refined = singleRouteOptimizer.optimize(route, refineContext);
        return refined.getOptimizedPoints();
    }

    private RouteChoice chooseBestEndRoute(DispatchTrip trip, RoutePoint start, List<RoutePoint> endCandidates,
                                           List<RoutePoint> remaining, Map<String, Object> request,
                                           double timeBudgetMinutes, MatrixDistanceContext distanceContext,
                                           RouteOptimizeTraceService.TraceHandle trace, int routeNo) {
        RouteChoice best = null;
        List<RoutePoint> candidates = endCandidates.isEmpty() ? new ArrayList<RoutePoint>() : endCandidates;
        if (candidates.isEmpty()) {
            candidates.add(trip.end);
        }
        for (RoutePoint candidateEnd : candidates) {
            List<RoutePoint> route = buildCapacityRoute(start, candidateEnd, remaining,
                    trip.targetLoadWeightKg, trip.maxCapacityKg, request, timeBudgetMinutes, distanceContext, trace, routeNo, trip);
            double distance = routeDistance(route, distanceContext);
            if (best == null || distance < best.distance) {
                best = new RouteChoice(candidateEnd, route, distance);
            }
        }
        return best;
    }

    private double routeDistance(List<RoutePoint> route, MatrixDistanceContext distanceContext) {
        double total = 0D;
        for (int i = 0; i < route.size() - 1; i++) {
            total += distanceContext.distance(route.get(i), route.get(i + 1));
        }
        return total;
    }
    private List<RoutePoint> collectedPoints(List<RoutePoint> route) {
        if (route.size() <= 2) {
            return new ArrayList<RoutePoint>();
        }
        return new ArrayList<RoutePoint>(route.subList(1, route.size() - 1));
    }

    private List<DispatchTrip> buildDispatchPlan(Map<String, Object> request, RoutePoint defaultStart, RoutePoint defaultEnd,
                                                 double defaultRatedCapacityKg, double defaultMaxCapacityKg,
                                                 double targetLoadRate) {
        List<Map<String, Object>> vehicles = maps(request.get("vehicles"));
        if (!vehicles.isEmpty()) {
            String dispatchMode = dispatchMode(request);
            if ("ROUND_ROBIN".equals(dispatchMode)) {
                return buildRoundRobinDispatchPlan(vehicles, defaultStart, defaultEnd,
                        defaultRatedCapacityKg, defaultMaxCapacityKg, targetLoadRate, maxRoutes(request));
            }
            if ("USER_ORDER_THEN_ROUND_ROBIN".equals(dispatchMode)) {
                return buildUserOrderThenRoundRobinDispatchPlan(vehicles, defaultStart, defaultEnd,
                        defaultRatedCapacityKg, defaultMaxCapacityKg, targetLoadRate, maxRoutes(request));
            }
            if ("WORK_HOURS".equals(dispatchMode)) {
                return buildWorkHoursDispatchPlan(vehicles, defaultStart, defaultEnd,
                        defaultRatedCapacityKg, defaultMaxCapacityKg, targetLoadRate, maxRoutes(request));
            }
            return buildUserOrderDispatchPlan(vehicles, defaultStart, defaultEnd,
                    defaultRatedCapacityKg, defaultMaxCapacityKg, targetLoadRate);
        }

        List<DispatchTrip> plan = new ArrayList<DispatchTrip>();
        int maxRoutes = maxRoutes(request);
        for (int routeNo = 1; routeNo <= maxRoutes; routeNo++) {
            RoutePoint start = routeNo == 1 ? defaultStart : defaultEnd;
            plan.add(new DispatchTrip(1, "legacy", "默认车辆", "", routeNo,
                    defaultRatedCapacityKg, defaultMaxCapacityKg, defaultRatedCapacityKg * targetLoadRate,
                    start, defaultEnd));
        }
        return plan;
    }

    private List<DispatchTrip> buildUserOrderDispatchPlan(List<Map<String, Object>> vehicles, RoutePoint defaultStart,
                                                          RoutePoint defaultEnd, double defaultRatedCapacityKg,
                                                          double defaultMaxCapacityKg, double targetLoadRate) {
        List<DispatchTrip> plan = new ArrayList<DispatchTrip>();
        int vehicleIndex = 1;
        for (Map<String, Object> vehicle : vehicles) {
            VehiclePlan vehiclePlan = vehiclePlan(vehicle, vehicleIndex, defaultStart, defaultEnd,
                    defaultRatedCapacityKg, defaultMaxCapacityKg, targetLoadRate);
            for (int tripNo = 1; tripNo <= vehiclePlan.tripCount; tripNo++) {
                plan.add(dispatchTrip(vehiclePlan, tripNo));
            }
            vehicleIndex++;
        }
        return plan;
    }

    private List<DispatchTrip> buildWorkHoursDispatchPlan(List<Map<String, Object>> vehicles, RoutePoint defaultStart,
                                                            RoutePoint defaultEnd, double defaultRatedCapacityKg,
                                                            double defaultMaxCapacityKg, double targetLoadRate,
                                                            int maxRoutes) {
        List<DispatchTrip> plan = new ArrayList<DispatchTrip>();
        int vehicleIndex = 1;
        for (Map<String, Object> vehicle : vehicles) {
            VehiclePlan vehiclePlan = vehiclePlan(vehicle, vehicleIndex, defaultStart, defaultEnd,
                    defaultRatedCapacityKg, defaultMaxCapacityKg, targetLoadRate);
            for (int tripNo = 1; tripNo <= maxRoutes; tripNo++) {
                plan.add(dispatchTrip(vehiclePlan, tripNo));
            }
            vehicleIndex++;
        }
        return plan;
    }

    private List<DispatchTrip> buildRoundRobinDispatchPlan(List<Map<String, Object>> vehicles, RoutePoint defaultStart,
                                                           RoutePoint defaultEnd, double defaultRatedCapacityKg,
                                                           double defaultMaxCapacityKg, double targetLoadRate,
                                                           int maxRoutes) {
        List<VehiclePlan> vehiclePlans = sortedVehiclePlans(vehicles, defaultStart, defaultEnd,
                defaultRatedCapacityKg, defaultMaxCapacityKg, targetLoadRate);
        return appendRoundRobinTrips(new ArrayList<DispatchTrip>(), vehiclePlans, new HashMap<String, Integer>(), maxRoutes);
    }

    private List<DispatchTrip> buildUserOrderThenRoundRobinDispatchPlan(List<Map<String, Object>> vehicles, RoutePoint defaultStart,
                                                                       RoutePoint defaultEnd, double defaultRatedCapacityKg,
                                                                       double defaultMaxCapacityKg, double targetLoadRate,
                                                                       int maxRoutes) {
        List<DispatchTrip> plan = buildUserOrderDispatchPlan(vehicles, defaultStart, defaultEnd,
                defaultRatedCapacityKg, defaultMaxCapacityKg, targetLoadRate);
        if (plan.size() >= maxRoutes) {
            return plan.subList(0, maxRoutes);
        }
        Map<String, Integer> tripCounts = new HashMap<String, Integer>();
        for (DispatchTrip trip : plan) {
            Integer count = tripCounts.get(trip.vehicleId);
            tripCounts.put(trip.vehicleId, count == null ? 1 : count + 1);
        }
        List<VehiclePlan> vehiclePlans = sortedVehiclePlans(vehicles, defaultStart, defaultEnd,
                defaultRatedCapacityKg, defaultMaxCapacityKg, targetLoadRate);
        return appendRoundRobinTrips(plan, vehiclePlans, tripCounts, maxRoutes);
    }

    private List<VehiclePlan> sortedVehiclePlans(List<Map<String, Object>> vehicles, RoutePoint defaultStart,
                                                 RoutePoint defaultEnd, double defaultRatedCapacityKg,
                                                 double defaultMaxCapacityKg, double targetLoadRate) {
        List<VehiclePlan> vehiclePlans = new ArrayList<VehiclePlan>();
        int vehicleIndex = 1;
        for (Map<String, Object> vehicle : vehicles) {
            vehiclePlans.add(vehiclePlan(vehicle, vehicleIndex, defaultStart, defaultEnd,
                    defaultRatedCapacityKg, defaultMaxCapacityKg, targetLoadRate));
            vehicleIndex++;
        }
        Collections.sort(vehiclePlans, new Comparator<VehiclePlan>() {
            public int compare(VehiclePlan a, VehiclePlan b) {
                int rated = Double.compare(b.ratedCapacityKg, a.ratedCapacityKg);
                return rated != 0 ? rated : Integer.compare(a.vehicleIndex, b.vehicleIndex);
            }
        });
        return vehiclePlans;
    }

    private List<DispatchTrip> appendRoundRobinTrips(List<DispatchTrip> plan, List<VehiclePlan> vehiclePlans,
                                                     Map<String, Integer> tripCounts, int maxRoutes) {
        if (vehiclePlans.isEmpty()) {
            return plan;
        }
        while (plan.size() < maxRoutes) {
            for (VehiclePlan vehiclePlan : vehiclePlans) {
                if (plan.size() >= maxRoutes) {
                    break;
                }
                Integer count = tripCounts.get(vehiclePlan.vehicleId);
                int tripNo = count == null ? 1 : count + 1;
                plan.add(dispatchTrip(vehiclePlan, tripNo));
                tripCounts.put(vehiclePlan.vehicleId, tripNo);
            }
        }
        return plan;
    }

    private VehiclePlan vehiclePlan(Map<String, Object> vehicle, int vehicleIndex, RoutePoint defaultStart,
                                    RoutePoint defaultEnd, double defaultRatedCapacityKg,
                                    double defaultMaxCapacityKg, double targetLoadRate) {
        double rated = positiveOrDefault(toDouble(vehicle.get("ratedCapacityKg")), defaultRatedCapacityKg);
        double max = positiveOrDefault(toDouble(vehicle.get("maxCapacityKg")), Math.max(rated, defaultMaxCapacityKg));
        int tripCount = positiveOrDefault(toInteger(vehicle.get("tripCount")), 1);
        RoutePoint start = anchorPointFromVehicle(vehicle, "start", defaultStart);
        RoutePoint end = anchorPointFromVehicle(vehicle, "end", defaultEnd);
        return new VehiclePlan(
                vehicleIndex,
                textOrDefault(vehicle.get("vehicleId"), "vehicle-" + vehicleIndex),
                textOrDefault(vehicle.get("vehicleName"), "车辆" + vehicleIndex),
                textOrDefault(vehicle.get("vehicleType"), ""),
                tripCount,
                rated,
                max,
                rated * targetLoadRate,
                start,
                end);
    }

    private DispatchTrip dispatchTrip(VehiclePlan vehiclePlan, int tripNo) {
        RoutePoint tripStart = tripNo == 1 ? vehiclePlan.start : vehiclePlan.end;
        return new DispatchTrip(
                vehiclePlan.vehicleIndex,
                vehiclePlan.vehicleId,
                vehiclePlan.vehicleName,
                vehiclePlan.vehicleType,
                tripNo,
                vehiclePlan.ratedCapacityKg,
                vehiclePlan.maxCapacityKg,
                vehiclePlan.targetLoadWeightKg,
                tripStart,
                vehiclePlan.end);
    }

    private String dispatchMode(Map<String, Object> request) {
        String value = textOrDefault(request.get("dispatchMode"), "USER_ORDER").trim().toUpperCase();
        if ("ROUND_ROBIN".equals(value) || "USER_ORDER_THEN_ROUND_ROBIN".equals(value)
                || "WORK_HOURS".equals(value)) {
            return value;
        }
        return "USER_ORDER";
    }

    private List<RoutePoint> endCandidates(Map<String, Object> request, RoutePoint fallbackEnd) {
        List<RoutePoint> candidates = new ArrayList<RoutePoint>();
        for (Map<String, Object> row : maps(request.get("endCandidates"))) {
            RoutePoint candidate = anchorPointFromMap(row, fallbackEnd);
            if (candidate != null && candidate.hasCoordinate()) {
                addUniquePoint(candidates, candidate);
            }
        }
        if (candidates.isEmpty()) {
            candidates.add(fallbackEnd);
        }
        return candidates;
    }

    private RoutePoint anchorPointFromMap(Map<String, Object> row, RoutePoint fallback) {
        Double longitude = toDouble(row.get("longitude"));
        Double latitude = toDouble(row.get("latitude"));
        if (longitude == null || latitude == null) {
            return null;
        }
        Long facilityId = toLong(row.get("facilityId"));
        String name = textOrDefault(row.get("facilityName"), fallback.getFacilityName());
        return new RoutePoint(facilityId == null ? fallback.getFacilityId() : facilityId,
                name, longitude, latitude, null, 0D, 0D, null, 0D, null, "ANCHOR");
    }
    private RoutePoint anchorPointFromVehicle(Map<String, Object> vehicle, String prefix, RoutePoint fallback) {
        Double longitude = toDouble(vehicle.get(prefix + "Longitude"));
        Double latitude = toDouble(vehicle.get(prefix + "Latitude"));
        if (longitude == null || latitude == null) {
            return fallback;
        }
        String name = textOrDefault(vehicle.get(prefix + "FacilityName"), fallback.getFacilityName());
        Long facilityId = toLong(vehicle.get(prefix + "FacilityId"));
        return new RoutePoint(facilityId == null ? fallback.getFacilityId() : facilityId,
                name, longitude, latitude, null, 0D, 0D, null, 0D, null, "ANCHOR");
    }

    private double totalRouteCapacity(List<Map<String, Object>> routes) {
        double total = 0D;
        for (Map<String, Object> route : routes) {
            total += valueOrZero(toDouble(route.get("ratedCapacityKg")));
        }
        return total;
    }

    private double totalPlannedCapacity(List<DispatchTrip> plan) {
        double total = 0D;
        for (DispatchTrip trip : plan) {
            total += trip.ratedCapacityKg;
        }
        return total;
    }

    private Map<String, Object> multiRouteView(int routeNo, List<RoutePoint> route, Map<String, Object> request,
                                               DispatchTrip trip, DistanceContext planningContext,
                                               DistanceContext displayContext, boolean retainRoadSegments) {
        Map<String, Object> view = new HashMap<String, Object>();
        List<RoutePoint> collected = collectedPoints(route);
        double weight = sumEstimatedWeight(collected);
        SpeedProfile profile = speedProfile(request);
        List<Map<String, Object>> planningSegments = segmentViews(route, profile, planningContext);
        List<Map<String, Object>> displaySegments = (!planningContext.useRoadPath && !displayContext.useRoadPath)
                || displayContext == planningContext
                ? planningSegments
                : segmentViews(route, profile, displayContext);
        view.put("routeNo", routeNo);
        view.put("vehicleIndex", trip.vehicleIndex);
        view.put("vehicleId", trip.vehicleId);
        view.put("vehicleName", trip.vehicleName);
        view.put("vehicleType", trip.vehicleType);
        view.put("tripNo", trip.tripNo);
        view.put("ratedCapacityKg", round(trip.ratedCapacityKg));
        view.put("maxCapacityKg", round(trip.maxCapacityKg));
        view.put("targetLoadWeightKg", round(trip.targetLoadWeightKg));
        view.put("pointCount", collected.size());
        view.put("sequence", sequence(route));
        view.put("estimatedWeightKg", round(weight));
        view.put("estimatedVolumeLiter", round(sumEstimatedVolume(collected)));
        view.put("loadRate", trip.ratedCapacityKg <= 0D ? 0D : round(weight / trip.ratedCapacityKg));
        double planningDistance = sumSegmentDistance(planningSegments);
        double planningTravelDurationMinutes = sumSegmentDuration(planningSegments);
        double displayDistance = sumSegmentDistance(displaySegments);
        double displayTravelDurationMinutes = sumSegmentDuration(displaySegments);
        double operationDurationMinutes = sumOperationDuration(collected, request);
        double totalDurationMinutes = planningTravelDurationMinutes + operationDurationMinutes;
        double displayTotalDurationMinutes = displayTravelDurationMinutes + operationDurationMinutes;
        double workLimitMinutes = positiveOrDefault(request, "workHours", 8D) * 60D;
        // Planning metrics are independent from map rendering mode.
        view.put("distance", round(planningDistance));
        view.put("travelDurationMinutes", round(planningTravelDurationMinutes));
        view.put("operationDurationMinutes", round(operationDurationMinutes));
        view.put("totalDurationMinutes", round(totalDurationMinutes));
        view.put("durationMinutes", round(totalDurationMinutes));
        view.put("planningDistance", round(planningDistance));
        view.put("planningTravelDurationMinutes", round(planningTravelDurationMinutes));
        view.put("planningTotalDurationMinutes", round(totalDurationMinutes));
        view.put("displayDistance", round(displayDistance));
        view.put("displayTravelDurationMinutes", round(displayTravelDurationMinutes));
        view.put("displayTotalDurationMinutes", round(displayTotalDurationMinutes));
        view.put("workLimitMinutes", round(workLimitMinutes));
        view.put("workLimitHours", round(workLimitMinutes / 60D));
        view.put("timeExceeded", totalDurationMinutes > workLimitMinutes);
        view.put("overdueMinutes", round(Math.max(0D, totalDurationMinutes - workLimitMinutes)));
        view.put("points", pointViews(route, request));
        view.put("segments", displaySegments);
        if (retainRoadSegments) {
            // Reuse road segments already resolved during planning.
            view.put("roadSegments", planningSegments);
            view.put("roadDistance", round(planningDistance));
            view.put("roadDurationMinutes", round(planningTravelDurationMinutes));
        }
        view.put("polyline", polyline(route));
        return view;
    }

    private List<Map<String, Object>> segmentViews(List<RoutePoint> points, SpeedProfile speedProfile, DistanceContext distanceContext) {
        List<Map<String, Object>> segments = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < points.size() - 1; i++) {
            RoutePoint from = points.get(i);
            RoutePoint to = points.get(i + 1);
            RouteMapPathService.ResolvedPath resolvedPath = distanceContext.resolve(from, to);
            double distance = distanceContext.distance(from, to);
            SpeedDecision speed = speedProfile.forSegment(points, i, distance);
            Map<String, Object> segment = new HashMap<String, Object>();
            segment.put("order", i + 1);
            segment.put("fromFacilityId", from.getFacilityId());
            segment.put("fromFacilityName", from.getFacilityName());
            segment.put("toFacilityId", to.getFacilityId());
            segment.put("toFacilityName", to.getFacilityName());
            segment.put("distance", round(distance));
            segment.put("durationMinutes", round(minutes(distance, speed.kmh)));
            segment.put("speedKmh", round(speed.kmh));
            segment.put("speedClass", speed.speedClass);
            segment.put("speedReason", speed.reason);
            segment.put("odDurationMinutes", resolvedPath.getDurationSeconds() == null
                    ? null
                    : round(resolvedPath.getDurationSeconds() / 60D));
            segment.put("durationSource", "SEGMENT_SPEED_ESTIMATE");
            segment.put("pathSource", resolvedPath.getSource());
            segment.put("path", resolvedPath.getPath());
            segments.add(segment);
        }
        return segments;
    }

    private RouteMapPathService.ResolvedPath directResolvedPath(RoutePoint from, RoutePoint to) {
        return new RouteMapPathService.ResolvedPath(directPath(from, to), null, null, "DIRECT");
    }

    private List<Map<String, Object>> directPath(RoutePoint from, RoutePoint to) {
        List<Map<String, Object>> path = new ArrayList<Map<String, Object>>();
        appendCoordinate(path, from);
        appendCoordinate(path, to);
        return path;
    }

    private void appendCoordinate(List<Map<String, Object>> path, RoutePoint point) {
        if (!point.hasCoordinate()) {
            return;
        }
        Map<String, Object> coordinate = new HashMap<String, Object>();
        coordinate.put("longitude", point.getLongitude());
        coordinate.put("latitude", point.getLatitude());
        coordinate.put("facilityId", point.getFacilityId());
        coordinate.put("facilityName", point.getFacilityName());
        path.add(coordinate);
    }

    private List<RoutePoint> distancePoints(List<RoutePoint> sourcePoints, List<DispatchTrip> dispatchPlan,
                                            List<RoutePoint> endCandidates) {
        List<RoutePoint> points = new ArrayList<RoutePoint>(sourcePoints);
        for (DispatchTrip trip : dispatchPlan) {
            addUniquePoint(points, trip.start);
            addUniquePoint(points, trip.end);
        }
        for (RoutePoint endCandidate : endCandidates) {
            addUniquePoint(points, endCandidate);
        }
        return points;
    }

    private void addUniquePoint(List<RoutePoint> points, RoutePoint candidate) {
        if (candidate == null) {
            return;
        }
        String candidateKey = stablePointKey(candidate);
        for (RoutePoint point : points) {
            if (stablePointKey(point).equals(candidateKey)) {
                return;
            }
        }
        points.add(candidate);
    }

    private boolean isCacheablePoint(RoutePoint point) {
        return point != null && point.getFacilityId() != null && point.getFacilityId() > 0;
    }

    private String stablePointKey(RoutePoint point) {
        if (point == null) {
            return "null";
        }
        if (point.getFacilityId() != null) {
            return "ID:" + point.getFacilityId();
        }
        return "XY:" + point.getLongitude() + "," + point.getLatitude();
    }


    private static class MultiRouteTask {
        private final String taskId;
        private volatile String status = "QUEUED";
        private volatile String phase = "QUEUED";
        private volatile String message = "等待执行";
        private volatile String planningStrategy;
        private volatile int candidatePoints;
        private volatile int totalRoutes;
        private volatile int currentRouteNo;
        private volatile int completedRoutes;
        private volatile int assignedPoints;
        private volatile int remainingPoints;
        private volatile int currentRoutePoints;
        private volatile String currentVehicleName;
        private volatile int currentTripNo;
        private volatile boolean cancelled;
        private volatile Map<String, Object> result;
        private volatile Future<?> future;
        private volatile long startedAt = System.currentTimeMillis();
        private volatile long updatedAt = startedAt;
        private volatile long finishedAt;

        private MultiRouteTask(String taskId) {
            this.taskId = taskId;
        }

        private synchronized void update(String status, String phase, String message) {
            this.status = status;
            this.phase = phase;
            this.message = message;
            this.updatedAt = System.currentTimeMillis();
        }

        private synchronized void prepare(int candidatePoints, int totalRoutes, String planningStrategy) {
            this.candidatePoints = candidatePoints;
            this.totalRoutes = totalRoutes;
            this.planningStrategy = planningStrategy;
            this.remainingPoints = candidatePoints;
            update("RUNNING", "PREPARE", "已整理 " + candidatePoints + " 个点位，计划最多 " + totalRoutes + " 趟");
        }

        private synchronized void route(int routeNo, int totalRoutes, String vehicleName, int tripNo, String phase, String message, int currentRoutePoints, int remainingPoints) {
            this.currentRouteNo = routeNo;
            this.totalRoutes = totalRoutes;
            this.currentVehicleName = vehicleName;
            this.currentTripNo = tripNo;
            this.currentRoutePoints = currentRoutePoints;
            this.remainingPoints = remainingPoints;
            update("RUNNING", phase, message);
        }

        private synchronized void routeDone(int routeNo, int completedRoutes, int assignedPoints, int remainingPoints) {
            this.currentRouteNo = routeNo;
            this.completedRoutes = completedRoutes;
            this.assignedPoints = assignedPoints;
            this.remainingPoints = remainingPoints;
            update("RUNNING", "ROUTE_DONE", "第 " + routeNo + " 趟路线已完成");
        }

        private synchronized void complete(Map<String, Object> result) {
            this.result = result;
            Object routeCount = result.get("routeCount");
            if (routeCount instanceof Number) {
                this.completedRoutes = ((Number) routeCount).intValue();
            }
            this.finishedAt = System.currentTimeMillis();
            update("DONE", "DONE", "路线规划完成");
        }

        private synchronized void fail(Exception e) {
            this.finishedAt = System.currentTimeMillis();
            update("FAILED", "FAILED", e.getMessage() == null ? "路线规划失败" : e.getMessage());
        }

        private synchronized void cancel() {
            this.cancelled = true;
            Future<?> taskFuture = this.future;
            if (taskFuture != null) {
                taskFuture.cancel(true);
            }
            cancelled();
        }

        private synchronized void cancelled() {
            this.finishedAt = System.currentTimeMillis();
            update("CANCELLED", "CANCELLED", "路线规划已取消");
        }

        private synchronized Map<String, Object> view(boolean includeResult) {
            Map<String, Object> view = new HashMap<String, Object>();
            view.put("taskId", taskId);
            view.put("status", status);
            view.put("phase", phase);
            view.put("message", message);
            view.put("planningStrategy", planningStrategy);
            view.put("candidatePoints", candidatePoints);
            view.put("totalRoutes", totalRoutes);
            view.put("currentRouteNo", currentRouteNo);
            view.put("completedRoutes", completedRoutes);
            view.put("assignedPoints", assignedPoints);
            view.put("remainingPoints", remainingPoints);
            view.put("currentRoutePoints", currentRoutePoints);
            view.put("currentVehicleName", currentVehicleName);
            view.put("currentTripNo", currentTripNo);
            view.put("startedAt", startedAt);
            view.put("updatedAt", updatedAt);
            view.put("finishedAt", finishedAt);
            if (includeResult && result != null) {
                view.put("result", result);
            }
            return view;
        }
    }

    private class MatrixDistanceContext implements SingleRouteOptimizer.DistanceCalculator {
        private final DistanceContext delegate;
        private final Map<String, Integer> indexByKey = new HashMap<String, Integer>();
        private final double[][] distanceMeters;
        private final int pointCount;
        private long routeDistanceCalls;
        private long routeMatrixHits;
        private long routeDelegateFallbacks;

        private MatrixDistanceContext(List<RoutePoint> points, DistanceContext delegate) {
            long startedAt = System.currentTimeMillis();
            this.delegate = delegate;
            List<RoutePoint> uniquePoints = new ArrayList<RoutePoint>();
            for (RoutePoint point : points) {
                String key = matrixPointKey(point);
                if (!indexByKey.containsKey(key)) {
                    indexByKey.put(key, uniquePoints.size());
                    uniquePoints.add(point);
                }
            }
            this.pointCount = uniquePoints.size();
            this.distanceMeters = new double[pointCount][pointCount];
            long matrixEntries = 0L;
            long roadEntries = 0L;
            long directEntries = 0L;
            for (int i = 0; i < pointCount; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("多路线任务已取消");
                }
                RoutePoint from = uniquePoints.get(i);
                for (int j = 0; j < pointCount; j++) {
                    if (i == j) {
                        distanceMeters[i][j] = 0D;
                    } else {
                        RouteMapPathService.ResolvedPath resolvedPath = delegate.resolveForMatrix(from, uniquePoints.get(j));
                        distanceMeters[i][j] = resolvedPath.getDistanceMeters() == null
                                ? singleRouteOptimizer.distance(from, uniquePoints.get(j))
                                : resolvedPath.getDistanceMeters();
                        matrixEntries++;
                        String source = resolvedPath.getSource();
                        if (source != null && (source.startsWith("OD") || source.startsWith("BAIDU"))) {
                            roadEntries++;
                        } else {
                            directEntries++;
                        }
                    }
                }
            }
            log.info("Route distance matrix built: mode={}, points={}, entries={}, roadEntries={}, directEntries={}, elapsed={}ms, delegateStats={}",
                    delegate.useRoadPath ? "ROAD" : "DIRECT", pointCount, matrixEntries,
                    roadEntries, directEntries, System.currentTimeMillis() - startedAt, delegate.summary());
        }

        @Override
        public double distance(RoutePoint from, RoutePoint to) {
            routeDistanceCalls++;
            Integer fromIndex = indexByKey.get(matrixPointKey(from));
            Integer toIndex = indexByKey.get(matrixPointKey(to));
            if (fromIndex != null && toIndex != null) {
                routeMatrixHits++;
                return distanceMeters[fromIndex][toIndex];
            }
            routeDelegateFallbacks++;
            return delegate.distance(from, to);
        }

        private void resetRouteStats() {
            routeDistanceCalls = 0L;
            routeMatrixHits = 0L;
            routeDelegateFallbacks = 0L;
        }

        private String routeSummary() {
            return "matrixPoints=" + pointCount
                    + ", routeDistanceCalls=" + routeDistanceCalls
                    + ", matrixHits=" + routeMatrixHits
                    + ", delegateFallbacks=" + routeDelegateFallbacks;
        }
    }
    private boolean isRoadResolvedPath(RouteMapPathService.ResolvedPath path) {
        if (path == null || path.getDistanceMeters() == null) {
            return false;
        }
        String source = path.getSource();
        return source != null && (source.startsWith("OD") || source.startsWith("BAIDU"));
    }

    private class DistanceContext implements SingleRouteOptimizer.DistanceCalculator {
        private final boolean useRoadPath;
        private final boolean strictRoad;
        private final Map<String, RouteMapPathService.ResolvedPath> resolvedCache = new HashMap<String, RouteMapPathService.ResolvedPath>();
        private final Map<String, RouteMapPathService.ResolvedPath> preloadedCache = new HashMap<String, RouteMapPathService.ResolvedPath>();
        private final Map<String, Integer> sourceCounts = new HashMap<String, Integer>();
        private boolean preloadAttempted;
        private long resolveCalls;
        private long cacheHits;
        private long cacheMisses;
        private long preloadedHits;
        private long preloadedMisses;

        private DistanceContext(boolean useRoadPath, boolean strictRoad) {
            this.useRoadPath = useRoadPath;
            this.strictRoad = strictRoad && useRoadPath;
        }

        private void preload(List<RoutePoint> points) {
            if (!useRoadPath) {
                return;
            }
            preloadAttempted = true;
            preloadedCache.clear();
            preloadedCache.putAll(routeMapPathService.preloadCachedPaths(points));
        }

        @Override
        public double distance(RoutePoint from, RoutePoint to) {
            RouteMapPathService.ResolvedPath resolvedPath = resolve(from, to);
            return resolvedPath.getDistanceMeters() == null
                    ? singleRouteOptimizer.distance(from, to)
                    : resolvedPath.getDistanceMeters();
        }

        private RouteMapPathService.ResolvedPath resolve(RoutePoint from, RoutePoint to) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("多路线任务已取消");
            }
            resolveCalls++;
            String key = pointKey(from) + "->" + pointKey(to) + "#" + (useRoadPath ? "ROAD" : "DIRECT");
            if (resolvedCache.containsKey(key)) {
                cacheHits++;
                return resolvedCache.get(key);
            }
            cacheMisses++;
            RouteMapPathService.ResolvedPath resolvedPath = preloadedResolvedPath(from, to);
            if (resolvedPath == null) {
                resolvedPath = useRoadPath
                        ? (preloadAttempted ? routeMapPathService.resolveWithoutCache(from, to) : routeMapPathService.resolve(from, to))
                        : directResolvedPath(from, to);
            }
            ensureUsablePath(from, to, resolvedPath);
            resolvedCache.put(key, resolvedPath);
            countSource(resolvedPath.getSource());
            if (useRoadPath && cacheMisses % 100 == 0) {
                log.info("Route distance resolving progress: {}", summary());
            }
            return resolvedPath;
        }

        private RouteMapPathService.ResolvedPath resolveForMatrix(RoutePoint from, RoutePoint to) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("多路线任务已取消");
            }
            resolveCalls++;
            String normalKey = pointKey(from) + "->" + pointKey(to) + "#" + (useRoadPath ? "ROAD" : "DIRECT");
            String matrixKey = normalKey + "#MATRIX";
            if (resolvedCache.containsKey(matrixKey)) {
                cacheHits++;
                return resolvedCache.get(matrixKey);
            }
            if (resolvedCache.containsKey(normalKey)) {
                cacheHits++;
                RouteMapPathService.ResolvedPath resolvedPath = resolvedCache.get(normalKey);
                resolvedCache.put(matrixKey, resolvedPath);
                return resolvedPath;
            }
            cacheMisses++;
            RouteMapPathService.ResolvedPath resolvedPath = useRoadPath ? preloadedResolvedPath(from, to) : null;
            if (resolvedPath == null) {
                resolvedPath = useRoadPath
                        ? routeMapPathService.resolveWithoutCache(from, to)
                        : directResolvedPath(from, to);
            }
            ensureUsablePath(from, to, resolvedPath);
            resolvedCache.put(normalKey, resolvedPath);
            resolvedCache.put(matrixKey, resolvedPath);
            countSource(resolvedPath.getSource());
            if (useRoadPath && cacheMisses % 1000 == 0) {
                log.info("Route distance matrix resolving progress: {}", summary());
            }
            return resolvedPath;
        }

        private void ensureRoadPaths(List<RoutePoint> points) {
            if (!useRoadPath || points == null) {
                return;
            }
            long startedAt = System.currentTimeMillis();
            int pairCount = 0;
            for (RoutePoint from : points) {
                for (RoutePoint to : points) {
                    if (from == to) {
                        continue;
                    }
                    pairCount++;
                    resolve(from, to);
                }
            }
            log.info("Road OD pairs ready: points={}, directedPairs={}, elapsed={}ms, stats={}",
                    points.size(), pairCount, System.currentTimeMillis() - startedAt, summary());
        }

        private void ensureUsablePath(RoutePoint from, RoutePoint to, RouteMapPathService.ResolvedPath path) {
            if (strictRoad && !isRoadResolvedPath(path)) {
                throw new IllegalStateException("道路规划缺少可用 OD：" + pointLabel(from) + " -> " + pointLabel(to)
                        + "。已禁止使用直线距离混算，请检查百度配置或补算结果。");
            }
        }

        private String pointLabel(RoutePoint point) {
            return point == null ? "null" : String.valueOf(point.getFacilityId()) + "/" + point.getFacilityName();
        }

        private RouteMapPathService.ResolvedPath preloadedResolvedPath(RoutePoint from, RoutePoint to) {
            if (!useRoadPath || !preloadAttempted || !isCacheablePoint(from) || !isCacheablePoint(to)) {
                return null;
            }
            String key = routeMapPathService.pathKey(from, to);
            RouteMapPathService.ResolvedPath resolvedPath = preloadedCache.get(key);
            if (resolvedPath == null) {
                preloadedMisses++;
                return null;
            }
            preloadedHits++;
            return resolvedPath;
        }

        private void countSource(String source) {
            String key = source == null ? "UNKNOWN" : source;
            Integer count = sourceCounts.get(key);
            sourceCounts.put(key, count == null ? 1 : count + 1);
        }

        private String summary() {
            return "mode=" + (useRoadPath ? "ROAD" : "DIRECT")
                    + ", calls=" + resolveCalls
                    + ", localCacheHits=" + cacheHits
                    + ", localCacheMisses=" + cacheMisses
                    + ", uniquePairs=" + resolvedCache.size()
                    + ", preloadedPairs=" + preloadedCache.size()
                    + ", preloadedHits=" + preloadedHits
                    + ", preloadedMisses=" + preloadedMisses
                    + ", sources=" + sourceCounts;
        }

        private String pointKey(RoutePoint point) {
            if (point.getFacilityId() != null) {
                return "ID:" + point.getFacilityId();
            }
            return "XY:" + point.getLongitude() + "," + point.getLatitude();
        }
    }

    private String matrixPointKey(RoutePoint point) {
        if (point.getFacilityId() != null) {
            return "ID:" + point.getFacilityId();
        }
        return "XY:" + point.getLongitude() + "," + point.getLatitude();
    }
    private boolean useRoadPath(Map<String, Object> request) {
        Object value = request.get("useRoadPath");
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String multiRouteStrategy(Map<String, Object> request) {
        Object value = request.get("multiRouteStrategy");
        if (value == null) {
            value = request.get("planningStrategy");
        }
        String strategy = value == null ? "" : String.valueOf(value).trim().toUpperCase();
        if (STRATEGY_DIRECT_GROUP_ROAD_REFINE.equals(strategy) || STRATEGY_ROAD_GLOBAL.equals(strategy)) {
            return strategy;
        }
        if (useRoadPath(request)) {
            return STRATEGY_ROAD_GLOBAL;
        }
        return STRATEGY_DIRECT_GROUP;
    }

    private boolean multiRouteUsesRoadPath(String planningStrategy, Map<String, Object> request) {
        return STRATEGY_ROAD_GLOBAL.equals(planningStrategy) || (STRATEGY_DIRECT_GROUP.equals(planningStrategy) && useRoadPath(request));
    }

    private boolean multiRouteRefinesWithRoad(String planningStrategy) {
        return STRATEGY_DIRECT_GROUP_ROAD_REFINE.equals(planningStrategy);
    }

    private boolean displayRoadPath(Map<String, Object> request, boolean fallback) {
        Object value = request.get("displayRoadPath");
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private double sumSegmentDistance(List<Map<String, Object>> segments) {
        double total = 0D;
        for (Map<String, Object> segment : segments) {
            total += valueOrZero(toDouble(segment.get("distance")));
        }
        return total;
    }

    private double sumSegmentDuration(List<Map<String, Object>> segments) {
        double total = 0D;
        for (Map<String, Object> segment : segments) {
            total += valueOrZero(toDouble(segment.get("durationMinutes")));
        }
        return total;
    }

    private List<Map<String, Object>> polyline(List<RoutePoint> points) {
        List<Map<String, Object>> line = new ArrayList<Map<String, Object>>();
        for (RoutePoint point : points) {
            if (point.hasCoordinate()) {
                line.add(coordinate(point));
            }
        }
        return line;
    }

    private Map<String, Object> coordinate(RoutePoint point) {
        Map<String, Object> coordinate = new HashMap<String, Object>();
        coordinate.put("facilityId", point.getFacilityId());
        coordinate.put("longitude", point.getLongitude());
        coordinate.put("latitude", point.getLatitude());
        return coordinate;
    }

    private double minutes(double distanceMeters, double speedKmh) {
        if (speedKmh <= 0D) {
            return 0D;
        }
        return distanceMeters / (speedKmh * 1000D) * 60D;
    }

    private SpeedProfile speedProfile(Map<String, Object> request) {
        Double legacySpeed = toDouble(request.get("speedKmh"));
        if (legacySpeed != null && legacySpeed > 0D) {
            return new SpeedProfile(legacySpeed, legacySpeed, legacySpeed,
                    positiveOrDefault(request, "denseRadiusMeters", 1000D),
                    positiveOrDefault(request, "densePointThreshold", 5D),
                    positiveOrDefault(request, "transferDistanceMeters", 3000D));
        }
        return new SpeedProfile(
                positiveOrDefault(request, "denseSpeedKmh", 15D),
                positiveOrDefault(request, "normalSpeedKmh", 25D),
                positiveOrDefault(request, "transferSpeedKmh", 40D),
                positiveOrDefault(request, "denseRadiusMeters", 1000D),
                positiveOrDefault(request, "densePointThreshold", 5D),
                positiveOrDefault(request, "transferDistanceMeters", 3000D));
    }

    private double positiveOrDefault(Map<String, Object> request, String key, double fallback) {
        Double value = toDouble(request.get(key));
        return value == null || value <= 0D ? fallback : value;
    }

    private static class SpeedProfile {
        private final double denseSpeedKmh;
        private final double normalSpeedKmh;
        private final double transferSpeedKmh;
        private final double denseRadiusMeters;
        private final double densePointThreshold;
        private final double transferDistanceMeters;

        private SpeedProfile(double denseSpeedKmh, double normalSpeedKmh, double transferSpeedKmh,
                             double denseRadiusMeters, double densePointThreshold, double transferDistanceMeters) {
            this.denseSpeedKmh = denseSpeedKmh;
            this.normalSpeedKmh = normalSpeedKmh;
            this.transferSpeedKmh = transferSpeedKmh;
            this.denseRadiusMeters = denseRadiusMeters;
            this.densePointThreshold = densePointThreshold;
            this.transferDistanceMeters = transferDistanceMeters;
        }

        private SpeedDecision forSegment(List<RoutePoint> points, int index, double distanceMeters) {
            if (index == 0 || index == points.size() - 2) {
                return new SpeedDecision(transferSpeedKmh, "TRANSFER", "起点/终点场站转场");
            }
            if (distanceMeters >= transferDistanceMeters) {
                return new SpeedDecision(transferSpeedKmh, "TRANSFER", "长距离转场");
            }
            RoutePoint from = points.get(index);
            RoutePoint to = points.get(index + 1);
            if (!from.hasCoordinate() || !to.hasCoordinate()) {
                return new SpeedDecision(normalSpeedKmh, "NORMAL", "普通收运路段");
            }
            double midLongitude = (from.getLongitude() + to.getLongitude()) / 2D;
            double midLatitude = (from.getLatitude() + to.getLatitude()) / 2D;
            int nearby = 0;
            for (int i = 1; i < points.size() - 1; i++) {
                RoutePoint point = points.get(i);
                if (point.hasCoordinate() && haversineMeters(midLongitude, midLatitude,
                        point.getLongitude(), point.getLatitude()) <= denseRadiusMeters) {
                    nearby++;
                }
            }
            if (nearby >= densePointThreshold) {
                return new SpeedDecision(denseSpeedKmh, "DENSE", "一公里范围内点位较密集(" + nearby + "个)");
            }
            return new SpeedDecision(normalSpeedKmh, "NORMAL", "普通收运路段");
        }
    }

    private static class SpeedDecision {
        private final double kmh;
        private final String speedClass;
        private final String reason;

        private SpeedDecision(double kmh, String speedClass, String reason) {
            this.kmh = kmh;
            this.speedClass = speedClass;
            this.reason = reason;
        }
    }

    private static double haversineMeters(double longitude1, double latitude1,
                                          double longitude2, double latitude2) {
        double earthRadius = 6371000D;
        double dLat = Math.toRadians(latitude2 - latitude1);
        double dLon = Math.toRadians(longitude2 - longitude1);
        double lat1 = Math.toRadians(latitude1);
        double lat2 = Math.toRadians(latitude2);
        double a = Math.sin(dLat / 2D) * Math.sin(dLat / 2D)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2D) * Math.sin(dLon / 2D);
        return 2D * earthRadius * Math.atan2(Math.sqrt(a), Math.sqrt(1D - a));
    }

    private String buildMultiMessage(boolean companyMode, String planningStrategy) {
        String distanceMode;
        if (STRATEGY_DIRECT_GROUP_ROAD_REFINE.equals(planningStrategy)) {
            distanceMode = "当前先用直线距离快速分组，再对每趟路线内部按 OD 缓存/百度补算道路距离精排。";
        } else if (STRATEGY_ROAD_GLOBAL.equals(planningStrategy)) {
            distanceMode = "当前先补齐全部有向道路 OD 点对，再按统一道路距离选择点位和生成路线；补算失败则停止本次道路规划。";
        } else {
            distanceMode = "当前使用点位直线距离选择点位和生成路线，未开启真实道路算路。";
        }
        if (companyMode) {
            return "已按公司点位池、预计垃圾量和目标装载率生成多路线预览。若公司维护了场站坐标，则使用传入的真实起终点；未传坐标时回退到点位中心。" + distanceMode;
        }
        return "已按路线点位池、预计垃圾量和目标装载率生成多路线预览。传入起终点坐标时使用页面配置；未传坐标时回退到原路线首尾点。" + distanceMode;
    }

    private boolean hasAnchorCoordinate(Map<String, Object> request, String prefix) {
        return toDouble(request.get(prefix + "Longitude")) != null && toDouble(request.get(prefix + "Latitude")) != null;
    }

    private RoutePoint anchorPoint(Map<String, Object> request, List<RoutePoint> points, String prefix,
                                   Long facilityId, String name) {
        Double longitude = toDouble(request.get(prefix + "Longitude"));
        Double latitude = toDouble(request.get(prefix + "Latitude"));
        if (longitude == null || latitude == null) {
            longitude = centroidLongitude(points);
            latitude = centroidLatitude(points);
        }
        String anchorName = textOrDefault(request.get(prefix + "FacilityName"), name);
        return new RoutePoint(facilityId, anchorName, longitude, latitude, null, 0D, 0D, null, 0D, null, "ANCHOR");
    }

    private double centroidLongitude(List<RoutePoint> points) {
        double total = 0D;
        int count = 0;
        for (RoutePoint point : points) {
            if (point.getLongitude() != null) {
                total += point.getLongitude();
                count++;
            }
        }
        return count == 0 ? 0D : total / count;
    }

    private double centroidLatitude(List<RoutePoint> points) {
        double total = 0D;
        int count = 0;
        for (RoutePoint point : points) {
            if (point.getLatitude() != null) {
                total += point.getLatitude();
                count++;
            }
        }
        return count == 0 ? 0D : total / count;
    }

    private double ratedCapacityKg(Map<String, Object> request) {
        Double value = toDouble(request.get("ratedCapacityKg"));
        return value == null || value <= 0D ? 0D : value;
    }

    private double defaultedRatedCapacityKg(Map<String, Object> request) {
        double value = ratedCapacityKg(request);
        return value <= 0D ? 5000D : value;
    }

    private double maxCapacityKg(Map<String, Object> request, double ratedCapacityKg) {
        Double value = toDouble(request.get("maxCapacityKg"));
        if (value == null || value <= 0D) {
            return ratedCapacityKg;
        }
        return value;
    }

    private int maxRoutes(Map<String, Object> request) {
        Integer value = toInteger(request.get("maxRoutes"));
        if (value == null || value <= 0) {
            return 10;
        }
        return value;
    }

    private double targetLoadRate(Map<String, Object> request) {
        Double value = toDouble(request.get("targetLoadRate"));
        return value == null || value <= 0D ? 0.9D : value;
    }

    private double loadRate(List<RoutePoint> points, Map<String, Object> request) {
        double ratedCapacityKg = ratedCapacityKg(request);
        if (ratedCapacityKg <= 0D) {
            return 0D;
        }
        return round(sumEstimatedWeight(points) / ratedCapacityKg);
    }

    private double sumEstimatedWeight(List<RoutePoint> points) {
        double total = 0D;
        for (RoutePoint point : points) {
            total += valueOrZero(point.getEstimatedWeightKg());
        }
        return total;
    }

    private double sumEstimatedVolume(List<RoutePoint> points) {
        double total = 0D;
        for (RoutePoint point : points) {
            total += valueOrZero(point.getEstimatedVolumeLiter());
        }
        return total;
    }

    private double sumOperationDuration(List<RoutePoint> points, Map<String, Object> request) {
        double total = 0D;
        for (RoutePoint point : points) {
            total += operationDuration(point, request, false);
        }
        return total;
    }

    private double operationDuration(RoutePoint point, Map<String, Object> request, boolean routeAnchor) {
        if (point == null || request == null || routeAnchor || "ANCHOR".equals(point.getWeightSource())) {
            return 0D;
        }
        double secondsPerContainer = requestNumber(request, "secondsPerContainer", 35D);
        double minutesPerPoint = requestNumber(request, "minutesPerPoint", 3D);
        return bucketHandlingUnits(point) * secondsPerContainer / 60D + minutesPerPoint;
    }

    /**
     * Returns handling units: one per 660L bucket and one per two 240L buckets.
     */
    private double bucketHandlingUnits(RoutePoint point) {
        String containerInfo = point.getContainerInfo();
        if (containerInfo == null || containerInfo.trim().isEmpty()) {
            return valueOrZero(point.getContainerCount());
        }
        double units = 0D;
        boolean parsed = false;
        String[] parts = containerInfo.split(",");
        for (String part : parts) {
            String[] pair = part.split("/");
            if (pair.length != 2) {
                continue;
            }
            try {
                double size = Double.parseDouble(pair[0].trim());
                double count = Double.parseDouble(pair[1].trim());
                if (count < 0D) {
                    continue;
                }
                parsed = true;
                if (Math.abs(size - 240D) < 0.001D) {
                    units += Math.ceil(count / 2D);
                } else {
                    units += count;
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed bucket specification.
            }
        }
        return parsed ? units : valueOrZero(point.getContainerCount());
    }

    private double requestNumber(Map<String, Object> request, String key, double fallback) {
        if (request == null) {
            return fallback;
        }
        Double value = toDouble(request.get(key));
        return value == null ? fallback : value;
    }

    private double valueOrZero(Double value) {
        return value == null ? 0D : value;
    }

    private double sumAssignedWeight(List<Map<String, Object>> routes) {
        double total = 0D;
        for (Map<String, Object> route : routes) {
            total += valueOrZero(toDouble(route.get("estimatedWeightKg")));
        }
        return total;
    }

    private int pointsInRoutes(List<Map<String, Object>> routes) {
        int total = 0;
        for (Map<String, Object> route : routes) {
            Integer pointCount = toInteger(route.get("pointCount"));
            total += pointCount == null ? 0 : pointCount;
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (value instanceof List<?>) {
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (Object item : (List<?>) value) {
                if (item instanceof Map<?, ?>) {
                    rows.add((Map<String, Object>) item);
                }
            }
            return rows;
        }
        return new ArrayList<Map<String, Object>>();
    }

    private double positiveOrDefault(Double value, double fallback) {
        return value == null || value <= 0D ? fallback : value;
    }

    private int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
    private String textOrDefault(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private Set<Long> toLongSet(Object value) {
        Set<Long> values = new HashSet<Long>();
        if (value == null) {
            return values;
        }
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                if (item != null) {
                    values.add(toLong(item));
                }
            }
            return values;
        }
        String text = String.valueOf(value);
        if (text.trim().isEmpty()) {
            return values;
        }
        for (String item : text.split(",")) {
            if (!item.trim().isEmpty()) {
                values.add(Long.valueOf(item.trim()));
            }
        }
        return values;
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.valueOf(String.valueOf(value));
    }

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).doubleValue();
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        String text = String.valueOf(value);
        if (text.trim().isEmpty()) {
            return null;
        }
        return Double.valueOf(text);
    }

    private double round(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private static class VehiclePlan {
        private final int vehicleIndex;
        private final String vehicleId;
        private final String vehicleName;
        private final String vehicleType;
        private final int tripCount;
        private final double ratedCapacityKg;
        private final double maxCapacityKg;
        private final double targetLoadWeightKg;
        private final RoutePoint start;
        private final RoutePoint end;

        private VehiclePlan(int vehicleIndex, String vehicleId, String vehicleName, String vehicleType, int tripCount,
                            double ratedCapacityKg, double maxCapacityKg, double targetLoadWeightKg,
                            RoutePoint start, RoutePoint end) {
            this.vehicleIndex = vehicleIndex;
            this.vehicleId = vehicleId;
            this.vehicleName = vehicleName;
            this.vehicleType = vehicleType;
            this.tripCount = tripCount;
            this.ratedCapacityKg = ratedCapacityKg;
            this.maxCapacityKg = maxCapacityKg;
            this.targetLoadWeightKg = targetLoadWeightKg;
            this.start = start;
            this.end = end;
        }
    }
    private static class DispatchTrip {
        private final int vehicleIndex;
        private final String vehicleId;
        private final String vehicleName;
        private final String vehicleType;
        private final int tripNo;
        private final double ratedCapacityKg;
        private final double maxCapacityKg;
        private final double targetLoadWeightKg;
        private final RoutePoint start;
        private final RoutePoint end;

        private DispatchTrip(int vehicleIndex, String vehicleId, String vehicleName, String vehicleType, int tripNo,
                             double ratedCapacityKg, double maxCapacityKg, double targetLoadWeightKg,
                             RoutePoint start, RoutePoint end) {
            this.vehicleIndex = vehicleIndex;
            this.vehicleId = vehicleId;
            this.vehicleName = vehicleName;
            this.vehicleType = vehicleType;
            this.tripNo = tripNo;
            this.ratedCapacityKg = ratedCapacityKg;
            this.maxCapacityKg = maxCapacityKg;
            this.targetLoadWeightKg = targetLoadWeightKg;
            this.start = start;
            this.end = end;
        }

        private DispatchTrip withStartAndEnd(RoutePoint start, RoutePoint end) {
            return new DispatchTrip(vehicleIndex, vehicleId, vehicleName, vehicleType, tripNo,
                    ratedCapacityKg, maxCapacityKg, targetLoadWeightKg, start, end);
        }
    }

    private static class RouteChoice {
        private final RoutePoint end;
        private final List<RoutePoint> route;
        private final double distance;

        private RouteChoice(RoutePoint end, List<RoutePoint> route, double distance) {
            this.end = end;
            this.route = route;
            this.distance = distance;
        }
    }
    private static class InsertChoice {
        private final RoutePoint point;
        private final int insertIndex;
        private final double increase;

        private InsertChoice(RoutePoint point, int insertIndex, double increase) {
            this.point = point;
            this.insertIndex = insertIndex;
            this.increase = increase;
        }
    }
}


