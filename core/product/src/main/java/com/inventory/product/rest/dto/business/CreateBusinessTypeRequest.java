package com.inventory.product.rest.dto.business;

import lombok.Data;

import java.util.Map;

@Data
public class CreateBusinessTypeRequest {

    private String code;
    private String name;
    private Map<String, Object> registeredAttributes;
    private Map<String, Object> registeredTaxRules;
}

