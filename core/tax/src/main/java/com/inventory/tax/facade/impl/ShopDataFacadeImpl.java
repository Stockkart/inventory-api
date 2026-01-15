package com.inventory.tax.facade.impl;

import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.tax.facade.ShopDataFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Implementation of ShopDataFacade using the product module's repository.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ShopDataFacadeImpl implements ShopDataFacade {
    
    private final ShopRepository shopRepository;
    
    @Override
    public Optional<ShopData> getShopById(String shopId) {
        log.debug("Fetching shop data for shopId: {}", shopId);
        
        return shopRepository.findById(shopId)
            .map(this::toShopData);
    }
    
    private ShopData toShopData(Shop shop) {
        String address = shop.getLocation() != null ? shop.getLocation().getPrimaryAddress() : null;
        String state = shop.getLocation() != null ? shop.getLocation().getState() : null;
        
        return new ShopData(
            shop.getShopId(),
            shop.getName(),
            shop.getGstinNo(),
            address,
            state,
            null, // stateCode - derive from state if needed
            shop.getSgst(),
            shop.getCgst()
        );
    }
}

