package com.zhj.route.algorithm;

public class RoutePoint {
    private final Long facilityId;
    private final String facilityName;
    private final Double longitude;
    private final Double latitude;
    private final Integer originalOrder;
    private final Double estimatedVolumeLiter;
    private final Double estimatedWeightKg;
    private final String containerInfo;
    private final Double containerCount;
    private final Double litersPerTon;
    private final String weightSource;

    public RoutePoint(Long facilityId, String facilityName, Double longitude, Double latitude, Integer originalOrder,
                      Double estimatedVolumeLiter, Double estimatedWeightKg, String containerInfo,
                      Double containerCount, Double litersPerTon, String weightSource) {
        this.facilityId = facilityId;
        this.facilityName = facilityName;
        this.longitude = longitude;
        this.latitude = latitude;
        this.originalOrder = originalOrder;
        this.estimatedVolumeLiter = estimatedVolumeLiter;
        this.estimatedWeightKg = estimatedWeightKg;
        this.containerInfo = containerInfo;
        this.containerCount = containerCount;
        this.litersPerTon = litersPerTon;
        this.weightSource = weightSource;
    }

    public Long getFacilityId() {
        return facilityId;
    }

    public String getFacilityName() {
        return facilityName;
    }

    public Double getLongitude() {
        return longitude;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Integer getOriginalOrder() {
        return originalOrder;
    }

    public Double getEstimatedVolumeLiter() {
        return estimatedVolumeLiter;
    }

    public Double getEstimatedWeightKg() {
        return estimatedWeightKg;
    }

    public String getContainerInfo() {
        return containerInfo;
    }

    public Double getContainerCount() {
        return containerCount;
    }

    public Double getLitersPerTon() {
        return litersPerTon;
    }

    public String getWeightSource() {
        return weightSource;
    }

    public boolean hasCoordinate() {
        return longitude != null && latitude != null;
    }
}


