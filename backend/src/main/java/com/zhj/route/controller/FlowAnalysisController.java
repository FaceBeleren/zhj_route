package com.zhj.route.controller;

import com.zhj.route.service.FlowAnalysisService;
import com.zhj.route.service.ClyxVehicleDayService;
import com.zhj.route.service.FlowBatchCompareExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final ClyxVehicleDayService clyxVehicleDayService;
    private final FlowBatchCompareExportService batchCompareExportService;

    public FlowAnalysisController(FlowAnalysisService flowAnalysisService, ClyxVehicleDayService clyxVehicleDayService,
            FlowBatchCompareExportService batchCompareExportService) {
        this.flowAnalysisService = flowAnalysisService;
        this.clyxVehicleDayService = clyxVehicleDayService;
        this.batchCompareExportService = batchCompareExportService;
    }

    @GetMapping("/vehicle-daily-report")
    public Map<String, Object> vehicleDailyReport(@RequestParam String unitId, @RequestParam String routeId,
            @RequestParam String carCode, @RequestParam String day) {
        return clyxVehicleDayService.report(unitId, routeId, carCode, day);
    }

    @GetMapping("/vehicle-gps-track")
    public Map<String, Object> vehicleGpsTrack(@RequestParam String unitId, @RequestParam String routeId,
            @RequestParam String carCode, @RequestParam String day) {
        return clyxVehicleDayService.track(unitId, routeId, carCode, day);
    }

    @PostMapping("/batch-comparison-export")
    public ResponseEntity<byte[]> exportBatchComparison(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=flow-batch-comparison.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(batchCompareExportService.export(request));
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
