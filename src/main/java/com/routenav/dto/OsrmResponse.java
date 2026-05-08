package com.routenav.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OsrmResponse {

    @JsonProperty("code")
    private String code;

    @JsonProperty("routes")
    private List<OsrmRoute> routes;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OsrmRoute {

        @JsonProperty("distance")
        private Double distance;

        @JsonProperty("duration")
        private Double duration;

        @JsonProperty("geometry")
        private OsrmGeometry geometry;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OsrmGeometry {

        @JsonProperty("type")
        private String type;

        @JsonProperty("coordinates")
        private List<List<Double>> coordinates;
    }
}

