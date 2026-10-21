package com.zhj.route.controller;

import com.zhj.route.service.RouteMapPathService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/route-map")
public class RouteMapController {
    private final RouteMapPathService routeMapPathService;

    public RouteMapController(RouteMapPathService routeMapPathService) {
        this.routeMapPathService = routeMapPathService;
    }

    @GetMapping("/status")
    public Map<String, Object> routeMapStatus() {
        return routeMapPathService.status();
    }

    @PostMapping("/preview")
    public Map<String, Object> routeMapPreview(@RequestBody Map<String, Object> request) {
        return routeMapPathService.preview(request);
    }
}
