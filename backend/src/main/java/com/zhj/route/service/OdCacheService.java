package com.zhj.route.service;

import com.zhj.route.algorithm.RoutePoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class OdCacheService {
    private static final Logger log = LoggerFactory.getLogger(OdCacheService.class);
    private final RouteMapPathService routeMapPathService;
    private final ExecutorService executor = Executors.newFixedThreadPool(1);
    private final Map<String, OdCacheTask> tasks = new ConcurrentHashMap<String, OdCacheTask>();

    public OdCacheService(RouteMapPathService routeMapPathService) { this.routeMapPathService = routeMapPathService; }

    public Map<String, Object> start(Map<String, Object> request) {
        List<RoutePoint> points = toPoints(request == null ? null : request.get("points"));
        if (points.size() < 2) throw new IllegalArgumentException("至少需要选择两个有坐标的点位、停车场或设施点");
        OdCacheTask task = new OdCacheTask(UUID.randomUUID().toString(), points);
        tasks.put(task.taskId, task);
        task.future = executor.submit(new Runnable() { @Override public void run() { execute(task); } });
        return task.view(false);
    }

    public Map<String, Object> get(String taskId) { OdCacheTask task = tasks.get(taskId); return task == null ? missing(taskId) : task.view(true); }

    public Map<String, Object> cancel(String taskId) {
        OdCacheTask task = tasks.get(taskId);
        if (task == null) return missing(taskId);
        task.cancelled = true;
        if (task.future != null) task.future.cancel(true);
        task.status = "CANCELLED"; task.phase = "CANCELLED"; task.message = "正在停止，已完成的缓存仍然保留";
        return task.view(false);
    }

    private void execute(OdCacheTask task) {
        long started = System.currentTimeMillis();
        try {
            task.status = "RUNNING"; task.phase = "CHECK_CACHE"; task.message = "正在查询已缓存道路 OD";
            Map<String, RouteMapPathService.ResolvedPath> cached = routeMapPathService.preloadCachedPaths(task.points);
            task.cachedPairs = cached.size(); task.completedPairs = task.cachedPairs; task.totalPairs = task.points.size() * (task.points.size() - 1);
            task.message = "已找到 " + task.cachedPairs + " 对缓存，待计算 " + Math.max(0, task.totalPairs - task.cachedPairs) + " 对";
            if (task.cancelled) return;
            task.phase = "CALCULATE"; task.message = "正在调用百度计算缺失道路 OD";
            for (RoutePoint from : task.points) for (RoutePoint to : task.points) {
                if (from.getFacilityId().equals(to.getFacilityId())) continue;
                if (task.cancelled || Thread.currentThread().isInterrupted()) return;
                String key = routeMapPathService.pathKey(from, to);
                if (cached.containsKey(key)) continue;
                task.currentPair = label(from) + " -> " + label(to);
                RouteMapPathService.ResolvedPath path = routeMapPathService.resolveWithoutCache(from, to);
                if (path != null && path.getDistanceMeters() != null && ("BAIDU_ONLINE".equals(path.getSource()) || path.getSource().startsWith("OD"))) task.successPairs++;
                else { task.failedPairs++; if (task.failures.size() < 20) task.failures.add(task.currentPair + "：未得到道路结果"); }
                task.completedPairs++; task.message = "已完成 " + task.completedPairs + "/" + task.totalPairs + " 对";
            }
            task.phase = "DONE"; task.status = task.failedPairs == 0 ? "DONE" : "PARTIAL";
            task.message = task.status.equals("DONE") ? "缓存计算完成，结果已写入 ljszy_odpair_pool" : "计算完成，但有 " + task.failedPairs + " 对未成功写入，请检查百度配置或坐标";
        } catch (Exception e) {
            if (task.cancelled || Thread.currentThread().isInterrupted()) { task.status = "CANCELLED"; task.phase = "CANCELLED"; task.message = "已停止，已完成的缓存仍然保留"; }
            else { task.status = "FAILED"; task.phase = "FAILED"; task.message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); log.warn("OD cache task failed: taskId={}, {}", task.taskId, task.message, e); }
        } finally { task.elapsedMs = System.currentTimeMillis() - started; task.finishedAt = System.currentTimeMillis(); }
    }

    private List<RoutePoint> toPoints(Object value) {
        List<RoutePoint> result = new ArrayList<RoutePoint>(); Set<Long> ids = new HashSet<Long>();
        if (!(value instanceof List)) return result;
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) continue; Map<?, ?> row = (Map<?, ?>) item;
            Long id = longValue(row.get("facilityId")); Double longitude = doubleValue(row.get("longitude")); Double latitude = doubleValue(row.get("latitude"));
            if (id == null || id <= 0 || longitude == null || latitude == null || !ids.add(id)) continue;
            result.add(new RoutePoint(id, text(row.get("facilityName")), longitude, latitude, result.size() + 1, doubleValue(row.get("estimatedVolumeLiter")), doubleValue(row.get("estimatedWeightKg")), text(row.get("containerInfo")), doubleValue(row.get("containerCount")), doubleValue(row.get("litersPerTon")), text(row.get("weightSource"))));
        }
        return result;
    }
    private Map<String, Object> missing(String taskId) { Map<String, Object> result = new HashMap<String, Object>(); result.put("taskId", taskId); result.put("status", "NOT_FOUND"); result.put("message", "任务不存在或服务已重启"); return result; }
    private String label(RoutePoint point) { return String.valueOf(point.getFacilityId()) + "/" + (point.getFacilityName() == null ? "" : point.getFacilityName()); }
    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static Long longValue(Object value) { try { return value == null ? null : Long.valueOf(String.valueOf(value)); } catch (Exception e) { return null; } }
    private static Double doubleValue(Object value) { try { return value == null ? null : Double.valueOf(String.valueOf(value)); } catch (Exception e) { return null; } }

    private static class OdCacheTask {
        final String taskId; final List<RoutePoint> points; volatile String status = "QUEUED"; volatile String phase = "PREPARE"; volatile String message = "等待开始";
        volatile int totalPairs; volatile int cachedPairs; volatile int completedPairs; volatile int successPairs; volatile int failedPairs; volatile long elapsedMs; volatile long startedAt = System.currentTimeMillis(); volatile long finishedAt; volatile String currentPair = ""; volatile boolean cancelled; volatile Future<?> future; final List<String> failures = new ArrayList<String>();
        OdCacheTask(String taskId, List<RoutePoint> points) { this.taskId = taskId; this.points = points; }
        Map<String, Object> view(boolean detail) {
            Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("taskId", taskId); result.put("status", status); result.put("phase", phase); result.put("message", message); result.put("totalPoints", points.size()); result.put("totalPairs", totalPairs); result.put("cachedPairs", cachedPairs); result.put("pendingPairs", Math.max(0, totalPairs - cachedPairs)); result.put("completedPairs", completedPairs); result.put("successPairs", successPairs); result.put("failedPairs", failedPairs); result.put("percent", totalPairs == 0 ? 0 : Math.min(100, Math.round(completedPairs * 10000f / totalPairs) / 100f)); result.put("currentPair", currentPair); result.put("elapsedMs", elapsedMs); result.put("startedAt", startedAt); result.put("finishedAt", finishedAt); if (detail) result.put("failures", new ArrayList<String>(failures)); return result;
        }
    }
}