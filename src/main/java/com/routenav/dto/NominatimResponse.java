package com.routenav.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NominatimResponse {

    @JsonProperty("display_name")
    private String displayName;

    @JsonProperty("lat")
    private String lat;

    @JsonProperty("lon")
    private String lon;

    @JsonProperty("type")
    private String type;
}

