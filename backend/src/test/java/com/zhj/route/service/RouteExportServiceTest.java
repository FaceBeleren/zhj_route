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
    void parallelExportSeparatesFullTemplateDetailsFromDailyScheduleAndArrivalRows() throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("cycleDays", 1);
        request.put("scheduleStartDate", "2026-10-09");
        request.put("points", Arrays.asList(exportPoint(0, "A", "A点"), exportPoint(1, "B", "B点")));
        Map<String, Object> full = scheme(Arrays.asList(), Arrays.asList(0, 1));
        full.put("name", "独立全点模板");
        Map<String, Object> route = new HashMap<>();
        route.put("tripNo", 1);
        Map<String, Object> a = routePoint("A", "A点", "MIDDLE");
        a.put("templateToken", "01-001");
        a.put("plannedArrivalTime", "02:10");
        Map<String, Object> b = routePoint("B", "B点", "MIDDLE");
        route.put("points", Arrays.asList(a, b));
        Map<String, Object> result = new HashMap<>();
        result.put("routes", Arrays.asList(route));
        full.put("result", result);
        request.put("schemes", Arrays.asList(full));
        Map<String, Object> day = scheme(Arrays.asList(1), Arrays.asList(0));
        day.put("name", "第1天沿完整模板");
        Map<String, Object> skipped = new HashMap<>(b);
        skipped.put("scheduleSkipped", true);
        skipped.put("operationDurationMinutes", 0D);
        skipped.put("plannedArrivalTime", "02:20");
        skipped.put("plannedDepartureTime", "02:20");
        Map<String, Object> dailyRoute = new HashMap<>(route);
        dailyRoute.put("points", Arrays.asList(a, skipped));
        Map<String, Object> dailyResult = new HashMap<>();
        dailyResult.put("routes", Arrays.asList(dailyRoute));
        day.put("result", dailyResult);
        request.put("scheduleSchemes", Arrays.asList(day));
        request.put("fixedDailySchemes", Arrays.asList(day));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(new RouteExportService().exportAssignmentVersions(request)))) {
            assertEquals("独立全点模板", workbook.getSheet("点位顺序").getRow(1).getCell(0).getStringCellValue());
            assertEquals("√", workbook.getSheet("排班表").getRow(1).getCell(1).getStringCellValue());
            assertEquals("", workbook.getSheet("排班表").getRow(1).getCell(2).getStringCellValue());
            assertEquals("", workbook.getSheet("优化排班表").getRow(2).getCell(1).getStringCellValue());
            Sheet arrivals = workbook.getSheet("每日到达时间");
            assertEquals("02:10", arrivals.getRow(1).getCell(13).getStringCellValue());
            assertEquals("经过不收", arrivals.getRow(2).getCell(7).getStringCellValue());
            assertEquals(0D, arrivals.getRow(2).getCell(15).getNumericCellValue());
        }
    }

    @Test
    void fixedMasterSheetKeepsMasterOrderAndDailyTripTokens() throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("cycleDays", 2);
        request.put("scheduleStartDate", "2026-10-09");
        request.put("points", Arrays.asList(exportPoint(0, "A", "A点"), exportPoint(1, "B", "B点")));
        request.put("schemes", Arrays.asList(scheme(Arrays.asList(1, 2), Arrays.asList(0, 1))));
        Map<String, Object> master = new HashMap<>();
        Map<String, Object> templatePoint = exportPoint(1, "B", "B点");
        templatePoint.put("templateToken", "01-001");
        master.put("points", Arrays.asList(templatePoint, exportPoint(0, "A", "A点")));
        Map<String, Object> first = scheme(Arrays.asList(1), Arrays.asList(0));
        Map<String, Object> violating = routePoint("A", "A点", "MIDDLE");
        violating.put("timeWindowViolation", true);
        Map<String, Object> route = new HashMap<>();
        route.put("tripNo", 2);
        violating.put("templateToken", "02-003");
        Map<String, Object> skipped = routePoint("B", "B点", "MIDDLE");
        skipped.put("scheduleSkipped", true);
        route.put("points", Arrays.asList(skipped, violating));
        Map<String, Object> result = new HashMap<>();
        result.put("routes", Arrays.asList(route));
        first.put("result", result);
        Map<String, Object> disabled = scheme(Arrays.asList(2), Arrays.asList(0, 1));
        disabled.put("scheduleDisabled", true);
        master.put("schemes", Arrays.asList(first, disabled));
        request.put("fixedMaster", master);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(new RouteExportService().exportAssignmentVersions(request)))) {
            Sheet sheet = workbook.getSheet("固定主路线排班表");
            assertEquals("B点", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("01-001", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("—", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("02-003", sheet.getRow(2).getCell(2).getStringCellValue());
            assertEquals("停排", sheet.getRow(2).getCell(3).getStringCellValue());
            assertEquals(org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex(), workbook.getFontAt(sheet.getRow(2).getCell(2).getCellStyle().getFontIndex()).getColor());
            assertEquals(2, sheet.getPaneInformation().getVerticalSplitPosition());
        }
    }

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
