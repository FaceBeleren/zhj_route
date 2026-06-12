package com.zhj.route.algorithm;

public class RoutePoint {
    private final Long facilityId;
    private final String facilityName;
    private final Double longitude;
    private final Double latitude;
    private final Integer originalOrder;

    public RoutePoint(Long facilityId, String facilityName, Double longitude, Double latitude, Integer originalOrder) {
        this.facilityId = facilityId;
        this.facilityName = facilityName;
        this.longitude = longitude;
        this.latitude = latitude;
        this.originalOrder = originalOrder;
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

    public boolean hasCoordinate() {
        return longitude != null && latitude != null;
    }
}
