package com.zhj.route.algorithm;

import java.sql.Time;
import java.time.LocalTime;
import java.util.Date;

public final class FacilityTimeWindow {
    private FacilityTimeWindow() {
    }

    public static Integer parseClockMinutes(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            int minutes = ((Number) value).intValue();
            return minutes >= 0 && minutes < 24 * 60 ? minutes : null;
        }
        if (value instanceof Time) {
            return localTimeMinutes(((Time) value).toLocalTime());
        }
        if (value instanceof LocalTime) {
            return localTimeMinutes((LocalTime) value);
        }
        if (value instanceof Date) {
            return localTimeMinutes(new Time(((Date) value).getTime()).toLocalTime());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        int space = text.indexOf(' ');
        if (space >= 0 && text.length() > space + 1) {
            text = text.substring(space + 1);
        }
        String[] parts = text.split(":");
        if (parts.length < 2) {
            return null;
        }
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return null;
            }
            return hour * 60 + minute;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static int minuteOfDay(double elapsedMinutes) {
        int minutes = (int) Math.floor(elapsedMinutes);
        int clock = minutes % (24 * 60);
        return clock < 0 ? clock + 24 * 60 : clock;
    }

    public static boolean allowsEntry(RoutePoint point, int minuteOfDay) {
        if (point == null) {
            return true;
        }
        return withinOptionalWindow(point.getAllowTimeBeginMinutes(), point.getAllowTimeEndMinutes(), minuteOfDay)
                && !withinClosedWindow(point.getBarredTimeBeginMinutes(), point.getBarredTimeEndMinutes(), minuteOfDay);
    }

    public static String format(Integer minutes) {
        if (minutes == null) {
            return null;
        }
        int clock = ((minutes % (24 * 60)) + 24 * 60) % (24 * 60);
        return String.format("%02d:%02d", clock / 60, clock % 60);
    }

    private static boolean withinOptionalWindow(Integer begin, Integer end, int minuteOfDay) {
        if (begin == null && end == null) {
            return true;
        }
        if (begin == null) {
            return minuteOfDay <= end;
        }
        if (end == null) {
            return minuteOfDay >= begin;
        }
        return withinClosedWindow(begin, end, minuteOfDay);
    }

    private static boolean withinClosedWindow(Integer begin, Integer end, int minuteOfDay) {
        if (begin == null || end == null) {
            return false;
        }
        if (begin <= end) {
            return minuteOfDay >= begin && minuteOfDay <= end;
        }
        return minuteOfDay >= begin || minuteOfDay <= end;
    }

    private static int localTimeMinutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
