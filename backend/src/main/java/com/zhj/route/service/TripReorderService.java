package com.zhj.route.service;

import com.zhj.route.algorithm.FacilityTimeWindow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reorders fixed trip groups to improve point arrival time-window compliance. */
@Service
public class TripReorderService {
    private final RouteOptimizeService routeOptimizeService;

    public TripReorderService(RouteOptimizeService routeOptimizeService) {
        this.routeOptimizeService = routeOptimizeService;
    }

    public Map<String, Object> reorder(Map<String, Object> request) {
        Map<String, Object> sourceResult = map(request.get("result"));
        List<Map<String, Object>> sourceRoutes = maps(sourceResult.get("routes"));
        if (sourceRoutes.size() < 2) return response(false, "当前方案少于两趟，无法调整趟序", sourceResult, score(sourceRoutes, startMinutes(request)), null);

        double start = startMinutes(request);
        Score before = score(sourceRoutes, start);
        if (before.violations == 0) return response(false, "当前方案时间窗均已通过", sourceResult, before, before);

        Map<String, List<Map<String, Object>>> grouped = groupByVehicle(sourceRoutes);
        Map<String, List<Map<String, Object>>> optimized = new LinkedHashMap<String, List<Map<String, Object>>>();
        boolean reorderable = false;
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            List<Map<String, Object>> routes = entry.getValue();
            if (routes.size() > 1) reorderable = true;
            optimized.put(entry.getKey(), routes.size() > 1 ? improveOrder(routes, start) : routes);
        }
        if (!reorderable) return response(false, "同一车辆没有两趟以上路线，无法调整趟序", sourceResult, before, before);

        List<Map<String, Object>> candidateOrder = mergeVehicleOrders(sourceRoutes, optimized);
        Score approximate = score(candidateOrder, start);
        if (!timeImproved(approximate, before)) {
            return response(false, "调整趟序无法改善时间窗", sourceResult, before, before);
        }

        List<Map<String, Object>> rebuilt = rebuildRoutes(candidateOrder, sourceRoutes, request, sourceResult);
        Score after = score(rebuilt, start);
        if (!timeImproved(after, before)) {
            return response(false, "候选趟序经道路距离复核后没有改善，已保留原顺序", sourceResult, before, after);
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>(sourceResult);
        result.put("routes", rebuilt);
        result.put("routeCount", rebuilt.size());
        result.put("message", after.violations == 0
                ? "已调整趟序，当前点位时间窗全部通过。"
                : "已调整趟序，仍有 " + after.violations + " 个点位存在时间窗冲突。");
        result.put("tripReordered", true);
        result.put("timeWindowViolationBefore", before.violations);
        result.put("timeWindowViolationAfter", after.violations);
        result.put("timeWindowPenaltyBefore", round(before.penaltyMinutes));
        result.put("timeWindowPenaltyAfter", round(after.penaltyMinutes));
        return response(true, String.valueOf(result.get("message")), result, before, after);
    }

    private List<Map<String, Object>> improveOrder(List<Map<String, Object>> original, double start) {
        List<Map<String, Object>> current = new ArrayList<Map<String, Object>>(original);
        Score currentScore = score(current, start);
        int maxIterations = Math.max(1, current.size() * current.size());
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            List<Map<String, Object>> best = current;
            Score bestScore = currentScore;
            for (int from = 0; from < current.size(); from++) {
                for (int to = 0; to < current.size(); to++) {
                    if (from == to) continue;
                    List<Map<String, Object>> moved = new ArrayList<Map<String, Object>>(current);
                    Map<String, Object> route = moved.remove(from);
                    moved.add(to, route);
                    Score movedScore = score(moved, start);
                    if (compare(movedScore, bestScore) < 0) {
                        best = moved;
                        bestScore = movedScore;
                    }
                }
            }
            for (int left = 0; left < current.size(); left++) {
                for (int right = left + 1; right < current.size(); right++) {
                    List<Map<String, Object>> swapped = new ArrayList<Map<String, Object>>(current);
                    Collections.swap(swapped, left, right);
                    Score swappedScore = score(swapped, start);
                    if (compare(swappedScore, bestScore) < 0) {
                        best = swapped;
                        bestScore = swappedScore;
                    }
                }
            }
            if (best == current) break;
            current = best;
            currentScore = bestScore;
        }
        return current;
    }

    private List<Map<String, Object>> rebuildRoutes(List<Map<String, Object>> ordered, List<Map<String, Object>> original,
                                                     Map<String, Object> request, Map<String, Object> sourceResult) {
        Map<String, Map<String, Object>> baseStart = new HashMap<String, Map<String, Object>>();
        for (Map<String, Object> route : original) {
            List<Map<String, Object>> points = maps(route.get("points"));
            if (!points.isEmpty() && !baseStart.containsKey(vehicleKey(route))) baseStart.put(vehicleKey(route), copy(points.get(0)));
        }
        Map<String, Map<String, Object>> previousEnd = new HashMap<String, Map<String, Object>>();
        Map<String, Integer> vehicleTrips = new HashMap<String, Integer>();
        List<Map<String, Object>> rebuilt = new ArrayList<Map<String, Object>>();
        boolean road = "ROAD".equalsIgnoreCase(text(sourceResult.get("distanceMode")));
        int routeNo = 1;
        for (Map<String, Object> source : ordered) {
            String key = vehicleKey(source);
            int tripNo = vehicleTrips.containsKey(key) ? vehicleTrips.get(key) + 1 : 1;
            vehicleTrips.put(key, tripNo);
            List<Map<String, Object>> oldPoints = maps(source.get("points"));
            if (oldPoints.size() < 2) continue;
            Map<String, Object> start = tripNo == 1 ? copy(baseStart.get(key)) : copy(previousEnd.get(key));
            Map<String, Object> end = copy(oldPoints.get(oldPoints.size() - 1));
            start.put("operationDurationMinutes", 0D);
            List<Map<String, Object>> points = new ArrayList<Map<String, Object>>();
            points.add(start);
            for (int i = 1; i < oldPoints.size() - 1; i++) points.add(copy(oldPoints.get(i)));
            points.add(end);
            for (int i = 0; i < points.size(); i++) {
                points.get(i).put("order", i + 1);
                points.get(i).put("orderNum", i + 1);
                points.get(i).put("role", i == 0 ? "START" : (i == points.size() - 1 ? "END" : "MIDDLE"));
            }

            Map<String, Object> segmentRequest = new HashMap<String, Object>();
            segmentRequest.put("points", points);
            segmentRequest.put("displayRoadPath", road);
            copyOption(request, segmentRequest, "speedKmh");
            copyOption(request, segmentRequest, "roadSpeedKmh");
            copyOption(request, segmentRequest, "communitySpeedKmh");
            copyOption(request, segmentRequest, "internalSpeedKmh");
            Map<String, Object> segmentResult = routeOptimizeService.routeSegmentsPreview(segmentRequest);
            List<Map<String, Object>> segments = maps(segmentResult.get("segments"));
            if (road) ensureRoadSegments(segments, points.size() - 1);

            double distance = number(segmentResult.get("distance"));
            double travel = number(segmentResult.get("durationMinutes"));
            double operation = 0D;
            for (Map<String, Object> point : points) operation += number(point.get("operationDurationMinutes"));
            Map<String, Object> route = new LinkedHashMap<String, Object>(source);
            route.put("routeNo", routeNo++);
            route.put("tripNo", tripNo);
            route.put("points", points);
            route.put("sequence", facilityIds(points));
            route.put("segments", segments);
            if (road) route.put("roadSegments", segments); else route.remove("roadSegments");
            route.put("distance", round(distance));
            route.put("planningDistance", round(distance));
            route.put("displayDistance", round(distance));
            route.put("travelDurationMinutes", round(travel));
            route.put("planningTravelDurationMinutes", round(travel));
            route.put("displayTravelDurationMinutes", round(travel));
            route.put("operationDurationMinutes", round(operation));
            route.put("totalDurationMinutes", round(travel + operation));
            route.put("planningTotalDurationMinutes", round(travel + operation));
            route.put("displayTotalDurationMinutes", round(travel + operation));
            route.put("durationMinutes", round(travel + operation));
            rebuilt.add(route);
            previousEnd.put(key, end);
        }
        return rebuilt;
    }

    private Score score(List<Map<String, Object>> routes, double baseStart) {
        Map<String, Double> nextStart = new HashMap<String, Double>();
        int violations = 0;
        double penalty = 0D;
        double completion = 0D;
        for (Map<String, Object> route : routes) {
            String key = vehicleKey(route);
            double cursor = nextStart.containsKey(key) ? nextStart.get(key) : baseStart;
            List<Map<String, Object>> points = maps(route.get("points"));
            List<Map<String, Object>> segments = maps(route.get("segments"));
            for (int i = 0; i < points.size(); i++) {
                if (i > 0 && i - 1 < segments.size()) cursor += number(segments.get(i - 1).get("durationMinutes"));
                Map<String, Object> point = points.get(i);
                if (isCollectionPoint(point, i, points.size()) && hasWindow(point)) {
                    int minute = minuteOfDay(cursor);
                    if (!allows(point, minute)) {
                        violations++;
                        penalty += windowDistance(point, minute);
                    }
                }
                cursor += number(point.get("operationDurationMinutes"));
            }
            double official = number(route.get("durationMinutes"));
            double routeStart = nextStart.containsKey(key) ? nextStart.get(key) : baseStart;
            cursor = Math.max(cursor, routeStart + official);
            nextStart.put(key, cursor);
        }
        for (Double end : nextStart.values()) completion += Math.max(0D, end - baseStart);
        return new Score(violations, penalty, completion);
    }

    private boolean allows(Map<String, Object> point, int minute) {
        com.zhj.route.algorithm.RoutePoint routePoint = new com.zhj.route.algorithm.RoutePoint(null, null, null, null, null, null, null, null, null, null, null, null);
        routePoint.setTimeWindow(FacilityTimeWindow.parseClockMinutes(point.get("allowTimeBegin")), FacilityTimeWindow.parseClockMinutes(point.get("allowTimeEnd")), FacilityTimeWindow.parseClockMinutes(point.get("barredTimeBegin")), FacilityTimeWindow.parseClockMinutes(point.get("barredTimeEnd")));
        return FacilityTimeWindow.allowsEntry(routePoint, minute);
    }

    private double windowDistance(Map<String, Object> point, int minute) {
        for (int distance = 1; distance <= 720; distance++) {
            if (allows(point, (minute + distance) % 1440) || allows(point, (minute - distance + 1440) % 1440)) return distance;
        }
        return 720D;
    }

    private Map<String, List<Map<String, Object>>> groupByVehicle(List<Map<String, Object>> routes) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (Map<String, Object> route : routes) grouped.computeIfAbsent(vehicleKey(route), k -> new ArrayList<Map<String, Object>>()).add(route);
        return grouped;
    }

    private List<Map<String, Object>> mergeVehicleOrders(List<Map<String, Object>> original,
                                                          Map<String, List<Map<String, Object>>> orders) {
        Map<String, Integer> indexes = new HashMap<String, Integer>();
        List<Map<String, Object>> merged = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> route : original) {
            String key = vehicleKey(route);
            int index = indexes.containsKey(key) ? indexes.get(key) : 0;
            merged.add(orders.get(key).get(index));
            indexes.put(key, index + 1);
        }
        return merged;
    }

    private Map<String, Object> response(boolean improved, String message, Map<String, Object> result, Score before, Score after) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("improved", improved);
        response.put("message", message);
        response.put("beforeViolationCount", before == null ? 0 : before.violations);
        response.put("afterViolationCount", after == null ? (before == null ? 0 : before.violations) : after.violations);
        response.put("beforePenaltyMinutes", before == null ? 0D : round(before.penaltyMinutes));
        response.put("afterPenaltyMinutes", after == null ? (before == null ? 0D : round(before.penaltyMinutes)) : round(after.penaltyMinutes));
        response.put("result", result);
        return response;
    }

    private int compare(Score left, Score right) {
        int value = Integer.compare(left.violations, right.violations);
        if (value != 0) return value;
        value = Double.compare(left.penaltyMinutes, right.penaltyMinutes);
        if (value != 0) return value;
        return Double.compare(left.completionMinutes, right.completionMinutes);
    }

    private boolean timeImproved(Score candidate, Score baseline) {
        return candidate.violations < baseline.violations
                || (candidate.violations == baseline.violations && candidate.penaltyMinutes + 0.01D < baseline.penaltyMinutes);
    }

    private boolean hasWindow(Map<String, Object> point) {
        return point.get("allowTimeBegin") != null || point.get("allowTimeEnd") != null
                || (point.get("barredTimeBegin") != null && point.get("barredTimeEnd") != null);
    }

    private boolean isCollectionPoint(Map<String, Object> point, int index, int size) {
        String role = text(point.get("role"));
        return "MIDDLE".equalsIgnoreCase(role) || (role.isEmpty() && index > 0 && index < size - 1);
    }

    private void ensureRoadSegments(List<Map<String, Object>> segments, int expected) {
        if (segments.size() != expected) throw new IllegalStateException("重排后未返回完整道路分段");
        for (Map<String, Object> segment : segments) {
            String source = text(segment.get("pathSource"));
            if (!("OD_CACHE".equals(source) || "OD_PRELOAD".equals(source) || "BAIDU_ONLINE".equals(source))) {
                throw new IllegalStateException("重排后存在非道路距离结果，已保留原方案");
            }
        }
    }

    private void copyOption(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) to.put(key, from.get(key));
    }

    private List<Object> facilityIds(List<Map<String, Object>> points) {
        List<Object> ids = new ArrayList<Object>();
        for (Map<String, Object> point : points) ids.add(point.get("facilityId"));
        return ids;
    }

    private double startMinutes(Map<String, Object> request) {
        Integer value = FacilityTimeWindow.parseClockMinutes(request.get("plannedStartTime"));
        return value == null ? 360D : value.doubleValue();
    }

    private int minuteOfDay(double value) {
        int minute = (int) Math.round(value) % 1440;
        return minute < 0 ? minute + 1440 : minute;
    }

    private String vehicleKey(Map<String, Object> route) {
        String id = text(route.get("vehicleId"));
        return id.isEmpty() ? text(route.get("vehicleName")) : id;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List)) return new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object item : (List<Object>) value) if (item instanceof Map) result.add((Map<String, Object>) item);
        return result;
    }

    private Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(source);
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private double number(Object value) {
        if (value == null) return 0D;
        try { return Double.parseDouble(String.valueOf(value)); } catch (NumberFormatException ignored) { return 0D; }
    }
    private double round(double value) { return Math.round(value * 100D) / 100D; }

    private static class Score {
        private final int violations;
        private final double penaltyMinutes;
        private final double completionMinutes;
        private Score(int violations, double penaltyMinutes, double completionMinutes) {
            this.violations = violations;
            this.penaltyMinutes = penaltyMinutes;
            this.completionMinutes = completionMinutes;
        }
    }
}