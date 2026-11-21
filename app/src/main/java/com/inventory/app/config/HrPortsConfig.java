package com.inventory.app.config;

import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.product.service.ShopService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the ports hr declares in {@code com.inventory.hr.api.port} to the modules that own the
 * data. product owns shops and does not depend on hr, so the composition root connects the two
 * (module-boundaries.yml, B6 and B10).
 */
@Configuration
public class HrPortsConfig {

  @Bean
  ShopNamePort shopNamePort(ShopService shopService) {
    return shopService::getShopName;
  }
}
