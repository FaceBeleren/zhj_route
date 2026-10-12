package com.zhj.route.service;

import com.zhj.route.algorithm.FacilityTimeWindow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
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

    /** Point relocation search until feasible or no strict improvement; does not modify the input. */
    public Map<String, Object> repairPoints(Map<String, Object> request) {
        Map<String, Object> source = map(request.get("result"));
        List<Map<String, Object>> original = maps(source.get("routes"));
        double start = startMinutes(request);
        ScenarioEvaluator evaluator = new ScenarioEvaluator(request, original, start);
        ScenarioEvaluation currentEvaluation = evaluator.evaluate(original);
        ScenarioEvaluation originalEvaluation = currentEvaluation;
        Score before = currentEvaluation.score;
        List<Map<String, Object>> current = original;
        Score currentScore = before;
        int evaluations = 0;
        int iterations = 0;
        java.util.Set<String> visited = new java.util.HashSet<String>();
        visited.add(orderSignature(current));
        boolean expanded = "EXPANDED".equalsIgnoreCase(text(request.get("searchMode")));
        while (currentScore.violations > 0) {
            iterations++;
            List<Map<String, Object>> best = current;
            Score bestScore = currentScore;
            ScenarioEvaluation bestEvaluation = currentEvaluation;
            Map<Integer, Map<Integer, Integer>> conflicts = evaluator.conflicts(current, currentEvaluation);
            for (int r = 0; r < current.size(); r++) {
                List<Map<String, Object>> points = maps(current.get(r).get("points"));
                for (int from = 1; from < points.size() - 1; from++) {
                    Integer direction = conflicts.getOrDefault(r, Collections.emptyMap()).get(from);
                    if (!isCollectionPoint(points.get(from), from, points.size())) continue;
                    if (!expanded && direction == null) continue;
                    // Evaluate every insertion position: intermediate equal-score moves need not be applied.
                    for (int step = 1; step <= 2 * (points.size() - 2); step++) {
                        int offset = (step + 1) / 2;
                        int to = from + (step % 2 == 1 ? offset : -offset);
                        if (to < 1 || to >= points.size() - 1) continue;
                        if (!expanded && ((direction == 1 && to < from) || (direction == -1 && to > from))) continue;
                        List<Map<String, Object>> candidate = new ArrayList<Map<String, Object>>(current);
                        Map<String, Object> route = copy(current.get(r));
                        List<Map<String, Object>> moved = new ArrayList<Map<String, Object>>(points);
                        moved.add(to, moved.remove(from));
                        route.put("points", moved);
                        candidate.set(r, route);
                        if (!visited.add(orderSignature(candidate))) continue;
                        evaluations++;
                        // Trip membership and endpoints are fixed: rebuild only the changed trip.
                        List<Map<String, Object>> rebuilt = new ArrayList<Map<String, Object>>(current);
                        Map<String, Object> changed = rebuildRoutes(Collections.singletonList(route), Collections.singletonList(current.get(r)), request, source).get(0);
                        changed.put("routeNo", current.get(r).get("routeNo"));
                        changed.put("tripNo", current.get(r).get("tripNo"));
                        rebuilt.set(r, changed);
                        ScenarioEvaluation candidateEvaluation = evaluator.evaluate(rebuilt);
                        Score candidateScore = candidateEvaluation.score;
                        if (compare(candidateScore, bestScore) < 0) {
                            best = rebuilt;
                            bestScore = candidateScore;
                            bestEvaluation = candidateEvaluation;
                            // Even after all windows pass, finish this round's insertion
                            // candidates so a later feasible position can be more efficient.
                        }
                    }
                }
            }
            if (best == current) break;
            current = best;
            currentScore = bestScore;
            currentEvaluation = bestEvaluation;
        }
        boolean improved = compare(currentScore, before) < 0;
        Map<String, Object> result = copy(source);
        if (improved) {
            result.put("routes", current);
            result.put("timeWindowRepaired", true);
        }
        String reason = currentScore.violations == 0 ? "时间窗全部通过，已完成本轮候选比较" : "当前搜索未找到进一步改善";
        String message = "冲突点由 " + before.violations + " 个变为 " + currentScore.violations + " 个；" + reason;
        if (evaluator.joint) message = evaluator.summary(currentEvaluation) + "；" + (currentScore.violations == 0 ? "已完成本轮候选比较" : "当前搜索未找到全部场景均通过的固定顺序");
        Map<String, Object> response = response(improved, message, result, before, currentScore);
        response.put("evaluations", evaluations);
        response.put("iterations", iterations);
        response.put("limited", false); // Keep the response field for existing clients; search has no artificial cap.
        response.put("searchMode", expanded ? "EXPANDED" : "CONFLICT_ONLY");
        response.put("improvementType", timeImproved(currentScore, before) ? "TIME_WINDOW" : improved ? "EFFICIENCY_ONLY" : "NONE");
        response.put("beforeDistance", before.distance);
        response.put("afterDistance", currentScore.distance);
        response.put("beforeCompletionMinutes", before.completionMinutes);
        response.put("afterCompletionMinutes", currentScore.completionMinutes);
        if (evaluator.joint) {
            response.put("jointScenarioValidation", true);
            response.put("templateBeforeViolationCount", originalEvaluation.templateScore.violations);
            response.put("templateAfterViolationCount", currentEvaluation.templateScore.violations);
            response.put("dailyBefore", originalEvaluation.dailySummaries);
            response.put("dailyAfter", currentEvaluation.dailySummaries);
            response.put("allScenariosPassed", currentScore.violations == 0 && evaluator.unassignedCount == 0);
        }
        return response;
    }

    /** Request-local road cache: evaluate real bypass routes, not full-route travel with service removed. */
    public java.util.function.Function<List<Map<String, Object>>, Map<String, Object>> jointScenarioChecker(
            Map<String, Object> request, java.util.function.Function<List<Map<String, Object>>, Map<String, Object>> roads) {
        ScenarioEvaluator evaluator = new ScenarioEvaluator(request, maps(map(request.get("result")).get("routes")), startMinutes(request));
        evaluator.localRoads = roads;
        return routes -> {
            ScenarioEvaluation evaluation = evaluator.evaluate(routes);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("violations", evaluation.score.violations);
            out.put("templateViolations", evaluation.templateScore.violations);
            out.put("dailyChecks", evaluation.dailySummaries);
            out.put("message", evaluator.summary(evaluation));
            out.put("allScenariosPassed", evaluation.score.violations == 0 && evaluator.unassignedCount == 0);
            return out;
        };
    }

    private class ScenarioEvaluator {
        private final Map<String, Object> request;
        private final List<Map<String, Object>> days;
        private final Map<String, Map<String, Object>> roadCache = new HashMap<>();
        private final java.util.Set<String> covered = new java.util.HashSet<>();
        private final double start;
        private final boolean joint;
        private final int unassignedCount;
        private java.util.function.Function<List<Map<String, Object>>, Map<String, Object>> localRoads;

        ScenarioEvaluator(Map<String, Object> request, List<Map<String, Object>> routes, double start) {
            this.request = request;
            this.start = start;
            days = maps(request.get("scheduleDays"));
            joint = request.containsKey("scheduleDays");
            if (joint && (days.isEmpty() || !"ROAD".equalsIgnoreCase(text(map(request.get("result")).get("distanceMode"))))) {
                throw new IllegalArgumentException("周期联合修正需要完整排班日期和道路模板");
            }
            for (Map<String, Object> route : routes) {
                List<Map<String, Object>> points = maps(route.get("points"));
                for (int i = 1; i < points.size() - 1; i++) if (isCollectionPoint(points.get(i), i, points.size())) covered.add(text(points.get(i).get("facilityId")));
            }
            java.util.Set<String> dayNumbers = new java.util.HashSet<>();
            java.util.Set<String> missing = new java.util.HashSet<>();
            for (Map<String, Object> day : days) {
                if (number(day.get("day")) < 1 || !dayNumbers.add(text(day.get("day"))) || !(day.get("facilityIds") instanceof List)) throw new IllegalArgumentException("排班日期或应收点列表无效");
                for (String id : dueIds(day)) if (!covered.contains(id)) missing.add(id);
            }
            Map<String, Object> result = map(request.get("result"));
            unassignedCount = Math.max(missing.size(), Math.max(maps(result.get("unassignedPoints")).size(), (int) number(result.get("unassignedPointCount"))));
        }

        private java.util.Set<String> dueIds(Map<String, Object> day) {
            java.util.Set<String> due = new java.util.HashSet<>();
            for (Object id : (List<?>) day.get("facilityIds")) {
                if (id == null || text(id).isEmpty()) throw new IllegalArgumentException("排班应收点ID不能为空");
                due.add(text(id));
            }
            return due;
        }

        ScenarioEvaluation evaluate(List<Map<String, Object>> routes) {
            if (joint) for (Map<String, Object> route : routes) {
                validateRoad(maps(route.get("segments")), maps(route.get("points")).size() - 1);
            }
            Score template = score(routes, start, localRoads == null);
            if (localRoads != null && template.violations > 0) return new ScenarioEvaluation(template, template, new ArrayList<>(), new ArrayList<>());
            if (!joint) return new ScenarioEvaluation(template, template, new ArrayList<>(), new ArrayList<>());
            int violations = template.violations;
            double penalty = template.penaltyMinutes, completion = 0D, distance = 0D;
            List<List<Map<String, Object>>> dailyRoutes = new ArrayList<>();
            List<Map<String, Object>> summaries = new ArrayList<>();
            for (Map<String, Object> day : days) {
                java.util.Set<String> due = dueIds(day);
                List<Map<String, Object>> filtered = new ArrayList<>();
                for (Map<String, Object> route : routes) {
                    List<Map<String, Object>> full = maps(route.get("points"));
                    List<Map<String, Object>> points = new ArrayList<>();
                    for (int i = 0; i < full.size(); i++) if (!isCollectionPoint(full.get(i), i, full.size()) || due.contains(text(full.get(i).get("facilityId")))) points.add(full.get(i));
                    if (points.size() == full.size()) {
                        validateRoad(maps(route.get("segments")), points.size() - 1);
                        filtered.add(route);
                    } else {
                        Map<String, Object> road = bypassRoad(points);
                        Map<String, Object> daily = copy(route);
                        daily.put("points", points);
                        daily.put("segments", road.get("segments"));
                        daily.put("distance", road.get("distance"));
                        double operation = 0D;
                        for (Map<String, Object> point : points) operation += number(point.get("operationDurationMinutes"));
                        daily.put("durationMinutes", round(number(road.get("durationMinutes")) + operation));
                        filtered.add(daily);
                    }
                }
                Score dailyScore = score(filtered, start, localRoads == null);
                violations += dailyScore.violations;
                penalty += dailyScore.penaltyMinutes;
                completion += dailyScore.completionMinutes;
                distance += dailyScore.distance;
                dailyRoutes.add(filtered);
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("day", day.get("day"));
                summary.put("violationCount", dailyScore.violations);
                summary.put("penaltyMinutes", round(dailyScore.penaltyMinutes));
                summary.put("completionMinutes", round(dailyScore.completionMinutes));
                summary.put("distance", round(dailyScore.distance));
                summary.put("dueUnassignedCount", due.stream().filter(id -> !covered.contains(id)).count());
                summaries.add(summary);
                if (localRoads != null && dailyScore.violations > 0) break;
            }
            return new ScenarioEvaluation(new Score(violations, penalty, completion, distance), template, dailyRoutes, summaries);
        }

        private Map<String, Object> bypassRoad(List<Map<String, Object>> points) {
            if (localRoads != null) return localRoads.apply(points);
            StringBuilder key = new StringBuilder();
            for (Map<String, Object> point : points) key.append(text(point.get("facilityId"))).append('@').append(point.get("longitude")).append(',').append(point.get("latitude")).append(';');
            Map<String, Object> road = roadCache.get(key.toString());
            if (road != null) return road;
            // An empty later trip remains at the unloading site. Match the frontend's zero-road rule.
            if (!points.isEmpty() && points.get(0).get("longitude") instanceof Number && points.get(0).get("latitude") instanceof Number
                    && Double.isFinite(number(points.get(0).get("longitude"))) && Double.isFinite(number(points.get(0).get("latitude")))
                    && points.stream().allMatch(point -> points.get(0).get("longitude").equals(point.get("longitude")) && points.get(0).get("latitude").equals(point.get("latitude")))) {
                List<Map<String, Object>> segments = new ArrayList<>();
                for (int i = 1; i < points.size(); i++) {
                    Map<String, Object> segment = new LinkedHashMap<>();
                    segment.put("distance", 0D); segment.put("durationMinutes", 0D); segment.put("pathSource", "SAME_POINT");
                    segments.add(segment);
                }
                road = new LinkedHashMap<>();
                road.put("segments", segments); road.put("distance", 0D); road.put("durationMinutes", 0D);
                roadCache.put(key.toString(), road);
                return road;
            }
            Map<String, Object> input = copy(request);
            input.remove("result");
            input.remove("scheduleDays");
            input.put("points", points);
            input.put("displayRoadPath", true);
            road = routeOptimizeService.routeSegmentsPreview(input);
            List<Map<String, Object>> segments = maps(road.get("segments"));
            validateRoad(segments, points.size() - 1);
            double travel = 0D, distance = 0D;
            for (Map<String, Object> segment : segments) { travel += number(segment.get("durationMinutes")); distance += number(segment.get("distance")); }
            road = copy(road);
            road.put("durationMinutes", round(travel));
            road.put("distance", round(distance));
            roadCache.put(key.toString(), road);
            return road;
        }

        private void validateRoad(List<Map<String, Object>> segments, int expected) {
            if (expected < 1 || segments.size() != expected) throw new IllegalStateException("每日跳点道路分段不完整，未应用修正");
            for (Map<String, Object> segment : segments) {
                String source = text(segment.get("pathSource"));
                if (!Arrays.asList("OD_CACHE", "OD_PRELOAD", "BAIDU_ONLINE", "SAME_POINT").contains(source)) throw new IllegalStateException("每日跳点包含非道路距离，未应用修正");
            }
            for (Map<String, Object> segment : segments) for (String field : new String[]{"distance", "durationMinutes"}) {
                if (segment.get(field) == null || !Double.isFinite(number(segment.get(field))) || number(segment.get(field)) < 0) throw new IllegalStateException("每日跳点道路数据缺失，未应用修正");
                try { Double.parseDouble(text(segment.get(field))); } catch (NumberFormatException ex) { throw new IllegalStateException("每日跳点道路数据无效，未应用修正"); }
            }
        }

        Map<Integer, Map<Integer, Integer>> conflicts(List<Map<String, Object>> template, ScenarioEvaluation evaluation) {
            Map<Integer, Map<Integer, Integer>> merged = conflictDirections(template, start);
            for (List<Map<String, Object>> routes : evaluation.dailyRoutes) {
                Map<Integer, Map<Integer, Integer>> daily = conflictDirections(routes, start);
                for (Map.Entry<Integer, Map<Integer, Integer>> entry : daily.entrySet()) {
                    List<Map<String, Object>> points = maps(routes.get(entry.getKey()).get("points"));
                    List<Map<String, Object>> full = maps(template.get(entry.getKey()).get("points"));
                    for (Map.Entry<Integer, Integer> conflict : entry.getValue().entrySet()) {
                        String id = text(points.get(conflict.getKey()).get("facilityId"));
                        for (int i = 1; i < full.size() - 1; i++) if (id.equals(text(full.get(i).get("facilityId")))) {
                            merged.computeIfAbsent(entry.getKey(), ignored -> new HashMap<>()).merge(i, conflict.getValue(), (left, right) -> left.equals(right) ? left : 0);
                        }
                    }
                }
            }
            return merged;
        }

        String summary(ScenarioEvaluation evaluation) {
            StringBuilder text = new StringBuilder("完整模板：").append(evaluation.templateScore.violations).append("处冲突；排班周期：");
            for (Map<String, Object> day : evaluation.dailySummaries) text.append("第").append(day.get("day")).append("天").append(number(day.get("violationCount")) == 0 ? "通过" : day.get("violationCount") + "处冲突").append("；");
            if (unassignedCount > 0) text.append("仍有").append(unassignedCount).append("个点未分配，不代表收运任务全部可执行");
            return text.toString();
        }
    }

    private static class ScenarioEvaluation {
        final Score score, templateScore;
        final List<List<Map<String, Object>>> dailyRoutes;
        final List<Map<String, Object>> dailySummaries;
        ScenarioEvaluation(Score score, Score templateScore, List<List<Map<String, Object>>> dailyRoutes, List<Map<String, Object>> dailySummaries) {
            this.score = score; this.templateScore = templateScore; this.dailyRoutes = dailyRoutes; this.dailySummaries = dailySummaries;
        }
    }

    /** Recompute conflicts every round, with continuous execution independently per vehicle. */
    private Map<Integer, Map<Integer, Integer>> conflictDirections(List<Map<String, Object>> routes, double start) {
        Map<Integer, Map<Integer, Integer>> conflicts = new HashMap<>();
        Map<String, Double> nextStart = new HashMap<>();
        for (int r = 0; r < routes.size(); r++) {
            Map<String, Object> route = routes.get(r);
            String key = vehicleKey(route);
            double routeStart = nextStart.getOrDefault(key, start);
            double cursor = routeStart;
            List<Map<String, Object>> points = maps(route.get("points"));
            List<Map<String, Object>> segments = maps(route.get("segments"));
            for (int i = 0; i < points.size(); i++) {
                if (i > 0 && i - 1 < segments.size()) cursor += number(segments.get(i - 1).get("durationMinutes"));
                Map<String, Object> point = points.get(i);
                int minute = minuteOfDay(cursor);
                if (isCollectionPoint(point, i, points.size()) && hasWindow(point) && !allows(point, minute)) {
                    int direction = 0; // Forbidden, overnight and incomplete windows search both directions.
                    Integer begin = FacilityTimeWindow.parseClockMinutes(point.get("allowTimeBegin"));
                    Integer end = FacilityTimeWindow.parseClockMinutes(point.get("allowTimeEnd"));
                    boolean barred = point.get("barredTimeBegin") != null && point.get("barredTimeEnd") != null;
                    if (!barred && begin != null && end != null && begin <= end) direction = minute < begin ? 1 : -1;
                    conflicts.computeIfAbsent(r, ignored -> new HashMap<>()).put(i, direction);
                }
                cursor += number(point.get("operationDurationMinutes"));
            }
            nextStart.put(key, Math.max(cursor, routeStart + number(route.get("durationMinutes"))));
        }
        return conflicts;
    }

    private String orderSignature(List<Map<String, Object>> routes) {
        StringBuilder signature = new StringBuilder();
        for (Map<String, Object> route : routes) {
            signature.append('|').append(vehicleKey(route)).append(':');
            for (Map<String, Object> point : maps(route.get("points"))) signature.append(text(point.get("facilityId"))).append(',');
        }
        return signature.toString();
    }

    private double totalDistance(List<Map<String, Object>> routes) {
        double distance = 0;
        for (Map<String, Object> route : routes) {
            List<Map<String, Object>> segments = maps(route.get("segments"));
            if (!segments.isEmpty()) {
                for (Map<String, Object> segment : segments) distance += number(segment.get("distance"));
            } else {
                distance += number(route.get("distance"));
            }
        }
        return round(distance);
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
            copyOption(request, segmentRequest, "denseSpeedKmh");
            copyOption(request, segmentRequest, "normalSpeedKmh");
            copyOption(request, segmentRequest, "transferSpeedKmh");
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
            double workLimit = number(source.get("workLimitMinutes"));
            if (workLimit > 0) {
                route.put("timeExceeded", travel + operation > workLimit);
                route.put("overdueMinutes", round(Math.max(0D, travel + operation - workLimit)));
            }
            route.remove("polyline");
            route.remove("roadDistance");
            route.remove("roadDurationMinutes");
            rebuilt.add(route);
            previousEnd.put(key, end);
        }
        return rebuilt;
    }

    private Score score(List<Map<String, Object>> routes, double baseStart) {
        return score(routes, baseStart, true);
    }

    private Score score(List<Map<String, Object>> routes, double baseStart, boolean calculatePenalty) {
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
                        if (calculatePenalty) penalty += windowDistance(point, minute);
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
        return new Score(violations, penalty, completion, totalDistance(routes));
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
        value = Double.compare(left.completionMinutes, right.completionMinutes);
        if (value != 0) return value;
        return Double.compare(left.distance, right.distance);
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
        private final double distance;
        private Score(int violations, double penaltyMinutes, double completionMinutes, double distance) {
            this.violations = violations;
            this.penaltyMinutes = penaltyMinutes;
            this.completionMinutes = completionMinutes;
            this.distance = distance;
        }
    }
}
