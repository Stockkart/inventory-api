package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.enums.ItemType;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.rest.dto.response.ProductSuggestionDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/** The stock-in typeahead offers each catalog product once, however many copies exist. */
@ExtendWith(MockitoExtension.class)
class ProductServiceSuggestTest {

  private static final String SHOP = "shop-1";

  @Mock private ProductRepository productRepository;

  @InjectMocks private ProductService productService;

  @Test
  void copiesOfOneProductAreOfferedOnceUsingTheCopyWithABarcode() {
    Product history = product("history", "M2TONE 200ML 1X36", null);
    history.setBusinessType("PHARMACEUTICAL");
    history.setItemType(null);
    Product coded = product("coded", "M2TONE 200ML 1X36", "8901082053712");
    Product blank = product("blank", "M2TONE 200ML 1X36", null);
    Product other = product("other", "M2TONE 450ML", "8901082053736");
    when(productRepository.suggestByShopIdAndQuery(eq(SHOP), eq("m2"), any(Pageable.class)))
        .thenReturn(List.of(history, coded, blank, other));

    List<ProductSuggestionDto> rows = productService.suggest(SHOP, "m2");

    assertEquals(List.of("coded", "other"), rows.stream().map(ProductSuggestionDto::getId).toList());
  }

  @Test
  void differentBarcodesOfOneNameStaySeparateRows() {
    Product a = product("a", "STRIKER", "SK3667569ZZ8NU");
    Product b = product("b", "STRIKER", "SK3667SN9AD1JU");
    when(productRepository.suggestByShopIdAndQuery(eq(SHOP), eq("str"), any(Pageable.class)))
        .thenReturn(List.of(a, b));

    List<ProductSuggestionDto> rows = productService.suggest(SHOP, "str");

    assertEquals(List.of("a", "b"), rows.stream().map(ProductSuggestionDto::getId).toList());
  }

  @Test
  void atMostTenRowsAreOffered() {
    List<Product> many =
        java.util.stream.IntStream.range(0, 15)
            .mapToObj(i -> product("p" + i, "ITEM " + i, null))
            .toList();
    when(productRepository.suggestByShopIdAndQuery(eq(SHOP), eq("item"), any(Pageable.class)))
        .thenReturn(many);

    assertEquals(10, productService.suggest(SHOP, "item").size());
  }

  private static Product product(String id, String name, String barcode) {
    Product p = new Product();
    p.setId(id);
    p.setShopId(SHOP);
    p.setName(name);
    p.setNormalizedName(name.toLowerCase());
    p.setCompanyName("CHARAK PHARMA PVT. LTD.");
    p.setBarcode(barcode);
    p.setBusinessType("MEDICAL");
    p.setItemType(ItemType.NORMAL);
    p.setBaseUnit("PCS");
    p.setHsn("30049011");
    return p;
  }
}
