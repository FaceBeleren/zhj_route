package com.zhj.route.controller;

import com.zhj.route.service.RouteQueryService;
import com.zhj.route.service.RouteConformanceService;
import com.zhj.route.service.RouteOptimizeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RouteDataController {
    private final RouteQueryService routeQueryService;
    private final RouteConformanceService routeConformanceService;
    private final RouteOptimizeService routeOptimizeService;

    public RouteDataController(
            RouteQueryService routeQueryService,
            RouteConformanceService routeConformanceService,
            RouteOptimizeService routeOptimizeService) {
        this.routeQueryService = routeQueryService;
        this.routeConformanceService = routeConformanceService;
        this.routeOptimizeService = routeOptimizeService;
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
}
