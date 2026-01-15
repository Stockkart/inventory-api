package com.inventory.tax.facade;

import java.util.Optional;

/**
 * Facade interface for accessing shop data from the product/user module.
 */
public interface ShopDataFacade {
    
    /**
     * Get shop details by ID.
     */
    Optional<ShopData> getShopById(String shopId);
    
    /**
     * Data transfer object for shop information.
     */
    record ShopData(
        String id,
        String name,
        String gstin,
        String address,
        String state,
        String stateCode,
        String defaultSgst,
        String defaultCgst
    ) {}
}

