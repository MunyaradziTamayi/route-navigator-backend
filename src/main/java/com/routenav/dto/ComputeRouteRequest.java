package com.routenav.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ComputeRouteRequest {

    @Valid
    @NotNull
    private Point origin;

    @Valid
    @NotNull
    private Point destination;

    private String algorithm;

    @Data
    public static class Point {
        private Double lat;

        private Double lon;

        private String label;
    }
}
