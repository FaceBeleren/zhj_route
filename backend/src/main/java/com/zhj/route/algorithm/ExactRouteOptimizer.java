package com.zhj.route.algorithm;

import java.util.concurrent.CancellationException;
import java.util.function.DoublePredicate;

/** Exhaustive directed-distance search with fixed endpoints and no crossing of internal anchors. */
public class ExactRouteOptimizer {
    public Result optimize(double[][] distances, boolean[] fixed) {
        return optimize(distances, fixed, null);
    }

    public Result optimize(double[][] distances, boolean[] fixed, Timing timing) {
        int size = fixed.length;
        if (size < 2 || distances.length != size || !fixed[0] || !fixed[size - 1]) {
            throw new IllegalArgumentException("全排列需要固定起终点及完整距离矩阵");
        }
        int movable = 0;
        for (int i = 0; i < size; i++) {
            if (!fixed[i]) movable++;
            if (distances[i].length != size) throw new IllegalArgumentException("距离矩阵不完整");
            for (double distance : distances[i]) {
                if (!Double.isFinite(distance) || distance < 0) throw new IllegalArgumentException("道路距离无效");
            }
        }
        if (movable > 10) throw new IllegalArgumentException("全排列最多支持10个可排序收运点");
        Search search = new Search(distances, fixed, timing);
        search.visit(1, 0D, timing == null ? 0D : timing.startMinutes + timing.operationMinutes[0], timing != null);
        return new Result(search.bestOrder, search.bestDistance, search.evaluated, search.feasibleOrder);
    }

    public static class Timing {
        private final double[][] travelMinutes;
        private final double[] operationMinutes;
        private final double startMinutes;
        private final DoublePredicate[] allowedArrival;
        private final DoublePredicate allowedFinish;

        public Timing(double[][] travelMinutes, double[] operationMinutes, double startMinutes,
                      DoublePredicate[] allowedArrival, DoublePredicate allowedFinish) {
            this.travelMinutes = travelMinutes;
            this.operationMinutes = operationMinutes;
            this.startMinutes = startMinutes;
            this.allowedArrival = allowedArrival;
            this.allowedFinish = allowedFinish;
        }
    }

    private static class Search {
        private final double[][] distances;
        private final boolean[] fixed;
        private final boolean[] used;
        private final int[] groups;
        private final int[] order;
        private int[] bestOrder;
        private double bestDistance;
        private long evaluated;
        private final Timing timing;
        private int[] feasibleOrder;
        private double feasibleDistance = Double.POSITIVE_INFINITY;

        Search(double[][] distances, boolean[] fixed, Timing timing) {
            this.distances = distances;
            this.fixed = fixed;
            this.timing = timing;
            this.used = new boolean[fixed.length];
            this.groups = new int[fixed.length];
            this.order = new int[fixed.length];
            int group = 0;
            for (int i = 0; i < fixed.length; i++) {
                if (fixed[i]) group++;
                groups[i] = group;
                order[i] = i;
                if (i > 0) bestDistance += distances[i - 1][i];
            }
            bestOrder = order.clone();
        }

        void visit(int position, double distance, double clock, boolean feasible) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("全排列已取消");
            if (position == order.length) {
                evaluated++;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestOrder = order.clone();
                }
                if (feasible && distance < feasibleDistance && timing.allowedFinish.test(clock)) {
                    feasibleDistance = distance;
                    feasibleOrder = order.clone();
                }
                return;
            }
            if (fixed[position]) {
                append(position, position, distance, clock, feasible);
                return;
            }
            for (int candidate = 1; candidate < order.length - 1; candidate++) {
                if (fixed[candidate] || used[candidate] || groups[candidate] != groups[position]) continue;
                order[position] = candidate;
                used[candidate] = true;
                append(position, candidate, distance, clock, feasible);
                used[candidate] = false;
            }
        }

        private void append(int position, int point, double distance, double clock, boolean feasible) {
            int previous = order[position - 1];
            order[position] = point;
            if (feasible) {
                double arrival = clock + timing.travelMinutes[previous][point];
                DoublePredicate allowed = timing.allowedArrival[point];
                feasible = allowed == null || allowed.test(arrival);
                clock = arrival + timing.operationMinutes[point];
            }
            visit(position + 1, distance + distances[previous][point], clock, feasible);
        }
    }

    public static class Result {
        private final int[] order;
        private final double distance;
        private final long evaluated;

        private final int[] feasibleOrder;

        Result(int[] order, double distance, long evaluated, int[] feasibleOrder) {
            this.feasibleOrder = feasibleOrder;
            this.order = order;
            this.distance = distance;
            this.evaluated = evaluated;
        }

        public int[] getOrder() { return order.clone(); }
        public double getDistance() { return distance; }
        public long getEvaluated() { return evaluated; }
        public int[] getFeasibleOrder() { return feasibleOrder == null ? null : feasibleOrder.clone(); }
    }
}
