package com.inventory.video.utils;

import com.inventory.video.domain.model.TutorialVideo;
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

  public static String resolveVideoId(TutorialVideo video) {
    if (video == null) {
      return null;
    }
    if (video.getYoutubeVideoId() != null && !video.getYoutubeVideoId().isBlank()) {
      return video.getYoutubeVideoId();
    }
    return extractVideoId(video.getYoutubeUrl());
  }
}
