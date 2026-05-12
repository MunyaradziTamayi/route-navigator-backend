package com.routenav.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GoogleRoutesResponse {

    @JsonProperty("routes")
    private List<GoogleRoute> routes;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GoogleRoute {

        @JsonProperty("distanceMeters")
        private Long distanceMeters;

        @JsonProperty("duration")
        private String duration;

        @JsonProperty("staticDuration")
        private String staticDuration;

        @JsonProperty("polyline")
        private GooglePolyline polyline;

        @JsonProperty("legs")
        private List<GoogleRouteLeg> legs;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GoogleRouteLeg {

        @JsonProperty("distanceMeters")
        private Long distanceMeters;

        @JsonProperty("duration")
        private String duration;

        @JsonProperty("staticDuration")
        private String staticDuration;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GooglePolyline {

        @JsonProperty("encodedPolyline")
        private String encodedPolyline;
    }
}
