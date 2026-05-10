package com.routenav.dto;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisitResponse {

    private Long id;
    private String originLabel;
    private String destinationLabel;
    private Double originLat;
    private Double originLon;
    private Double destinationLat;
    private Double destinationLon;
    private List<String> workerIds;
    private List<String> workerNames;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer visitsPerDay;
    private Double distanceKm;
    private Integer durationSeconds;
    private Object geoJson;
    private String clientLabel;
    private String notes;
    private LocalDateTime scheduledDate;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
