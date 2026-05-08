package com.routenav.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RouteRequest {

    @NotBlank
    private String originName;

    @NotBlank
    private String destinationName;

    @NotNull
    private Double originLat;

    @NotNull
    private Double originLon;

    @NotNull
    private Double destinationLat;

    @NotNull
    private Double destinationLon;
}

