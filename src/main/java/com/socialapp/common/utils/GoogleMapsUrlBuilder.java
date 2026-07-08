package com.socialapp.common.utils;

import lombok.experimental.UtilityClass;

/**
 * Builds a plain Google Maps deep link (no API key/billing required — this is just the public
 * "https://www.google.com/maps" URL scheme, not a call to the paid Maps Platform API).
 */
@UtilityClass
public class GoogleMapsUrlBuilder {

  public String build(Double latitude, Double longitude) {
    if (latitude == null || longitude == null) {
      return null;
    }
    return "https://www.google.com/maps/search/?api=1&query=" + latitude + "," + longitude;
  }
}
