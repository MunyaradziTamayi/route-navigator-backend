package com.routenav.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteResponse {

    private Long id;
    private String originName;
    private String destinationName;
    private Double distanceKm;
    private String duration;
    private String geometry;
    private List<String> instructions;
}

