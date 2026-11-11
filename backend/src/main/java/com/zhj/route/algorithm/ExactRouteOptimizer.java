package com.zhj.route.algorithm;

import java.util.concurrent.CancellationException;

/** Exhaustive directed-distance search with fixed endpoints and no crossing of internal anchors. */
public class ExactRouteOptimizer {
    public Result optimize(double[][] distances, boolean[] fixed) {
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
        Search search = new Search(distances, fixed);
        search.visit(1, 0D);
        return new Result(search.bestOrder, search.bestDistance, search.evaluated);
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

        Search(double[][] distances, boolean[] fixed) {
            this.distances = distances;
            this.fixed = fixed;
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

        void visit(int position, double distance) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("全排列已取消");
            if (position == order.length) {
                evaluated++;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestOrder = order.clone();
                }
                return;
            }
            if (fixed[position]) {
                order[position] = position;
                visit(position + 1, distance + distances[order[position - 1]][position]);
                return;
            }
            for (int candidate = 1; candidate < order.length - 1; candidate++) {
                if (fixed[candidate] || used[candidate] || groups[candidate] != groups[position]) continue;
                order[position] = candidate;
                used[candidate] = true;
                visit(position + 1, distance + distances[order[position - 1]][candidate]);
                used[candidate] = false;
            }
        }
    }

    public static class Result {
        private final int[] order;
        private final double distance;
        private final long evaluated;

        Result(int[] order, double distance, long evaluated) {
            this.order = order;
            this.distance = distance;
            this.evaluated = evaluated;
        }

        public int[] getOrder() { return order.clone(); }
        public double getDistance() { return distance; }
        public long getEvaluated() { return evaluated; }
    }
}
