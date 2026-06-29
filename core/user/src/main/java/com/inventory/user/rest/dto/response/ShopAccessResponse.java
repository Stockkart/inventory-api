package com.inventory.user.rest.dto.response;

import com.inventory.user.domain.model.ProductSearchEditMode;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopAccessResponse {
  private String role;
  private String relationship;
  private boolean owner;
  private boolean canManageAccess;
  private ProductSearchAccessDto productSearch;
  private StockCorrectionAccessDto stockCorrection;
  private Map<String, Boolean> modules;
  private TeamAccessDto team;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ProductSearchAccessDto {
    private boolean canView;
    private boolean canEdit;
    private ProductSearchEditMode editMode;
    private boolean canEditAll;
    private List<String> editableFields;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class StockCorrectionAccessDto {
    /** Any shop member may propose a pending correction. */
    private boolean canCreate;
    /** Owner, manager, or admin may approve or reject lines. */
    private boolean canApprove;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class TeamAccessDto {
    private boolean manageInvitations;
    private boolean manageJoinRequests;
    private boolean manageShopUsers;
    private boolean viewMyInvitations;
  }
}
