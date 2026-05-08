package com.routenav.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComputeRouteResponse {

    private Object geoJson;
    private Double distanceKm;
    private Integer durationSeconds;
}
