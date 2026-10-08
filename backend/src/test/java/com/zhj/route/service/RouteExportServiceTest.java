package com.zhj.route.service;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteExportServiceTest {

    @Test
    void expandsMergedSchemesIntoDailyScheduleMatrix() throws Exception {
        Map<String, Object> request = new HashMap<String, Object>();
        request.put("scheduleStartDate", "2026-10-08");
        request.put("cycleDays", 3);
        request.put("points", Arrays.asList(point(0, "A点"), point(1, "B点"), point(2, "C点")));
        request.put("schemes", Arrays.asList(
                scheme(Arrays.asList(1, 3), Arrays.asList(0, 2)),
                scheme(Arrays.asList(2), Arrays.asList(1))));

        byte[] bytes = new RouteExportService().exportAssignmentSchedule(request);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("排班表");
            assertEquals("日期 / 天数", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("A点", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("B点", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("C点", sheet.getRow(0).getCell(3).getStringCellValue());
            assertEquals("2026-10-08（第1天）", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("√", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("√", sheet.getRow(1).getCell(3).getStringCellValue());
            assertEquals("2026-10-09（第2天）", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals("√", sheet.getRow(2).getCell(2).getStringCellValue());
            assertEquals("2026-10-10（第3天）", sheet.getRow(3).getCell(0).getStringCellValue());
            assertEquals("√", sheet.getRow(3).getCell(1).getStringCellValue());
            assertEquals("√", sheet.getRow(3).getCell(3).getStringCellValue());
            assertEquals(1, sheet.getPaneInformation().getVerticalSplitPosition());
            assertEquals(1, sheet.getPaneInformation().getHorizontalSplitPosition());
        }
    }

    @Test
    void keepsDisabledScheduleDayAndDimsItsExcelRow() throws Exception {
        Map<String, Object> disabledScheme = scheme(Arrays.asList(2), Arrays.asList(1));
        disabledScheme.put("scheduleDisabled", true);
        Map<String, Object> request = new HashMap<String, Object>();
        request.put("scheduleStartDate", "2026-10-08");
        request.put("cycleDays", 2);
        request.put("points", Arrays.asList(point(0, "A点"), point(1, "B点")));
        request.put("schemes", Arrays.asList(scheme(Arrays.asList(1), Arrays.asList(0)), disabledScheme));

        byte[] bytes = new RouteExportService().exportAssignmentSchedule(request);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("排班表");
            assertEquals("2026-10-09（第2天，已停排）", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals("", sheet.getRow(2).getCell(2).getStringCellValue());
            assertTrue(sheet.getRow(2).getCell(0).getCellStyle().getFillPattern() != org.apache.poi.ss.usermodel.FillPatternType.NO_FILL);
            assertEquals(sheet.getRow(2).getCell(0).getCellStyle().getFillForegroundColor(), sheet.getRow(2).getCell(2).getCellStyle().getFillForegroundColor());
        }
    }

    @Test
    void appendsOriginalAndOptimizedSchedulesToAllSchemesExport() throws Exception {
        Map<String, Object> firstScheme = scheme(Arrays.asList(1), Arrays.asList(0, 1));
        firstScheme.put("status", "DONE");
        Map<String, Object> result = new HashMap<String, Object>();
        Map<String, Object> route = new HashMap<String, Object>();
        route.put("tripNo", 2);
        Map<String, Object> violatingPoint = routePoint("A", "A点", "MIDDLE");
        violatingPoint.put("timeWindowViolation", true);
        route.put("points", Arrays.asList(
                routePoint("PARK", "停车场", "START"),
                violatingPoint,
                routePoint("B", "B点", "MIDDLE"),
                routePoint("END", "处理厂", "END")));
        result.put("routes", Arrays.asList(route));
        firstScheme.put("result", result);

        Map<String, Object> disabledScheme = scheme(Arrays.asList(2), Arrays.asList(1));
        disabledScheme.put("scheduleDisabled", true);
        Map<String, Object> request = new HashMap<String, Object>();
        request.put("scheduleStartDate", "2026-10-08");
        request.put("cycleDays", 2);
        request.put("points", Arrays.asList(exportPoint(0, "A", "A点"), exportPoint(1, "B", "B点")));
        request.put("schemes", Arrays.asList(firstScheme, disabledScheme));

        byte[] bytes = new RouteExportService().exportAssignmentVersions(request);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet schedule = workbook.getSheet("排班表");
            Sheet optimized = workbook.getSheet("优化排班表");
            assertEquals("√", schedule.getRow(1).getCell(1).getStringCellValue());
            assertEquals("A点", optimized.getRow(1).getCell(0).getStringCellValue());
            assertEquals("02-001", optimized.getRow(1).getCell(1).getStringCellValue());
            int violationFont = optimized.getRow(1).getCell(1).getCellStyle().getFontIndex();
            assertEquals(org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex(), workbook.getFontAt(violationFont).getColor());
            assertEquals("B点", optimized.getRow(2).getCell(0).getStringCellValue());
            assertEquals("02-002", optimized.getRow(2).getCell(1).getStringCellValue());
            assertEquals("2026-10-09（第2天，已停排）", optimized.getRow(0).getCell(2).getStringCellValue());
            assertEquals("", optimized.getRow(2).getCell(2).getStringCellValue());
            assertEquals(1, optimized.getPaneInformation().getVerticalSplitPosition());
            assertEquals(1, optimized.getPaneInformation().getHorizontalSplitPosition());
        }
    }

    private Map<String, Object> point(int index, String name) {
        Map<String, Object> point = new HashMap<String, Object>();
        point.put("index", index);
        point.put("facilityName", name);
        return point;
    }

    private Map<String, Object> exportPoint(int index, String id, String name) {
        Map<String, Object> point = point(index, name);
        point.put("facilityId", id);
        return point;
    }

    private Map<String, Object> routePoint(String id, String name, String role) {
        Map<String, Object> point = new HashMap<String, Object>();
        point.put("facilityId", id);
        point.put("facilityName", name);
        point.put("role", role);
        return point;
    }

    private Map<String, Object> scheme(List<Integer> days, List<Integer> selectedIndices) {
        Map<String, Object> scheme = new HashMap<String, Object>();
        scheme.put("applicableDays", new ArrayList<Integer>(days));
        scheme.put("selectedIndices", new ArrayList<Integer>(selectedIndices));
        return scheme;
    }
}
