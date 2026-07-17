package com.socialapp.common.utils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class FileExtensions {

  public static String getExtension(String filename, String defaultExtension) {
    if (filename == null || !filename.contains(".")) {
      return defaultExtension;
    }
    return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
  }
}
