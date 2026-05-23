package com.inventory.help.utils;

import com.inventory.help.domain.model.HelpVideo;

public final class HelpVideoPathUtils {

  private HelpVideoPathUtils() {}

  public static String normalizePath(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      return "/dashboard";
    }
    String path = rawPath.trim();
    if (!path.startsWith("/")) {
      path = "/" + path;
    }
    if (path.length() > 1 && path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

  public static boolean matchesRoute(HelpVideo video, String path) {
    if (video.getRoutePaths() == null || video.getRoutePaths().isEmpty()) {
      return false;
    }
    for (String routePath : video.getRoutePaths()) {
      String normalized = normalizePath(routePath);
      if (normalized.isEmpty()) {
        continue;
      }
      if (path.equals(normalized)) {
        return true;
      }
      if (normalized.equals("/dashboard") && path.equals("/dashboard")) {
        return true;
      }
      if (!normalized.equals("/dashboard") && path.startsWith(normalized + "/")) {
        return true;
      }
    }
    return false;
  }

  public static int routeMatchScore(HelpVideo video, String path) {
    if (video.getRoutePaths() == null) {
      return 0;
    }
    int best = 0;
    for (String routePath : video.getRoutePaths()) {
      String normalized = normalizePath(routePath);
      if (path.equals(normalized)) {
        best = Math.max(best, normalized.length() + 1000);
      } else if (path.startsWith(normalized + "/")) {
        best = Math.max(best, normalized.length());
      }
    }
    return best;
  }
}
