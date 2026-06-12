package com.zhj.route.algorithm;

import java.util.List;

public class RouteOptimizationResult {
    private final List<RoutePoint> originalPoints;
    private final List<RoutePoint> optimizedPoints;
    private final double originalDistance;
    private final double optimizedDistance;

    public RouteOptimizationResult(
            List<RoutePoint> originalPoints,
            List<RoutePoint> optimizedPoints,
            double originalDistance,
            double optimizedDistance) {
        this.originalPoints = originalPoints;
        this.optimizedPoints = optimizedPoints;
        this.originalDistance = originalDistance;
        this.optimizedDistance = optimizedDistance;
    }

    public List<RoutePoint> getOriginalPoints() {
        return originalPoints;
    }

    public List<RoutePoint> getOptimizedPoints() {
        return optimizedPoints;
    }

    public double getOriginalDistance() {
        return originalDistance;
    }

    public double getOptimizedDistance() {
        return optimizedDistance;
    }

    public double getSavedDistance() {
        return Math.max(0D, originalDistance - optimizedDistance);
    }

    public double getSavedRate() {
        if (originalDistance <= 0D) {
            return 0D;
        }
        return getSavedDistance() / originalDistance;
    }
}
