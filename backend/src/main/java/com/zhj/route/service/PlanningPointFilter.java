package com.zhj.route.service;

import java.util.*;

/** Disposal facilities are anchors, not collection candidates. Original route records remain unchanged. */
public final class PlanningPointFilter {
    private PlanningPointFilter() {}
    public static boolean isDisposal(Map<String,Object> point) {
        Object type=point.get("facilityType");
        if(type!=null && !String.valueOf(type).trim().isEmpty()) {
            try { return Double.parseDouble(String.valueOf(type).trim())==5D; }
            catch(NumberFormatException ignored) { /* Legacy rows may only have a type label. */ }
        }
        String label=String.valueOf(point.get("facilityTypeName")).trim();
        return Arrays.asList("处置场","处理厂","垃圾处理厂","生活垃圾处理厂","垃圾处置场").contains(label);
    }
    public static List<Map<String,Object>> collections(List<Map<String,Object>> rows) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:rows)if(!isDisposal(row))result.add(row);
        return result;
    }
}
