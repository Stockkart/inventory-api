package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.MemberModulePermissions;
import com.inventory.user.domain.model.MemberPermissions;
import com.inventory.user.domain.model.ProductSearchEditMode;
import com.inventory.user.domain.model.ShopRbacPolicyDocument;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.ShopRbacPolicyRepository;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.rest.dto.request.UpdateMemberPermissionsRequest;
import com.inventory.user.rest.dto.request.UpdateShopRbacPolicyRequest;
import com.inventory.user.rest.dto.response.ShopAccessResponse;
import com.inventory.user.rest.dto.response.ShopMemberAccessDto;
import com.inventory.user.rest.dto.response.ShopRbacAdminResponse;
import com.inventory.user.validation.UserValidator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@Transactional
public class RbacService {

  public static final String MODULE_ACCOUNTING = "accounting";
  public static final String MODULE_ANALYTICS = "analytics";
  public static final String MODULE_TAXES = "taxes";
  public static final String MODULE_STOCK_CORRECTION = "stockCorrection";
  public static final String MODULE_MARKETING = "marketing";
  public static final String MODULE_PAYMENT_PLAN = "paymentPlan";
  public static final String MODULE_PRODUCT_SEARCH_EDIT = "productSearchEdit";

  public static final String TEAM_MANAGE_INVITATIONS = "manageInvitations";
  public static final String TEAM_MANAGE_JOIN_REQUESTS = "manageJoinRequests";
  public static final String TEAM_MANAGE_SHOP_USERS = "manageShopUsers";
  public static final String TEAM_VIEW_MY_INVITATIONS = "viewMyInvitations";

  public static final List<String> CORE_PRODUCT_SEARCH_FIELDS =
      List.of(
          "name",
          "description",
          "companyName",
          "count",
          "location",
          "batchNo",
          "expiryDate",
          "costPrice",
          "priceToRetail",
          "maximumRetailPrice",
          "sellingPrice",
          "hsn",
          "cgst",
          "sgst",
          "barcode",
          "baseUnit",
          "unitsPerPack");

  @Autowired private ShopRbacPolicyRepository policyRepository;
  @Autowired private UserShopMembershipRepository membershipRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private UserShopMembershipService membershipService;
  @Autowired private UserValidator userValidator;

  @Transactional(readOnly = true)
  public ShopAccessResponse getEffectiveAccess(String userId, String shopId) {
    userValidator.validateUserHasShopAccess(membershipService.hasAccess(userId, shopId));
    MembershipContext ctx = resolveMembershipContext(userId, shopId);
    ProductSearchEditMode editMode = getOrDefaultPolicy(shopId).getProductSearchEditMode();
    return buildAccessResponse(ctx, editMode);
  }

  @Transactional(readOnly = true)
  public ShopRbacAdminResponse getAdminView(String ownerUserId, String shopId) {
    requireOwner(ownerUserId, shopId);
    ShopRbacPolicyDocument policy = getOrDefaultPolicy(shopId);
    List<UserShopMembership> memberships = membershipRepository.findByShopIdAndActiveTrue(shopId);
    List<ShopMemberAccessDto> members = new ArrayList<>();
    for (UserShopMembership membership : memberships) {
      UserAccount account =
          userAccountRepository.findById(membership.getUserId()).orElse(null);
      if (account == null) {
        continue;
      }
      MembershipContext ctx = contextFromMembership(membership, account);
      members.add(
          ShopMemberAccessDto.builder()
              .userId(account.getUserId())
              .name(account.getName())
              .email(account.getEmail())
              .role(membership.getRole())
              .relationship(membership.getRelationship())
              .active(membership.isActive())
              .joinedAt(membership.getJoinedAt())
              .permissions(membership.getPermissions())
              .effectiveAccess(buildAccessResponse(ctx, policy.getProductSearchEditMode()))
              .build());
    }
    return ShopRbacAdminResponse.builder()
        .productSearchEditMode(policy.getProductSearchEditMode())
        .members(members)
        .build();
  }

  public ShopRbacPolicyDocument updateShopPolicy(
      String ownerUserId, String shopId, UpdateShopRbacPolicyRequest request) {
    requireOwner(ownerUserId, shopId);
    if (request == null || request.getProductSearchEditMode() == null) {
      throw new ValidationException("productSearchEditMode is required");
    }
    ShopRbacPolicyDocument policy = getOrDefaultPolicy(shopId);
    policy.setProductSearchEditMode(request.getProductSearchEditMode());
    policy.setUpdatedAt(Instant.now());
    policy.setUpdatedByUserId(ownerUserId);
    return policyRepository.save(policy);
  }

  public ShopMemberAccessDto updateMemberPermissions(
      String ownerUserId,
      String shopId,
      String targetUserId,
      UpdateMemberPermissionsRequest request) {
    requireOwner(ownerUserId, shopId);
    if (!StringUtils.hasText(targetUserId)) {
      throw new ValidationException("userId is required");
    }
    UserShopMembership membership =
        membershipRepository
            .findByUserIdAndShopIdAndActiveTrue(targetUserId, shopId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Shop membership", "userId", targetUserId));
    if (UserShopMembershipService.RELATIONSHIP_OWNER.equals(membership.getRelationship())) {
      throw new ValidationException("Owner access cannot be changed");
    }
    MemberPermissions permissions =
        membership.getPermissions() != null ? membership.getPermissions() : new MemberPermissions();
    if (request.getModules() != null) {
      permissions.setModules(request.getModules());
    }
    if (request.getProductSearchEditableFields() != null) {
      permissions.setProductSearchEditableFields(
          new LinkedHashSet<>(request.getProductSearchEditableFields()));
    }
    ProductSearchEditMode editMode = getOrDefaultPolicy(shopId).getProductSearchEditMode();
    syncProductSearchEditFlag(permissions, editMode);
    membership.setPermissions(permissions);
    membershipRepository.save(membership);

    UserAccount account =
        userAccountRepository
            .findById(targetUserId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "userId", targetUserId));
    MembershipContext ctx = contextFromMembership(membership, account);
    return ShopMemberAccessDto.builder()
        .userId(account.getUserId())
        .name(account.getName())
        .email(account.getEmail())
        .role(membership.getRole())
        .relationship(membership.getRelationship())
        .active(membership.isActive())
        .joinedAt(membership.getJoinedAt())
        .permissions(membership.getPermissions())
        .effectiveAccess(buildAccessResponse(ctx, editMode))
        .build();
  }

  public void requireModule(String userId, String shopId, String moduleKey) {
    ShopAccessResponse access = getEffectiveAccess(userId, shopId);
    if (Boolean.TRUE.equals(access.getModules().get(moduleKey))) {
      return;
    }
    throw new BaseException(ErrorCode.ACCESS_DENIED, "You do not have access to this module");
  }

  public void requireTeamAccess(String userId, String shopId, String teamKey) {
    ShopAccessResponse access = getEffectiveAccess(userId, shopId);
    ShopAccessResponse.TeamAccessDto team = access.getTeam();
    boolean allowed =
        switch (teamKey) {
          case TEAM_MANAGE_INVITATIONS -> team.isManageInvitations();
          case TEAM_MANAGE_JOIN_REQUESTS -> team.isManageJoinRequests();
          case TEAM_MANAGE_SHOP_USERS -> team.isManageShopUsers();
          case TEAM_VIEW_MY_INVITATIONS -> team.isViewMyInvitations();
          default -> false;
        };
    if (!allowed) {
      throw new BaseException(ErrorCode.ACCESS_DENIED, "You do not have access to this feature");
    }
  }

  /**
   * Validates product-search inventory updates against shop edit policy and per-member field grants.
   * {@link ProductSearchEditMode#FULL_EDIT} allows any field; {@link ProductSearchEditMode#PERMISSION_BASED}
   * only allows assigned core fields.
   */
  public void validateProductSearchFieldUpdates(
      String userId, String shopId, Set<String> attemptedFields) {
    if (attemptedFields == null || attemptedFields.isEmpty()) {
      return;
    }
    ShopAccessResponse access = getEffectiveAccess(userId, shopId);
    if (access.isOwner()) {
      return;
    }
    ShopAccessResponse.ProductSearchAccessDto productSearch = access.getProductSearch();
    if (!productSearch.isCanEdit()) {
      throw new BaseException(
          ErrorCode.ACCESS_DENIED, "You do not have permission to edit products");
    }
    if (productSearch.getEditMode() == ProductSearchEditMode.FULL_EDIT) {
      return;
    }
    Set<String> allowed = new LinkedHashSet<>(productSearch.getEditableFields());
    for (String field : attemptedFields) {
      if (!allowed.contains(field)) {
        throw new BaseException(
            ErrorCode.ACCESS_DENIED,
            "You are not allowed to edit product field: " + field);
      }
    }
  }

  /** Owner, manager, or admin may approve or reject stock correction lines. */
  public void requireStockCorrectionApproval(String userId, String shopId) {
    ShopAccessResponse access = getEffectiveAccess(userId, shopId);
    if (access.getStockCorrection() != null && access.getStockCorrection().isCanApprove()) {
      return;
    }
    throw new BaseException(
        ErrorCode.ACCESS_DENIED, "Only the owner or manager can approve stock corrections");
  }

  private void requireOwner(String userId, String shopId) {
    if (!membershipService.hasOwnerAccess(userId, shopId)) {
      throw new BaseException(
          ErrorCode.ACCESS_DENIED, "Only the shop owner can manage access settings");
    }
  }

  private ShopRbacPolicyDocument getOrDefaultPolicy(String shopId) {
    return policyRepository
        .findByShopId(shopId)
        .orElseGet(
            () -> {
              ShopRbacPolicyDocument policy = new ShopRbacPolicyDocument();
              policy.setShopId(shopId);
              policy.setProductSearchEditMode(ProductSearchEditMode.FULL_EDIT);
              return policy;
            });
  }

  private MembershipContext resolveMembershipContext(String userId, String shopId) {
    UserAccount account =
        userAccountRepository
            .findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));
    UserShopMembership membership =
        membershipRepository.findByUserIdAndShopIdAndActiveTrue(userId, shopId).orElse(null);
    if (membership != null) {
      return contextFromMembership(membership, account);
    }
    // Legacy: shopId on account without membership row
    boolean legacyOwner = shopId.equals(account.getShopId());
    UserRole role = account.getRole() != null ? account.getRole() : UserRole.OWNER;
    return new MembershipContext(
        role,
        legacyOwner ? UserShopMembershipService.RELATIONSHIP_OWNER : UserShopMembershipService.RELATIONSHIP_INVITED,
        legacyOwner,
        null);
  }

  private MembershipContext contextFromMembership(
      UserShopMembership membership, UserAccount account) {
    UserRole role =
        membership.getRole() != null
            ? membership.getRole()
            : account.getRole() != null ? account.getRole() : UserRole.CASHIER;
    String relationship =
        membership.getRelationship() != null
            ? membership.getRelationship()
            : UserShopMembershipService.RELATIONSHIP_INVITED;
    boolean owner = UserShopMembershipService.RELATIONSHIP_OWNER.equals(relationship);
    return new MembershipContext(role, relationship, owner, membership.getPermissions());
  }

  private ShopAccessResponse buildAccessResponse(
      MembershipContext ctx, ProductSearchEditMode editMode) {
    if (ctx.owner()) {
      return ownerAccess(editMode);
    }
    Map<String, Boolean> modules = resolveModules(ctx.role(), ctx.permissions());
    ShopAccessResponse.TeamAccessDto team = resolveTeam(ctx.role());
    List<String> editableFields = resolveEditableFields(ctx, editMode, modules);
    boolean canEditProducts = hasProductSearchEditAccess(editMode, modules, editableFields);
    if (canEditProducts && editMode == ProductSearchEditMode.PERMISSION_BASED && !editableFields.isEmpty()) {
      modules = new LinkedHashMap<>(modules);
      modules.put(MODULE_PRODUCT_SEARCH_EDIT, true);
    }
    boolean canEditAll =
        canEditProducts
            && (editMode == ProductSearchEditMode.FULL_EDIT
                || (editMode == ProductSearchEditMode.PERMISSION_BASED
                    && editableFields.size() == CORE_PRODUCT_SEARCH_FIELDS.size()));

    return ShopAccessResponse.builder()
        .role(ctx.role().name())
        .relationship(ctx.relationship())
        .owner(false)
        .canManageAccess(false)
        .productSearch(
            ShopAccessResponse.ProductSearchAccessDto.builder()
                .canView(true)
                .canEdit(canEditProducts)
                .editMode(editMode)
                .canEditAll(canEditAll)
                .editableFields(editableFields)
                .build())
        .stockCorrection(resolveStockCorrectionAccess(ctx))
        .modules(modules)
        .team(team)
        .build();
  }

  private ShopAccessResponse.StockCorrectionAccessDto resolveStockCorrectionAccess(
      MembershipContext ctx) {
    return ShopAccessResponse.StockCorrectionAccessDto.builder()
        .canCreate(true)
        .canApprove(canApproveStockCorrections(ctx))
        .build();
  }

  private boolean canApproveStockCorrections(MembershipContext ctx) {
    if (ctx.owner()) {
      return true;
    }
    UserRole role = ctx.role();
    return role == UserRole.OWNER || role == UserRole.MANAGER || role == UserRole.ADMIN;
  }

  private ShopAccessResponse ownerAccess(ProductSearchEditMode editMode) {
    Map<String, Boolean> allModules = defaultModulesForRole(UserRole.OWNER, null);
    allModules.put(MODULE_PRODUCT_SEARCH_EDIT, true);
    return ShopAccessResponse.builder()
        .role(UserRole.OWNER.name())
        .relationship(UserShopMembershipService.RELATIONSHIP_OWNER)
        .owner(true)
        .canManageAccess(true)
        .productSearch(
            ShopAccessResponse.ProductSearchAccessDto.builder()
                .canView(true)
                .canEdit(true)
                .editMode(editMode)
                .canEditAll(true)
                .editableFields(CORE_PRODUCT_SEARCH_FIELDS)
                .build())
        .stockCorrection(
            ShopAccessResponse.StockCorrectionAccessDto.builder()
                .canCreate(true)
                .canApprove(true)
                .build())
        .modules(allModules)
        .team(
            ShopAccessResponse.TeamAccessDto.builder()
                .manageInvitations(true)
                .manageJoinRequests(true)
                .manageShopUsers(true)
                .viewMyInvitations(true)
                .build())
        .build();
  }

  private Map<String, Boolean> resolveModules(UserRole role, MemberPermissions stored) {
    Map<String, Boolean> defaults = defaultModulesForRole(role, stored);
    if (stored == null || stored.getModules() == null) {
      return defaults;
    }
    MemberModulePermissions overrides = stored.getModules();
    Map<String, Boolean> resolved = new LinkedHashMap<>(defaults);
    applyOverride(resolved, MODULE_ACCOUNTING, overrides.getAccounting());
    applyOverride(resolved, MODULE_ANALYTICS, overrides.getAnalytics());
    applyOverride(resolved, MODULE_TAXES, overrides.getTaxes());
    applyOverride(resolved, MODULE_STOCK_CORRECTION, overrides.getStockCorrection());
    applyOverride(resolved, MODULE_MARKETING, overrides.getMarketing());
    applyOverride(resolved, MODULE_PAYMENT_PLAN, overrides.getPaymentPlan());
    applyOverride(resolved, MODULE_PRODUCT_SEARCH_EDIT, overrides.getProductSearchEdit());
    return resolved;
  }

  private void applyOverride(Map<String, Boolean> target, String key, Boolean override) {
    if (override != null) {
      target.put(key, override);
    }
  }

  private Map<String, Boolean> defaultModulesForRole(UserRole role, MemberPermissions stored) {
    Map<String, Boolean> modules = new LinkedHashMap<>();
    boolean fullStaff = role == UserRole.ADMIN || role == UserRole.MANAGER || role == UserRole.OWNER;
    boolean cashier = role == UserRole.CASHIER;
    modules.put(MODULE_ACCOUNTING, fullStaff && !cashier);
    modules.put(MODULE_ANALYTICS, fullStaff && !cashier);
    modules.put(MODULE_TAXES, fullStaff && !cashier);
    modules.put(MODULE_STOCK_CORRECTION, fullStaff && !cashier);
    modules.put(MODULE_MARKETING, fullStaff && !cashier);
    modules.put(MODULE_PAYMENT_PLAN, fullStaff && !cashier);
    modules.put(MODULE_PRODUCT_SEARCH_EDIT, false);
    return modules;
  }

  private ShopAccessResponse.TeamAccessDto resolveTeam(UserRole role) {
    boolean staff = role != UserRole.CASHIER;
    return ShopAccessResponse.TeamAccessDto.builder()
        .manageInvitations(staff)
        .manageJoinRequests(staff)
        .manageShopUsers(staff)
        .viewMyInvitations(true)
        .build();
  }

  private List<String> resolveEditableFields(
      MembershipContext ctx, ProductSearchEditMode editMode, Map<String, Boolean> modules) {
    if (editMode == ProductSearchEditMode.FULL_EDIT) {
      if (!Boolean.TRUE.equals(modules.get(MODULE_PRODUCT_SEARCH_EDIT))) {
        return List.of();
      }
      return CORE_PRODUCT_SEARCH_FIELDS;
    }
    if (ctx.permissions() == null
        || ctx.permissions().getProductSearchEditableFields() == null
        || ctx.permissions().getProductSearchEditableFields().isEmpty()) {
      return List.of();
    }
    Set<String> allowed = new LinkedHashSet<>();
    for (String field : ctx.permissions().getProductSearchEditableFields()) {
      if (CORE_PRODUCT_SEARCH_FIELDS.contains(field)) {
        allowed.add(field);
      }
    }
    return List.copyOf(allowed);
  }

  /** In permission-based mode, any assigned field grants edit; in full-edit mode, module flag is required. */
  private boolean hasProductSearchEditAccess(
      ProductSearchEditMode editMode, Map<String, Boolean> modules, List<String> editableFields) {
    if (editMode == ProductSearchEditMode.PERMISSION_BASED) {
      return !editableFields.isEmpty();
    }
    return Boolean.TRUE.equals(modules.get(MODULE_PRODUCT_SEARCH_EDIT));
  }

  /**
   * Keeps {@link #MODULE_PRODUCT_SEARCH_EDIT} aligned with assigned fields when the shop uses
   * permission-based product search editing.
   */
  private void syncProductSearchEditFlag(
      MemberPermissions permissions, ProductSearchEditMode editMode) {
    if (editMode != ProductSearchEditMode.PERMISSION_BASED) {
      return;
    }
    MemberModulePermissions modules =
        permissions.getModules() != null ? permissions.getModules() : new MemberModulePermissions();
    Set<String> fields = permissions.getProductSearchEditableFields();
    modules.setProductSearchEdit(fields != null && !fields.isEmpty());
    permissions.setModules(modules);
  }

  private record MembershipContext(
      UserRole role, String relationship, boolean owner, MemberPermissions permissions) {}
}
