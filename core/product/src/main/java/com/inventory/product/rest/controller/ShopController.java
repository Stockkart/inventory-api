package com.inventory.product.rest.controller;

import com.inventory.product.rest.dto.shop.RegisterShopRequest;
import com.inventory.product.rest.dto.shop.ShopApprovalRequest;
import com.inventory.product.rest.dto.shop.ShopApprovalResponse;
import com.inventory.product.rest.dto.shop.ShopRegistrationResponse;
import com.inventory.product.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class ShopController {

    private final ShopService shopService;

    @PostMapping("/api/v1/shops/register")
    public ResponseEntity<ShopRegistrationResponse> register(@RequestBody RegisterShopRequest request) {
        return ResponseEntity.ok(shopService.register(request));
    }

    @PostMapping("/admin/shops/{shopId}/approve")
    public ResponseEntity<ShopApprovalResponse> approve(@PathVariable String shopId,
                                                        @RequestBody ShopApprovalRequest request) {
        return ResponseEntity.ok(shopService.approve(shopId, request));
    }
}

