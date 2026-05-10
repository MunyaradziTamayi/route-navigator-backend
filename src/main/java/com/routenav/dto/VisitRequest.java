package com.routenav.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import lombok.Data;

@Data
public class VisitRequest {

    @Valid
    @NotNull
    private Point origin;

    @Valid
    @NotNull
    private Point destination;

    @NotEmpty
    private List<String> workerIds;

    @NotEmpty
    private List<String> workerNames;

    @NotNull
    private LocalTime startTime;

    @NotNull
    private LocalTime endTime;

    @Min(1)
    private Integer visitsPerDay;
    private String clientLabel;
    private String notes;
    private LocalDateTime scheduledDate;
    private String status;

    @NotNull
    private Double distanceKm;

    @NotNull
    private Integer durationSeconds;

    private Object geoJson;

    @Data
    public static class Point {
        @NotBlank
        private String label;

        @NotNull
        private Double lat;

        @NotNull
        private Double lon;
    }
}
