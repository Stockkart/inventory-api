package com.inventory.product.service;

import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.shop.RegisterShopRequest;
import com.inventory.product.rest.dto.shop.ShopApprovalRequest;
import com.inventory.product.rest.dto.shop.ShopApprovalResponse;
import com.inventory.product.rest.dto.shop.ShopRegistrationResponse;
import com.inventory.product.rest.mapper.ShopMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class ShopService {

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private ShopMapper shopMapper;

    public ShopRegistrationResponse register(RegisterShopRequest request) {
        Shop shop = Shop.builder()
                .shopId("shop-" + UUID.randomUUID())
                .name(request.getName())
                .location(request.getLocation())
                .businessId(request.getBusinessId())
                .contactEmail(request.getContactEmail())
                .status("PENDING")
                .active(false)
                .userLimit(0)
                .userCount(0)
                .initialAdminName(request.getInitialAdmin().getName())
                .initialAdminEmail(request.getInitialAdmin().getEmail())
                .createdAt(Instant.now())
                .build();
        shopRepository.save(shop);
        return shopMapper.toRegistrationResponse(shop);
    }

    public ShopApprovalResponse approve(String shopId, ShopApprovalRequest request) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("Shop not found"));
        shop.setActive(request.isApprove());
        shop.setStatus(request.isApprove() ? "ACTIVE" : "REJECTED");
        shop.setUserLimit(request.getUserLimit());
        if (request.isApprove()) {
            shop.setApprovedAt(Instant.now());
        }
        shopRepository.save(shop);
        return shopMapper.toApprovalResponse(shop);
    }
}

