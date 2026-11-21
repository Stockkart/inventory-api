package com.inventory.pluginengine.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import org.springframework.util.StringUtils;

/**
 * What a menu item's portions look like once they are stored, and how one is found again.
 *
 * <p>Two things happen at write, both of them once and for good:
 *
 * <ul>
 *   <li>a portion with no id yet is given a slug derived from its name — {@code Half} becomes
 *       {@code half}, {@code "1/2 Plate"} becomes {@code 1-2-plate} — with a numeric suffix on a
 *       collision within the same item ({@code half-2}). A portion that already carries an id
 *       keeps it, whatever its name has since become: the id is in every {@code sellableRef} the
 *       portion was ever sold under, and rewriting it would orphan them all.
 *   <li>an item with portions has its {@code sellingPrice} set to null. Ignoring it at read
 *       instead would leave a price on the document that nothing honours, waiting for a future
 *       reader to trust it. A payload carrying both is normalised rather than refused, so an
 *       older client is not locked out of saving a menu.
 * </ul>
 */
public final class MenuRates {

  private MenuRates() {}

  private static final int MAX_SLUG_LENGTH = 40;

  public static boolean isPortioned(MenuItem item) {
    return item != null && item.getRates() != null && !item.getRates().isEmpty();
  }

  /** The portion this id names, or empty — never "the first one", which sells the wrong food. */
  public static Optional<MenuRate> findById(MenuItem item, String rateId) {
    if (!isPortioned(item) || !StringUtils.hasText(rateId)) {
      return Optional.empty();
    }
    String wanted = rateId.trim();
    for (MenuRate rate : item.getRates()) {
      if (rate != null && wanted.equals(trimToNull(rate.getId()))) {
        return Optional.of(rate);
      }
    }
    return Optional.empty();
  }

  /** Freezes ids onto new portions and drops a single price the portions have replaced. */
  public static void normalize(MenuItem item) {
    if (item == null) {
      return;
    }
    List<MenuRate> rates = item.getRates();
    if (rates == null || rates.isEmpty()) {
      if (rates != null) {
        item.setRates(null);
      }
      return;
    }

    List<MenuRate> kept = new ArrayList<>();
    Set<String> taken = new LinkedHashSet<>();
    for (MenuRate rate : rates) {
      if (rate == null) {
        continue;
      }
      String name = trimToNull(rate.getName());
      rate.setName(name);
      String existing = trimToNull(rate.getId());
      if (existing != null) {
        // Frozen. Whatever the name is now, this portion has been sold under this id.
        rate.setId(existing);
        taken.add(existing);
      }
      kept.add(rate);
    }
    // Ids are assigned in a second pass so a new portion cannot take a slug that an existing
    // portion further down the list already owns.
    for (MenuRate rate : kept) {
      if (trimToNull(rate.getId()) == null) {
        String slug = uniqueSlug(slugify(rate.getName()), taken);
        rate.setId(slug);
        taken.add(slug);
      }
    }

    item.setRates(kept);
    if (!kept.isEmpty()) {
      item.setSellingPrice(null);
    }
  }

  /** Lower-case, alphanumerics kept, every other run collapsed to a single dash. */
  static String slugify(String name) {
    if (!StringUtils.hasText(name)) {
      return "portion";
    }
    StringBuilder out = new StringBuilder();
    boolean pendingDash = false;
    for (char c : name.trim().toLowerCase(Locale.ROOT).toCharArray()) {
      if (Character.isLetterOrDigit(c)) {
        if (pendingDash && out.length() > 0) {
          out.append('-');
        }
        pendingDash = false;
        out.append(c);
        if (out.length() >= MAX_SLUG_LENGTH) {
          break;
        }
      } else {
        pendingDash = true;
      }
    }
    // A name of nothing but punctuation still needs an id, and it must not be the empty string:
    // an empty variant is not a parseable sellableRef.
    return out.length() == 0 ? "portion" : out.toString();
  }

  private static String uniqueSlug(String base, Set<String> taken) {
    if (!taken.contains(base)) {
      return base;
    }
    for (int suffix = 2; ; suffix++) {
      String candidate = base + "-" + suffix;
      if (!taken.contains(candidate)) {
        return candidate;
      }
    }
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
