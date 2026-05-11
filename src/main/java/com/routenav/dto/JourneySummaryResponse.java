package com.routenav.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JourneySummaryResponse {

    private String id;
    private String originLabel;
    private String destinationLabel;
    private Double distanceKm;
    private Double straightLineDistanceKm;
    private String createdAt;
}
