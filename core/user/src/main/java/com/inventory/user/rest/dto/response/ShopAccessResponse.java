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
  /**
   * HR permissions and the caller's linked employee in this shop. Only filled by
   * {@code GET /shops/me/access} ({@code RbacService.getEffectiveAccessForClient}); {@code null}
   * when the HR module is not deployed (no {@code HrAccessAdapter} bean). The frontend treats
   * {@code null} as no HR permissions.
   */
  private HrAccessDto hr;

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

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class HrAccessDto {
    /** Effective HR permission names (HrPermission enum names); empty = self-service only. */
    private List<String> permissions;
    /** The caller's own employee record in this shop; {@code null} = not linked. */
    private String employeeId;
  }
}
