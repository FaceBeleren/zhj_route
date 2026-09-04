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

    public List<Map<String, Object>> vehicles(String unitId, String startDate, String endDate) {
        DateRange range = range(startDate, endDate);
        String sql = "SELECT car_code AS carCode, route_id AS routeId, route_name AS routeName, "
                + "COUNT(*) AS recordCount, COUNT(DISTINCT DATE(car_start_time)) AS activeDays "
                + "FROM ljszy_route_record WHERE been_deleted=0 AND unit_id=? "
                + "AND car_start_time>=? AND car_start_time<? AND car_code IS NOT NULL AND car_code<>'' "
                + "GROUP BY car_code, route_id, route_name ORDER BY car_code, route_id";
        return jdbcTemplate.queryForList(sql, unitId, range.start.atStartOfDay(), range.end.plusDays(1).atStartOfDay());
    }

    public Map<String, Object> start(final Map<String, Object> request) {
        final String unitId = text(request == null ? null : request.get("unitId"));
        final String carCode = text(request == null ? null : request.get("carCode"));
        if (blank(unitId) || blank(carCode)) throw new IllegalArgumentException("公司和车辆不能为空");
        range(text(request.get("startDate")), text(request.get("endDate")));
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
            List<Record> records = loadRecords(text(task.request.get("unitId")), text(task.request.get("carCode")), text(task.request.get("routeId")), range);
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

    private List<Record> loadRecords(String unitId, String carCode, String routeId, DateRange range) {
        List<Object> args = new ArrayList<Object>();
        args.add(unitId); args.add(carCode); args.add(range.start.atStartOfDay()); args.add(range.end.plusDays(1).atStartOfDay());
        String sql = "SELECT id, route_id AS routeId, route_name AS routeName, car_code AS carCode, "
                + "car_start_time AS carStartTime, car_end_time AS carEndTime, collect_transport_count AS expectedTrips, "
                + "job_duration AS jobDuration, mileage AS mileage FROM ljszy_route_record "
                + "WHERE been_deleted=0 AND unit_id=? AND car_code=? AND car_start_time>=? AND car_start_time<?";
        if (!blank(routeId)) { sql += " AND route_id=?"; args.add(routeId); }
        sql += " ORDER BY car_start_time, id";
        List<Record> result = new ArrayList<Record>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, args.toArray())) {
            Record r = new Record();
            r.id = number(row.get("id")); r.routeId = number(row.get("routeId")); r.routeName = text(row.get("routeName"));
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
                    e.operationLength = decimal(row.get("operationLength")); e.longitude = decimal(row.get("longitude")); e.latitude = decimal(row.get("latitude"));
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
        task.loadedEvents = result.values().stream().mapToInt(List::size).sum();
        return result;
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
        Trip trip = new Trip(); trip.recordId = record.id; trip.date = record.start == null ? null : record.start.toLocalDate();
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
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>(); int groupNo = 1;
        for (Cluster cluster : clusters) groups.add(clusterView(cluster, groupNo++, range, complete.size()));
        result.put("groups", groups); result.put("unassignedPoints", unassignedPoints(groups));
        return result;
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
        List<Map<String,Object>> points = new ArrayList<Map<String,Object>>(); for (Long id : ordered) { Event p = catalog.get(id); Map<String,Object> row = eventView(p); int support = counts.get(id); int exact = 0; int through = 0; for (Trip trip : cluster.trips) { Event best = null; for (Event event : trip.points) if (id.equals(event.facilityId) && (best == null || (best.matchType != null && best.matchType != 0 && event.matchType != null && event.matchType == 0))) best = event; if (best != null) { if (best.matchType != null && best.matchType == 0) exact++; else if (best.matchType != null && best.matchType == 1) through++; } } row.put("support", round((double)support / cluster.trips.size())); row.put("visitCount", support); row.put("collectedCount", exact); row.put("throughCount", through); row.put("repeatLevel", support >= 3 ? "3+" : String.valueOf(support)); row.put("orderConfidence", round(orderConfidence(p.facilityId, cluster.trips, ordered))); row.put("pointSource", "FLOW_INFERRED"); points.add(row); }
        String endpointKey = mostFrequent(endpoints); Event endpoint = endpointRows.get(endpointKey);
        List<Map<String,Object>> tripViews = new ArrayList<Map<String,Object>>(); for (Trip t : cluster.trips) tripViews.add(tripView(t));
        Map<String,Object> view = new LinkedHashMap<String,Object>(); view.put("groupNo", no); view.put("groupName", "流水分堆" + no); view.put("tripCount", cluster.trips.size()); view.put("activeDays", dates.size()); view.put("stable", cluster.trips.size() >= 3 && dates.size() >= 2); view.put("stability", round(averageWithin(cluster))); view.put("supportOfAllCompleteTrips", round(totalComplete == 0 ? 0 : (double)cluster.trips.size() / totalComplete)); view.put("visitsPerWeek", round(cluster.trips.size() * 7D / Math.max(1, ChronoUnit.DAYS.between(range.start, range.end) + 1))); view.put("points", points); view.put("representativePoints", ordered); view.put("typicalEnd", endpoint == null ? null : eventView(endpoint)); view.put("alternativeEnds", endpointDistribution(endpoints, endpointRows, cluster.trips.size())); view.put("trips", tripViews); return view;
    }

    private List<Map<String,Object>> dailyTrips(List<Trip> trips) { Map<LocalDate,List<Map<String,Object>>> map = new LinkedHashMap<LocalDate,List<Map<String,Object>>>(); for (Trip t:trips) { if(!map.containsKey(t.date)) map.put(t.date,new ArrayList<Map<String,Object>>()); map.get(t.date).add(tripView(t)); } List<Map<String,Object>> out=new ArrayList<Map<String,Object>>(); for(Map.Entry<LocalDate,List<Map<String,Object>>> e:map.entrySet()){Map<String,Object> row=new LinkedHashMap<String,Object>();row.put("date",e.getKey()==null?null:e.getKey().toString());row.put("tripCount",e.getValue().size());row.put("trips",e.getValue());out.add(row);} return out; }
    private Map<String,Object> tripView(Trip t){Map<String,Object> row=new LinkedHashMap<String,Object>();row.put("recordId",t.recordId);row.put("date",t.date==null?null:t.date.toString());row.put("complete",t.complete);row.put("expectedTrips",t.expectedTrips);row.put("start",t.start==null?null:eventView(t.start));row.put("end",t.end==null?null:eventView(t.end));List<Map<String,Object>> ps=new ArrayList<Map<String,Object>>();Set<Long> unique=new HashSet<Long>();Map<Long,Integer> occurrence=new HashMap<Long,Integer>();for(Event e:t.points)if(e.facilityId!=null)occurrence.put(e.facilityId,value(occurrence.get(e.facilityId))+1);int exact=0,through=0;double operationSeconds=0;for(Event e:t.points){Map<String,Object> point=eventView(e);point.put("visitCount",e.facilityId==null?1:occurrence.get(e.facilityId));ps.add(point);if(e.facilityId!=null)unique.add(e.facilityId);if(e.matchType!=null&&e.matchType==0)exact++;if(e.matchType!=null&&e.matchType==1)through++;operationSeconds+=e.operationLength==null?0:e.operationLength;}row.put("points",ps);row.put("pointCount",t.points.size());row.put("uniquePointCount",unique.size());row.put("duplicatePointCount",Math.max(0,t.points.size()-unique.size()));row.put("collectedPointCount",exact);row.put("throughPointCount",through);row.put("operationSeconds",operationSeconds);return row;}
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
    private double avg(List<Double>v){if(v==null||v.isEmpty())return 0;double s=0;for(Double x:v)s+=x;return s/v.size();}
    private double round(double n){return Math.round(n*100D)/100D;}
    private String text(Object v){return v==null?null:String.valueOf(v);}
    private boolean blank(String v){return v==null||v.trim().isEmpty();}
    private Long number(Object v){try{return v==null?null:Long.valueOf(String.valueOf(v));}catch(Exception e){return null;}}
    private Integer integer(Object v){try{return v==null?null:Integer.valueOf(String.valueOf(v));}catch(Exception e){return null;}}
    private Double decimal(Object v){try{return v==null?0D:Double.valueOf(String.valueOf(v));}catch(Exception e){return 0D;}}
    private LocalDateTime dateTime(Object v){if(v instanceof LocalDateTime)return (LocalDateTime)v;if(v instanceof java.time.LocalDate)return ((java.time.LocalDate)v).atStartOfDay();if(v instanceof java.sql.Timestamp)return ((java.sql.Timestamp)v).toLocalDateTime();if(v instanceof java.util.Date)return LocalDateTime.ofInstant(((java.util.Date)v).toInstant(),java.time.ZoneId.systemDefault());if(v instanceof CharSequence){String s=String.valueOf(v).trim();try{return LocalDateTime.parse(s.replace(" ","T"));}catch(Exception ignored){try{return java.time.LocalDate.parse(s).atStartOfDay();}catch(Exception ignoredDate){return null;}}}return null;}
    private Map<String,Object> missing(String id){Map<String,Object>m=new HashMap<String,Object>();m.put("taskId",id);m.put("status","NOT_FOUND");m.put("message","任务不存在或服务已重启");return m;}

    private static class DateRange{final LocalDate start,end;DateRange(LocalDate s,LocalDate e){start=s;end=e;}}
    private static class Record{Long id,routeId,jobDuration,mileage;String routeName,carCode;LocalDateTime start,end;Integer expectedTrips;}
    private static class Event{Long id,recordId,facilityId;String name,mergeSign;Integer workType,matchType;LocalDateTime time,leaveTime;Double operationLength,longitude,latitude;}
    private static class Trip{Long recordId;LocalDate date;Integer expectedTrips;boolean complete;Event start,end;List<Event>points=new ArrayList<Event>();}
    private static class Cluster{List<Trip>trips=new ArrayList<Trip>();}
    private class Task{final String id;final Map<String,Object>request;volatile String status="QUEUED",phase="PREPARE",message="等待执行";volatile int totalRecords,loadedEvents;volatile long startedAt=System.currentTimeMillis(),finishedAt,elapsedMs;volatile boolean cancelled;volatile Future<?>future;volatile Map<String,Object>result;Task(String i,Map<String,Object>r){id=i;request=r==null?new HashMap<String,Object>():new HashMap<String,Object>(r);}void update(String s,String p,String m){status=s;phase=p;message=m;}Map<String,Object>view(boolean detail){Map<String,Object>m=new LinkedHashMap<String,Object>();m.put("taskId",id);m.put("status",status);m.put("phase",phase);m.put("message",message);m.put("percent",status.equals("DONE")?100:phase.equals("LOAD_EVENTS")?25:phase.equals("SPLIT_TRIPS")?55:phase.equals("CLUSTER")?80:0);m.put("totalRecords",totalRecords);m.put("loadedEvents",loadedEvents);m.put("startedAt",startedAt);m.put("finishedAt",finishedAt);m.put("elapsedMs",elapsedMs);if(detail&&result!=null)m.put("result",result);return m;}}
}
