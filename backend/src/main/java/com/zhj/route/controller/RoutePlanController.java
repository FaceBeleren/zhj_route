package com.zhj.route.controller;

import com.zhj.route.service.RoutePlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/route-plans")
public class RoutePlanController {
    private final RoutePlanService routePlanService;

    public RoutePlanController(RoutePlanService routePlanService) {
        this.routePlanService = routePlanService;
    }

    @PutMapping("/groups/folder")
    public Map<String, Object> moveRoutePlanGroups(@RequestBody Map<String, Object> request) {
        return routePlanService.moveGroupsToFolder(request);
    }

    @PostMapping("/groups")
    public Map<String, Object> saveRoutePlanGroup(@RequestBody Map<String, Object> request) {
        return routePlanService.saveGroup(request);
    }

    @PostMapping("/routes")
    public Map<String, Object> saveRoutePlanRoute(@RequestBody Map<String, Object> request) {
        return routePlanService.saveRoute(request);
    }

    @GetMapping("/groups")
    public List<Map<String, Object>> routePlanGroups(
            @RequestParam(required = false) String unitId,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String keyword) {
        return routePlanService.groups(unitId, sourceType, keyword);
    }

    @GetMapping("/groups/{id}")
    public Map<String, Object> routePlanGroup(@PathVariable Long id) {
        return routePlanService.group(id);
    }

    @GetMapping("/groups/{id}/versions")
    public List<Map<String, Object>> routePlanVersions(@PathVariable Long id) {
        Map<String, Object> group = routePlanService.group(id);
        Long rootGroupId = group.get("rootGroupId") == null ? id : Long.valueOf(String.valueOf(group.get("rootGroupId")));
        return routePlanService.versions(rootGroupId);
    }

    @PostMapping("/groups/{id}/restore")
    public Map<String, Object> restoreRoutePlanVersion(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        return routePlanService.restoreVersion(id, request);
    }

    @GetMapping("/routes/{id}")
    public Map<String, Object> routePlanRoute(@PathVariable Long id) {
        return routePlanService.route(id);
    }

    @DeleteMapping("/groups/{id}")
    public Map<String, Object> deleteRoutePlanGroup(@PathVariable Long id) {
        return routePlanService.deleteGroup(id);
    }

    @DeleteMapping("/routes/{id}")
    public Map<String, Object> deleteRoutePlanRoute(@PathVariable Long id) {
        return routePlanService.deleteRoute(id);
    }
}
