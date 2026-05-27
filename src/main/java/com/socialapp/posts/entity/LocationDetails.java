package com.socialapp.posts.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.socialapp.posts.entity.enums.LocationType;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationDetails implements Serializable {
  private static final long serialVersionUID = 1L;

  @JsonProperty("google_place_id")
  private String googlePlaceId;

  @JsonProperty("location_type")
  private LocationType locationType;

  @JsonProperty("display_name")
  private String displayName;

  private Double latitude;
  private Double longitude;
  private String city;
  private String country;
}