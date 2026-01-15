package com.inventory.tax.facade;

import java.util.Optional;

/**
 * Facade interface for accessing customer data from the user module.
 */
public interface CustomerDataFacade {
    
    /**
     * Get customer details by ID.
     */
    Optional<CustomerData> getCustomerById(String customerId);
    
    /**
     * Data transfer object for customer information.
     */
    record CustomerData(
        String id,
        String name,
        String gstin,
        String phone,
        String email,
        String address,
        String state,
        String stateCode
    ) {}
}

