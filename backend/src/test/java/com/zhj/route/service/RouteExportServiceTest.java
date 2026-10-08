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

    private Map<String, Object> point(int index, String name) {
        Map<String, Object> point = new HashMap<String, Object>();
        point.put("index", index);
        point.put("facilityName", name);
        return point;
    }

    private Map<String, Object> scheme(List<Integer> days, List<Integer> selectedIndices) {
        Map<String, Object> scheme = new HashMap<String, Object>();
        scheme.put("applicableDays", new ArrayList<Integer>(days));
        scheme.put("selectedIndices", new ArrayList<Integer>(selectedIndices));
        return scheme;
    }
}
