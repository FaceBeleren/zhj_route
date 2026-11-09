package com.zhj.route.service;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowBatchCompareExportServiceTest {
    @Test
    void exportsNumericScoresAndKeepsMissingGpsBlank() throws Exception {
        Map<String, Object> request = new HashMap<String, Object>();
        request.put("label", "完整模板");
        request.put("carCode", "鲁CL0725");
        request.put("before", summary(1, 0.9, 17.63));
        request.put("after", summary(1, 0.97, null));
        request.put("rows", Arrays.asList(row("交付前", 0.9, 17630D), row("交付后", 0.97, null)));
        byte[] bytes = new FlowBatchCompareExportService().export(request);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals("鲁CL0725", workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue());
            assertEquals(0.9, workbook.getSheetAt(0).getRow(11).getCell(7).getNumericCellValue());
            assertEquals(17.63, workbook.getSheetAt(0).getRow(11).getCell(9).getNumericCellValue());
            assertEquals(7.0, workbook.getSheetAt(0).getRow(8).getCell(5).getNumericCellValue(), 0.000001);
            assertEquals(null, workbook.getSheetAt(0).getRow(12).getCell(9));
        }
    }

    private Map<String, Object> summary(int days, double rate, Double km) {
        Map<String, Object> value = new HashMap<String, Object>();
        value.put("days", days);
        value.put("scoreDays", days);
        value.put("gpsDays", km == null ? 0 : days);
        value.put("orderRate", rate);
        value.put("averageGpsKm", km);
        return value;
    }

    private Map<String, Object> row(String phase, double rate, Double meters) {
        Map<String, Object> value = new HashMap<String, Object>();
        value.put("phase", phase);
        value.put("date", "2026-10-07");
        value.put("carCode", "鲁CL0725");
        value.put("orderRate", rate);
        value.put("gpsMeters", meters);
        return value;
    }
}