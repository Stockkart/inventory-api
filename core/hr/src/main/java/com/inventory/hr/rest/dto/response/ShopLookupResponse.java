package com.inventory.hr.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Answer to {@code GET /api/v1/hr/worker/shops?ownerEmail=} (worker-app R3.2, R3.3): the shops the
 * owner e-mail owns. Every miss (no such account, not an owner, owns nothing) is the same empty
 * list, so the answer never says whether an e-mail is a Stockkart account (R11.4). Mirrored in
 * {@code attendance-protocol/src/worker.types.ts}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShopLookupResponse {

  private List<Shop> shops;

  /** One shop; {@code ownerFirstName} is the first whitespace-separated token of the owner's name. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Shop {
    private String shopId;
    private String shopName;
    private String ownerFirstName;
  }
}
