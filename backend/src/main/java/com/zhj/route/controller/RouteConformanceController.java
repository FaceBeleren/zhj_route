package com.zhj.route.controller;

import com.zhj.route.service.RouteConformanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conformance")
public class RouteConformanceController {
    private final RouteConformanceService routeConformanceService;

    public RouteConformanceController(RouteConformanceService routeConformanceService) {
        this.routeConformanceService = routeConformanceService;
    }

    @GetMapping("/companies/score")
    public List<Map<String, Object>> companyScores(
            @RequestParam(required = false) String unitIds,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return routeConformanceService.companyScores(unitIds, startDate, endDate);
    }

    @GetMapping("/companies/{unitId}/routes")
    public List<Map<String, Object>> routeScores(
            @PathVariable String unitId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return routeConformanceService.routeScores(unitId, startDate, endDate);
    }

    @GetMapping("/routes/{routeId}/trips")
    public List<Map<String, Object>> tripScores(
            @PathVariable Long routeId,
            @RequestParam String unitId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return routeConformanceService.tripScores(unitId, routeId, startDate, endDate);
    }

    @GetMapping("/routes/{routeId}/trips/{recordId}/explain")
    public Map<String, Object> tripExplanation(
            @PathVariable Long routeId,
            @PathVariable Long recordId,
            @RequestParam String unitId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return routeConformanceService.tripExplanation(unitId, routeId, recordId, startDate, endDate);
    }
}
