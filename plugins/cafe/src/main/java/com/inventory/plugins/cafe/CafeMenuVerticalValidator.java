package com.inventory.plugins.cafe;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.pluginengine.menu.MenuSection;
import com.inventory.pluginengine.menu.MenuSellMode;
import com.inventory.pluginengine.menu.MenuVerticalValidator;
import com.inventory.pluginengine.menu.ShopMenu;
import com.inventory.pluginengine.schema.VerticalSchema;
import java.math.BigDecimal;
import java.util.HashSet;
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
    for (MenuSection section : menu.getSections()) {
      if (section == null || section.getItems() == null) {
        continue;
      }
      for (MenuItem item : section.getItems()) {
        validateItem(item, itemIds);
      }
    }
  }

  private static void validateItem(MenuItem item, Set<String> itemIds) {
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
    if (item.getSellingPrice() == null || item.getSellingPrice().compareTo(BigDecimal.ZERO) <= 0) {
      throw new ValidationException("Menu item selling price must be positive for: " + item.getName());
    }
    if (item.getSellMode() == null) {
      item.setSellMode(MenuSellMode.menu);
    }
    if (item.getAvailable() == null) {
      item.setAvailable(Boolean.TRUE);
    }
  }
}
