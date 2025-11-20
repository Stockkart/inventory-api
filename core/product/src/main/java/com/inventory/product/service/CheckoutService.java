package com.inventory.product.service;

import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.Sale;
import com.inventory.product.domain.model.SaleItem;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.domain.repository.SaleRepository;
import com.inventory.product.rest.dto.sale.CheckoutRequest;
import com.inventory.product.rest.dto.sale.CheckoutResponse;
import com.inventory.product.rest.dto.sale.InvalidateSaleRequest;
import com.inventory.product.rest.dto.sale.SaleStatusResponse;
import com.inventory.product.rest.mapper.SaleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CheckoutService {

    private final ProductRepository productRepository;
    private final SaleRepository saleRepository;
    private final SaleMapper saleMapper;

    public CheckoutResponse checkout(CheckoutRequest request) {
        List<SaleItem> saleItems = request.getItems().stream().map(item -> {
            Product product = productRepository.findById(item.getBarcode())
                    .orElseThrow(() -> new IllegalArgumentException("Product not found"));
            BigDecimal qty = BigDecimal.valueOf(item.getQty());
            BigDecimal discount = item.getDiscount() == null ? BigDecimal.ZERO : BigDecimal.valueOf(item.getDiscount());
            BigDecimal total = product.getPrice().multiply(qty).subtract(discount);
            return SaleItem.builder()
                    .productId(product.getBarcode())
                    .productName(product.getName())
                    .quantity(item.getQty())
                    .salePrice(product.getPrice())
                    .discount(discount)
                    .total(total)
                    .build();
        }).toList();

        BigDecimal grandTotal = saleItems.stream()
                .map(SaleItem::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Sale sale = Sale.builder()
                .id("sale-" + UUID.randomUUID())
                .invoiceId(UUID.randomUUID().toString())
                .invoiceNo(generateInvoiceNo())
                .shopId(request.getShopId())
                .userId(request.getUserId())
                .items(saleItems)
                .subTotal(grandTotal)
                .taxTotal(BigDecimal.ZERO)
                .discountTotal(BigDecimal.ZERO)
                .grandTotal(grandTotal)
                .soldAt(Instant.now())
                .valid(true)
                .paymentMethod(request.getPaymentMethod())
                .build();

        saleRepository.save(sale);
        return saleMapper.toCheckoutResponse(sale);
    }

    private String generateInvoiceNo() {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd").format(java.time.LocalDate.now());
        return "INV-" + stamp + "-" + (int) (Math.random() * 10_000);
    }

    public SaleStatusResponse invalidate(String saleId, InvalidateSaleRequest request) {
        Sale sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new IllegalArgumentException("Sale not found"));
        sale.setValid(false);
        saleRepository.save(sale);
        return SaleStatusResponse.builder()
                .saleId(sale.getId())
                .valid(sale.isValid())
                .build();
    }
}

