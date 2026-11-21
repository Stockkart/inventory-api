package com.inventory.product.rest.dto.response;

import com.inventory.product.labels.SheetSpec;

/**
 * The computed grid for one {@link com.inventory.product.labels.SheetPreset} / sticker size pair,
 * as advertised in the field catalog (Req 10.3).
 *
 * @param columns grid columns
 * @param rows grid rows
 * @param perSheet stickers per sheet ({@code columns * rows})
 * @param pitchXMm horizontal cell pitch in millimetres
 * @param pitchYMm vertical cell pitch in millimetres
 */
public record SheetGridDto(int columns, int rows, int perSheet, double pitchXMm, double pitchYMm) {

  /** Builds the DTO from a resolved {@link SheetSpec}. */
  public static SheetGridDto from(SheetSpec spec) {
    return new SheetGridDto(
        spec.columns(), spec.rows(), spec.perSheet(), spec.pitchXMm(), spec.pitchYMm());
  }
}
