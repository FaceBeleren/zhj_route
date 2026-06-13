package com.zhj.route.service;

import com.zhj.route.algorithm.RouteOptimizationResult;
import com.zhj.route.algorithm.RoutePoint;
import com.zhj.route.algorithm.SingleRouteOptimizer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RouteOptimizeService {
    private final RouteQueryService routeQueryService;
    private final SingleRouteOptimizer singleRouteOptimizer = new SingleRouteOptimizer();

    public RouteOptimizeService(RouteQueryService routeQueryService) {
        this.routeQueryService = routeQueryService;
    }

    public Map<String, Object> optimizePreview(Map<String, Object> request) {
        Object routeIdValue = request.get("routeId");
        if (routeIdValue == null) {
            throw new IllegalArgumentException("routeId is required");
        }

        Long routeId = Long.valueOf(String.valueOf(routeIdValue));
        List<Map<String, Object>> planRows = routeQueryService.routePlanPoints(routeId);
        List<RoutePoint> points = toRoutePoints(planRows);
        RouteOptimizationResult optimization = singleRouteOptimizer.optimize(points);

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("routeId", routeId);
        result.put("status", points.size() < 3 ? "UNCHANGED" : "DONE");
        result.put("message", buildMessage(points));
        result.put("pointCount", points.size());
        result.put("originalSequence", sequence(optimization.getOriginalPoints()));
        result.put("optimizedSequence", sequence(optimization.getOptimizedPoints()));
        result.put("originalDistance", round(optimization.getOriginalDistance()));
        result.put("optimizedDistance", round(optimization.getOptimizedDistance()));
        result.put("savedDistance", round(optimization.getSavedDistance()));
        result.put("savedRate", round(optimization.getSavedRate()));
        result.put("estimatedWeightKg", round(sumEstimatedWeight(points)));
        result.put("estimatedVolumeLiter", round(sumEstimatedVolume(points)));
        result.put("ratedCapacityKg", round(ratedCapacityKg(request)));
        result.put("targetLoadRate", round(targetLoadRate(request)));
        result.put("targetLoadWeightKg", round(ratedCapacityKg(request) * targetLoadRate(request)));
        result.put("loadRate", loadRate(points, request));
        result.put("points", pointViews(optimization.getOptimizedPoints()));
        result.put("segments", segmentViews(optimization.getOptimizedPoints(), speedKmh(request)));
        result.put("polyline", polyline(optimization.getOptimizedPoints()));
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
                    toDouble(row.get("litersPerTon")),
                    row.get("weightSource") == null ? null : String.valueOf(row.get("weightSource"))));
        }
        return points;
    }

    private String buildMessage(List<RoutePoint> points) {
        if (points.isEmpty()) {
            return "该路线没有规划点位，无法生成优化预览。";
        }
        if (points.size() < 3) {
            return "该路线点位不足 3 个，保持原规划顺序。";
        }
        return "已使用单路线全路径插入算法生成优化预览。当前版本使用点位直线距离，尚未接入真实道路 OD。";
    }

    private List<Long> sequence(List<RoutePoint> points) {
        List<Long> sequence = new ArrayList<Long>();
        for (RoutePoint point : points) {
            sequence.add(point.getFacilityId());
        }
        return sequence;
    }

    private List<Map<String, Object>> pointViews(List<RoutePoint> points) {
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
            view.put("litersPerTon", point.getLitersPerTon());
            view.put("weightSource", point.getWeightSource());
            view.put("role", i == 0 ? "START" : (i == points.size() - 1 ? "END" : "MIDDLE"));
            views.add(view);
        }
        return views;
    }

    private List<Map<String, Object>> segmentViews(List<RoutePoint> points, double speedKmh) {
        List<Map<String, Object>> segments = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < points.size() - 1; i++) {
            RoutePoint from = points.get(i);
            RoutePoint to = points.get(i + 1);
            double distance = singleRouteOptimizer.distance(from, to);
            Map<String, Object> segment = new HashMap<String, Object>();
            segment.put("order", i + 1);
            segment.put("fromFacilityId", from.getFacilityId());
            segment.put("fromFacilityName", from.getFacilityName());
            segment.put("toFacilityId", to.getFacilityId());
            segment.put("toFacilityName", to.getFacilityName());
            segment.put("distance", round(distance));
            segment.put("durationMinutes", round(minutes(distance, speedKmh)));
            segment.put("path", path(from, to));
            segments.add(segment);
        }
        return segments;
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

    private List<Map<String, Object>> path(RoutePoint from, RoutePoint to) {
        List<Map<String, Object>> path = new ArrayList<Map<String, Object>>();
        if (from.hasCoordinate()) {
            path.add(coordinate(from));
        }
        if (to.hasCoordinate()) {
            path.add(coordinate(to));
        }
        return path;
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

    private double speedKmh(Map<String, Object> request) {
        Object value = request.get("speedKmh");
        if (value == null) {
            return 20D;
        }
        Double speed = toDouble(value);
        if (speed == null || speed <= 0D) {
            return 20D;
        }
        return speed;
    }

    private double ratedCapacityKg(Map<String, Object> request) {
        Double value = toDouble(request.get("ratedCapacityKg"));
        return value == null || value <= 0D ? 0D : value;
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

    private double valueOrZero(Double value) {
        return value == null ? 0D : value;
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
}
