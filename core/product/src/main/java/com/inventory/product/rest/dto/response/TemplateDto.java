package com.inventory.product.rest.dto.response;

import com.inventory.product.labels.StickerTemplate;
import java.util.List;

/**
 * One selectable sticker template in the field catalog (Req 11).
 *
 * @param id the {@link StickerTemplate} name ({@code "STACKED"} / {@code "COMPACT"})
 * @param label the human-readable label shown in the picker
 */
public record TemplateDto(String id, String label) {

  /** The fixed template choices in display order. */
  public static final List<TemplateDto> ALL =
      List.of(
          new TemplateDto(StickerTemplate.STACKED.name(), "Stacked (classic)"),
          new TemplateDto(StickerTemplate.COMPACT.name(), "Compact (two-column)"));
}
