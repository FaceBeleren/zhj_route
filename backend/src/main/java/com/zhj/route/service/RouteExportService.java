package com.zhj.route.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class RouteExportService {

    public byte[] exportMultiRoutes(Map<String, Object> result) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);
            writeSummary(workbook, headerStyle, result);
            writeRoutes(workbook, headerStyle, result);
            writeSegments(workbook, headerStyle, result);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("导出Excel失败", e);
        }
    }


    public byte[] exportClusterPreview(Map<String, Object> result) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);
            writeClusterSummary(workbook, headerStyle, result);
            writeClusterPoints(workbook, headerStyle, "聚类前点位", maps(result.get("beforeGroups")));
            writeClusterPoints(workbook, headerStyle, "聚类后点位", maps(result.get("afterGroups")));
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("导出Excel失败", e);
        }
    }
    private void writeSummary(Workbook workbook, CellStyle headerStyle, Map<String, Object> result) {
        Sheet sheet = workbook.createSheet("汇总");
        writeHeader(sheet, headerStyle, "指标", "值");
        int rowIndex = 1;
        rowIndex = writePair(sheet, rowIndex, "状态", value(result.get("status")));
        rowIndex = writePair(sheet, rowIndex, "说明", value(result.get("message")));
        rowIndex = writePair(sheet, rowIndex, "路线数", value(result.get("routeCount")));
        rowIndex = writePair(sheet, rowIndex, "已分配点位", value(result.get("assignedPointCount")));
        rowIndex = writePair(sheet, rowIndex, "未分配点位", value(result.get("unassignedPointCount")));
        rowIndex = writePair(sheet, rowIndex, "已分配重量kg", value(result.get("assignedWeightKg")));
        rowIndex = writePair(sheet, rowIndex, "未分配重量kg", value(result.get("unassignedWeightKg")));
        rowIndex = writePair(sheet, rowIndex, "计划趟次", value(result.get("dispatchTripCount")));
        rowIndex = writePair(sheet, rowIndex, "计划额定总量kg", value(result.get("totalPlannedCapacityKg")));
        rowIndex = writePair(sheet, rowIndex, "目标单趟重量kg", value(result.get("targetLoadWeightKg")));
        rowIndex = writePair(sheet, rowIndex, "额定载重kg", value(result.get("ratedCapacityKg")));
        writePair(sheet, rowIndex, "目标装载率", value(result.get("targetLoadRate")));
        autosize(sheet, 2);
    }

    private void writeRoutes(Workbook workbook, CellStyle headerStyle, Map<String, Object> result) {
        Sheet sheet = workbook.createSheet("路线明细");
        writeHeader(sheet, headerStyle, "路线", "车辆", "车型", "第几趟", "额定载重kg", "点位顺序", "点位ID", "点位名称", "角色", "预计重量kg", "预计体积L", "装载率", "路线距离m", "路线耗时min");
        int rowIndex = 1;
        for (Map<String, Object> route : maps(result.get("routes"))) {
            for (Map<String, Object> point : maps(route.get("points"))) {
                Row row = sheet.createRow(rowIndex++);
                write(row, 0, route.get("routeNo"));
                write(row, 1, route.get("vehicleName"));
                write(row, 2, route.get("vehicleType"));
                write(row, 3, route.get("tripNo"));
                write(row, 4, route.get("ratedCapacityKg"));
                write(row, 5, point.get("order"));
                write(row, 6, point.get("facilityId"));
                write(row, 7, point.get("facilityName"));
                write(row, 8, point.get("role"));
                write(row, 9, point.get("estimatedWeightKg"));
                write(row, 10, point.get("estimatedVolumeLiter"));
                write(row, 11, route.get("loadRate"));
                write(row, 12, route.get("distance"));
                write(row, 13, route.get("durationMinutes"));
            }
        }
        autosize(sheet, 14);
    }

    private void writeSegments(Workbook workbook, CellStyle headerStyle, Map<String, Object> result) {
        Sheet sheet = workbook.createSheet("路段明细");
        writeHeader(sheet, headerStyle, "路线", "车辆", "车型", "第几趟", "路段", "起点ID", "起点名称", "终点ID", "终点名称", "距离m", "耗时min", "路径来源");
        int rowIndex = 1;
        for (Map<String, Object> route : maps(result.get("routes"))) {
            for (Map<String, Object> segment : maps(route.get("segments"))) {
                Row row = sheet.createRow(rowIndex++);
                write(row, 0, route.get("routeNo"));
                write(row, 1, route.get("vehicleName"));
                write(row, 2, route.get("vehicleType"));
                write(row, 3, route.get("tripNo"));
                write(row, 4, segment.get("order"));
                write(row, 5, segment.get("fromFacilityId"));
                write(row, 6, segment.get("fromFacilityName"));
                write(row, 7, segment.get("toFacilityId"));
                write(row, 8, segment.get("toFacilityName"));
                write(row, 9, segment.get("distance"));
                write(row, 10, segment.get("durationMinutes"));
                write(row, 11, pathSourceLabel(segment.get("pathSource")));
            }
        }
        autosize(sheet, 12);
    }


    private void writeClusterSummary(Workbook workbook, CellStyle headerStyle, Map<String, Object> result) {
        Sheet sheet = workbook.createSheet("分堆汇总");
        writeHeader(sheet, headerStyle, "阶段", "堆名", "点位数", "桶数", "660L桶", "240L桶", "预计体积L", "预计重量kg", "预计作业min", "说明");
        int rowIndex = 1;
        rowIndex = writeClusterSummaryRows(sheet, rowIndex, "聚类前", maps(result.get("beforeGroups")));
        writeClusterSummaryRows(sheet, rowIndex, "聚类后", maps(result.get("afterGroups")));
        autosize(sheet, 10);
    }

    private int writeClusterSummaryRows(Sheet sheet, int rowIndex, String stage, List<Map<String, Object>> groups) {
        for (Map<String, Object> group : groups) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, stage);
            write(row, 1, group.get("groupName"));
            write(row, 2, group.get("pointCount"));
            write(row, 3, group.get("containerCount"));
            write(row, 4, group.get("container660Count"));
            write(row, 5, group.get("container240Count"));
            write(row, 6, group.get("estimatedVolumeLiter"));
            write(row, 7, group.get("estimatedWeightKg"));
            write(row, 8, group.get("operationMinutes"));
            write(row, 9, value(group.get("explanation")));
        }
        return rowIndex;
    }

    private void writeClusterPoints(Workbook workbook, CellStyle headerStyle, String sheetName, List<Map<String, Object>> groups) {
        Sheet sheet = workbook.createSheet(sheetName);
        writeHeader(sheet, headerStyle, "堆名", "点位ID", "点位名称", "设施类型", "经度", "纬度", "桶信息", "桶数", "预计体积L", "预计重量kg");
        int rowIndex = 1;
        for (Map<String, Object> group : groups) {
            for (Map<String, Object> point : maps(group.get("points"))) {
                Row row = sheet.createRow(rowIndex++);
                write(row, 0, group.get("groupName"));
                write(row, 1, point.get("facilityId"));
                write(row, 2, point.get("facilityName"));
                write(row, 3, point.get("facilityTypeName"));
                write(row, 4, point.get("longitude"));
                write(row, 5, point.get("latitude"));
                write(row, 6, point.get("containerInfo"));
                write(row, 7, point.get("containerCount"));
                write(row, 8, point.get("estimatedVolumeLiter"));
                write(row, 9, point.get("estimatedWeightKg"));
            }
        }
        autosize(sheet, 10);
    }
    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private void writeHeader(Sheet sheet, CellStyle style, String... headers) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
        }
    }

    private int writePair(Sheet sheet, int rowIndex, String name, String value) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(name);
        row.createCell(1).setCellValue(value);
        return rowIndex + 1;
    }

    private void write(Row row, int index, Object value) {
        Cell cell = row.createCell(index);
        if (value instanceof Number) {
            cell.setCellValue(((Number) value).doubleValue());
            return;
        }
        if (value instanceof BigDecimal) {
            cell.setCellValue(((BigDecimal) value).doubleValue());
            return;
        }
        cell.setCellValue(value(value));
    }

    private void autosize(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : java.util.Collections.<Map<String, Object>>emptyList();
    }

    private String pathSourceLabel(Object source) {
        String value = value(source);
        if ("OD_CACHE".equals(value)) {
            return "OD缓存";
        }
        if ("BAIDU_ONLINE".equals(value)) {
            return "百度在线";
        }
        return "直线回退";
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}

