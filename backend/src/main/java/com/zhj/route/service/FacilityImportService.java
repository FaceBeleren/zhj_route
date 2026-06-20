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
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Header header = findNameHeader(sheet, formatter);
            if (header == null) {
                throw new IllegalArgumentException("未找到名称列，请确认表头包含：名称、点位名称或设施名称");
            }
            List<String> names = readNames(sheet, formatter, header);
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("sheetName", sheet.getSheetName());
            result.put("headerRow", header.rowIndex + 1);
            result.put("nameColumn", header.columnIndex + 1);
            result.put("nameColumnTitle", header.title);
            result.put("names", names);
            result.put("nameCount", names.size());
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
            if (name.isEmpty()) {
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
                || "垃圾点位名称".equals(normalized);
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