package com.inventory.pluginengine.ref;

import org.springframework.util.StringUtils;

/**
 * Typed sellable identity encoded as {@code kind:id} (e.g. {@code inventory:lot-uuid},
 * {@code menu:item-uuid}), optionally naming a variant of that sellable as
 * {@code kind:id@variant} (e.g. {@code menu:item-uuid@half}).
 *
 * <p>The variant is how a menu portion travels. Every store in the cafe flow already persists
 * {@code sellableRef} — the cart line, the KOT line snapshot, the {@code PurchaseItem} — so
 * carrying the portion inside the ref puts it on the kitchen ticket and the invoice with no new
 * field threaded through any of them. It also settles line identity for free: the cart's line key
 * is the encoded ref, so a Half and a Full of one dish are two lines rather than one.
 *
 * <p><b>The grammar, which {@link #parse} implements exactly and rejects everything else rather
 * than guessing:</b>
 *
 * <pre>
 *   ref     := kind ":" id [ "@" variant ]
 *   kind    := one or more chars, up to the FIRST colon
 *   id      := one or more chars, up to the FIRST "@" if present
 *   variant := one or more chars, no "@", to end of string
 * </pre>
 *
 * <p>So the first {@code @} separates, and a second one ({@code menu:abc@a@b}), an empty variant
 * ({@code menu:abc@}) and an empty id ({@code menu:@half}) are each an
 * {@link IllegalArgumentException} — never a silently different portion, which would sell the
 * customer food they did not order at a price they did not agree to. A ref with no {@code @}
 * parses exactly as it always has, so every purchase, reprint and invoice written before portions
 * existed reads back unchanged.
 */
public record SellableRef(String kind, String id, String variant) {

  public static final String KIND_INVENTORY = "inventory";
  public static final String KIND_MENU = "menu";

  private static final String SEPARATOR = ":";
  private static final char VARIANT_SEPARATOR = '@';

  public SellableRef {
    if (!StringUtils.hasText(kind) || !StringUtils.hasText(id)) {
      throw new IllegalArgumentException("SellableRef kind and id are required");
    }
    if (variant != null) {
      if (!StringUtils.hasText(variant)) {
        throw new IllegalArgumentException("SellableRef variant cannot be blank");
      }
      if (variant.indexOf(VARIANT_SEPARATOR) >= 0) {
        throw new IllegalArgumentException("SellableRef variant cannot contain '@': " + variant);
      }
    }
  }

  /** The two-argument form every caller that knows nothing of variants keeps using. */
  public SellableRef(String kind, String id) {
    this(kind, id, null);
  }

  public static SellableRef inventory(String lotId) {
    return new SellableRef(KIND_INVENTORY, lotId.trim());
  }

  public static SellableRef menu(String menuItemId) {
    return new SellableRef(KIND_MENU, menuItemId.trim());
  }

  /** {@code menu:<itemId>@<rateId>} — the portion of a dish, not a dish of its own. */
  public static SellableRef menu(String menuItemId, String rateId) {
    return new SellableRef(KIND_MENU, menuItemId.trim(), rateId == null ? null : rateId.trim());
  }

  public String encode() {
    return variant == null ? kind + SEPARATOR + id : kind + SEPARATOR + id + VARIANT_SEPARATOR + variant;
  }

  public static SellableRef parse(String encoded) {
    if (!StringUtils.hasText(encoded)) {
      throw new IllegalArgumentException("sellableRef is required");
    }
    String trimmed = encoded.trim();
    int sep = trimmed.indexOf(SEPARATOR);
    if (sep <= 0 || sep >= trimmed.length() - 1) {
      throw new IllegalArgumentException("Invalid sellableRef: " + encoded);
    }
    String kind = trimmed.substring(0, sep);
    String rest = trimmed.substring(sep + 1);

    // The FIRST '@' separates, and only one is allowed. indexOf/lastIndexOf rather than
    // split("@"): a split hands back an array whose length has to be assumed about, and the
    // assumption that goes wrong ("menu:abc@a@b" -> take [1]) silently sells a different portion.
    int at = rest.indexOf(VARIANT_SEPARATOR);
    if (at < 0) {
      return new SellableRef(kind, rest);
    }
    String id = rest.substring(0, at);
    String variant = rest.substring(at + 1);
    if (id.isEmpty()) {
      throw new IllegalArgumentException("Invalid sellableRef, no id before '@': " + encoded);
    }
    if (variant.isEmpty()) {
      throw new IllegalArgumentException("Invalid sellableRef, empty variant after '@': " + encoded);
    }
    if (variant.indexOf(VARIANT_SEPARATOR) >= 0) {
      throw new IllegalArgumentException("Invalid sellableRef, more than one '@': " + encoded);
    }
    return new SellableRef(kind, id, variant);
  }

  public static SellableRef parseLenient(String encoded) {
    if (!StringUtils.hasText(encoded)) {
      return null;
    }
    try {
      return parse(encoded);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /** True when this ref names a variant of its sellable — a menu portion. */
  public boolean hasVariant() {
    return variant != null;
  }

  public boolean isInventory() {
    return KIND_INVENTORY.equals(kind);
  }

  public boolean isMenu() {
    return KIND_MENU.equals(kind);
  }
}
