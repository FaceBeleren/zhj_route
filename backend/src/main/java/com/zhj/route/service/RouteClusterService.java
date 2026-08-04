package com.zhj.route.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RouteClusterService {
    private static final List<String> COLORS = Arrays.asList(
            "#2563eb", "#dc2626", "#16a34a", "#9333ea", "#ea580c", "#0891b2",
            "#be123c", "#4f46e5", "#65a30d", "#ca8a04", "#0f766e", "#7c3aed");

    private final RouteQueryService routeQueryService;

    public RouteClusterService(RouteQueryService routeQueryService) {
        this.routeQueryService = routeQueryService;
    }

    public Map<String, Object> preview(Map<String, Object> request) {
        String unitId = text(request.get("unitId"));
        if (unitId.isEmpty()) {
            throw new IllegalArgumentException("unitId不能为空");
        }
        List<Map<String, Object>> rows = routeQueryService.companyFacilityPoints(unitId);
        Map<Long, Point> pointById = new LinkedHashMap<Long, Point>();
        for (Map<String, Object> row : rows) {
            Point point = toPoint(row);
            if (point.facilityId != null) {
                pointById.put(point.facilityId, point);
            }
        }
        Set<Long> requestedIds = longSet(request.get("facilityIds"));
        List<Point> points = new ArrayList<Point>();
        if (requestedIds.isEmpty()) {
            points.addAll(pointById.values());
        } else {
            for (Long id : requestedIds) {
                Point point = pointById.get(id);
                if (point != null) {
                    points.add(point);
                }
            }
        }
        if (points.isEmpty()) {
            throw new IllegalArgumentException("当前没有可分堆的点位");
        }
        TimeConfig timeConfig = timeConfig(request.get("timeConfig"));
        String clusterMode = clusterMode(request.get("clusterMode"));
        List<Map<String, Object>> beforeGroups = buildBeforeGroups(points, pointById, request.get("originalGroups"), timeConfig);
        int target = targetGroupCount(request.get("targetGroupCount"), points);
        List<Group> clustered = cluster(points, target, clusterMode);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", "DONE");
        result.put("message", "已生成点位分堆预览");
        result.put("pointCount", points.size());
        result.put("targetGroupCount", target);
        result.put("autoGroupCount", target);
        result.put("beforeGroups", beforeGroups);
        result.put("afterGroups", groupViews(clustered, "cluster", timeConfig));
        result.put("timeConfig", timeConfig.toMap());
        result.put("clusterMode", clusterMode);
        result.put("algorithmNotes", algorithmNotes(clusterMode));
        return result;
    }

    private List<Map<String, Object>> buildBeforeGroups(List<Point> selectedPoints, Map<Long, Point> pointById,
                                                        Object originalGroupsValue, TimeConfig timeConfig) {
        List<Map<String, Object>> originalGroups = maps(originalGroupsValue);
        List<Group> groups = new ArrayList<Group>();
        Set<Long> used = new HashSet<Long>();
        int index = 1;
        for (Map<String, Object> original : originalGroups) {
            List<Point> groupPoints = new ArrayList<Point>();
            for (Long id : longSet(original.get("facilityIds"))) {
                Point point = pointById.get(id);
                if (point != null && containsPoint(selectedPoints, point.facilityId)) {
                    groupPoints.add(point);
                    used.add(point.facilityId);
                }
            }
            if (groupPoints.isEmpty()) {
                continue;
            }
            Group group = new Group("before-" + index, textOrDefault(original.get("groupName"), "原始分堆" + index), color(index - 1));
            group.points.addAll(groupPoints);
            group.explanation.add("Excel原始分堆");
            groups.add(group);
            index++;
        }
        if (groups.isEmpty()) {
            Group group = new Group("before-1", "当前选中点位", color(0));
            group.points.addAll(selectedPoints);
            group.explanation.add("手动选择形成的原始分堆");
            groups.add(group);
        } else {
            List<Point> unmatched = new ArrayList<Point>();
            for (Point point : selectedPoints) {
                if (point.facilityId != null && !used.contains(point.facilityId)) {
                    unmatched.add(point);
                }
            }
            if (!unmatched.isEmpty()) {
                Group group = new Group("before-" + index, "未归入导入分堆", color(index - 1));
                group.points.addAll(unmatched);
                group.explanation.add("选中但未命中Excel分堆");
                groups.add(group);
            }
        }
        return groupViews(groups, "before", timeConfig);
    }

    private List<Group> cluster(List<Point> points, int target, String clusterMode) {
        List<Group> groups = initialGroups(points, target);
        for (int i = 0; i < 20; i++) {
            assignByCoordinate(points, groups);
            recompute(groups);
        }
        assignMissingCoordinates(points, groups);
        boolean geoOnly = "geo".equals(clusterMode);
        if (!geoOnly) {
            balanceByCountAndCompactness(groups);
        }
        for (Group group : groups) {
            group.explanation.add("地理聚类");
            if (!geoOnly) {
                group.explanation.add("点数均衡");
                group.explanation.add("紧凑度优化");
            }
        }
        return groups;
    }

    private List<Group> initialGroups(List<Point> points, int target) {
        List<Point> coordinatePoints = new ArrayList<Point>();
        for (Point point : points) {
            if (point.hasCoordinate()) {
                coordinatePoints.add(point);
            }
        }
        Collections.sort(coordinatePoints, new Comparator<Point>() {
            public int compare(Point a, Point b) {
                int lng = Double.compare(a.longitude, b.longitude);
                if (lng != 0) return lng;
                return Double.compare(a.latitude, b.latitude);
            }
        });
        List<Group> groups = new ArrayList<Group>();
        for (int i = 0; i < target; i++) {
            Group group = new Group("cluster-" + (i + 1), "聚类" + (i + 1), color(i));
            Point seed = coordinatePoints.isEmpty() ? null : coordinatePoints.get(Math.min(coordinatePoints.size() - 1, (int) Math.round(i * (coordinatePoints.size() - 1D) / Math.max(1, target - 1))));
            if (seed != null) {
                group.centerLongitude = seed.longitude;
                group.centerLatitude = seed.latitude;
            }
            groups.add(group);
        }
        return groups;
    }

    private void assignByCoordinate(List<Point> points, List<Group> groups) {
        for (Group group : groups) {
            group.points.clear();
        }
        for (Point point : points) {
            if (!point.hasCoordinate()) {
                continue;
            }
            nearestGroup(point, groups).points.add(point);
        }
    }

    private void recompute(List<Group> groups) {
        for (Group group : groups) {
            if (group.points.isEmpty()) {
                continue;
            }
            double longitude = 0D;
            double latitude = 0D;
            int count = 0;
            for (Point point : group.points) {
                if (point.hasCoordinate()) {
                    longitude += point.longitude;
                    latitude += point.latitude;
                    count++;
                }
            }
            if (count > 0) {
                group.centerLongitude = longitude / count;
                group.centerLatitude = latitude / count;
            }
        }
    }

    private void assignMissingCoordinates(List<Point> points, List<Group> groups) {
        for (Point point : points) {
            if (point.hasCoordinate()) {
                continue;
            }
            smallestGroup(groups).points.add(point);
        }
    }

    private void balanceByCountAndCompactness(List<Group> groups) {
        if (groups.size() <= 1) {
            return;
        }
        int totalCount = 0;
        for (Group group : groups) {
            totalCount += group.points.size();
        }
        double avgCount = totalCount * 1D / groups.size();
        int minCount = Math.max(1, (int) Math.floor(avgCount * 0.65D));
        int maxCount = Math.max(minCount, (int) Math.ceil(avgCount * 1.35D));
        for (int pass = 0; pass < 8; pass++) {
            for (Group low : groups) {
                if (low.points.size() >= minCount) {
                    continue;
                }
                Group high = largestGroup(groups);
                if (high == low || high.points.size() <= minCount) {
                    continue;
                }
                moveNearestBoundaryPoint(high, low);
            }
            for (Group high : groups) {
                if (high.points.size() <= maxCount) {
                    continue;
                }
                Group low = nearestAcceptingGroup(high, groups, maxCount);
                if (low == high) {
                    continue;
                }
                moveNearestBoundaryPoint(high, low);
            }
            improveCompactness(groups, minCount, maxCount);
            recompute(groups);
        }
    }

    private void improveCompactness(List<Group> groups, int minCount, int maxCount) {
        for (Group from : groups) {
            if (from.points.size() <= minCount) {
                continue;
            }
            Move best = null;
            for (Point point : new ArrayList<Point>(from.points)) {
                double fromDistance = distanceToGroup(point, from);
                for (Group to : groups) {
                    if (to == from || to.points.size() >= maxCount) {
                        continue;
                    }
                    double toDistance = distanceToGroup(point, to);
                    double improvement = fromDistance - toDistance;
                    if (improvement <= 0D) {
                        continue;
                    }
                    if (best == null || improvement > best.improvement) {
                        best = new Move(point, from, to, improvement);
                    }
                }
            }
            if (best != null) {
                best.from.points.remove(best.point);
                best.to.points.add(best.point);
            }
        }
    }

    private void moveNearestBoundaryPoint(Group from, Group to) {
        if (from.points.size() <= 1) {
            return;
        }
        Point best = null;
        double bestScore = Double.MAX_VALUE;
        for (Point point : from.points) {
            double score = distanceToGroup(point, to) - distanceToGroup(point, from);
            if (score < bestScore) {
                bestScore = score;
                best = point;
            }
        }
        if (best != null) {
            from.points.remove(best);
            to.points.add(best);
        }
    }

    private Group nearestGroup(Point point, List<Group> groups) {
        Group best = groups.get(0);
        double bestDistance = Double.MAX_VALUE;
        for (Group group : groups) {
            double distance = distanceToGroup(point, group);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = group;
            }
        }
        return best;
    }

    private double distanceToGroup(Point point, Group group) {
        if (!point.hasCoordinate() || group.centerLongitude == null || group.centerLatitude == null) {
            return Double.MAX_VALUE / 2D;
        }
        double x = point.longitude - group.centerLongitude;
        double y = point.latitude - group.centerLatitude;
        return x * x + y * y;
    }

    private Group smallestGroup(List<Group> groups) {
        Group best = groups.get(0);
        for (Group group : groups) {
            if (group.points.size() < best.points.size()) {
                best = group;
            }
        }
        return best;
    }

    private Group largestGroup(List<Group> groups) {
        Group best = groups.get(0);
        for (Group group : groups) {
            if (group.points.size() > best.points.size()) {
                best = group;
            }
        }
        return best;
    }

    private Group nearestAcceptingGroup(Group source, List<Group> groups, int maxCount) {
        Group best = source;
        double bestDistance = Double.MAX_VALUE;
        for (Group group : groups) {
            if (group == source || group.points.size() >= maxCount) {
                continue;
            }
            double distance = groupDistance(source, group);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = group;
            }
        }
        return best;
    }

    private double groupDistance(Group a, Group b) {
        if (a.centerLongitude == null || a.centerLatitude == null || b.centerLongitude == null || b.centerLatitude == null) {
            return Double.MAX_VALUE / 2D;
        }
        double x = a.centerLongitude - b.centerLongitude;
        double y = a.centerLatitude - b.centerLatitude;
        return x * x + y * y;
    }

    private List<Map<String, Object>> groupViews(List<Group> groups, String prefix, TimeConfig timeConfig) {
        List<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("groupId", group.groupId == null ? prefix + "-" + (i + 1) : group.groupId);
            view.put("groupName", group.groupName);
            view.put("color", group.color);
            view.put("pointCount", group.points.size());
            view.put("containerCount", round(containerCount(group.points)));
            view.put("container660Count", round(containerCountBySize(group.points, "660")));
            view.put("container240Count", round(containerCountBySize(group.points, "240")));
            view.put("estimatedVolumeLiter", round(volume(group.points)));
            view.put("estimatedWeightKg", round(weight(group.points)));
            view.put("operationMinutes", round(operationMinutes(group.points, timeConfig)));
            view.put("explanation", group.explanation);
            view.put("facilityIds", facilityIds(group.points));
            view.put("points", pointViews(group.points));
            views.add(view);
        }
        return views;
    }

    private List<Map<String, Object>> pointViews(List<Point> points) {
        List<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        for (Point point : points) {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("facilityId", point.facilityId);
            view.put("facilityName", point.facilityName);
            view.put("facilityTypeName", point.facilityTypeName);
            view.put("longitude", point.longitude);
            view.put("latitude", point.latitude);
            view.put("containerInfo", point.containerInfo);
            view.put("containerCount", round(point.containerCount));
            view.put("estimatedVolumeLiter", round(point.estimatedVolumeLiter));
            view.put("estimatedWeightKg", round(point.estimatedWeightKg));
            views.add(view);
        }
        return views;
    }

    private List<Long> facilityIds(List<Point> points) {
        List<Long> ids = new ArrayList<Long>();
        for (Point point : points) {
            if (point.facilityId != null) {
                ids.add(point.facilityId);
            }
        }
        return ids;
    }

    private int targetGroupCount(Object targetValue, List<Point> points) {
        Integer explicit = integer(targetValue);
        if (explicit != null && explicit > 0) {
            return clamp(explicit, 1, Math.max(1, points.size()));
        }
        int byCount = (int) Math.ceil(points.size() / 120D);
        int bySpread = estimateBySpread(points);
        int target = Math.max(1, Math.max(byCount, bySpread));
        return clamp(target, 1, Math.min(Math.max(1, points.size()), 12));
    }

    private int estimateBySpread(List<Point> points) {
        double minLongitude = Double.MAX_VALUE;
        double maxLongitude = -Double.MAX_VALUE;
        double minLatitude = Double.MAX_VALUE;
        double maxLatitude = -Double.MAX_VALUE;
        int coordinateCount = 0;
        for (Point point : points) {
            if (!point.hasCoordinate()) {
                continue;
            }
            minLongitude = Math.min(minLongitude, point.longitude);
            maxLongitude = Math.max(maxLongitude, point.longitude);
            minLatitude = Math.min(minLatitude, point.latitude);
            maxLatitude = Math.max(maxLatitude, point.latitude);
            coordinateCount++;
        }
        if (coordinateCount < 2) {
            return 1;
        }
        double longitudeSpan = Math.max(0D, maxLongitude - minLongitude);
        double latitudeSpan = Math.max(0D, maxLatitude - minLatitude);
        double spread = Math.sqrt(longitudeSpan * longitudeSpan + latitudeSpan * latitudeSpan);
        if (spread < 0.04D) {
            return 1;
        }
        if (spread < 0.08D) {
            return 2;
        }
        if (spread < 0.14D) {
            return 3;
        }
        return 4;
    }

    private String clusterMode(Object value) {
        String mode = text(value);
        if ("geo".equalsIgnoreCase(mode) || "geographic".equalsIgnoreCase(mode)) {
            return "geo";
        }
        return "balanced";
    }

    private List<String> algorithmNotes(String clusterMode) {
        if ("geo".equals(clusterMode)) {
            return Arrays.asList("纯地理聚类");
        }
        return Arrays.asList("地理聚类", "点数均衡", "紧凑度优化");
    }

    private TimeConfig timeConfig(Object value) {
        TimeConfig config = new TimeConfig();
        Map<String, Object> map = map(value);
        Double seconds = decimal(map.get("secondsPerContainer"));
        Double minutes = decimal(map.get("minutesPerPoint"));
        Double hours = decimal(map.get("workHours"));
        if (seconds != null && seconds > 0D) config.secondsPerContainer = seconds;
        if (minutes != null && minutes >= 0D) config.minutesPerPoint = minutes;
        if (hours != null && hours > 0D) config.workHours = hours;
        return config;
    }

    private double operationMinutes(List<Point> points, TimeConfig timeConfig) {
        double handlingUnits = 0D;
        for (Point point : points) {
            handlingUnits += point.bucketHandlingUnits();
        }
        return handlingUnits * timeConfig.secondsPerContainer / 60D + points.size() * timeConfig.minutesPerPoint;
    }

    private double containerCount(List<Point> points) {
        double total = 0D;
        for (Point point : points) total += point.containerCount;
        return total;
    }

    private double containerCountBySize(List<Point> points, String size) {
        double total = 0D;
        for (Point point : points) total += point.containerCountBySize(size);
        return total;
    }

    private double volume(List<Point> points) {
        double total = 0D;
        for (Point point : points) total += point.estimatedVolumeLiter;
        return total;
    }

    private double weight(List<Point> points) {
        double total = 0D;
        for (Point point : points) total += point.estimatedWeightKg;
        return total;
    }

    private boolean containsPoint(List<Point> points, Long facilityId) {
        if (facilityId == null) return false;
        for (Point point : points) {
            if (facilityId.equals(point.facilityId)) return true;
        }
        return false;
    }

    private Point toPoint(Map<String, Object> row) {
        Point point = new Point();
        point.facilityId = longValue(row.get("facilityId"));
        point.facilityName = text(row.get("facilityName"));
        point.facilityTypeName = text(row.get("facilityTypeName"));
        point.longitude = decimal(row.get("longitude"));
        point.latitude = decimal(row.get("latitude"));
        point.containerInfo = text(row.get("containerInfo"));
        point.containerCount = valueOrZero(decimal(row.get("containerCount")));
        point.estimatedVolumeLiter = valueOrZero(decimal(row.get("estimatedVolumeLiter")));
        point.estimatedWeightKg = valueOrZero(decimal(row.get("estimatedWeightKg")));
        return point;
    }

    private String color(int index) {
        return COLORS.get(Math.abs(index) % COLORS.size());
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private Double round(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private double valueOrZero(Double value) {
        return value == null ? 0D : value;
    }

    private String textOrDefault(Object value, String fallback) {
        String text = text(value);
        return text.isEmpty() ? fallback : text;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Long longValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Long.valueOf(text);
    }

    private Integer integer(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Integer.valueOf(text);
    }

    private Double decimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal) return ((BigDecimal) value).doubleValue();
        if (value instanceof Number) return ((Number) value).doubleValue();
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Double.valueOf(text);
    }

    private Set<Long> longSet(Object value) {
        Set<Long> result = new HashSet<Long>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                Long parsed = longValue(item);
                if (parsed != null) result.add(parsed);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : Collections.<Map<String, Object>>emptyList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    private static class Group {
        private final String groupId;
        private final String groupName;
        private final String color;
        private final List<Point> points = new ArrayList<Point>();
        private final List<String> explanation = new ArrayList<String>();
        private Double centerLongitude;
        private Double centerLatitude;

        private Group(String groupId, String groupName, String color) {
            this.groupId = groupId;
            this.groupName = groupName;
            this.color = color;
        }
    }

    private static class Point {
        private Long facilityId;
        private String facilityName;
        private String facilityTypeName;
        private Double longitude;
        private Double latitude;
        private String containerInfo;
        private double containerCount;
        private double estimatedVolumeLiter;
        private double estimatedWeightKg;

        private boolean hasCoordinate() {
            return longitude != null && latitude != null;
        }

        private double bucketHandlingUnits() {
            if (containerInfo == null || containerInfo.isEmpty()) {
                return containerCount;
            }
            double total = 0D;
            boolean parsed = false;
            String[] parts = containerInfo.split(",");
            for (String part : parts) {
                String[] pair = part.split("/");
                if (pair.length != 2) {
                    continue;
                }
                try {
                    double size = Double.valueOf(pair[0].trim());
                    double count = Double.valueOf(pair[1].trim());
                    if (count < 0D) {
                        continue;
                    }
                    parsed = true;
                    total += Math.abs(size - 240D) < 0.001D ? Math.ceil(count / 2D) : count;
                } catch (NumberFormatException ignored) {
                    // Ignore malformed bucket specification.
                }
            }
            return parsed ? total : containerCount;
        }

        private double containerCountBySize(String size) {
            if (containerInfo == null || containerInfo.isEmpty()) {
                return 0D;
            }
            double total = 0D;
            String[] parts = containerInfo.split(",");
            for (String part : parts) {
                String[] pair = part.split("/");
                if (pair.length != 2 || !size.equals(pair[0].trim())) {
                    continue;
                }
                try {
                    total += Double.valueOf(pair[1].trim());
                } catch (NumberFormatException ignored) {
                    // 忽略脏桶数格式，前端仍展示原始containerInfo。
                }
            }
            return total;
        }
    }

    private static class Move {
        private final Point point;
        private final Group from;
        private final Group to;
        private final double improvement;

        private Move(Point point, Group from, Group to, double improvement) {
            this.point = point;
            this.from = from;
            this.to = to;
            this.improvement = improvement;
        }
    }
    private static class TimeConfig {
        private double secondsPerContainer = 35D;
        private double minutesPerPoint = 3D;
        private double workHours = 8D;

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("secondsPerContainer", secondsPerContainer);
            map.put("minutesPerPoint", minutesPerPoint);
            map.put("workHours", workHours);
            return map;
        }
    }
}


