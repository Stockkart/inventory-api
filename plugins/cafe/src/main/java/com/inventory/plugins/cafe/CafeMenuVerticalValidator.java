package com.inventory.plugins.cafe;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.menu.MenuDirectLinks;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.pluginengine.menu.MenuRate;
import com.inventory.pluginengine.menu.MenuRates;
import com.inventory.pluginengine.menu.MenuSection;
import com.inventory.pluginengine.menu.MenuSellMode;
import com.inventory.pluginengine.menu.MenuVerticalValidator;
import com.inventory.pluginengine.menu.ShopMenu;
import com.inventory.pluginengine.schema.VerticalSchema;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CafeMenuVerticalValidator implements MenuVerticalValidator {

  @Override
  public String getVerticalId() {
    return "cafe";
  }

  @Override
  public void validate(ShopMenu menu, VerticalSchema schema, String shopId) {
    if (menu == null) {
      throw new ValidationException("Menu is required");
    }
    if (menu.getSections() == null || menu.getSections().isEmpty()) {
      return;
    }
    Set<String> itemIds = new HashSet<>();
    // inventoryId -> title of the section that already places it: a lot is placed once.
    Map<String, String> linkedIn = new HashMap<>();
    for (MenuSection section : menu.getSections()) {
      if (section == null || section.getItems() == null) {
        continue;
      }
      String sectionTitle =
          StringUtils.hasText(section.getTitle()) ? section.getTitle().trim() : "another section";
      for (MenuItem item : section.getItems()) {
        validateItem(item, itemIds, linkedIn, sectionTitle);
      }
    }
  }

  private static void validateItem(
      MenuItem item, Set<String> itemIds, Map<String, String> linkedIn, String sectionTitle) {
    if (item == null) {
      return;
    }
    if (!StringUtils.hasText(item.getId())) {
      throw new ValidationException("Each menu item must have an id");
    }
    if (!itemIds.add(item.getId().trim())) {
      throw new ValidationException("Duplicate menu item id: " + item.getId());
    }
    if (!StringUtils.hasText(item.getName())) {
      throw new ValidationException("Menu item name is required for id: " + item.getId());
    }
    if (MenuDirectLinks.isDirect(item)) {
      // A placed stock lot: the lot owns the price, so there is none to check here.
      if (!StringUtils.hasText(item.getInventoryId())) {
        throw new ValidationException("Pick a stock item for " + item.getName());
      }
      String where = linkedIn.putIfAbsent(item.getInventoryId().trim(), sectionTitle);
      if (where != null) {
        throw new ValidationException(item.getName().trim() + " is already in " + where);
      }
    } else if (MenuRates.isPortioned(item)) {
      // The portions ARE the price. sellingPrice was normalised to null on the way in, and
      // demanding a positive one here would make a portioned item unsaveable.
      validateRates(item);
    } else if (item.getSellingPrice() == null
        || item.getSellingPrice().compareTo(BigDecimal.ZERO) <= 0) {
      throw new ValidationException("Menu item selling price must be positive for: " + item.getName());
    }
    if (item.getSellMode() == null) {
      item.setSellMode(MenuSellMode.menu);
    }
    if (item.getAvailable() == null) {
      item.setAvailable(Boolean.TRUE);
    }
  }

  /**
   * Per portion: an id, a name, and a price worth charging. Ids unique within the item because
   * the id is what a {@code sellableRef} names, and two portions answering to one id would make
   * which one was sold a coin toss. Names unique case-insensitively after trimming, because a
   * picker showing "Half" twice is a picker a cashier cannot use -- while the shop's own casing
   * is kept exactly as typed, since comparison is not rewriting.
   */
  private static void validateRates(MenuItem item) {
    Set<String> rateIds = new HashSet<>();
    Set<String> rateNames = new HashSet<>();
    for (MenuRate rate : item.getRates()) {
      if (rate == null) {
        throw new ValidationException("A portion of " + item.getName() + " is empty");
      }
      if (!StringUtils.hasText(rate.getId())) {
        throw new ValidationException("Each portion of " + item.getName() + " must have an id");
      }
      if (!StringUtils.hasText(rate.getName())) {
        throw new ValidationException(
            "Portion name is required for " + item.getName() + " portion: " + rate.getId());
      }
      if (rate.getPrice() == null || rate.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
        throw new ValidationException(
            "Portion price must be positive for " + item.getName() + ": " + rate.getName().trim());
      }
      if (!rateIds.add(rate.getId().trim())) {
        throw new ValidationException(
            "Duplicate portion id on " + item.getName() + ": " + rate.getId().trim());
      }
      if (!rateNames.add(rate.getName().trim().toLowerCase(Locale.ROOT))) {
        throw new ValidationException(
            "Duplicate portion name on " + item.getName() + ": " + rate.getName().trim());
      }
    }
  }
}
