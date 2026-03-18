package com.inventory.user.utils;

import org.springframework.util.StringUtils;

public final class TextUtils {

  private TextUtils() {}

  public static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}

