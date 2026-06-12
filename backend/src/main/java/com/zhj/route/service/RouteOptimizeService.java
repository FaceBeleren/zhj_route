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
        result.put("points", pointViews(optimization.getOptimizedPoints()));
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
                    toInteger(row.get("orderNum"))));
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
            view.put("role", i == 0 ? "START" : (i == points.size() - 1 ? "END" : "MIDDLE"));
            views.add(view);
        }
        return views;
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
