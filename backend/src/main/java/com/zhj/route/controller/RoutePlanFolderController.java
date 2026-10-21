package com.zhj.route.controller;

import com.zhj.route.service.RoutePlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/route-plan-folders")
public class RoutePlanFolderController {
    private final RoutePlanService routePlanService;

    public RoutePlanFolderController(RoutePlanService routePlanService) {
        this.routePlanService = routePlanService;
    }

    @GetMapping("")
    public List<Map<String, Object>> routePlanFolders(@RequestParam(required = false) String unitId) {
        return routePlanService.folders(unitId);
    }

    @PostMapping("")
    public Map<String, Object> createRoutePlanFolder(@RequestBody Map<String, Object> request) {
        return routePlanService.createFolder(request);
    }
}
