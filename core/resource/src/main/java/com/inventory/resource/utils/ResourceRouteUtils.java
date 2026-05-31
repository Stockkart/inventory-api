package com.inventory.resource.utils;

import com.inventory.resource.domain.model.TutorialResource;

public final class ResourceRouteUtils {

  private ResourceRouteUtils() {}

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

  public static boolean matchesRoute(TutorialResource resource, String path) {
    if (resource.getRoutePaths() == null || resource.getRoutePaths().isEmpty()) {
      return false;
    }
    for (String routePath : resource.getRoutePaths()) {
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

  public static int routeMatchScore(TutorialResource resource, String path) {
    if (resource.getRoutePaths() == null) {
      return 0;
    }
    int best = 0;
    for (String routePath : resource.getRoutePaths()) {
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
