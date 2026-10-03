package com.inventory.product.labels;

/**
 * An enabled field as carried in layout responses, enriched with the display label and value type
 * so the renderer can print labels without consulting the catalog.
 *
 * @param valueType lowercase wire name of the {@link ValueType} ({@code text|number|...})
 * @param zone the field's {@link LabelZone} on a {@link StickerTemplate#COMPACT} sticker; always
 *     {@link LabelZone#LEFT} for {@link StickerTemplate#STACKED} (Req 11)
 * @param showLabel whether the renderer prints this field's label (Req 11)
 */
public record EnabledFieldDto(
    String fieldKey, String label, String valueType, LabelZone zone, boolean showLabel) {

  public EnabledFieldDto {
    zone = zone == null ? LabelZone.LEFT : zone;
  }

  /**
   * Convenience constructor for the pre-template shape: {@link LabelZone#LEFT} with the label
   * hidden.
   */
  public EnabledFieldDto(String fieldKey, String label, String valueType) {
    this(fieldKey, label, valueType, LabelZone.LEFT, false);
  }

  /** Builds the DTO from a catalog field with an explicit zone and label flag (Req 11). */
  public static EnabledFieldDto from(PrintableField field, LabelZone zone, boolean showLabel) {
    return new EnabledFieldDto(
        field.fieldKey(), field.label(), field.valueType().wireName(), zone, showLabel);
  }

  /** Builds the DTO from a catalog field with default zone/label (pre-template callers). */
  public static EnabledFieldDto from(PrintableField field) {
    return new EnabledFieldDto(field.fieldKey(), field.label(), field.valueType().wireName());
  }
}
