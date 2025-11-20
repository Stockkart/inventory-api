package com.inventory.product.rest.dto.shop;

import lombok.Data;

@Data
public class RegisterShopRequest {

    private String name;
    private String location;
    private String businessId;
    private String contactEmail;
    private InitialAdmin initialAdmin;

    @Data
    public static class InitialAdmin {
        private String name;
        private String email;
    }
}

