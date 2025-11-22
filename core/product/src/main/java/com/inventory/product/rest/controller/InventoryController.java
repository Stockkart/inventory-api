package com.inventory.product.rest.controller;

import com.inventory.product.rest.dto.inventory.InventoryDetailResponse;
import com.inventory.product.rest.dto.inventory.InventoryListResponse;
import com.inventory.product.rest.dto.inventory.InventoryReceiptResponse;
import com.inventory.product.rest.dto.inventory.ReceiveInventoryRequest;
import com.inventory.product.service.InventoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class InventoryController {

  @Autowired
  private InventoryService inventoryService;

  @PostMapping("/inventory/receive")
  public ResponseEntity<InventoryReceiptResponse> receive(@RequestBody ReceiveInventoryRequest request) {
    return ResponseEntity.ok(inventoryService.receive(request));
  }

  @GetMapping("/inventory")
  public ResponseEntity<InventoryListResponse> list(@RequestParam String shopId) {
    return ResponseEntity.ok(inventoryService.list(shopId));
  }

  @GetMapping("/inventory/{lotId}")
  public ResponseEntity<InventoryDetailResponse> getLot(@PathVariable String lotId) {
    return ResponseEntity.ok(inventoryService.getLot(lotId));
  }
}

