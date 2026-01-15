package com.inventory.tax.facade.impl;

import com.inventory.tax.facade.CustomerDataFacade;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Implementation of CustomerDataFacade using the user module's repository.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CustomerDataFacadeImpl implements CustomerDataFacade {
    
    private final CustomerRepository customerRepository;
    
    @Override
    public Optional<CustomerData> getCustomerById(String customerId) {
        log.debug("Fetching customer data for customerId: {}", customerId);
        
        return customerRepository.findById(customerId)
            .map(this::toCustomerData);
    }
    
    private CustomerData toCustomerData(Customer customer) {
        return new CustomerData(
            customer.getId(),
            customer.getName(),
            customer.getGstin(),
            customer.getPhone(),
            customer.getEmail(),
            customer.getAddress(),
            null, // state - not in current Customer model
            null  // stateCode - not in current Customer model
        );
    }
}

