package com.zhj.route.controller;

import com.zhj.route.service.FacilityImportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@RestController
@RequestMapping("/api/import")
public class FacilityImportController {
    private final FacilityImportService facilityImportService;

    public FacilityImportController(FacilityImportService facilityImportService) {
        this.facilityImportService = facilityImportService;
    }

    @PostMapping("/facility-names")
    public Map<String, Object> importFacilityNames(@RequestParam("file") MultipartFile file) {
        return facilityImportService.extractNames(file);
    }

    @PostMapping("/route-preview")
    public Map<String, Object> importRoutePreview(
            @RequestParam("unitId") String unitId,
            @RequestParam("file") MultipartFile file) {
        return facilityImportService.importRoutePreview(unitId, file);
    }
}
