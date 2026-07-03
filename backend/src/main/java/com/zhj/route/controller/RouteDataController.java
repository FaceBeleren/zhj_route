package com.zhj.route.controller;

import com.zhj.route.service.RouteQueryService;
import com.zhj.route.service.RouteConformanceService;
import com.zhj.route.service.FacilityImportService;
import com.zhj.route.service.RouteMapPathService;
import com.zhj.route.service.RouteOptimizeService;
import com.zhj.route.service.RouteExportService;
import com.zhj.route.service.RouteClusterService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RouteDataController {
    private final RouteQueryService routeQueryService;
    private final RouteConformanceService routeConformanceService;
    private final RouteOptimizeService routeOptimizeService;
    private final RouteMapPathService routeMapPathService;
    private final RouteExportService routeExportService;
    private final FacilityImportService facilityImportService;
    private final RouteClusterService routeClusterService;

    public RouteDataController(
            RouteQueryService routeQueryService,
            RouteConformanceService routeConformanceService,
            RouteOptimizeService routeOptimizeService,
            RouteMapPathService routeMapPathService,
            RouteExportService routeExportService,
            FacilityImportService facilityImportService,
            RouteClusterService routeClusterService) {
        this.routeQueryService = routeQueryService;
        this.routeConformanceService = routeConformanceService;
        this.routeOptimizeService = routeOptimizeService;
        this.routeMapPathService = routeMapPathService;
        this.routeExportService = routeExportService;
        this.facilityImportService = facilityImportService;
        this.routeClusterService = routeClusterService;
    }

    @GetMapping("/companies")
    public List<Map<String, Object>> companies() {
        return routeQueryService.companies();
    }

    @GetMapping("/companies/{unitId}/routes")
    public List<Map<String, Object>> routes(
            @PathVariable String unitId,
            @RequestParam(required = false) Integer dataType) {
        return routeQueryService.routes(unitId, dataType);
    }

    @GetMapping("/companies/{unitId}/facilities")
    public List<Map<String, Object>> companyFacilityPoints(@PathVariable String unitId) {
        return routeQueryService.companyFacilityPoints(unitId);
    }

    @GetMapping("/companies/{unitId}/route-anchors")
    public Map<String, Object> companyRouteAnchors(@PathVariable String unitId) {
        return routeQueryService.companyRouteAnchors(unitId);
    }

    @GetMapping("/routes/{routeId}/plan-points")
    public List<Map<String, Object>> routePlanPoints(@PathVariable Long routeId) {
        return routeQueryService.routePlanPoints(routeId);
    }

    @GetMapping("/routes/{routeId}/records")
    public List<Map<String, Object>> routeRecords(
            @PathVariable Long routeId,
            @RequestParam String unitId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return routeQueryService.routeRecords(unitId, routeId, startDate, endDate);
    }

    @GetMapping("/records/{recordId}/points")
    public List<Map<String, Object>> recordPoints(@PathVariable Long recordId) {
        return routeQueryService.recordPoints(recordId);
    }

    @PostMapping("/optimize/preview")
    public Map<String, Object> optimizePreview(@RequestBody Map<String, Object> request) {
        return routeOptimizeService.optimizePreview(request);
    }

    @PostMapping("/optimize/multi-preview")
    public Map<String, Object> optimizeMultiPreview(@RequestBody Map<String, Object> request) {
        return routeOptimizeService.optimizeMultiPreview(request);
    }

    @PostMapping("/optimize/multi-preview/tasks")
    public Map<String, Object> startOptimizeMultiPreviewTask(@RequestBody Map<String, Object> request) {
        return routeOptimizeService.startMultiPreviewTask(request);
    }

    @GetMapping("/optimize/multi-preview/tasks/{taskId}")
    public Map<String, Object> optimizeMultiPreviewTask(@PathVariable String taskId) {
        return routeOptimizeService.multiPreviewTask(taskId);
    }

    @PostMapping("/optimize/multi-preview/tasks/{taskId}/cancel")
    public Map<String, Object> cancelOptimizeMultiPreviewTask(@PathVariable String taskId) {
        return routeOptimizeService.cancelMultiPreviewTask(taskId);
    }

    @PostMapping("/optimize/cluster-preview")
    public Map<String, Object> clusterPreview(@RequestBody Map<String, Object> request) {
        return routeClusterService.preview(request);
    }

    @PostMapping("/optimize/cluster-export")
    public ResponseEntity<byte[]> exportClusterPreview(@RequestBody Map<String, Object> request) {
        byte[] bytes = routeExportService.exportClusterPreview(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=route-clusters.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @PostMapping("/optimize/route-segments")
    public Map<String, Object> routeSegmentsPreview(@RequestBody Map<String, Object> request) {
        return routeOptimizeService.routeSegmentsPreview(request);
    }

    @PostMapping("/optimize/multi-export")
    public ResponseEntity<byte[]> exportMultiPreview(@RequestBody Map<String, Object> request) {
        byte[] bytes = routeExportService.exportMultiRoutes(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=multi-routes.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @GetMapping("/route-map/status")
    public Map<String, Object> routeMapStatus() {
        return routeMapPathService.status();
    }

    @PostMapping("/route-map/preview")
    public Map<String, Object> routeMapPreview(@RequestBody Map<String, Object> request) {
        return routeMapPathService.preview(request);
    }

    @PostMapping("/import/facility-names")
    public Map<String, Object> importFacilityNames(@RequestParam("file") MultipartFile file) {
        return facilityImportService.extractNames(file);
    }

    @GetMapping("/conformance/companies/score")
    public List<Map<String, Object>> companyScores(
            @RequestParam(required = false) String unitIds,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return routeConformanceService.companyScores(unitIds, startDate, endDate);
    }

    @GetMapping("/conformance/companies/{unitId}/routes")
    public List<Map<String, Object>> routeScores(
            @PathVariable String unitId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return routeConformanceService.routeScores(unitId, startDate, endDate);
    }

    @GetMapping("/conformance/routes/{routeId}/trips")
    public List<Map<String, Object>> tripScores(
            @PathVariable Long routeId,
            @RequestParam String unitId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return routeConformanceService.tripScores(unitId, routeId, startDate, endDate);
    }

    @GetMapping("/conformance/routes/{routeId}/trips/{recordId}/explain")
    public Map<String, Object> tripExplanation(
            @PathVariable Long routeId,
            @PathVariable Long recordId,
            @RequestParam String unitId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return routeConformanceService.tripExplanation(unitId, routeId, recordId, startDate, endDate);
    }
}
