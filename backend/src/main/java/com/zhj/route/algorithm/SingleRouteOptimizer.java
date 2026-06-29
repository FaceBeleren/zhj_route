package com.zhj.route.algorithm;

import java.util.ArrayList;
import java.util.List;

public class SingleRouteOptimizer {
    private static final double EARTH_RADIUS_METERS = 6371000D;

    public RouteOptimizationResult optimize(List<RoutePoint> points) {
        return optimize(points, new DistanceCalculator() {
            @Override
            public double distance(RoutePoint a, RoutePoint b) {
                return SingleRouteOptimizer.this.distance(a, b);
            }
        });
    }

    public RouteOptimizationResult optimize(List<RoutePoint> points, DistanceCalculator distanceCalculator) {
        List<RoutePoint> original = new ArrayList<RoutePoint>(points);
        if (points.size() < 3) {
            double originalDistance = totalDistance(original, distanceCalculator);
            return new RouteOptimizationResult(original, original, originalDistance, originalDistance);
        }

        List<RoutePoint> optimized = new ArrayList<RoutePoint>();
        optimized.add(points.get(0));
        optimized.add(points.get(points.size() - 1));

        List<RoutePoint> remaining = new ArrayList<RoutePoint>(points.subList(1, points.size() - 1));
        while (!remaining.isEmpty()) {
            InsertChoice best = null;
            for (RoutePoint candidate : remaining) {
                for (int segment = 0; segment < optimized.size() - 1; segment++) {
                    RoutePoint previous = optimized.get(segment);
                    RoutePoint next = optimized.get(segment + 1);
                    double increase = distanceCalculator.distance(previous, candidate)
                            + distanceCalculator.distance(candidate, next)
                            - distanceCalculator.distance(previous, next);
                    if (best == null || increase < best.increase) {
                        best = new InsertChoice(candidate, segment + 1, increase);
                    }
                }
            }
            optimized.add(best.insertIndex, best.point);
            remaining.remove(best.point);
        }

        return new RouteOptimizationResult(
                original,
                optimized,
                totalDistance(original, distanceCalculator),
                totalDistance(optimized, distanceCalculator));
    }

    public double totalDistance(List<RoutePoint> points) {
        return totalDistance(points, new DistanceCalculator() {
            @Override
            public double distance(RoutePoint a, RoutePoint b) {
                return SingleRouteOptimizer.this.distance(a, b);
            }
        });
    }

    public double totalDistance(List<RoutePoint> points, DistanceCalculator distanceCalculator) {
        double total = 0D;
        for (int i = 0; i < points.size() - 1; i++) {
            total += distanceCalculator.distance(points.get(i), points.get(i + 1));
        }
        return total;
    }

    public double distance(RoutePoint a, RoutePoint b) {
        if (!a.hasCoordinate() || !b.hasCoordinate()) {
            return 0D;
        }
        double lat1 = Math.toRadians(a.getLatitude());
        double lat2 = Math.toRadians(b.getLatitude());
        double deltaLat = Math.toRadians(b.getLatitude() - a.getLatitude());
        double deltaLng = Math.toRadians(b.getLongitude() - a.getLongitude());
        double h = Math.sin(deltaLat / 2D) * Math.sin(deltaLat / 2D)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLng / 2D) * Math.sin(deltaLng / 2D);
        return 2D * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(h), Math.sqrt(1D - h));
    }

    public interface DistanceCalculator {
        double distance(RoutePoint a, RoutePoint b);
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
