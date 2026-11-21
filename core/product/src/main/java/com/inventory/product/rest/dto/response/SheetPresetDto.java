package com.inventory.product.rest.dto.response;

import com.inventory.product.labels.SheetLayoutCalculator;
import com.inventory.product.labels.SheetPreset;
import com.inventory.product.labels.StickerSizeSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One sheet preset in the field catalog (Req 10.2, 10.3).
 *
 * <p>{@code perStickerSize} carries one {@link SheetGridDto} per compatible sticker size, computed
 * with {@link SheetLayoutCalculator}; its key is the sticker size id.
 *
 * @param id stable preset id, e.g. {@code A4_PLAIN}
 * @param label human-readable label
 * @param pageWidthMm page width in millimetres
 * @param pageHeightMm page height in millimetres
 * @param marginTopMm top margin in millimetres
 * @param marginLeftMm left margin in millimetres
 * @param plain whether the grid is derived per sticker size (plain) or fixed (die-cut)
 * @param compatibleStickerSizes sticker size ids this preset supports; empty for plain presets
 * @param perStickerSize computed grid per compatible sticker size
 */
public record SheetPresetDto(
    String id,
    String label,
    double pageWidthMm,
    double pageHeightMm,
    double marginTopMm,
    double marginLeftMm,
    boolean plain,
    List<String> compatibleStickerSizes,
    Map<String, SheetGridDto> perStickerSize) {

  public SheetPresetDto {
    compatibleStickerSizes =
        compatibleStickerSizes == null ? List.of() : List.copyOf(compatibleStickerSizes);
    perStickerSize = perStickerSize == null ? Map.of() : Map.copyOf(perStickerSize);
  }

  /**
   * Builds the DTO from a preset, computing {@code perStickerSize} for every sticker size the
   * preset is compatible with (in {@code stickerSizes} order).
   */
  public static SheetPresetDto from(SheetPreset preset, List<StickerSizeSpec> stickerSizes) {
    Map<String, SheetGridDto> perStickerSize = new LinkedHashMap<>();
    for (StickerSizeSpec size : stickerSizes) {
      if (SheetLayoutCalculator.isCompatible(preset, size.size())) {
        perStickerSize.put(size.size(), SheetGridDto.from(SheetLayoutCalculator.resolve(preset, size)));
      }
    }
    return new SheetPresetDto(
        preset.id(),
        preset.label(),
        preset.pageWidthMm(),
        preset.pageHeightMm(),
        preset.marginTopMm(),
        preset.marginLeftMm(),
        preset.plain(),
        preset.compatibleStickerSizes(),
        perStickerSize);
  }
}
