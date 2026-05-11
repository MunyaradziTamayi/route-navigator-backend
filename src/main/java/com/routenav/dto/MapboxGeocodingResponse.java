package com.routenav.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MapboxGeocodingResponse {

    @JsonProperty("features")
    private List<MapboxFeature> features;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MapboxFeature {

        @JsonProperty("properties")
        private MapboxProperties properties;

        @JsonProperty("geometry")
        private MapboxGeometry geometry;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MapboxProperties {

        @JsonProperty("name")
        private String name;

        @JsonProperty("full_address")
        private String fullAddress;

        @JsonProperty("place_formatted")
        private String placeFormatted;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MapboxGeometry {

        @JsonProperty("coordinates")
        private List<Double> coordinates;
    }
}
