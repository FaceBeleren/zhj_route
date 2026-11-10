package com.zhj.route.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class FlowBatchCompareExportService {
    public byte[] export(Map<String, Object> request) {
        List<Map<String, Object>> rows = rows(request.get("rows"));
        if (rows.size() > 90) throw new IllegalArgumentException("批量对照最多导出90天");
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("前后对比");
            CellStyle header = workbook.createCellStyle();
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font white = workbook.createFont();
            white.setColor(IndexedColors.WHITE.getIndex());
            white.setBold(true);
            header.setFont(white);
            CellStyle decimal = workbook.createCellStyle();
            decimal.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            CellStyle percent = workbook.createCellStyle();
            percent.setDataFormat(workbook.createDataFormat().getFormat("0.0%"));
            CellStyle points = workbook.createCellStyle();
            points.setDataFormat(workbook.createDataFormat().getFormat("0.0\" 个百分点\""));

            line(sheet, 0, new String[] {"规划执行前后对比", text(request.get("label"))});
            line(sheet, 1, new String[] {"车辆", text(request.get("carCode"))});
            line(sheet, 2, new String[] {"GPS 分段类型", text(request.get("segmentLabels"))});
            Map<String, Object> ranges = map(request.get("ranges"));
            line(sheet, 3, new String[] {"交付前", text(ranges.get("beforeStart")) + " 至 " + text(ranges.get("beforeEnd")),
                    "交付后", text(ranges.get("afterStart")) + " 至 " + text(ranges.get("afterEnd"))});
            String[] summaryHeaders = {"阶段", "日期数", "有效顺序天数", "符合顺序对", "可比顺序对", "合计拟合度", "GPS有效天数", "日均所选GPS里程(km)", "日均共同点"};
            header(sheet, 5, summaryHeaders, header);
            Map<String, Object> before = map(request.get("before"));
            Map<String, Object> after = map(request.get("after"));
            summary(sheet, 6, "交付前", before, decimal, percent);
            summary(sheet, 7, "交付后", after, decimal, percent);
            summary(sheet, 8, "变化（后－前）", difference(before, after), decimal, points);
            List<String> headers = new ArrayList<>(Arrays.asList("阶段", "日期", "车辆", "实际趟数", "共同收运点",
                    "符合顺序对", "可比顺序对", "拟合度", "LCS点数", "所选GPS里程(km)"));
            int segmentStart = headers.size();
            Object requestedColumns = request.get("segmentColumns");
            List<?> segmentColumns = requestedColumns instanceof List ? (List<?>) requestedColumns : null;
            if (segmentColumns == null) {
                headers.add("分段里程");
            } else {
                if (segmentColumns.size() > org.apache.poi.ss.SpreadsheetVersion.EXCEL2007.getMaxColumns() - segmentStart - 1) {
                    throw new IllegalArgumentException("分段里程列数超过 Excel 上限");
                }
                for (Object column : segmentColumns) headers.add(text(column) + " (km)");
            }
            int noteColumn = headers.size();
            headers.add("备注");
            header(sheet, 10, headers.toArray(new String[0]), header);
            int index = 11;
            for (Map<String, Object> data : rows) {
                Row row = sheet.createRow(index++);
                string(row, 0, data.get("phase"));
                string(row, 1, data.get("date"));
                string(row, 2, data.get("carCode"));
                number(row, 3, data.get("tripCount"), null);
                number(row, 4, data.get("sharedCount"), null);
                number(row, 5, data.get("correctPairs"), null);
                number(row, 6, data.get("pairCount"), null);
                number(row, 7, data.get("orderRate"), percent);
                number(row, 8, data.get("lcsLength"), null);
                Double meters = numeric(data.get("gpsMeters"));
                number(row, 9, meters == null ? null : meters / 1000D, decimal);
                if (segmentColumns == null) {
                    string(row, segmentStart, data.get("segmentBreakdown"));
                } else {
                    Map<String, Object> segmentMeters = map(data.get("segmentMeters"));
                    for (int i = 0; i < segmentColumns.size(); i++) {
                        Double segment = numeric(segmentMeters.get(text(segmentColumns.get(i))));
                        number(row, segmentStart + i, segment == null ? null : segment / 1000D, decimal);
                    }
                }
                string(row, noteColumn, data.get("note"));
            }
            line(sheet, index + 1, new String[] {"口径", "同一规划模板；每车每天全部趟次首次收运点计分。所选GPS分段逐段测距，不跨图层连线。"});
            line(sheet, index + 2, new String[] {"说明", "缺失数据留空，不当作零。疑似额外任务未核实前不扣除原始里程；里程变化不直接等于规划节约。"});
            int[] widths = {14, 18, 17, 14, 18, 16, 16, 14, 14, 22};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            for (int i = segmentStart; i < noteColumn; i++) sheet.setColumnWidth(i, (segmentColumns == null ? 60 : 27) * 256);
            sheet.setColumnWidth(noteColumn, 35 * 256);
            sheet.createFreezePane(0, 11);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("批量对照Excel导出失败", ex);
        }
    }

    private Map<String, Object> difference(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> result = new java.util.HashMap<String, Object>();
        String[] keys = {"days", "scoreDays", "correctPairs", "pairCount", "orderRate",
                "gpsDays", "averageGpsKm", "averageSharedCount"};
        for (String key : keys) {
            Double left = numeric(before.get(key));
            Double right = numeric(after.get(key));
            if (left != null && right != null) result.put(key, "orderRate".equals(key) ? (right - left) * 100D : right - left);
        }
        return result;
    }
    private void header(Sheet sheet, int index, String[] labels, CellStyle style) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < labels.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(labels[i]);
            cell.setCellStyle(style);
        }
    }

    private void summary(Sheet sheet, int index, String phase, Map<String, Object> data, CellStyle decimal, CellStyle percent) {
        Row row = sheet.createRow(index);
        string(row, 0, phase);
        number(row, 1, data.get("days"), null);
        number(row, 2, data.get("scoreDays"), null);
        number(row, 3, data.get("correctPairs"), null);
        number(row, 4, data.get("pairCount"), null);
        number(row, 5, data.get("orderRate"), percent);
        number(row, 6, data.get("gpsDays"), null);
        number(row, 7, data.get("averageGpsKm"), decimal);
        number(row, 8, data.get("averageSharedCount"), decimal);
    }

    private void line(Sheet sheet, int index, String[] values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
    }

    private void string(Row row, int index, Object value) {
        if (value != null && !String.valueOf(value).isEmpty()) row.createCell(index).setCellValue(String.valueOf(value));
    }

    private void number(Row row, int index, Object value, CellStyle style) {
        Double parsed = numeric(value);
        if (parsed == null) return;
        Cell cell = row.createCell(index);
        cell.setCellValue(parsed);
        if (style != null) cell.setCellStyle(style);
    }

    private Double numeric(Object value) {
        if (value == null || String.valueOf(value).isEmpty()) return null;
        try {
            double result = Double.parseDouble(String.valueOf(value));
            return Double.isFinite(result) ? result : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : java.util.Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : java.util.Collections.emptyList();
    }
}