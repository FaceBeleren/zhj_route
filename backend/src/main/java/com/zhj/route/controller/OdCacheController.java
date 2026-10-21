package com.zhj.route.controller;

import com.zhj.route.service.OdCacheService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/od-cache")
public class OdCacheController {
    private final OdCacheService odCacheService;

    public OdCacheController(OdCacheService odCacheService) {
        this.odCacheService = odCacheService;
    }

    @PostMapping("/tasks")
    public Map<String, Object> startOdCacheTask(@RequestBody Map<String, Object> request) {
        return odCacheService.start(request);
    }

    @GetMapping("/tasks/{taskId}")
    public Map<String, Object> odCacheTask(@PathVariable String taskId) {
        return odCacheService.get(taskId);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Map<String, Object> cancelOdCacheTask(@PathVariable String taskId) {
        return odCacheService.cancel(taskId);
    }
}
