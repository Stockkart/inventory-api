package com.inventory.product.rest.dto.response;

import com.inventory.pluginengine.cards.CardSurfaceDefinition;
import com.inventory.pluginengine.cards.CardVariant;
import com.inventory.product.cardlayout.ResolvedCardLayout;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * One card surface with its resolved layout per variant (configurable-product-card Req 4.1–4.5).
 *
 * <p>JSON shape:
 *
 * <pre>{@code
 * { "surfaceId": "product-search", "label": "Product search", "billingModeAware": true,
 *   "isDefault": false, "updatedAt": "…", "updatedByUserId": "u_1",
 *   "variants": { "REGULAR": { "sections": [...], "options": {...} }, "BASIC": {...} } }
 * }</pre>
 *
 * @param isDefault true when no document is saved and the built-in default is being served
 */
public record SurfaceLayoutResponse(
    String surfaceId,
    String label,
    boolean billingModeAware,
    boolean isDefault,
    Instant updatedAt,
    String updatedByUserId,
    Map<CardVariant, ResolvedCardLayout> variants) {

  public SurfaceLayoutResponse {
    variants = variants == null ? Map.of() : Map.copyOf(new TreeMap<>(variants));
  }

  public static SurfaceLayoutResponse of(
      CardSurfaceDefinition surface,
      Map<CardVariant, ResolvedCardLayout> variants,
      boolean isDefault,
      Instant updatedAt,
      String updatedByUserId) {
    return new SurfaceLayoutResponse(
        surface.surfaceId(),
        surface.label(),
        surface.billingModeAware(),
        isDefault,
        updatedAt,
        updatedByUserId,
        variants);
  }
}
