package com.routenav.model;

import java.time.LocalDateTime;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("visits")
public class Visit {

    @Id
    private Long id;
    private String originLabel;
    private String destinationLabel;
    private Double originLat;
    private Double originLon;
    private Double destinationLat;
    private Double destinationLon;
    private String clientLabel;
    private String notes;
    private LocalDateTime scheduledDate;
    private String workerIds;
    private String workerNames;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer visitsPerDay;
    private Double distanceKm;
    private Integer durationSeconds;
    private String geometry;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
