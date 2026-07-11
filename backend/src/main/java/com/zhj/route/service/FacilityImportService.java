package com.zhj.route.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbcTemplate;

    public FacilityImportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

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



    public Map<String, Object> importRoutePreview(String unitId, MultipartFile file) {
        if (unitId == null || unitId.trim().isEmpty()) {
            throw new IllegalArgumentException("请选择项目公司");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的Excel文件");
        }
        ParsedRouteSheet parsed = parseRouteSheet(file);
        Map<String, Map<String, Object>> candidates = routeDisplayCandidates(unitId);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> points = new ArrayList<Map<String, Object>>();
        int order = 1;
        for (String name : parsed.orderedNames) {
            Map<String, Object> row = new HashMap<String, Object>();
            row.put("order", order);
            row.put("inputName", name);
            Map<String, Object> matched = candidates.get(normalizeName(name));
            if (matched == null) {
                row.put("matched", false);
                row.put("message", "未匹配到公司点位或场站");
            } else {
                row.put("matched", true);
                row.put("point", matched);
                Map<String, Object> point = new HashMap<String, Object>(matched);
                point.put("order", points.size() + 1);
                point.put("role", "MIDDLE");
                points.add(point);
                row.put("mapOrder", points.size());
            }
            rows.add(row);
            order++;
        }
        if (!points.isEmpty()) {
            points.get(0).put("role", "START");
            points.get(points.size() - 1).put("role", "END");
        }
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("sheetName", parsed.sheetName);
        result.put("headerRow", parsed.header.rowIndex + 1);
        result.put("nameColumn", parsed.header.columnIndex + 1);
        result.put("nameColumnTitle", parsed.header.title);
        result.put("rows", rows);
        result.put("points", points);
        result.put("totalCount", parsed.orderedNames.size());
        result.put("matchedCount", points.size());
        result.put("unmatchedCount", parsed.orderedNames.size() - points.size());
        result.put("routeName", parsed.sheetName == null || parsed.sheetName.trim().isEmpty() ? "导入路线" : parsed.sheetName);
        return result;
    }

    private ParsedRouteSheet parseRouteSheet(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream(); Workbook workbook = WorkbookFactory.create(inputStream)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("Excel中没有工作表");
            }
            DataFormatter formatter = new DataFormatter();
            Sheet sheet = workbook.getSheetAt(0);
            Header header = findNameHeader(sheet, formatter);
            if (header == null) {
                throw new IllegalArgumentException("sheet1未找到名称列，请确认表头包含：名称、点位名称、设施名称或垃圾位置/桶位位置");
            }
            return new ParsedRouteSheet(sheet.getSheetName(), header, readOrderedNames(sheet, formatter, header));
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



    private List<String> readOrderedNames(Sheet sheet, DataFormatter formatter, Header header) {
        List<String> names = new ArrayList<String>();
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
            names.add(name);
        }
        return names;
    }

    private Map<String, Map<String, Object>> routeDisplayCandidates(String unitId) {
        Map<String, Map<String, Object>> result = new HashMap<String, Map<String, Object>>();
        for (Map<String, Object> point : queryFacilityCandidates(unitId)) {
            putCandidate(result, point);
        }
        for (Map<String, Object> point : queryParkingCandidates(unitId)) {
            putCandidate(result, point);
        }
        return result;
    }

    private List<Map<String, Object>> queryFacilityCandidates(String unitId) {
        String sql = "SELECT f.id AS facilityId, f.name AS facilityName, f.facility_type AS facilityType, " +
                "f.facility_type_name AS facilityTypeName, f.longitude_done AS longitude, f.latitude_done AS latitude, " +
                "container.containerInfo AS containerInfo, IFNULL(container.containerCount, 0) AS containerCount, " +
                "IFNULL(container.estimatedVolumeLiter, 0) AS estimatedVolumeLiter, base.liters_per_ton AS litersPerTon, " +
                "CASE WHEN container.estimatedVolumeLiter IS NULL OR container.estimatedVolumeLiter = 0 THEN 0 " +
                "WHEN base.liters_per_ton IS NULL OR base.liters_per_ton = 0 THEN 0 " +
                "ELSE ROUND(container.estimatedVolumeLiter * 1000 / base.liters_per_ton, 2) END AS estimatedWeightKg, " +
                "'FACILITY' AS anchorSource " +
                "FROM ljszy_facility_info f " +
                "LEFT JOIN (" +
                "SELECT facility_id, GROUP_CONCAT(CONCAT(container_type, '/', container_count) ORDER BY id SEPARATOR ',') AS containerInfo, " +
                "SUM(IFNULL(container_count, 0)) AS containerCount, " +
                "SUM(IFNULL(container_count, 0) * IFNULL(container_type, 0)) AS estimatedVolumeLiter " +
                "FROM ljszy_facility_container_info WHERE been_deleted = 0 GROUP BY facility_id" +
                ") container ON container.facility_id = f.id " +
                "LEFT JOIN ljszy_base_config base ON base.unit_id = f.department_id AND base.been_deleted = 0 " +
                "WHERE f.been_deleted = 0 AND f.department_id = ? " +
                "AND f.longitude_done IS NOT NULL AND f.latitude_done IS NOT NULL " +
                "ORDER BY FIELD(f.facility_type, 6, 5, 2), f.name, f.id";
        return jdbcTemplate.queryForList(sql, unitId);
    }

    private List<Map<String, Object>> queryParkingCandidates(String unitId) {
        String sql = "SELECT equip.id AS facilityId, equip.name AS facilityName, equip.typeId AS facilityType, " +
                "equip.typeName AS facilityTypeName, equip.longitudeDone AS longitude, equip.latitudeDone AS latitude, " +
                "0 AS containerCount, 0 AS estimatedVolumeLiter, 0 AS estimatedWeightKg, 'PARKING' AS anchorSource " +
                "FROM sszhgl.sszhgl_equipment equip " +
                "WHERE IFNULL(equip.beenDeleted, 0) = 0 " +
                "AND (equip.enable IS NULL OR equip.enable = 1) " +
                "AND equip.accUnitId = ? " +
                "AND (equip.typeId = '4' OR equip.typeName = '停车场') " +
                "AND equip.longitudeDone IS NOT NULL AND equip.latitudeDone IS NOT NULL " +
                "ORDER BY equip.name, equip.id";
        try {
            return jdbcTemplate.queryForList(sql, unitId);
        } catch (DataAccessException e) {
            return new ArrayList<Map<String, Object>>();
        }
    }

    private void putCandidate(Map<String, Map<String, Object>> result, Map<String, Object> point) {
        String key = normalizeName(point.get("facilityName") == null ? null : String.valueOf(point.get("facilityName")));
        if (!key.isEmpty() && !result.containsKey(key)) {
            result.put(key, point);
        }
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
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

    private static class ParsedRouteSheet {
        private final String sheetName;
        private final Header header;
        private final List<String> orderedNames;

        private ParsedRouteSheet(String sheetName, Header header, List<String> orderedNames) {
            this.sheetName = sheetName;
            this.header = header;
            this.orderedNames = orderedNames;
        }
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
