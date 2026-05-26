package com.routenav.dto;

import lombok.Data;

@Data
public class GoogleDistanceMatrixResponse {

    private Integer originIndex;

    private Integer destinationIndex;

    private Long distanceMeters;

    private String condition;

    private GoogleRouteMatrixStatus status;

    @Data
    public static class GoogleRouteMatrixStatus {
        private Integer code;
        private String message;
        private String status;
    }
}
