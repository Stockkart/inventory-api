package com.inventory.product.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Shop-level barcode label layout: enabled printable fields (ordered), sticker size and
 * layout options. Exactly one document per shop; created only when the shop saves a layout.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shop_barcode_label_layouts")
public class LabelLayoutDocument {

  @Id
  private String id;

  @Indexed(unique = true)
  private String shopId;

  /** Ordered field keys from the shop's Field_Catalog. */
  private List<String> enabledFieldKeys;

  /** {@code 50x25}, {@code 38x25}, or {@code 100x50}. */
  private String stickerSize;

  private Boolean showBarcodeText;

  private Boolean showFieldLabels;

  /** {@code HIDE_LINE} or {@code PRINT_BLANK} (see {@code BlankValueBehavior}). */
  private String blankValueBehavior;

  /**
   * {@code ROLL} or {@code SHEET} (see {@code PrintMedia}). Absent in documents written before the
   * sheet-layout feature; such documents resolve to {@code ROLL}.
   */
  private String printMedia;

  /** Sheet preset id when {@code printMedia} is {@code SHEET}; {@code null} otherwise. */
  private String sheetPreset;

  /**
   * Labels side by side on the roll when {@code printMedia} is {@code ROLL}. Absent (with {@code
   * rollColumnGapMm}) in documents saved before multi-across rolls; such layouts keep the legacy
   * single-column roll output.
   */
  private Integer rollLabelsAcross;

  /** Gap between neighbouring roll labels in millimetres; see {@link #rollLabelsAcross}. */
  private Double rollColumnGapMm;

  /**
   * {@code STACKED} or {@code COMPACT} (see {@code StickerTemplate}). Absent in documents written
   * before the sticker-template feature; such documents resolve to {@code STACKED}.
   */
  private String template;

  /**
   * {@code TOP} or {@code BOTTOM} (see {@code BarcodePosition}); only meaningful for {@code
   * COMPACT}. Absent documents resolve to {@code TOP}.
   */
  private String barcodePosition;

  /**
   * {@code RUPEE_SYMBOL} or {@code RS_PREFIX} (see {@code CurrencyStyle}). Absent documents resolve
   * to {@code RUPEE_SYMBOL}.
   */
  private String currencyStyle;

  /** Field key → {@code LabelZone} name; only used for {@code COMPACT}. May be {@code null}. */
  private Map<String, String> fieldZones;

  /** Field key → forced label on/off; absent keys use the automatic rule. May be {@code null}. */
  private Map<String, Boolean> fieldLabelOverrides;

  private Instant updatedAt;

  private String updatedByUserId;
}
