package com.zhj.route.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
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


    public byte[] exportAssignmentVersions(Map<String, Object> request) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);
            List<Map<String, Object>> schemes = maps(request.get("schemes"));
            List<CellStyle> schemeStyles = assignmentSchemeStyles(workbook);
            writeAssignmentSummary(workbook, headerStyle, schemeStyles, schemes);
            writeAssignmentRoutes(workbook, headerStyle, schemeStyles, schemes);
            writeAssignmentPoints(workbook, headerStyle, schemeStyles, schemes);
            writeAssignmentSegments(workbook, headerStyle, schemeStyles, schemes);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("导出全部方案Excel失败", e);
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
        writeHeader(sheet, headerStyle, "路线", "车辆", "车型", "第几趟", "路段", "起点ID", "起点名称", "终点ID", "终点名称", "距离m", "耗时min", "速度km/h", "速度档位", "路径来源");
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
                write(row, 11, segment.get("speedKmh"));
                write(row, 12, segment.get("speedClass"));
                write(row, 13, pathSourceLabel(segment.get("pathSource")));
            }
        }
        autosize(sheet, 14);
    }


    private void writeAssignmentSummary(Workbook workbook, CellStyle headerStyle, List<CellStyle> schemeStyles, List<Map<String, Object>> schemes) {
        Sheet sheet = workbook.createSheet("方案总览");
        writeHeader(sheet, headerStyle, "方案", "适用日", "状态", "趟次数", "已分配点位", "未分配点位", "预计重量kg", "未分配重量kg", "总距离m", "总时间min", "距离口径");
        int rowIndex = 1;
        for (int schemeIndex = 0; schemeIndex < schemes.size(); schemeIndex++) {
            Map<String, Object> scheme = schemes.get(schemeIndex);
            Map<String, Object> result = map(scheme.get("result"));
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, scheme.get("name"));
            write(row, 1, applicableDays(scheme));
            write(row, 2, scheme.get("status"));
            write(row, 3, result.get("routeCount"));
            write(row, 4, result.get("assignedPointCount"));
            write(row, 5, result.get("unassignedPointCount"));
            write(row, 6, result.get("assignedWeightKg"));
            write(row, 7, result.get("unassignedWeightKg"));
            double distance = 0D;
            double duration = 0D;
            List<Map<String, Object>> routes = maps(result.get("routes"));
            for (Map<String, Object> route : routes) {
                distance += number(route.get("distance"));
                duration += number(route.get("durationMinutes"));
            }
            if (!routes.isEmpty()) {
                write(row, 8, distance);
                write(row, 9, duration);
            }
            write(row, 10, distanceModeLabel(result));
            applyRowStyle(row, schemeStyles.get(schemeIndex % schemeStyles.size()));
        }
        finishAssignmentSheet(sheet, rowIndex, new int[]{14, 18, 12, 10, 14, 14, 15, 17, 15, 15, 18});
    }

    private void writeAssignmentRoutes(Workbook workbook, CellStyle headerStyle, List<CellStyle> schemeStyles, List<Map<String, Object>> schemes) {
        Sheet sheet = workbook.createSheet("趟次路线");
        writeHeader(sheet, headerStyle, "方案", "适用日", "状态", "路线", "车辆", "车型", "第几趟", "计划发车", "计划结束", "收运点数", "预计重量kg", "预计体积L", "装载率", "距离m", "行驶时间min", "点位及卸料作业min", "其中终点卸料min", "总时间min", "距离口径", "点位顺序");
        int rowIndex = 1;
        for (int schemeIndex = 0; schemeIndex < schemes.size(); schemeIndex++) {
            Map<String, Object> scheme = schemes.get(schemeIndex);
            Map<String, Object> result = map(scheme.get("result"));
            for (Map<String, Object> route : maps(result.get("routes"))) {
                Row row = sheet.createRow(rowIndex++);
                write(row, 0, scheme.get("name"));
                write(row, 1, applicableDays(scheme));
                write(row, 2, scheme.get("status"));
                write(row, 3, route.get("routeNo"));
                write(row, 4, vehicleName(route));
                write(row, 5, vehicleType(route));
                write(row, 6, route.get("tripNo"));
                write(row, 7, route.get("plannedStartTime"));
                write(row, 8, route.get("plannedEndTime"));
                write(row, 9, route.get("pointCount"));
                write(row, 10, route.get("estimatedWeightKg"));
                write(row, 11, route.get("estimatedVolumeLiter"));
                write(row, 12, route.get("loadRate"));
                write(row, 13, route.get("distance"));
                write(row, 14, route.get("travelDurationMinutes"));
                write(row, 15, route.get("operationDurationMinutes"));
                write(row, 16, route.get("terminalUnloadMinutes"));
                write(row, 17, route.get("durationMinutes"));
                write(row, 18, distanceModeLabel(result));
                write(row, 19, pointSequence(route));
                applyRowStyle(row, schemeStyles.get(schemeIndex % schemeStyles.size()));
            }
        }
        finishAssignmentSheet(sheet, rowIndex, new int[]{14, 18, 10, 9, 18, 16, 9, 14, 14, 11, 15, 15, 10, 14, 16, 20, 18, 14, 18, 72});
        sheet.setDefaultRowHeightInPoints(20);
    }

    private void writeAssignmentPoints(Workbook workbook, CellStyle headerStyle, List<CellStyle> schemeStyles, List<Map<String, Object>> schemes) {
        Sheet sheet = workbook.createSheet("点位顺序");
        writeHeader(sheet, headerStyle, "方案", "适用日", "路线", "车辆", "车型", "第几趟", "顺序", "点位角色", "点位ID", "点位名称", "设施类型", "经度", "纬度", "预计到达", "点位作业min", "预计离开", "预计重量kg", "预计体积L");
        int rowIndex = 1;
        for (int schemeIndex = 0; schemeIndex < schemes.size(); schemeIndex++) {
            Map<String, Object> scheme = schemes.get(schemeIndex);
            Map<String, Object> result = map(scheme.get("result"));
            for (Map<String, Object> route : maps(result.get("routes"))) {
                for (Map<String, Object> point : maps(route.get("points"))) {
                    Row row = sheet.createRow(rowIndex++);
                    write(row, 0, scheme.get("name"));
                    write(row, 1, applicableDays(scheme));
                    write(row, 2, route.get("routeNo"));
                    write(row, 3, vehicleName(route));
                    write(row, 4, vehicleType(route));
                    write(row, 5, route.get("tripNo"));
                    write(row, 6, point.get("order"));
                    write(row, 7, pointRoleLabel(point.get("role")));
                    write(row, 8, point.get("facilityId"));
                    write(row, 9, point.get("facilityName"));
                    write(row, 10, firstNonBlank(point.get("facilityTypeName"), "未配置"));
                    write(row, 11, point.get("longitude"));
                    write(row, 12, point.get("latitude"));
                    write(row, 13, point.get("plannedArrivalTime"));
                    write(row, 14, point.get("operationDurationMinutes"));
                    write(row, 15, point.get("plannedDepartureTime"));
                    write(row, 16, point.get("estimatedWeightKg"));
                    write(row, 17, point.get("estimatedVolumeLiter"));
                    applyRowStyle(row, schemeStyles.get(schemeIndex % schemeStyles.size()));
                }
            }
        }
        finishAssignmentSheet(sheet, rowIndex, new int[]{14, 18, 9, 18, 16, 9, 9, 12, 14, 28, 20, 15, 15, 14, 16, 14, 15, 15});
    }

    private void writeAssignmentSegments(Workbook workbook, CellStyle headerStyle, List<CellStyle> schemeStyles, List<Map<String, Object>> schemes) {
        Sheet sheet = workbook.createSheet("路段明细");
        writeHeader(sheet, headerStyle, "方案", "适用日", "路线", "车辆", "车型", "第几趟", "路段", "起点ID", "起点名称", "终点ID", "终点名称", "距离m", "耗时min", "速度km/h", "速度档位", "路径来源");
        int rowIndex = 1;
        for (int schemeIndex = 0; schemeIndex < schemes.size(); schemeIndex++) {
            Map<String, Object> scheme = schemes.get(schemeIndex);
            Map<String, Object> result = map(scheme.get("result"));
            for (Map<String, Object> route : maps(result.get("routes"))) {
                for (Map<String, Object> segment : maps(route.get("segments"))) {
                    Row row = sheet.createRow(rowIndex++);
                    write(row, 0, scheme.get("name"));
                    write(row, 1, applicableDays(scheme));
                    write(row, 2, route.get("routeNo"));
                    write(row, 3, vehicleName(route));
                    write(row, 4, vehicleType(route));
                    write(row, 5, route.get("tripNo"));
                    write(row, 6, segment.get("order"));
                    write(row, 7, segment.get("fromFacilityId"));
                    write(row, 8, segment.get("fromFacilityName"));
                    write(row, 9, segment.get("toFacilityId"));
                    write(row, 10, segment.get("toFacilityName"));
                    write(row, 11, segment.get("distance"));
                    write(row, 12, segment.get("durationMinutes"));
                    write(row, 13, segment.get("speedKmh"));
                    write(row, 14, segment.get("speedClass"));
                    write(row, 15, pathSourceLabel(segment.get("pathSource")));
                    applyRowStyle(row, schemeStyles.get(schemeIndex % schemeStyles.size()));
                }
            }
        }
        finishAssignmentSheet(sheet, rowIndex, new int[]{14, 18, 9, 18, 16, 9, 9, 14, 28, 14, 28, 14, 14, 14, 14, 16});
    }

    private List<CellStyle> assignmentSchemeStyles(Workbook workbook) {
        IndexedColors[] colors = new IndexedColors[]{IndexedColors.LIGHT_CORNFLOWER_BLUE, IndexedColors.LIGHT_GREEN, IndexedColors.LIGHT_YELLOW, IndexedColors.ROSE};
        List<CellStyle> styles = new java.util.ArrayList<CellStyle>();
        for (IndexedColors color : colors) {
            CellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(color.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setBorderBottom(BorderStyle.HAIR);
            style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            styles.add(style);
        }
        return styles;
    }

    private void applyRowStyle(Row row, CellStyle style) {
        for (Cell cell : row) cell.setCellStyle(style);
    }

    private void finishAssignmentSheet(Sheet sheet, int rowCount, int[] widths) {
        sheet.createFreezePane(0, 1);
        if (rowCount > 1) sheet.setAutoFilter(new CellRangeAddress(0, rowCount - 1, 0, widths.length - 1));
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, Math.min(255, widths[i]) * 256);
    }

    private String vehicleName(Map<String, Object> route) {
        return firstNonBlank(route.get("vehicleName"), "默认车辆");
    }

    private String vehicleType(Map<String, Object> route) {
        return firstNonBlank(route.get("vehicleType"), "未配置");
    }

    private String pointRoleLabel(Object role) {
        String value = value(role);
        if ("START".equals(value)) return "起点";
        if ("END".equals(value)) return "终点";
        if ("MIDDLE".equals(value)) return "收运点";
        return value;
    }

    private String distanceModeLabel(Map<String, Object> result) {
        String mode = value(result.get("distanceMode"));
        if ("ROAD".equals(mode)) return "道路距离（OD/百度）";
        if ("DIRECT".equals(mode)) return "直线距离";
        return "未标明";
    }

    private String applicableDays(Map<String, Object> scheme) {
        Object days = scheme.get("applicableDays");
        if (days instanceof List && !((List<?>) days).isEmpty()) {
            StringBuilder builder = new StringBuilder();
            for (Object day : (List<?>) days) {
                if (builder.length() > 0) builder.append("、");
                builder.append("第").append(value(day)).append("天");
            }
            return builder.toString();
        }
        Object cycleDay = scheme.get("cycleDay");
        return cycleDay == null ? "" : "第" + value(cycleDay) + "天";
    }

    private String pointSequence(Map<String, Object> route) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> point : maps(route.get("points"))) {
            if (builder.length() > 0) builder.append(" -> ");
            String name = value(point.get("facilityName"));
            builder.append(name.isEmpty() ? value(point.get("facilityId")) : name);
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : java.util.Collections.<String, Object>emptyMap();
    }

    private double number(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value == null) return 0D;
        try { return Double.parseDouble(String.valueOf(value)); } catch (NumberFormatException ignored) { return 0D; }
    }

    private String firstNonBlank(Object... values) {
        for (Object item : values) {
            String text = value(item);
            if (!text.trim().isEmpty()) return text;
        }
        return "";
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
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
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
        if ("OD_CACHE".equals(value) || "OD_PRELOAD".equals(value) || "OD_PRELOAD_DISTANCE".equals(value)) {
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

