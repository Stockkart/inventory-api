package com.inventory.hr.utils;

/** Great-circle distance (haversine) on a sphere of the mean Earth radius. */
public final class GeoDistance {

  /** Mean Earth radius in metres (IUGG). */
  static final double EARTH_RADIUS_METRES = 6_371_008.8;

  private GeoDistance() {}

  /** Metres between two points given in degrees. */
  public static double metres(double lat1, double lng1, double lat2, double lng2) {
    double phi1 = Math.toRadians(lat1);
    double phi2 = Math.toRadians(lat2);
    double dPhi = Math.toRadians(lat2 - lat1);
    double dLambda = Math.toRadians(lng2 - lng1);
    double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
        + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
    // min guards against a tiny overshoot above 1 from rounding
    return 2 * EARTH_RADIUS_METRES * Math.asin(Math.min(1, Math.sqrt(a)));
  }
}
