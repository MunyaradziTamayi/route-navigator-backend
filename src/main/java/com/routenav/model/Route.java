package com.routenav.model;

import java.time.LocalDateTime;
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
@Table("routes")
public class Route {

    @Id
    private Long id;
    private String originName;
    private String destinationName;
    private Double originLat;
    private Double originLon;
    private Double destinationLat;
    private Double destinationLon;
    private Double distanceKm;
    private Integer durationSeconds;
    private String geometry;
    private LocalDateTime createdAt;
}

