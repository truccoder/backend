package com.socialapp.security.util;

import java.util.Locale;

import lombok.experimental.UtilityClass;

@UtilityClass
public class EmailNormalizer {

  public static String normalize(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }
}
