package com.zhj.route.controller;

import com.zhj.route.service.FlowAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/flow-analysis")
public class FlowAnalysisController {
    private final FlowAnalysisService flowAnalysisService;

    public FlowAnalysisController(FlowAnalysisService flowAnalysisService) {
        this.flowAnalysisService = flowAnalysisService;
    }

    @GetMapping("/vehicles")
    public List<Map<String, Object>> flowAnalysisVehicles(
            @RequestParam String unitId,
            @RequestParam String routeId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return flowAnalysisService.vehicles(unitId, routeId, startDate, endDate);
    }

    @GetMapping("/configured-points")
    public List<Map<String, Object>> flowAnalysisConfiguredPoints(
            @RequestParam String unitId,
            @RequestParam String routeId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return flowAnalysisService.configuredPointsForRoute(unitId, routeId, startDate, endDate);
    }

    @PostMapping("/tasks")
    public Map<String, Object> startFlowAnalysis(@RequestBody Map<String, Object> request) {
        return flowAnalysisService.start(request);
    }

    @GetMapping("/tasks/{taskId}")
    public Map<String, Object> flowAnalysisTask(@PathVariable String taskId) {
        return flowAnalysisService.get(taskId);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Map<String, Object> cancelFlowAnalysis(@PathVariable String taskId) {
        return flowAnalysisService.cancel(taskId);
    }

    @PutMapping("/tasks/{taskId}/adjustments")
    public Map<String, Object> adjustFlowAnalysis(@PathVariable String taskId, @RequestBody Map<String, Object> adjustments) {
        return flowAnalysisService.adjust(taskId, adjustments);
    }
}
