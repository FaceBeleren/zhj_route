package com.zhj.route.controller;

import com.zhj.route.service.RouteQueryService;
import com.zhj.route.service.RouteConformanceService;
import com.zhj.route.service.FacilityImportService;
import com.zhj.route.service.RouteMapPathService;
import com.zhj.route.service.RouteOptimizeService;
import com.zhj.route.service.RouteExportService;
import com.zhj.route.service.RouteClusterService;
import com.zhj.route.service.RoutePlanService;
import com.zhj.route.service.OdCacheService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final RoutePlanService routePlanService;
    private final OdCacheService odCacheService;

    public RouteDataController(
            RouteQueryService routeQueryService,
            RouteConformanceService routeConformanceService,
            RouteOptimizeService routeOptimizeService,
            RouteMapPathService routeMapPathService,
            RouteExportService routeExportService,
            FacilityImportService facilityImportService,
            RouteClusterService routeClusterService,
            RoutePlanService routePlanService,
            OdCacheService odCacheService) {
        this.routeQueryService = routeQueryService;
        this.routeConformanceService = routeConformanceService;
        this.routeOptimizeService = routeOptimizeService;
        this.routeMapPathService = routeMapPathService;
        this.routeExportService = routeExportService;
        this.facilityImportService = facilityImportService;
        this.routeClusterService = routeClusterService;
        this.routePlanService = routePlanService;
        this.odCacheService = odCacheService;
    }


    @GetMapping("/route-plan-folders")
    public List<Map<String, Object>> routePlanFolders(@RequestParam(required = false) String unitId) {
        return routePlanService.folders(unitId);
    }

    @PostMapping("/route-plan-folders")
    public Map<String, Object> createRoutePlanFolder(@RequestBody Map<String, Object> request) {
        return routePlanService.createFolder(request);
    }

    @PutMapping("/route-plans/groups/folder")
    public Map<String, Object> moveRoutePlanGroups(@RequestBody Map<String, Object> request) {
        return routePlanService.moveGroupsToFolder(request);
    }

    @PostMapping("/route-plans/groups")
    public Map<String, Object> saveRoutePlanGroup(@RequestBody Map<String, Object> request) {
        return routePlanService.saveGroup(request);
    }

    @PostMapping("/route-plans/routes")
    public Map<String, Object> saveRoutePlanRoute(@RequestBody Map<String, Object> request) {
        return routePlanService.saveRoute(request);
    }

    @GetMapping("/route-plans/groups")
    public List<Map<String, Object>> routePlanGroups(
            @RequestParam(required = false) String unitId,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String keyword) {
        return routePlanService.groups(unitId, sourceType, keyword);
    }

    @GetMapping("/route-plans/groups/{id}")
    public Map<String, Object> routePlanGroup(@PathVariable Long id) {
        return routePlanService.group(id);
    }

    @GetMapping("/route-plans/groups/{id}/versions")
    public List<Map<String, Object>> routePlanVersions(@PathVariable Long id) {
        Map<String, Object> group = routePlanService.group(id);
        Long rootGroupId = group.get("rootGroupId") == null ? id : Long.valueOf(String.valueOf(group.get("rootGroupId")));
        return routePlanService.versions(rootGroupId);
    }

    @PostMapping("/route-plans/groups/{id}/restore")
    public Map<String, Object> restoreRoutePlanVersion(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        return routePlanService.restoreVersion(id, request);
    }

    @GetMapping("/route-plans/routes/{id}")
    public Map<String, Object> routePlanRoute(@PathVariable Long id) {
        return routePlanService.route(id);
    }

    @DeleteMapping("/route-plans/groups/{id}")
    public Map<String, Object> deleteRoutePlanGroup(@PathVariable Long id) {
        return routePlanService.deleteGroup(id);
    }

    @DeleteMapping("/route-plans/routes/{id}")
    public Map<String, Object> deleteRoutePlanRoute(@PathVariable Long id) {
        return routePlanService.deleteRoute(id);
    }

    @PostMapping("/od-cache/tasks")
    public Map<String, Object> startOdCacheTask(@RequestBody Map<String, Object> request) {
        return odCacheService.start(request);
    }

    @GetMapping("/od-cache/tasks/{taskId}")
    public Map<String, Object> odCacheTask(@PathVariable String taskId) {
        return odCacheService.get(taskId);
    }

    @PostMapping("/od-cache/tasks/{taskId}/cancel")
    public Map<String, Object> cancelOdCacheTask(@PathVariable String taskId) {
        return odCacheService.cancel(taskId);
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


    @PostMapping("/import/route-preview")
    public Map<String, Object> importRoutePreview(
            @RequestParam("unitId") String unitId,
            @RequestParam("file") MultipartFile file) {
        return facilityImportService.importRoutePreview(unitId, file);
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
