package com.inventory.resource.utils;

import com.inventory.resource.domain.model.TutorialResource;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YoutubeUrlUtils {

  private static final Pattern ID_PATTERN =
      Pattern.compile(
          "(?:youtube\\.com/(?:[^/]+/.+/|(?:v|e(?:mbed)?)/|.*[?&]v=)|youtu\\.be/)([A-Za-z0-9_-]{11})");

  private YoutubeUrlUtils() {}

  public static String extractVideoId(String youtubeUrl) {
    if (youtubeUrl == null || youtubeUrl.isBlank()) {
      return null;
    }
    String trimmed = youtubeUrl.trim();
    if (trimmed.matches("[A-Za-z0-9_-]{11}")) {
      return trimmed;
    }
    Matcher matcher = ID_PATTERN.matcher(trimmed);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return null;
  }

  public static String resolveVideoId(TutorialResource resource) {
    if (resource == null) {
      return null;
    }
    if (resource.getYoutubeVideoId() != null && !resource.getYoutubeVideoId().isBlank()) {
      return resource.getYoutubeVideoId();
    }
    return extractVideoId(resource.getYoutubeUrl());
  }
}
