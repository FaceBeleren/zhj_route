package com.zhj.route.controller;

import com.zhj.route.service.RouteOptimizeService;
import com.zhj.route.service.RouteExportService;
import com.zhj.route.service.RouteClusterService;
import com.zhj.route.service.TripReorderService;
import com.zhj.route.service.SmallWindowPlanningService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/optimize")
public class RouteOptimizeController {
    private final RouteOptimizeService routeOptimizeService;
    private final RouteExportService routeExportService;
    private final RouteClusterService routeClusterService;
    private final TripReorderService tripReorderService;
    private final SmallWindowPlanningService smallWindowPlanningService;

    public RouteOptimizeController(RouteOptimizeService routeOptimizeService, RouteExportService routeExportService, RouteClusterService routeClusterService, TripReorderService tripReorderService, SmallWindowPlanningService smallWindowPlanningService) {
        this.routeOptimizeService = routeOptimizeService;
        this.routeExportService = routeExportService;
        this.routeClusterService = routeClusterService;
        this.tripReorderService = tripReorderService;
        this.smallWindowPlanningService = smallWindowPlanningService;
    }

    @PostMapping("/preview")
    public Map<String, Object> optimizePreview(@RequestBody Map<String, Object> request) {
        return routeOptimizeService.optimizePreview(request);
    }

    @PostMapping("/multi-preview")
    public Map<String, Object> optimizeMultiPreview(@RequestBody Map<String, Object> request) {
        return routeOptimizeService.optimizeMultiPreview(request);
    }

    @PostMapping("/multi-preview/tasks")
    public Map<String, Object> startOptimizeMultiPreviewTask(@RequestBody Map<String, Object> request) {
        return routeOptimizeService.startMultiPreviewTask(request);
    }

    @GetMapping("/multi-preview/tasks/{taskId}")
    public Map<String, Object> optimizeMultiPreviewTask(@PathVariable String taskId) {
        return routeOptimizeService.multiPreviewTask(taskId);
    }

    @PostMapping("/multi-preview/tasks/{taskId}/cancel")
    public Map<String, Object> cancelOptimizeMultiPreviewTask(@PathVariable String taskId) {
        return routeOptimizeService.cancelMultiPreviewTask(taskId);
    }

    @PostMapping("/reorder-trips")
    public Map<String, Object> reorderTrips(@RequestBody Map<String, Object> request) {
        return tripReorderService.reorder(request);
    }

    @PostMapping("/repair-time-windows")
    public Map<String, Object> repairTimeWindows(@RequestBody Map<String, Object> request) {
        return tripReorderService.repairPoints(request);
    }

    @PostMapping("/cluster-preview")
    public Map<String, Object> clusterPreview(@RequestBody Map<String, Object> request) {
        return routeClusterService.preview(request);
    }

    @PostMapping("/cluster-export")
    public ResponseEntity<byte[]> exportClusterPreview(@RequestBody Map<String, Object> request) {
        byte[] bytes = routeExportService.exportClusterPreview(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=route-clusters.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @PostMapping("/route-segments")
    public Map<String, Object> routeSegmentsPreview(@RequestBody Map<String, Object> request) {
        return routeOptimizeService.routeSegmentsPreview(request);
    }

    @PostMapping("/exact-route-order")
    public Map<String, Object> exactRouteOrder(@RequestBody Map<String, Object> request) {
        return routeOptimizeService.exactRouteOrder(request);
    }

    @PostMapping("/selected-points-once")
    public Map<String,Object> adjustSelectedOnce(@RequestBody Map<String,Object> request) { return routeOptimizeService.adjustSelectedOnce(request); }

    @PostMapping("/small-window/tasks")
    public Map<String,Object> startSmallWindow(@RequestBody Map<String,Object> request) { return smallWindowPlanningService.start(request); }

    @GetMapping("/small-window/tasks/{taskId}")
    public Map<String,Object> smallWindowStatus(@PathVariable String taskId) { return smallWindowPlanningService.get(taskId); }

    @PostMapping("/small-window/tasks/{taskId}/cancel")
    public Map<String,Object> cancelSmallWindow(@PathVariable String taskId) { return smallWindowPlanningService.cancel(taskId); }

    @PostMapping("/small-window/tasks/{taskId}/insert")
    public Map<String,Object> insertSmallWindow(@PathVariable String taskId) { return smallWindowPlanningService.insert(taskId); }

    @PostMapping("/small-window/tasks/{taskId}/base-routes")
    public Map<String,Object> updateSmallWindowBaseRoutes(@PathVariable String taskId, @RequestBody Map<String,Object> request) {
        return smallWindowPlanningService.updateBaseRoutes(taskId, request);
    }

    @PostMapping("/multi-export")
    public ResponseEntity<byte[]> exportMultiPreview(@RequestBody Map<String, Object> request) {
        byte[] bytes = routeExportService.exportMultiRoutes(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=multi-routes.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @PostMapping("/assignment-export")
    public ResponseEntity<byte[]> exportAssignmentVersions(@RequestBody Map<String, Object> request) {
        byte[] bytes = routeExportService.exportAssignmentVersions(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=assignment-routes.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @PostMapping("/assignment-schedule-export")
    public ResponseEntity<byte[]> exportAssignmentSchedule(@RequestBody Map<String, Object> request) {
        byte[] bytes = routeExportService.exportAssignmentSchedule(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=assignment-schedule.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}
