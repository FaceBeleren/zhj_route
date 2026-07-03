package com.zhj.route.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FacilityImportService {
    private static final int SCAN_HEADER_ROWS = 20;
    private static final int MAX_EMPTY_ROWS_AFTER_HEADER = 30;

    public Map<String, Object> extractNames(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的Excel文件");
        }
        try (InputStream inputStream = file.getInputStream(); Workbook workbook = WorkbookFactory.create(inputStream)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("Excel中没有工作表");
            }
            DataFormatter formatter = new DataFormatter();
            List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
            Set<String> allNames = new LinkedHashSet<String>();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                Header header = findNameHeader(sheet, formatter);
                if (header == null) {
                    continue;
                }
                List<String> names = readNames(sheet, formatter, header);
                if (names.isEmpty()) {
                    continue;
                }
                allNames.addAll(names);
                Map<String, Object> group = new HashMap<String, Object>();
                group.put("sheetIndex", sheetIndex + 1);
                group.put("sheetName", sheet.getSheetName());
                group.put("groupName", sheet.getSheetName());
                group.put("headerRow", header.rowIndex + 1);
                group.put("nameColumn", header.columnIndex + 1);
                group.put("nameColumnTitle", header.title);
                group.put("names", names);
                group.put("nameCount", names.size());
                groups.add(group);
            }
            if (groups.isEmpty()) {
                throw new IllegalArgumentException("未找到名称列，请确认表头包含：名称、点位名称、设施名称或垃圾位置/桶位位置");
            }
            Map<String, Object> result = new HashMap<String, Object>();
            Map<String, Object> first = groups.get(0);
            result.put("sheetName", first.get("sheetName"));
            result.put("headerRow", first.get("headerRow"));
            result.put("nameColumn", first.get("nameColumn"));
            result.put("nameColumnTitle", first.get("nameColumnTitle"));
            result.put("names", new ArrayList<String>(allNames));
            result.put("nameCount", allNames.size());
            result.put("sheetCount", workbook.getNumberOfSheets());
            result.put("matchedSheetCount", groups.size());
            result.put("groups", groups);
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Excel解析失败，请确认文件为xls或xlsx格式");
        }
    }

    private Header findNameHeader(Sheet sheet, DataFormatter formatter) {
        int lastRow = Math.min(sheet.getLastRowNum(), SCAN_HEADER_ROWS - 1);
        for (int rowIndex = 0; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            short lastCell = row.getLastCellNum();
            for (int columnIndex = 0; columnIndex < lastCell; columnIndex++) {
                String title = cellText(row.getCell(columnIndex), formatter);
                if (isNameHeader(title)) {
                    return new Header(rowIndex, columnIndex, title);
                }
            }
        }
        return null;
    }

    private List<String> readNames(Sheet sheet, DataFormatter formatter, Header header) {
        Set<String> uniqueNames = new LinkedHashSet<String>();
        int emptyRows = 0;
        for (int rowIndex = header.rowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            String name = row == null ? "" : cellText(row.getCell(header.columnIndex), formatter);
            if (name.isEmpty() || isNameHeader(name)) {
                emptyRows++;
                if (emptyRows >= MAX_EMPTY_ROWS_AFTER_HEADER) {
                    break;
                }
                continue;
            }
            emptyRows = 0;
            uniqueNames.add(name);
        }
        return new ArrayList<String>(uniqueNames);
    }

    private boolean isNameHeader(String value) {
        String normalized = normalizeHeader(value);
        return "名称".equals(normalized)
                || "点位名称".equals(normalized)
                || "设施名称".equals(normalized)
                || "收集点名称".equals(normalized)
                || "垃圾点名称".equals(normalized)
                || "垃圾点位名称".equals(normalized)
                || "垃圾位置".equals(normalized)
                || "桶位位置".equals(normalized)
                || "垃圾位置或桶位位置".equals(normalized)
                || (normalized.contains("垃圾") && normalized.contains("位置"));
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    private String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell).trim();
    }

    private static class Header {
        private final int rowIndex;
        private final int columnIndex;
        private final String title;

        private Header(int rowIndex, int columnIndex, String title) {
            this.rowIndex = rowIndex;
            this.columnIndex = columnIndex;
            this.title = title;
        }
    }
}
