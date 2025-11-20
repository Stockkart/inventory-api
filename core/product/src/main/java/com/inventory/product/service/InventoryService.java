package com.inventory.product.service;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.rest.dto.inventory.InventoryDetailResponse;
import com.inventory.product.rest.dto.inventory.InventoryListResponse;
import com.inventory.product.rest.dto.inventory.InventoryReceiptResponse;
import com.inventory.product.rest.dto.inventory.InventorySummaryDto;
import com.inventory.product.rest.dto.inventory.ReceiveInventoryRequest;
import com.inventory.product.rest.mapper.InventoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InventoryService {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryMapper inventoryMapper;

    public InventoryReceiptResponse receive(ReceiveInventoryRequest request) {
        Product product = productRepository.findById(request.getBarcode())
                .orElseGet(() -> createProductFromRequest(request));
        if (request.getPrice() != null) {
            product.setPrice(request.getPrice());
        }
        if (request.getName() != null) {
            product.setName(request.getName());
        }
        productRepository.save(product);

        Inventory inventory = Inventory.builder()
                .lotId("lot-" + UUID.randomUUID())
                .productId(product.getBarcode())
                .location(request.getLocation())
                .receivedCount(request.getCount())
                .soldCount(0)
                .currentCount(request.getCount())
                .receivedDate(Instant.now())
                .expiryDate(request.getExpiryDate())
                .shopId(request.getShopId())
                .userId(request.getUserId())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        inventoryRepository.save(inventory);

        return InventoryReceiptResponse.builder()
                .lotId(inventory.getLotId())
                .productId(product.getBarcode())
                .reminderCreated(false)
                .build();
    }

    private Product createProductFromRequest(ReceiveInventoryRequest request) {
        Product product = new Product();
        product.setBarcode(request.getBarcode());
        product.setName(request.getName());
        product.setPrice(request.getPrice() != null ? request.getPrice() : BigDecimal.ZERO);
        product.setCompanyCode("");
        product.setProductTypeCode("");
        product.setCreatedAt(Instant.now());
        product.setUpdatedAt(Instant.now());
        return product;
    }

    public InventoryListResponse list(String shopId) {
        List<InventorySummaryDto> summaries = inventoryRepository.findByShopId(shopId).stream()
                .map(inventoryMapper::toSummary)
                .toList();
        return InventoryListResponse.builder()
                .meta(Map.of("page", 1, "size", summaries.size()))
                .data(summaries)
                .build();
    }

    public InventoryDetailResponse getLot(String lotId) {
        Inventory inventory = inventoryRepository.findById(lotId)
                .orElseThrow(() -> new IllegalArgumentException("Lot not found"));
        return inventoryMapper.toDetail(inventory);
    }
}

