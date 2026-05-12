package com.routenav.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RouteRequest {

    @NotBlank
    private String originName;

    @NotBlank
    private String destinationName;

    private Double originLat;

    private Double originLon;

    private Double destinationLat;

    private Double destinationLon;
}
