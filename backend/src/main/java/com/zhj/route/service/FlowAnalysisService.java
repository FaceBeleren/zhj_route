package com.zhj.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 从真实车辆流水中还原趟次，并归纳可复用的历史岗位分堆。
 * 该服务只读取业务流水；保存草案仍复用 RoutePlanService。
 */
@Service
public class FlowAnalysisService {
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int COLLECTION = 6;
    private static final int TRANSFER = 2;
    private static final int DISPOSAL = 5;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(1);
    private final Map<String, Task> tasks = new ConcurrentHashMap<String, Task>();

    public FlowAnalysisService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> vehicles(String unitId, String routeId, String startDate, String endDate) {
        DateRange range = range(startDate, endDate);
        if (blank(routeId)) throw new IllegalArgumentException("岗位不能为空");
        String sql = "SELECT car_code AS carCode, MAX(route_id) AS routeId, MAX(route_name) AS routeName, "
                + "COUNT(*) AS recordCount, COUNT(DISTINCT DATE(car_start_time)) AS activeDays "
                + "FROM ljszy_route_record WHERE been_deleted=0 AND unit_id=? AND route_id=? "
                + "AND car_start_time>=? AND car_start_time<? AND car_code IS NOT NULL AND car_code<>'' "
                + "GROUP BY car_code ORDER BY car_code";
        return jdbcTemplate.queryForList(sql, unitId, Long.valueOf(routeId), range.start.atStartOfDay(), range.end.plusDays(1).atStartOfDay());
    }

    public Map<String, Object> start(final Map<String, Object> request) {
        final String unitId = text(request == null ? null : request.get("unitId"));
        final String routeId = text(request == null ? null : request.get("routeId"));
        Set<String> carCodes = stringSet(request == null ? null : request.get("carCodes"));
        if (carCodes.isEmpty()) {
            String legacyCarCode = text(request == null ? null : request.get("carCode"));
            if (!blank(legacyCarCode)) carCodes.add(legacyCarCode);
        }
        if (blank(unitId) || blank(routeId)) throw new IllegalArgumentException("公司和岗位不能为空");
        if (carCodes.isEmpty()) throw new IllegalArgumentException("至少选择一辆执行车辆");
        range(text(request.get("startDate")), text(request.get("endDate")));
        request.put("carCodes", new ArrayList<String>(carCodes));
        final Task task = new Task(UUID.randomUUID().toString(), request);
        tasks.put(task.id, task);
        task.future = executor.submit(new Runnable() { @Override public void run() { execute(task); } });
        return task.view(false);
    }

    public Map<String, Object> get(String taskId) {
        Task task = tasks.get(taskId);
        return task == null ? missing(taskId) : task.view(true);
    }

    public Map<String, Object> cancel(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) return missing(taskId);
        task.cancelled = true;
        if (task.future != null) task.future.cancel(true);
        task.status = "CANCELLED";
        task.phase = "CANCELLED";
        task.message = "分析已停止";
        return task.view(false);
    }

    public Map<String, Object> adjust(String taskId, Map<String, Object> adjustments) {
        Task task = tasks.get(taskId);
        if (task == null) return missing(taskId);
        if (task.future != null && !task.future.isDone()) throw new IllegalStateException("分析尚未完成，请完成后再修正");
        task.request.put("adjustments", adjustments == null ? new HashMap<String, Object>() : adjustments);
        task.status = "QUEUED";
        task.phase = "PREPARE";
        task.message = "正在按修正重新分析";
        task.result = null;
        task.cancelled = false;
        task.future = executor.submit(new Runnable() { @Override public void run() { execute(task); } });
        return task.view(false);
    }

    private void execute(Task task) {
        long started = System.currentTimeMillis();
        try {
            DateRange range = range(text(task.request.get("startDate")), text(task.request.get("endDate")));
            task.update("RUNNING", "LOAD_RECORDS", "正在读取车辆流水");
            Set<String> carCodes = stringSet(task.request.get("carCodes"));
            if (carCodes.isEmpty()) {
                String legacyCarCode = text(task.request.get("carCode"));
                if (!blank(legacyCarCode)) carCodes.add(legacyCarCode);
            }
            List<Record> records = loadRecords(text(task.request.get("unitId")), text(task.request.get("routeId")), carCodes, range);
            task.totalRecords = records.size();
            task.update("RUNNING", "LOAD_EVENTS", "正在读取点位和场站事件");
            Map<Long, List<Event>> events = loadEvents(records, range, task);
            task.update("RUNNING", "SPLIT_TRIPS", "正在按中转站和处置场切分实际趟次");
            List<Trip> trips = buildTrips(records, events, task.request);
            task.update("RUNNING", "CLUSTER", "正在归纳历史岗位分堆");
            Map<String, Object> result = buildResult(task.request, range, records, trips);
            task.result = result;
            task.status = "DONE";
            task.phase = "DONE";
            task.message = "流水分析完成";
            task.finishedAt = System.currentTimeMillis();
        } catch (Exception ex) {
            if (task.cancelled || Thread.currentThread().isInterrupted()) {
                task.status = "CANCELLED"; task.phase = "CANCELLED"; task.message = "分析已停止";
            } else {
                task.status = "FAILED"; task.phase = "FAILED";
                task.message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
        } finally {
            task.elapsedMs = System.currentTimeMillis() - started;
            if (task.finishedAt == 0 && ("FAILED".equals(task.status) || "CANCELLED".equals(task.status))) task.finishedAt = System.currentTimeMillis();
        }
    }

    private List<Record> loadRecords(String unitId, String routeId, Set<String> carCodes, DateRange range) {
        List<Object> args = new ArrayList<Object>();
        args.add(unitId); args.add(Long.valueOf(routeId)); args.add(range.start.atStartOfDay()); args.add(range.end.plusDays(1).atStartOfDay());
        String sql = "SELECT id, route_id AS routeId, period_id AS periodId, route_name AS routeName, car_code AS carCode, "
                + "car_start_time AS carStartTime, car_end_time AS carEndTime, collect_transport_count AS expectedTrips, "
                + "job_duration AS jobDuration, mileage AS mileage FROM ljszy_route_record "
                + "WHERE been_deleted=0 AND unit_id=? AND route_id=? AND car_start_time>=? AND car_start_time<?";
        sql += " AND car_code IN (" + placeholders(carCodes.size()) + ")";
        args.addAll(carCodes);
        sql += " ORDER BY car_start_time, id";
        List<Record> result = new ArrayList<Record>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, args.toArray())) {
            Record r = new Record();
            r.id = number(row.get("id")); r.routeId = number(row.get("routeId")); r.periodId = number(row.get("periodId")); r.routeName = text(row.get("routeName"));
            r.carCode = text(row.get("carCode")); r.start = dateTime(row.get("carStartTime")); r.end = dateTime(row.get("carEndTime"));
            r.expectedTrips = integer(row.get("expectedTrips")); r.jobDuration = number(row.get("jobDuration")); r.mileage = number(row.get("mileage"));
            result.add(r);
        }
        return result;
    }

    private Map<Long, List<Event>> loadEvents(List<Record> records, DateRange range, Task task) {
        Map<Long, List<Event>> result = new HashMap<Long, List<Event>>();
        if (records.isEmpty()) return result;
        List<Long> ids = new ArrayList<Long>(); for (Record r : records) ids.add(r.id);
        for (String month : months(range)) {
            String table = "ljszy_route_facility_record_" + month;
            if (!tableExists(table)) continue;
            for (List<Long> chunk : chunks(ids, 600)) {
                String sql = "SELECT id, route_record_id AS recordId, facility_id AS facilityId, facility_name AS facilityName, "
                        + "facility_work_type AS workType, facility_match_type AS matchType, entry_point_time AS entryTime, "
                        + "leave_point_time AS leaveTime, operation_time_length AS operationLength, longitude, latitude, merge_sign AS mergeSign "
                        + "FROM " + table + " WHERE been_deleted=0 AND route_record_id IN (" + placeholders(chunk.size()) + ") "
                        + "ORDER BY COALESCE(entry_point_time, create_time), id";
                for (Map<String, Object> row : jdbcTemplate.queryForList(sql, chunk.toArray())) {
                    Event e = new Event();
                    e.id = number(row.get("id")); e.recordId = number(row.get("recordId")); e.facilityId = number(row.get("facilityId"));
                    e.name = text(row.get("facilityName")); e.workType = integer(row.get("workType")); e.matchType = integer(row.get("matchType"));
                    e.time = dateTime(row.get("entryTime")); e.leaveTime = dateTime(row.get("leaveTime")); if (e.time == null) e.time = e.leaveTime;
                    e.operationLength = decimal(row.get("operationLength")); e.longitude = nullableDecimal(row.get("longitude")); e.latitude = nullableDecimal(row.get("latitude"));
                    e.mergeSign = text(row.get("mergeSign"));
                    if (!result.containsKey(e.recordId)) result.put(e.recordId, new ArrayList<Event>());
                    result.get(e.recordId).add(e);
                }
            }
        }
        for (List<Event> list : result.values()) {
            Collections.sort(list, new Comparator<Event>() { public int compare(Event a, Event b) {
                int c = String.valueOf(a.time).compareTo(String.valueOf(b.time)); return c != 0 ? c : Long.compare(a.id, b.id);
            }});
        }
        fillMissingCoordinates(result);
        task.loadedEvents = result.values().stream().mapToInt(List::size).sum();
        return result;
    }


    private void fillMissingCoordinates(Map<Long, List<Event>> events) {
        if (events.isEmpty() || !tableExists("ljszy_facility_info")) return;
        Set<Long> facilityIds = new LinkedHashSet<Long>();
        for (List<Event> list : events.values()) for (Event event : list) if (event.facilityId != null && (event.longitude == null || event.latitude == null)) facilityIds.add(event.facilityId);
        if (facilityIds.isEmpty()) return;
        Map<Long, Double[]> coordinates = new HashMap<Long, Double[]>();
        for (List<Long> chunk : chunks(new ArrayList<Long>(facilityIds), 600)) {
            String sql = "SELECT id, longitude_done AS longitude, latitude_done AS latitude FROM ljszy_facility_info WHERE been_deleted=0 AND id IN (" + placeholders(chunk.size()) + ")";
            for (Map<String, Object> row : jdbcTemplate.queryForList(sql, chunk.toArray())) {
                Double longitude = nullableDecimal(row.get("longitude"));
                Double latitude = nullableDecimal(row.get("latitude"));
                if (longitude != null && latitude != null) coordinates.put(number(row.get("id")), new Double[]{longitude, latitude});
            }
        }
        for (List<Event> list : events.values()) for (Event event : list) {
            Double[] coordinate = coordinates.get(event.facilityId);
            if (coordinate == null) continue;
            if (event.longitude == null) event.longitude = coordinate[0];
            if (event.latitude == null) event.latitude = coordinate[1];
        }
    }

    private List<Trip> buildTrips(List<Record> records, Map<Long, List<Event>> events, Map<String, Object> request) {
        Set<Long> excluded = longSet(mapValue(request.get("adjustments"), "excludedEventIds"));
        Set<Long> forced = longSet(mapValue(request.get("adjustments"), "boundaryAfterEventIds"));
        List<Trip> result = new ArrayList<Trip>();
        for (Record record : records) {
            List<Event> normalized = normalize(events.get(record.id), excluded);
            List<Event> buffer = new ArrayList<Event>();
            Event lastStation = null;
            for (Event event : normalized) {
                if (event.workType == COLLECTION) buffer.add(event);
                boolean boundary = isStation(event) || forced.contains(event.id);
                if (boundary && !buffer.isEmpty()) {
                    result.add(makeTrip(record, buffer, isStation(event) ? event : null, true, lastStation));
                    buffer = new ArrayList<Event>();
                }
                if (isStation(event)) lastStation = event;
            }
            if (!buffer.isEmpty()) result.add(makeTrip(record, buffer, null, false, lastStation));
        }
        Collections.sort(result, new Comparator<Trip>() { public int compare(Trip a, Trip b) {
            int c = String.valueOf(a.date).compareTo(String.valueOf(b.date));
            if (c != 0) return c;
            c = String.valueOf(tripTime(a)).compareTo(String.valueOf(tripTime(b)));
            if (c != 0) return c;
            return Long.compare(a.recordId == null ? 0L : a.recordId, b.recordId == null ? 0L : b.recordId);
        }});
        return result;
    }

    private List<Event> normalize(List<Event> source, Set<Long> excluded) {
        if (source == null) return new ArrayList<Event>();
        Map<String, Event> selected = new LinkedHashMap<String, Event>();
        for (Event e : source) {
            if (excluded.contains(e.id)) continue;
            String timeKey = String.valueOf(e.time);
            if (timeKey.length() > 16) timeKey = timeKey.substring(0, 16);
            String key = String.valueOf(e.facilityId) + "|" + e.workType + "|" + timeKey + "|" + String.valueOf(e.mergeSign);
            Event previous = selected.get(key);
            if (previous == null || (previous.matchType != null && previous.matchType != 0 && e.matchType != null && e.matchType == 0)) selected.put(key, e);
        }
        List<Event> result = new ArrayList<Event>(selected.values());
        Collections.sort(result, new Comparator<Event>() { public int compare(Event a, Event b) {
            int c = String.valueOf(a.time).compareTo(String.valueOf(b.time)); return c != 0 ? c : Long.compare(a.id, b.id);
        }});
        return result;
    }

    private Trip makeTrip(Record record, List<Event> points, Event station, boolean complete, Event previousStation) {
        Trip trip = new Trip(); trip.recordId = record.id; trip.carCode = record.carCode; trip.date = record.start == null ? null : record.start.toLocalDate();
        trip.complete = complete; trip.expectedTrips = record.expectedTrips; trip.end = station; trip.start = previousStation;
        trip.points.addAll(points); return trip;
    }

    private Map<String, Object> buildResult(Map<String, Object> request, DateRange range, List<Record> records, List<Trip> trips) {
        double threshold = decimal(request.get("jaccardThreshold")); if (threshold <= 0 || threshold > 1) threshold = 0.60D;
        List<Trip> complete = new ArrayList<Trip>(); for (Trip t : trips) if (t.complete && !t.points.isEmpty()) complete.add(t);
        List<Cluster> clusters = cluster(complete, threshold);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("analysisVersion", "FLOW_V1"); result.put("unitId", request.get("unitId")); result.put("carCode", request.get("carCode"));
        result.put("startDate", range.start.toString()); result.put("endDate", range.end.toString()); result.put("periodDays", ChronoUnit.DAYS.between(range.start, range.end) + 1);
        result.put("recordCount", records.size()); result.put("tripCount", trips.size()); result.put("completeTripCount", complete.size());
        result.put("selectedCarCodes", stringSet(request.get("carCodes")));
        result.put("vehicleSummary", vehicleSummary(records, trips));
        int expectedTripCount = 0; int expectedTripRecords = 0; int mismatchRecords = 0;
        List<Map<String, Object>> tripCountChecks = new ArrayList<Map<String, Object>>();
        for (Record record : records) {
            if (record.expectedTrips == null || record.expectedTrips <= 0) continue;
            int actual = 0; for (Trip trip : trips) if (record.id == trip.recordId && trip.complete) actual++;
            expectedTripCount += record.expectedTrips; expectedTripRecords++;
            if (actual != record.expectedTrips) mismatchRecords++;
            Map<String, Object> check = new LinkedHashMap<String, Object>();
            check.put("recordId", record.id); check.put("expectedTrips", record.expectedTrips); check.put("actualCompleteTrips", actual);
            check.put("consistent", actual == record.expectedTrips); tripCountChecks.add(check);
        }
        result.put("expectedTripCount", expectedTripCount); result.put("expectedTripRecords", expectedTripRecords);
        result.put("tripCountMismatchRecords", mismatchRecords); result.put("tripCountChecks", tripCountChecks);
        int activeDays = new HashSet<LocalDate>(dates(trips)).size(); result.put("activeDays", activeDays);
        result.put("avgTripsPerActiveDay", round(activeDays == 0 ? 0 : (double) trips.size() / activeDays));
        result.put("anomalyTripCount", trips.size() - complete.size());
        result.put("dailyTrips", dailyTrips(trips));
        result.put("configuredPoints", configuredPoints(request, records));
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>(); int groupNo = 1;
        for (Cluster cluster : clusters) groups.add(clusterView(cluster, groupNo++, range, complete.size()));
        result.put("groups", groups); result.put("unassignedPoints", unassignedPoints(groups));
        return result;
    }

    private List<Map<String, Object>> configuredPoints(Map<String, Object> request, List<Record> records) {
        Set<Long> routeIds = new LinkedHashSet<Long>();
        Long requestedRouteId = number(request.get("routeId"));
        if (requestedRouteId != null) routeIds.add(requestedRouteId);
        else for (Record record : records) if (record.routeId != null) routeIds.add(record.routeId);
        if (routeIds.isEmpty()) return new ArrayList<Map<String, Object>>();
        Long frequencyPeriodId = representativePeriodId(records);
        Set<Long> recordPeriodIds = new LinkedHashSet<Long>();
        for (Record record : records) if (record.periodId != null) recordPeriodIds.add(record.periodId);
        boolean frequencyConfigChanged = recordPeriodIds.size() > 1;
        Map<String, Map<String, Object>> frequencyByPoint = new HashMap<String, Map<String, Object>>();
        if (frequencyPeriodId != null && tableExists("ljszy_schedule_period_fac_banding")) {
            List<Object> frequencyArgs = new ArrayList<Object>();
            frequencyArgs.add(frequencyPeriodId); frequencyArgs.addAll(routeIds);
            String frequencySql = "SELECT route_id AS routeId, fac_id AS facilityId, period AS frequencyPeriod, "
                    + "collect_count AS frequencyCollectCount FROM ljszy_schedule_period_fac_banding "
                    + "WHERE been_deleted=0 AND period_id=? AND route_id IN (" + placeholders(routeIds.size()) + ") "
                    + "ORDER BY id DESC";
            for (Map<String, Object> row : jdbcTemplate.queryForList(frequencySql, frequencyArgs.toArray())) {
                String key = frequencyPointKey(row.get("routeId"), row.get("facilityId"));
                if (!frequencyByPoint.containsKey(key)) frequencyByPoint.put(key, row);
            }
        }
        String sql = "SELECT b.route_id AS routeId, b.fac_id AS facilityId, b.order_num AS orderNum, "
                + "f.name AS facilityName, f.facility_type_name AS facilityTypeName, "
                + "f.longitude_done AS longitude, f.latitude_done AS latitude "
                + "FROM ljszy_route_fac_banding b LEFT JOIN ljszy_facility_info f ON f.id = b.fac_id "
                + "WHERE b.been_deleted=0 AND b.route_id IN (" + placeholders(routeIds.size()) + ") "
                + "ORDER BY b.route_id, COALESCE(b.order_num, 999999), b.id";
        List<Object> args = new ArrayList<Object>(); args.addAll(routeIds);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, args.toArray())) {
            Map<String, Object> point = new LinkedHashMap<String, Object>();
            point.put("routeId", row.get("routeId")); point.put("facilityId", row.get("facilityId"));
            point.put("orderNum", row.get("orderNum")); point.put("facilityName", row.get("facilityName"));
            point.put("facilityTypeName", row.get("facilityTypeName")); point.put("longitude", row.get("longitude"));
            point.put("latitude", row.get("latitude"));
            Map<String, Object> frequency = frequencyByPoint.get(frequencyPointKey(row.get("routeId"), row.get("facilityId")));
            if (frequency != null) {
                point.put("frequencyPeriod", frequency.get("frequencyPeriod"));
                point.put("frequencyCollectCount", frequency.get("frequencyCollectCount"));
                point.put("frequencyPeriodId", frequencyPeriodId);
                point.put("frequencyConfigChanged", frequencyConfigChanged);
            }
            rows.add(point);
        }
        return rows;
    }

    private Long representativePeriodId(List<Record> records) {
        Map<Long, Integer> counts = new LinkedHashMap<Long, Integer>();
        Map<Long, LocalDateTime> latest = new HashMap<Long, LocalDateTime>();
        for (Record record : records) {
            if (record.periodId == null) continue;
            counts.put(record.periodId, value(counts.get(record.periodId)) + 1);
            LocalDateTime currentLatest = latest.get(record.periodId);
            if (record.start != null && (currentLatest == null || record.start.isAfter(currentLatest))) latest.put(record.periodId, record.start);
        }
        Long selected = null;
        for (Long periodId : counts.keySet()) {
            if (selected == null || counts.get(periodId) > counts.get(selected)) selected = periodId;
            else if (counts.get(periodId).equals(counts.get(selected))) {
                LocalDateTime candidateTime = latest.get(periodId);
                LocalDateTime selectedTime = latest.get(selected);
                if (candidateTime != null && (selectedTime == null || candidateTime.isAfter(selectedTime))) selected = periodId;
                else if ((candidateTime == null && selectedTime == null || candidateTime != null && candidateTime.equals(selectedTime)) && periodId > selected) selected = periodId;
            }
        }
        return selected;
    }

    private String frequencyPointKey(Object routeId, Object facilityId) {
        return String.valueOf(routeId) + ":" + String.valueOf(facilityId);
    }
    private List<Cluster> cluster(List<Trip> trips, double threshold) {
        List<Cluster> clusters = new ArrayList<Cluster>(); for (Trip t : trips) { Cluster c = new Cluster(); c.trips.add(t); clusters.add(c); }
        while (true) {
            double best = threshold; int ai = -1, bi = -1;
            for (int i = 0; i < clusters.size(); i++) for (int j = i + 1; j < clusters.size(); j++) {
                double sim = averageSimilarity(clusters.get(i), clusters.get(j));
                if (sim >= best) { best = sim; ai = i; bi = j; }
            }
            if (ai < 0) break; clusters.get(ai).trips.addAll(clusters.get(bi).trips); clusters.remove(bi);
        }
        Collections.sort(clusters, new Comparator<Cluster>() { public int compare(Cluster a, Cluster b) { return Integer.compare(b.trips.size(), a.trips.size()); } });
        return clusters;
    }

    private double averageSimilarity(Cluster a, Cluster b) {
        double total = 0; int count = 0; for (Trip x : a.trips) for (Trip y : b.trips) { total += jaccard(ids(x.points), ids(y.points)); count++; }
        return count == 0 ? 0 : total / count;
    }

    private Map<String, Object> clusterView(Cluster cluster, int no, DateRange range, int totalComplete) {
        Map<Long, Integer> counts = new HashMap<Long, Integer>(); Map<Long, Event> catalog = new HashMap<Long, Event>(); Map<Long, List<Double>> positions = new HashMap<Long, List<Double>>();
        Map<String, Integer> endpoints = new HashMap<String, Integer>(); Map<String, Event> endpointRows = new HashMap<String, Event>(); Set<LocalDate> dates = new HashSet<LocalDate>();
        for (Trip trip : cluster.trips) {
            dates.add(trip.date); if (trip.end != null) { String key = String.valueOf(trip.end.facilityId); endpoints.put(key, value(endpoints.get(key)) + 1); endpointRows.put(key, trip.end); }
            Set<Long> seenInTrip = new HashSet<Long>(); for (int i = 0; i < trip.points.size(); i++) { Event p = trip.points.get(i); if (p.facilityId == null || !seenInTrip.add(p.facilityId)) continue; counts.put(p.facilityId, value(counts.get(p.facilityId)) + 1); catalog.put(p.facilityId, p); if (!positions.containsKey(p.facilityId)) positions.put(p.facilityId, new ArrayList<Double>()); positions.get(p.facilityId).add(trip.points.size() <= 1 ? 0D : (double)i / (trip.points.size() - 1)); }
        }
        List<Long> ordered = new ArrayList<Long>(counts.keySet()); Collections.sort(ordered, new Comparator<Long>() { public int compare(Long a, Long b) { return Double.compare(avg(positions.get(a)), avg(positions.get(b))); } });
        List<Map<String,Object>> points = new ArrayList<Map<String,Object>>(); for (Long id : ordered) { Event p = catalog.get(id); Map<String,Object> row = eventView(p); int support = counts.get(id); int exact = 0; int through = 0; for (Trip trip : cluster.trips) { Event best = null; for (Event event : trip.points) if (id.equals(event.facilityId) && (best == null || (best.matchType != null && best.matchType != 0 && event.matchType != null && event.matchType == 0))) best = event; if (best != null) { if (best.matchType != null && best.matchType == 0) exact++; else if (best.matchType != null && best.matchType == 1) through++; } } row.put("support", round((double)support / cluster.trips.size())); row.put("visitCount", support); row.put("collectedCount", exact); row.put("throughCount", through); row.put("finalMatchType", exact > 0 ? 0 : through > 0 ? 1 : null); row.put("finalMatchLabel", exact > 0 ? "已收运" : through > 0 ? "仅途经" : "未知"); row.put("repeatLevel", support >= 3 ? "3+" : String.valueOf(support)); row.put("orderConfidence", round(orderConfidence(p.facilityId, cluster.trips, ordered))); row.put("pointSource", "FLOW_INFERRED"); points.add(row); }
        String endpointKey = mostFrequent(endpoints); Event endpoint = endpointRows.get(endpointKey);
        List<Map<String,Object>> tripViews = new ArrayList<Map<String,Object>>(); for (Trip t : cluster.trips) tripViews.add(tripView(t));
        Map<String,Object> view = new LinkedHashMap<String,Object>(); view.put("groupNo", no); view.put("groupName", "流水分堆" + no); view.put("tripCount", cluster.trips.size()); view.put("activeDays", dates.size()); view.put("stable", cluster.trips.size() >= 3 && dates.size() >= 2); view.put("stability", round(averageWithin(cluster))); view.put("supportOfAllCompleteTrips", round(totalComplete == 0 ? 0 : (double)cluster.trips.size() / totalComplete)); view.put("visitsPerWeek", round(cluster.trips.size() * 7D / Math.max(1, ChronoUnit.DAYS.between(range.start, range.end) + 1))); view.put("points", points); view.put("representativePoints", ordered); view.put("typicalEnd", endpoint == null ? null : eventView(endpoint)); view.put("alternativeEnds", endpointDistribution(endpoints, endpointRows, cluster.trips.size())); view.put("trips", tripViews); return view;
    }

    private List<Map<String,Object>> dailyTrips(List<Trip> trips) {
        Map<LocalDate, List<Map<String,Object>>> byDate = new LinkedHashMap<LocalDate, List<Map<String,Object>>>();
        for (Trip trip : trips) {
            LocalDate date = trip.date;
            if (!byDate.containsKey(date)) byDate.put(date, new ArrayList<Map<String,Object>>());
            byDate.get(date).add(tripView(trip));
        }
        List<Map<String,Object>> out = new ArrayList<Map<String,Object>>();
        for (Map.Entry<LocalDate, List<Map<String,Object>>> entry : byDate.entrySet()) {
            Map<String,Object> row = new LinkedHashMap<String,Object>();
            row.put("date", entry.getKey() == null ? null : entry.getKey().toString());
            row.put("tripCount", entry.getValue().size());
            row.put("trips", entry.getValue());
            applyDailyPointOccurrences(entry.getValue(), row);
            out.add(row);
        }
        return out;
    }

    /** Add day-level counts while retaining every trip event in chronological order. */
    private void applyDailyPointOccurrences(List<Map<String,Object>> trips, Map<String,Object> day) {
        Map<Long, Map<String,Object>> stats = new LinkedHashMap<Long, Map<String,Object>>();
        Map<Long, Integer> totalIndexes = new HashMap<Long, Integer>();
        Map<Long, Integer> collectedIndexes = new HashMap<Long, Integer>();
        Map<Long, Integer> throughIndexes = new HashMap<Long, Integer>();
        for (Map<String,Object> trip : trips) {
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> points = (List<Map<String,Object>>) trip.get("points");
            if (points == null) continue;
            for (Map<String,Object> point : points) {
                Long facilityId = point.get("facilityId") == null ? null : number(point.get("facilityId"));
                if (facilityId == null) continue;
                Map<String,Object> stat = stats.get(facilityId);
                if (stat == null) {
                    stat = new LinkedHashMap<String,Object>();
                    stat.put("facilityId", facilityId);
                    stat.put("facilityName", point.get("facilityName"));
                    stat.put("totalCount", 0);
                    stat.put("collectedCount", 0);
                    stat.put("throughCount", 0);
                    stats.put(facilityId, stat);
                }
                int totalIndex = value(totalIndexes.get(facilityId)) + 1;
                totalIndexes.put(facilityId, totalIndex);
                stat.put("totalCount", totalIndex);
                point.put("dailyVisitIndex", totalIndex);
                Integer matchType = integer(point.get("matchType"));
                if (matchType != null && matchType == 0) {
                    int index = value(collectedIndexes.get(facilityId)) + 1;
                    collectedIndexes.put(facilityId, index);
                    stat.put("collectedCount", index);
                    point.put("typeVisitIndex", index);
                } else if (matchType != null && matchType == 1) {
                    int index = value(throughIndexes.get(facilityId)) + 1;
                    throughIndexes.put(facilityId, index);
                    stat.put("throughCount", index);
                    point.put("typeVisitIndex", index);
                }
            }
        }
        for (Map<String,Object> trip : trips) {
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> points = (List<Map<String,Object>>) trip.get("points");
            if (points == null) continue;
            for (Map<String,Object> point : points) {
                Long facilityId = point.get("facilityId") == null ? null : number(point.get("facilityId"));
                if (facilityId == null || !stats.containsKey(facilityId)) continue;
                Map<String,Object> stat = stats.get(facilityId);
                point.put("dailyVisitTotal", stat.get("totalCount"));
                Integer matchType = integer(point.get("matchType"));
                point.put("typeVisitTotal", matchType != null && matchType == 0 ? stat.get("collectedCount") : matchType != null && matchType == 1 ? stat.get("throughCount") : 0);
            }
        }
        List<Map<String,Object>> pointStats = new ArrayList<Map<String,Object>>();
        for (Map<String,Object> stat : stats.values()) {
            int collected = intValue(stat.get("collectedCount"));
            int through = intValue(stat.get("throughCount"));
            stat.put("finalMatchType", collected > 0 ? 0 : through > 0 ? 1 : null);
            stat.put("finalMatchLabel", collected > 0 ? "已收运" : through > 0 ? "仅途经" : "未知");
            stat.put("totalCount", collected + through);
            pointStats.add(stat);
        }
        day.put("pointStats", pointStats);
    }

    private Map<String,Object> tripView(Trip t) {
        Map<String,Object> row=new LinkedHashMap<String,Object>();
        row.put("recordId",t.recordId); row.put("carCode",t.carCode); row.put("date",t.date==null?null:t.date.toString());
        row.put("complete",t.complete); row.put("expectedTrips",t.expectedTrips);
        row.put("start",t.start==null?null:eventView(t.start)); row.put("end",t.end==null?null:eventView(t.end));
        List<Map<String,Object>> ps=new ArrayList<Map<String,Object>>(); Set<Long> unique=new HashSet<Long>();
        Map<Long,Integer> occurrence=new HashMap<Long,Integer>(); Map<Long,Integer> collectedOccurrence=new HashMap<Long,Integer>(); Map<Long,Integer> throughOccurrence=new HashMap<Long,Integer>();
        for(Event e:t.points) if(e.facilityId!=null) occurrence.put(e.facilityId,value(occurrence.get(e.facilityId))+1);
        int exact=0,through=0; double operationSeconds=0;
        for(Event e:t.points){
            Map<String,Object> point=eventView(e);
            int totalVisit = e.facilityId == null ? 1 : occurrence.get(e.facilityId);
            point.put("visitCount", totalVisit);
            if (e.facilityId != null && e.matchType != null && e.matchType == 0) {
                int index = value(collectedOccurrence.get(e.facilityId)) + 1; collectedOccurrence.put(e.facilityId, index); point.put("typeVisitIndex", index); point.put("typeVisitTotal", null);
            } else if (e.facilityId != null && e.matchType != null && e.matchType == 1) {
                int index = value(throughOccurrence.get(e.facilityId)) + 1; throughOccurrence.put(e.facilityId, index); point.put("typeVisitIndex", index); point.put("typeVisitTotal", null);
            }
            ps.add(point); if(e.facilityId!=null)unique.add(e.facilityId); if(e.matchType!=null&&e.matchType==0)exact++; if(e.matchType!=null&&e.matchType==1)through++; operationSeconds+=e.operationLength==null?0:e.operationLength;
        }
        row.put("points",ps); row.put("pointCount",t.points.size()); row.put("uniquePointCount",unique.size());
        row.put("duplicatePointCount",Math.max(0,t.points.size()-unique.size())); row.put("collectedPointCount",exact); row.put("throughPointCount",through); row.put("operationSeconds",operationSeconds); return row;
    }

    private List<Map<String,Object>> vehicleSummary(List<Record> records, List<Trip> trips) {
        Map<String, Map<String,Object>> summary = new LinkedHashMap<String, Map<String,Object>>();
        for (Record record : records) {
            if (blank(record.carCode)) continue;
            Map<String,Object> row = summary.get(record.carCode);
            if (row == null) { row = new LinkedHashMap<String,Object>(); row.put("carCode", record.carCode); row.put("recordCount", 0); row.put("activeDays", new HashSet<LocalDate>()); row.put("tripCount", 0); summary.put(record.carCode, row); }
            row.put("recordCount", intValue(row.get("recordCount")) + 1);
            @SuppressWarnings("unchecked") Set<LocalDate> days = (Set<LocalDate>) row.get("activeDays");
            if (record.start != null) days.add(record.start.toLocalDate());
        }
        for (Trip trip : trips) {
            Map<String,Object> row = summary.get(trip.carCode);
            if (row != null) row.put("tripCount", intValue(row.get("tripCount")) + 1);
        }
        List<Map<String,Object>> out = new ArrayList<Map<String,Object>>();
        for (Map<String,Object> row : summary.values()) {
            @SuppressWarnings("unchecked") Set<LocalDate> days = (Set<LocalDate>) row.remove("activeDays");
            row.put("activeDays", days == null ? 0 : days.size()); out.add(row);
        }
        return out;
    }

    private Map<String,Object> eventView(Event e){Map<String,Object> row=new LinkedHashMap<String,Object>();row.put("eventId",e.id);row.put("facilityId",e.facilityId);row.put("facilityName",e.name);row.put("facilityWorkType",e.workType);row.put("matchType",e.matchType);row.put("matchLabel",e.matchType!=null&&e.matchType==0?"已收":e.matchType!=null&&e.matchType==1?"途经":"未知");row.put("time",e.time);row.put("leaveTime",e.leaveTime);row.put("longitude",e.longitude);row.put("latitude",e.latitude);row.put("operationLength",e.operationLength);return row;}
    private List<Map<String,Object>> endpointDistribution(Map<String,Integer> counts,Map<String,Event> rows,int total){List<Map<String,Object>> out=new ArrayList<Map<String,Object>>();for(String key:counts.keySet()){Map<String,Object> row=eventView(rows.get(key));row.put("count",counts.get(key));row.put("ratio",round((double)counts.get(key)/Math.max(1,total)));out.add(row);}return out;}
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> unassignedPoints(List<Map<String,Object>> groups){List<Map<String,Object>> out=new ArrayList<Map<String,Object>>();for(Map<String,Object> g:groups){for(Map<String,Object> p:(List<Map<String,Object>>)g.get("points")){double support=decimal(p.get("support"));if(support<0.3D){Map<String,Object> row=new LinkedHashMap<String,Object>(p);row.put("reason","分堆内出现率低于30%");out.add(row);}}}return out;}
    private double orderConfidence(Long id,List<Trip> trips,List<Long> ordered){int index=ordered.indexOf(id);if(index<0)return 0;int total=0,good=0;for(Trip t:trips){int pos=-1;for(int i=0;i<t.points.size();i++)if(id.equals(t.points.get(i).facilityId)){pos=i;break;}if(pos<0)continue;for(int j=0;j<t.points.size();j++){int expected=ordered.indexOf(t.points.get(j).facilityId);if(expected>=0){total++;if((pos-j)*(index-expected)>=0)good++;}}}return total==0?0:(double)good/total;}
    private double averageWithin(Cluster c){if(c.trips.size()<2)return 1;double total=0;int n=0;for(int i=0;i<c.trips.size();i++)for(int j=i+1;j<c.trips.size();j++){total+=jaccard(ids(c.trips.get(i).points),ids(c.trips.get(j).points));n++;}return n==0?0:total/n;}
    private Set<Long> ids(List<Event> es){Set<Long>s=new HashSet<Long>();for(Event e:es)if(e.facilityId!=null)s.add(e.facilityId);return s;}
    private double jaccard(Set<Long>a,Set<Long>b){Set<Long>i=new HashSet<Long>(a);i.retainAll(b);Set<Long>u=new HashSet<Long>(a);u.addAll(b);return u.isEmpty()?1:(double)i.size()/u.size();}
    private boolean isStation(Event e){return e.workType!=null&&(e.workType==TRANSFER||e.workType==DISPOSAL);}
    private boolean tableExists(String table){return !jdbcTemplate.queryForList("SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=? LIMIT 1",table).isEmpty();}
    private DateRange range(String s,String e){LocalDate end=blank(e)?LocalDate.now():LocalDate.parse(e,DATE);LocalDate start=blank(s)?end.minusDays(29):LocalDate.parse(s,DATE);if(start.isAfter(end))throw new IllegalArgumentException("开始日期不能晚于结束日期");if(ChronoUnit.DAYS.between(start,end)>90)throw new IllegalArgumentException("日期范围不能超过91天");return new DateRange(start,end);}
    private List<String> months(DateRange r){List<String> out=new ArrayList<String>();LocalDate d=r.start.withDayOfMonth(1);while(!d.isAfter(r.end)){out.add(d.format(MONTH));d=d.plusMonths(1);}return out;}
    private List<List<Long>> chunks(List<Long> all,int size){List<List<Long>>out=new ArrayList<List<Long>>();for(int i=0;i<all.size();i+=size)out.add(new ArrayList<Long>(all.subList(i,Math.min(all.size(),i+size))));return out;}
    private String placeholders(int n){StringBuilder b=new StringBuilder();for(int i=0;i<n;i++){if(i>0)b.append(',');b.append('?');}return b.toString();}
    private Map<String,Object> mapValue(Object v,String key){if(v instanceof Map){Object x=((Map<?,?>)v).get(key);if(x instanceof Map)return (Map<String,Object>)x;if(x instanceof List){Map<String,Object> m=new HashMap<String,Object>();m.put(key,x);return m;}}return new HashMap<String,Object>();}
    private Set<Long> longSet(Map<String,Object> m){Set<Long>s=new HashSet<Long>();Object v=m.get("boundaryAfterEventIds");if(v==null)v=m.get("excludedEventIds");if(v instanceof List)for(Object x:(List<?>)v){Long n=number(x);if(n!=null)s.add(n);}return s;}
    private List<LocalDate> dates(List<Trip> ts){List<LocalDate>out=new ArrayList<LocalDate>();for(Trip t:ts)out.add(t.date);return out;}
    private String mostFrequent(Map<String,Integer> m){String best=null;int n=-1;for(Map.Entry<String,Integer>e:m.entrySet())if(e.getValue()>n){best=e.getKey();n=e.getValue();}return best;}
    private int value(Integer n){return n==null?0:n;}
    private int intValue(Object v){try{return v==null?0:Integer.parseInt(String.valueOf(v));}catch(Exception e){return 0;}}
    private double avg(List<Double>v){if(v==null||v.isEmpty())return 0;double s=0;for(Double x:v)s+=x;return s/v.size();}
    private double round(double n){return Math.round(n*100D)/100D;}
    private LocalDateTime tripTime(Trip trip) { if (trip == null || trip.points == null || trip.points.isEmpty()) return null; return trip.points.get(0).time; }
    private Set<String> stringSet(Object value) { Set<String> out = new LinkedHashSet<String>(); if (value instanceof Iterable) for (Object item : (Iterable<?>) value) { String text = text(item); if (!blank(text)) out.add(text); } else { String text = text(value); if (!blank(text)) out.add(text); } return out; }
    private String text(Object v){return v==null?null:String.valueOf(v);}
    private boolean blank(String v){return v==null||v.trim().isEmpty();}
    private Long number(Object v){try{return v==null?null:Long.valueOf(String.valueOf(v));}catch(Exception e){return null;}}
    private Integer integer(Object v){try{return v==null?null:Integer.valueOf(String.valueOf(v));}catch(Exception e){return null;}}
    private Double nullableDecimal(Object v){if(v==null)return null;String s=String.valueOf(v).trim();if(s.isEmpty()||"null".equalsIgnoreCase(s))return null;try{return Double.valueOf(s);}catch(Exception e){return null;}}
    private Double decimal(Object v){try{return v==null?0D:Double.valueOf(String.valueOf(v));}catch(Exception e){return 0D;}}
    private LocalDateTime dateTime(Object v){if(v instanceof LocalDateTime)return (LocalDateTime)v;if(v instanceof java.time.LocalDate)return ((java.time.LocalDate)v).atStartOfDay();if(v instanceof java.sql.Timestamp)return ((java.sql.Timestamp)v).toLocalDateTime();if(v instanceof java.util.Date)return LocalDateTime.ofInstant(((java.util.Date)v).toInstant(),java.time.ZoneId.systemDefault());if(v instanceof CharSequence){String s=String.valueOf(v).trim();try{return LocalDateTime.parse(s.replace(" ","T"));}catch(Exception ignored){try{return java.time.LocalDate.parse(s).atStartOfDay();}catch(Exception ignoredDate){return null;}}}return null;}
    private Map<String,Object> missing(String id){Map<String,Object>m=new HashMap<String,Object>();m.put("taskId",id);m.put("status","NOT_FOUND");m.put("message","任务不存在或服务已重启");return m;}

    private static class DateRange{final LocalDate start,end;DateRange(LocalDate s,LocalDate e){start=s;end=e;}}
    private static class Record{Long id,routeId,periodId,jobDuration,mileage;String routeName,carCode;LocalDateTime start,end;Integer expectedTrips;}
    private static class Event{Long id,recordId,facilityId;String name,mergeSign;Integer workType,matchType;LocalDateTime time,leaveTime;Double operationLength,longitude,latitude;}
    private static class Trip{Long recordId;LocalDate date;String carCode;Integer expectedTrips;boolean complete;Event start,end;List<Event>points=new ArrayList<Event>();}
    private static class Cluster{List<Trip>trips=new ArrayList<Trip>();}
    private class Task{final String id;final Map<String,Object>request;volatile String status="QUEUED",phase="PREPARE",message="等待执行";volatile int totalRecords,loadedEvents;volatile long startedAt=System.currentTimeMillis(),finishedAt,elapsedMs;volatile boolean cancelled;volatile Future<?>future;volatile Map<String,Object>result;Task(String i,Map<String,Object>r){id=i;request=r==null?new HashMap<String,Object>():new HashMap<String,Object>(r);}void update(String s,String p,String m){status=s;phase=p;message=m;}Map<String,Object>view(boolean detail){Map<String,Object>m=new LinkedHashMap<String,Object>();m.put("taskId",id);m.put("status",status);m.put("phase",phase);m.put("message",message);m.put("percent",status.equals("DONE")?100:phase.equals("LOAD_EVENTS")?25:phase.equals("SPLIT_TRIPS")?55:phase.equals("CLUSTER")?80:0);m.put("totalRecords",totalRecords);m.put("loadedEvents",loadedEvents);m.put("startedAt",startedAt);m.put("finishedAt",finishedAt);m.put("elapsedMs",elapsedMs);if(detail&&result!=null)m.put("result",result);return m;}}
}
