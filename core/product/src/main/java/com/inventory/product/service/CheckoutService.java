package com.inventory.product.service;

import com.inventory.common.gst.SupplyPlacement;
import com.inventory.common.util.GstStateCode;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.InsufficientStockException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.common.util.GstMath;
import com.inventory.common.util.TxnIdGenerator;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.AvailableUnit;
import com.inventory.product.domain.model.DocumentTypes;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.DocumentType;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.rest.dto.request.CreateCustomerRequest;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.AddToCartRequest;
import com.inventory.product.rest.dto.request.UpdatePurchaseStatusRequest;
import com.inventory.product.rest.dto.response.AddToCartResponse;
import com.inventory.product.rest.dto.response.CheckoutResponse;
import com.inventory.product.rest.dto.response.InventoryEventDto;
import com.inventory.product.rest.dto.response.PurchaseListResponse;
import com.inventory.product.rest.dto.response.PurchaseSummaryDto;
import com.inventory.product.mapper.InventoryMapper;
import com.inventory.product.mapper.PurchaseMapper;
import com.inventory.plan.rest.dto.request.RecordUsageRequest;
import com.inventory.plan.service.UsageService;
import com.inventory.product.util.PurchaseItemRefs;
import com.inventory.product.utils.CheckoutUtils;
import com.inventory.product.utils.constants.ProductMetricsConstants;
import com.inventory.pluginengine.cart.CartBuildContext;
import com.inventory.pluginengine.cart.CartLineContributor;
import com.inventory.pluginengine.cart.CartLineInput;
import com.inventory.pluginengine.cart.CartLineRefs;
import com.inventory.pluginengine.ref.SellableRef;
import com.inventory.product.service.vertical.CartContributorResolver;
import com.inventory.product.service.vertical.CartLineSnapshotMapper;
import com.inventory.product.service.vertical.CheckoutCompletionOrchestrator;
import com.inventory.product.validation.CheckoutValidator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
@Transactional(readOnly = true)
public class CheckoutService {

  /**
   * The zone a date typed into a filter is meant in.
   *
   * <p>Sales are stored as instants, and the shop reads and writes its dates in
   * local time. Reading a filter date as UTC would move its edges by five and a
   * half hours, so a sale cut in the evening would fall outside the day it was
   * made on.
   */
  private static final ZoneId SALE_DATE_ZONE = ZoneId.of("Asia/Kolkata");

  @Autowired
  private PurchaseRepository purchaseRepository;

  @Autowired
  private InventoryRepository inventoryRepository;

  @Autowired
  private PurchaseMapper purchaseMapper;

  @Autowired
  private CheckoutValidator checkoutValidator;

  @Autowired
  private PackagingUnitService packagingUnitService;

  @Autowired
  private com.inventory.user.service.CustomerService customerService;

  @Autowired
  private com.inventory.user.domain.repository.CustomerRepository customerRepository;

  @Autowired
  private com.inventory.user.domain.repository.ShopCustomerRepository shopCustomerRepository;

  @Autowired
  private ShopRepository shopRepository;

  /** Who is where (taxation module); the one rule shared with purchases and the GST returns. */
  @Autowired
  private org.springframework.beans.factory.ObjectProvider<SupplyPlacement> supplyPlacement;

  @Autowired
  private com.inventory.reminders.service.EventService eventService;

  @Autowired
  private InventoryMapper inventoryMapper;

  @Autowired
  private InvoiceSequenceService invoiceSequenceService;

  @Autowired
  private UsageService usageService;

  @Autowired(required = false)
  private com.inventory.metrics.MetricsWrapper metrics;

  @Autowired(required = false)
  private com.inventory.credit.service.CreditService creditService;

  @Autowired(required = false)
  private com.inventory.credit.service.CreditChargeFacade creditChargeFacade;

  @Autowired(required = false)
  private com.inventory.accounting.api.AccountingFacade accountingFacade;

  @Autowired
  private CartContributorResolver cartContributorResolver;

  @Autowired
  private CartRateResolver cartRateResolver;

  @Autowired
  private CartLineSnapshotMapper cartLineSnapshotMapper;

  @Autowired
  private CheckoutCompletionOrchestrator checkoutCompletionOrchestrator;

  @Autowired
  private QuotationService quotationService;

  @Autowired
  private com.inventory.product.service.estimate.EstimateInventoryPolicy estimateInventoryPolicy;

  @Transactional
  public AddToCartResponse addToCart(AddToCartRequest request, HttpServletRequest httpRequest) {
    // Get shopId and userId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");

    // Validate shopId and userId
    checkoutValidator.validateShopIdAndUserId(shopId, userId);

    // Validate request
    checkoutValidator.validateAddToCartRequest(request);

    log.info("Adding items to cart for shop: {}, user: {}", shopId, userId);

    try {
      Purchase existingCart = quotationService.resolveTargetCart(request, userId, shopId);

      String customerId;
      String customerName;
      if (existingCart != null && !PurchaseCustomerRequests.hasCustomerPatch(request)) {
        customerId = existingCart.getCustomerId();
        customerName = existingCart.getCustomerName();
      } else {
        customerId = getOrCreateCustomerId(shopId, request);
        customerName = PurchaseCustomerRequests.displayNameOverlay(customerId, request);
      }

      // Process new items
      List<PurchaseItem> newItems = processCartItems(request.getItems(), shopId, existingCart);
      Purchase stockCheckCart =
          existingCart != null ? existingCart : new Purchase();
      if (existingCart == null) {
        stockCheckCart.setItems(new ArrayList<>());
      }
      validateStockAvailabilityForCartUpdate(stockCheckCart, newItems, shopId);

      BillingMode cartBillingMode = checkoutValidator.resolveAndValidateCartBillingMode(existingCart, newItems);
      boolean targetIsEstimate =
          existingCart != null && DocumentTypes.isEstimate(existingCart);
      if (!targetIsEstimate && cartBillingMode == BillingMode.BASIC) {
        throw new ValidationException(
            "BASIC / estimate-only stock can only be sold on Sell Estimate. Open or create an estimate.");
      }

      Purchase purchase;
      if (existingCart != null) {
        // Update existing cart - merge items (including quantity-0 update-only items)
        log.info("Updating existing cart with ID: {}", existingCart.getId());
        purchase = updateCart(existingCart, newItems, request.getBusinessType(), customerId, customerName, cartBillingMode);
        if (metrics != null) {
          metrics.record(ProductMetricsConstants.CART_UPDATED, 1, "module", ProductMetricsConstants.MODULE);
        }
      } else {
        // New cart: only items with quantity > 0 can be added; quantity-0 items are for updating discount on existing cart only
        List<PurchaseItem> itemsToAdd = newItems.stream()
            .filter(i -> i.getQuantity() != null && i.getQuantity().compareTo(BigDecimal.ZERO) > 0)
            .toList();
        if (itemsToAdd.isEmpty() && !newItems.isEmpty()) {
          throw new ValidationException(
              "Additional discount, scheme, or selling price can only be updated for items already in the cart. Add items to create a cart first.");
        }
        log.info("Creating new cart");
        purchase = createCart(request, itemsToAdd, shopId, userId, customerId, customerName, cartBillingMode);
        if (metrics != null) {
          metrics.record(ProductMetricsConstants.CART_CREATED, 1, "module", ProductMetricsConstants.MODULE);
        }
      }

      log.info("Successfully updated cart with ID: {}", purchase.getId());

      // Build response
      return purchaseMapper.toAddToCartResponse(purchase);

    } catch (ValidationException | InsufficientStockException | ResourceNotFoundException e) {
      log.warn("Add to cart failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error during add to cart for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error adding items to cart: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error during add to cart for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred during add to cart: " + e.getMessage(), e);
    }
  }

  @Transactional
  public CheckoutResponse updatePurchaseStatus(UpdatePurchaseStatusRequest request, HttpServletRequest httpRequest) {
    // Get shopId and userId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");

    // Validate shopId and userId
    checkoutValidator.validateShopIdAndUserId(shopId, userId);

    // Validate request
    checkoutValidator.validateUpdateStatusRequest(request);

    // Set default payment method to "CASH" if not provided
    if (!StringUtils.hasText(request.getPaymentMethod())) {
      request.setPaymentMethod("CASH");
    }

    log.info("Updating purchase status to {} for purchase ID: {}, shop: {}, user: {}",
        request.getStatus(), request.getPurchaseId(), shopId, userId);

    try {
      // Find purchase by ID
      Purchase purchase = purchaseRepository.findById(request.getPurchaseId())
          .orElseThrow(() -> new ResourceNotFoundException("Purchase", "id",
              "No purchase found with ID " + request.getPurchaseId()));

      // Verify purchase belongs to the user's shop
      if (!shopId.equals(purchase.getShopId()) || !userId.equals(purchase.getUserId())) {
        throw new ValidationException("Purchase does not belong to the authenticated user's shop");
      }

      if (DocumentTypes.isEstimate(purchase)) {
        throw new ValidationException(
            "Estimates cannot be checked out. Convert the estimate to an invoice first.");
      }

      // Validate status transition
      PurchaseStatus currentStatus = purchase.getStatus();
      PurchaseStatus requestedStatus = request.getStatus();

      checkoutValidator.validateStatusTransition(currentStatus, requestedStatus);

      // If status is being changed to COMPLETED, check plan limits, decrease inventory, assign invoice number
      if (requestedStatus == PurchaseStatus.COMPLETED) {
        estimateInventoryPolicy.assertSaleCheckoutAllowed(purchase);
        BigDecimal grandTotal = purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO;
        if (usageService != null) {
          usageService.checkCanAddBill(shopId, grandTotal, 1);
        }
        log.info("Processing inventory updates for completed purchase ID: {}", purchase.getId());
        updateInventoryForCompletedPurchase(purchase);
        // Sale date for GSTR and reporting: when the sale was completed (invoice date)
        purchase.setSoldAt(Instant.now());
        // Assign invoice number only on completion (avoids wasting sequence when cart changes before purchase)
        if (!StringUtils.hasText(purchase.getInvoiceNo())) {
          BillingMode billingMode = purchase.getBillingMode() != null ? purchase.getBillingMode() : BillingMode.REGULAR;
          if (billingMode == BillingMode.BASIC) {
            purchase.setInvoiceNo(invoiceSequenceService.getNextBasicInvoiceNo(shopId));
          } else {
            purchase.setInvoiceNo(invoiceSequenceService.getNextInvoiceNo(shopId));
          }
        }
        if (!StringUtils.hasText(purchase.getTxnId())) {
          purchase.setTxnId(TxnIdGenerator.newId());
        }
      }

      // Update status and payment method
      purchase.setStatus(requestedStatus);
      purchase.setPaymentMethod(request.getPaymentMethod());
      if (requestedStatus == PurchaseStatus.COMPLETED) {
        applyPaymentSplitToPurchase(purchase, request);
      }
      purchase.setUpdatedAt(Instant.now());
      purchase = purchaseRepository.save(purchase);

      // Record billing usage after successful completion
      if (requestedStatus == PurchaseStatus.COMPLETED && usageService != null) {
        recordBillingUsageForPurchase(shopId, purchase);
      }
      if (requestedStatus == PurchaseStatus.COMPLETED && metrics != null) {
        BigDecimal grandTotal = purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO;
        String vertical = checkoutVertical(shopId);
        if (vertical != null) {
          metrics.record(
              ProductMetricsConstants.ORDERS_COMPLETED,
              1,
              "module",
              ProductMetricsConstants.MODULE,
              "vertical",
              vertical);
          if (grandTotal.compareTo(BigDecimal.ZERO) > 0) {
            metrics.record(
                ProductMetricsConstants.ORDERS_AMOUNT,
                grandTotal.doubleValue(),
                "module",
                ProductMetricsConstants.MODULE,
                "vertical",
                vertical);
          }
        } else {
          metrics.record(ProductMetricsConstants.ORDERS_COMPLETED, 1, "module", ProductMetricsConstants.MODULE);
          if (grandTotal.compareTo(BigDecimal.ZERO) > 0) {
            metrics.record(ProductMetricsConstants.ORDERS_AMOUNT, grandTotal.doubleValue(), "module", ProductMetricsConstants.MODULE);
          }
        }
      }

      log.info("Successfully updated purchase status from {} to {} for purchase ID: {}",
          currentStatus, requestedStatus, purchase.getId());

      // Build response
      CheckoutResponse response = purchaseMapper.toCheckoutResponse(purchase);
      if (requestedStatus == PurchaseStatus.COMPLETED) {
        String creditEntryId =
            postCreditAndAccountingForCompletedSale(purchase, request, shopId, userId);
        response.setCreditEntryId(creditEntryId);
        final Purchase completedPurchase = purchase;
        checkoutCompletionOrchestrator
            .onPurchaseCompleted(completedPurchase)
            .ifPresent(
                result -> {
                  response.setTokenNo(result.getTokenNo());
                  if (StringUtils.hasText(result.getTokenNo())) {
                    completedPurchase.setTokenNo(result.getTokenNo());
                    purchaseRepository.save(completedPurchase);
                  }
                });
      }
      return response;

    } catch (ValidationException | ResourceNotFoundException e) {
      log.warn("Update purchase status failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error during update purchase status for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error updating purchase status: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error during update purchase status for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred during update purchase status: " + e.getMessage(), e);
    }
  }

  @Transactional(readOnly = true)
  public AddToCartResponse getCart(HttpServletRequest httpRequest, String purchaseId) {
    // Get shopId and userId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");

    // Validate shopId and userId
    checkoutValidator.validateShopIdAndUserId(shopId, userId);

    log.info("Getting cart for shop: {}, user: {}, purchaseId: {}", shopId, userId, purchaseId);

    try {
      if (StringUtils.hasText(purchaseId)) {
        return quotationService.getQuotation(purchaseId.trim(), userId, shopId);
      }
      return quotationService
          .findLegacyActiveCart(userId, shopId)
          .orElseThrow(
              () ->
                  new ResourceNotFoundException(
                      "Cart",
                      "userId and shopId",
                      "No active cart found for user " + userId + " and shop " + shopId));

    } catch (ResourceNotFoundException e) {
      log.warn("Cart not found for shop: {}, user: {}", shopId, userId);
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while getting cart for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error getting cart: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error while getting cart for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred while getting cart: " + e.getMessage(), e);
    }
  }

  @Transactional(readOnly = true)
  public PurchaseListResponse getPurchases(Integer page, Integer limit, String order, HttpServletRequest httpRequest) {
    // Get shopId and userId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");

    // Validate shopId and userId
    checkoutValidator.validateShopIdAndUserId(shopId, userId);

    log.info("Getting purchases for shop: {}, user: {}, page: {}, limit: {}, order: {}",
        shopId, userId, page, limit, order);

    try {
      // Set defaults
      int pageNumber = (page != null && page > 0) ? page - 1 : 0; // Spring Data uses 0-based indexing
      int pageSize = (limit != null && limit > 0) ? limit : 20; // Default limit of 20

      // Validate page size (max 100 to prevent performance issues)
      if (pageSize > 100) {
        pageSize = 100;
        log.warn("Page size exceeded maximum, setting to 100");
      }

      // Parse order parameter (default: soldAt desc)
      Sort sort = parseSortOrder(order);

      // Create Pageable
      Pageable pageable = PageRequest.of(pageNumber, pageSize, sort);

      // Query purchases by shopId
      Page<Purchase> purchasePage = purchaseRepository.findByShopId(shopId, pageable);

      List<PurchaseSummaryDto> purchaseDtos = purchasePage.getContent().stream()
          .map(purchaseMapper::toPurchaseSummaryDto)
          .toList();

      PurchaseListResponse response = purchaseMapper.toPurchaseListResponse(
          purchaseDtos, pageNumber + 1, pageSize,
          purchasePage.getTotalElements(), purchasePage.getTotalPages());

      log.info("Retrieved {} purchases (page {} of {}) for shop: {}",
          purchaseDtos.size(), pageNumber + 1, purchasePage.getTotalPages(), shopId);

      return response;

    } catch (DataAccessException e) {
      log.error("Database error while getting purchases for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error getting purchases: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error while getting purchases for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred while getting purchases: " + e.getMessage(), e);
    }
  }

  /**
   * Search purchases with pagination and exact customer filters.
   * All provided customer fields are combined with AND semantics.
   *
   * @param page page number (1-based)
   * @param limit page size
   * @param invoiceNo optional invoice number, matched as a substring
   * @param soldFrom optional inclusive lower bound on the sale date
   * @param soldTo optional inclusive upper bound on the sale date
   * @param customer optional free text matched against a customer's name, phone,
   *     email or address -- the single box the counter types into. It matches as
   *     a substring, because a party is entered as its trading name and stored
   *     with its town appended, so an exact name reaches almost none of them
   * @param httpRequest HTTP request containing shopId
   * @return PurchaseListResponse with paginated purchases
   */
  @Transactional(readOnly = true)
  public PurchaseListResponse searchPurchases(Integer page, Integer limit, String invoiceNo,
                                              LocalDate soldFrom, LocalDate soldTo,
                                              String customer,
                                              HttpServletRequest httpRequest) {
    // Get shopId and userId from request attributes
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");

    // Validate shopId and userId
    checkoutValidator.validateShopIdAndUserId(shopId, userId);

    log.info("Searching purchases for shop: {}, user: {}, page: {}, limit: {}, invoiceNo: {}, customer: {}",
        shopId, userId, page, limit, invoiceNo, customer);

    try {
      // Set defaults
      int pageNumber = (page != null && page > 0) ? page - 1 : 0; // Spring Data uses 0-based indexing
      int pageSize = (limit != null && limit > 0) ? limit : 20; // Default limit of 20

      // Validate page size (max 100 to prevent performance issues)
      if (pageSize > 100) {
        pageSize = 100;
        log.warn("Page size exceeded maximum, setting to 100");
      }

      // Create Pageable with sorting by soldAt descending, id breaking the ties
      Pageable pageable = PageRequest.of(pageNumber, pageSize,
          withTieBreaker(Sort.by(Sort.Direction.DESC, "soldAt")));

      List<String> customerIds = null;

      // If customer search criteria provided, find matching customer IDs first
      if (StringUtils.hasText(customer)) {
        customerIds = findCustomerIdsMatching(shopId, customer);

        if (customerIds.isEmpty()) {
          return purchaseMapper.toPurchaseListResponse(
              List.of(), pageNumber + 1, pageSize, 0, 0);
        }
      }

      // One query carrying every criterion, so the search reaches the whole
      // history. Selecting on one field and sifting the rest in memory only
      // finds what happens to fall in the page that was fetched.
      Page<Purchase> purchasePage = purchaseRepository.search(
          shopId,
          invoiceNo,
          soldFrom != null ? soldFrom.atStartOfDay(SALE_DATE_ZONE).toInstant() : null,
          // The bound the caller gives is a day, and a day is inclusive: the
          // query takes the start of the day after, so a sale at any hour of
          // the last day is inside it.
          soldTo != null ? soldTo.plusDays(1).atStartOfDay(SALE_DATE_ZONE).toInstant() : null,
          customerIds,
          pageable);

      List<PurchaseSummaryDto> purchaseDtos = purchasePage.getContent().stream()
          .map(purchaseMapper::toPurchaseSummaryDto)
          .toList();

      PurchaseListResponse response = purchaseMapper.toPurchaseListResponse(
          purchaseDtos, pageNumber + 1, pageSize,
          purchasePage.getTotalElements(), purchasePage.getTotalPages());

      log.info("Retrieved {} purchases (page {} of {}) for shop: {}",
          purchaseDtos.size(), pageNumber + 1, purchasePage.getTotalPages(), shopId);

      return response;

    } catch (DataAccessException e) {
      log.error("Database error while searching purchases for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error searching purchases: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error while searching purchases for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred while searching purchases: " + e.getMessage(), e);
    }
  }

  /**
   * The shop's customers matching a search term.
   *
   * <p>One term, matched as a substring across name, phone, email and address,
   * because that is what the one box on the screen sends and the person typing
   * into it should not have to say which of the four they are holding.
   *
   * <p>A substring rather than an exact match: a party is entered as its trading
   * name and stored with its town appended, so ABID MEDICAL HALL never equals
   * ABID MEDICAL HALL BARACHATTI and an exact comparison reaches almost none of
   * them.
   */
  private List<String> findCustomerIdsMatching(String shopId, String term) {
    if (!StringUtils.hasText(term)) {
      return List.of();
    }
    Set<String> matched = new HashSet<>();
    for (Customer candidate : customerRepository.searchByQuery(term.trim())) {
      if (shopCustomerRepository.existsByShopIdAndCustomerId(shopId, candidate.getId())) {
        matched.add(candidate.getId());
      }
    }
    return new ArrayList<>(matched);
  }

  private Sort parseSortOrder(String order) {
    if (order == null || order.trim().isEmpty()) {
      // Default: soldAt desc
      return withTieBreaker(Sort.by(Sort.Direction.DESC, "soldAt"));
    }

    // Parse order string: "field:direction" or just "field" (defaults to desc)
    // Examples: "soldAt:desc", "soldAt:asc", "grandTotal:desc", "soldAt"
    String[] parts = order.split(":");
    String field = parts[0].trim();
    Sort.Direction direction = Sort.Direction.DESC; // Default direction

    if (parts.length > 1) {
      String dirStr = parts[1].trim().toLowerCase();
      if ("asc".equals(dirStr)) {
        direction = Sort.Direction.ASC;
      } else if ("desc".equals(dirStr)) {
        direction = Sort.Direction.DESC;
      }
    }

    // Validate field name (only allow certain fields for security)
    // Allowed fields: soldAt, grandTotal, invoiceNo
    if (!isValidSortField(field)) {
      log.warn("Invalid sort field: {}, using default (soldAt desc)", field);
      return withTieBreaker(Sort.by(Sort.Direction.DESC, "soldAt"));
    }

    return withTieBreaker(Sort.by(direction, field));
  }

  /**
   * The same sort, made a total order by appending the document id.
   *
   * <p>None of the sortable fields is unique. Sales are dated to the day they were
   * made, so a shop that bills more than once a day has ties by ordinary use, and
   * an imported history can put hundreds of sales on one timestamp. A page is a
   * separate query, and among equal sort keys the database is free to return a
   * different order each time -- so a tied document can land after the cursor on
   * one page and before it on the next, appearing twice or not at all. Pagination
   * skipped fifteen invoices of one shop's month and repeated seventeen others.
   *
   * <p>The id breaks every remaining tie, which makes the order the same for every
   * page and every request.
   */
  private Sort withTieBreaker(Sort sort) {
    return sort.and(Sort.by(Sort.Direction.DESC, "_id"));
  }

  private boolean isValidSortField(String field) {
    // Whitelist of allowed sort fields
    return "soldAt".equals(field) ||
        "grandTotal".equals(field) ||
        "invoiceNo".equals(field);
  }

  private List<PurchaseItem> processCartItems(List<AddToCartRequest.CartItem> items, String shopId,
      Purchase existingCart) {
    Optional<CartLineContributor> contributor = cartContributorResolver.resolveContributor(shopId);
    if (contributor.isPresent()) {
      List<CartLineInput> inputs = items.stream().map(this::toCartLineInput).toList();
      CartBuildContext ctx = cartContributorResolver.buildContext(shopId);

      List<PurchaseItem> menuDeltaItems = new ArrayList<>();
      List<CartLineInput> buildInputs = new ArrayList<>();
      for (CartLineInput input : inputs) {
        int qty = input.getQuantity() != null ? input.getQuantity() : 0;
        if (qty < 0) {
          SellableRef ref = CartLineRefs.parseRequired(input);
          if (ref.isMenu()) {
            menuDeltaItems.add(buildMenuQuantityDeltaItem(ref, qty));
            continue;
          }
        }
        buildInputs.add(input);
      }

      List<PurchaseItem> result = new ArrayList<>(menuDeltaItems);
      if (!buildInputs.isEmpty()) {
        contributor.get().validateRequestItems(buildInputs);
        result.addAll(
            contributor.get().buildLines(buildInputs, ctx).stream()
                .map(cartLineSnapshotMapper::toPurchaseItem)
                .toList());
      }
      return result;
    }

    List<PurchaseItem> purchaseItems = new ArrayList<>();

    for (AddToCartRequest.CartItem item : items) {
      try {
        // Validate item using CheckoutValidator
        checkoutValidator.validateCartItem(item);

      // Quantity 0 or null with only price/discount/scheme changes = update only (item must already be in cart)
      // Exclude when baseQuantity is non-zero: that indicates a quantity change (e.g. baseQuantity=-1 to remove 1)
      boolean hasSchemeChange = item.getSchemePayFor() != null || item.getSchemeFree() != null
          || item.getSchemeType() != null || item.getSchemePercentage() != null;
      boolean hasQuantityChange = (item.getBaseQuantity() != null && item.getBaseQuantity() != 0)
          || (item.getQuantity() != null && item.getQuantity() != 0);
      boolean updateOnly = (item.getQuantity() == null || item.getQuantity() == 0)
          && !hasQuantityChange
          && (item.getSaleAdditionalDiscount() != null || hasSchemeChange || item.getPriceToRetail() != null
              || StringUtils.hasText(item.getRateName()));

        if (updateOnly) {
          // Verify inventory exists and belongs to shop; no stock check needed
          Inventory inventory = inventoryRepository.findById(item.getId())
              .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", item.getId()));
          if (!shopId.equals(inventory.getShopId())) {
            throw new ValidationException("Inventory lot " + item.getId() + " does not belong to shop " + shopId);
          }
          // No unit sent: the edit is for the line already in the cart, at its unit (a pick on a
          // tablet line is not a switch back to strips).
          String saleUnit = StringUtils.hasText(item.getUnit())
              ? normalizeSaleUnit(item.getUnit(), inventory)
              : cartLineSaleUnit(existingCart, item.getId())
                  .orElseGet(() -> normalizeSaleUnit(null, inventory));
          int saleUnitFactor = getConversionFactorToBase(inventory, saleUnit);
          int pricingFactor = getDisplayToBaseFactor(inventory);
          int baseQuantity = resolveRequestedBaseQuantity(item, inventory, saleUnit, saleUnitFactor);
          BigDecimal pricingQuantity = BigDecimal.valueOf(baseQuantity)
              .divide(BigDecimal.valueOf(pricingFactor), 4, RoundingMode.HALF_UP);
          BigDecimal maximumRetailPrice = inventory.getMaximumRetailPrice();
          // Pass null for priceToRetail when not in request so merge keeps existing line's price
          PurchaseItem purchaseItem = purchaseMapper.createPurchaseItem(
              item.getId(),
              inventory.getName(),
              toSaleQuantityDecimal(baseQuantity, saleUnitFactor),
              maximumRetailPrice,
              item.getPriceToRetail(),
              BigDecimal.ZERO
          );
          if (item.getSaleAdditionalDiscount() != null) {
            purchaseItem.setSaleAdditionalDiscount(item.getSaleAdditionalDiscount());
          }
          if (item.getSchemeType() != null) {
            purchaseItem.setSchemeType(item.getSchemeType());
          }
          if (item.getSchemePercentage() != null) {
            purchaseItem.setSchemePercentage(item.getSchemePercentage());
          }
          // Normalize: PERCENTAGE -> only schemeType/schemePercentage; FIXED_UNITS -> only schemePayFor/schemeFree, schemePercentage null
          if (item.getSchemeType() == SchemeType.PERCENTAGE) {
            purchaseItem.setSchemePayFor(null);
            purchaseItem.setSchemeFree(null);
          } else {
            if (item.getSchemePayFor() != null) purchaseItem.setSchemePayFor(item.getSchemePayFor());
            if (item.getSchemeFree() != null) purchaseItem.setSchemeFree(item.getSchemeFree());
            purchaseItem.setSchemePercentage(null);
          }
          // A rate picked on a line already in the cart replaces its price, disc and scheme.
          // Only an explicit pick: an edit without rateName must not snap back to the default rate.
          if (StringUtils.hasText(item.getRateName())) {
            cartRateResolver.resolve(inventory, item.getRateName())
                .ifPresent(terms -> applyPickedRateTerms(purchaseItem, terms, inventory, saleUnit));
            purchaseItem.setRequestedRateName(item.getRateName().trim());
          }
          purchaseItem.setSaleUnit(saleUnit);
          purchaseItem.setBaseQuantity(baseQuantity);
          purchaseItem.setUnitFactor(saleUnitFactor);
          enrichCartItemPackaging(purchaseItem, inventory);
          CheckoutUtils.applyItemTaxMode(purchaseItem, CheckoutUtils.resolveInventoryBillingMode(inventory));
          purchaseItems.add(purchaseItem);
        } else if ((item.getQuantity() != null && item.getQuantity() < 0)
            || (item.getBaseQuantity() != null && item.getBaseQuantity() < 0)) {
          // For negative quantities (reduce/remove), verify lotId exists and belongs to the shop
          // Stock validation is not needed for removing items
          Inventory inventory = inventoryRepository.findById(item.getId())
              .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", item.getId()));

          if (!shopId.equals(inventory.getShopId())) {
            throw new ValidationException("Inventory lot " + item.getId() + " does not belong to shop " + shopId);
          }
          String saleUnit = normalizeSaleUnit(item.getUnit(), inventory);
          int factor = getConversionFactorToBase(inventory, saleUnit);
          int pricingFactor = getDisplayToBaseFactor(inventory);
          int baseQuantity = (item.getBaseQuantity() != null && item.getBaseQuantity() < 0)
              ? item.getBaseQuantity()
              : toBaseQuantity(item.getQuantity(), factor);
          BigDecimal pricingQuantity = BigDecimal.valueOf(baseQuantity)
              .divide(BigDecimal.valueOf(pricingFactor), 4, RoundingMode.HALF_UP);
          BigDecimal maximumRetailPrice = inventory.getMaximumRetailPrice();

          // For negative quantities, create a PurchaseItem with negative quantity
          // The updateCart method will handle the logic
          PurchaseItem purchaseItem = purchaseMapper.createPurchaseItem(
              item.getId(),
              inventory.getName(),
              toSaleQuantityDecimal(baseQuantity, factor),
              maximumRetailPrice,
              BigDecimal.ZERO, // Not used for negative quantities
              BigDecimal.ZERO
          );
          purchaseItem.setBatchFilledPrice(true);
          purchaseItem.setSaleUnit(saleUnit);
          purchaseItem.setBaseQuantity(baseQuantity);
          purchaseItem.setUnitFactor(factor);
          enrichCartItemPackaging(purchaseItem, inventory);
          CheckoutUtils.applyItemTaxMode(purchaseItem, CheckoutUtils.resolveInventoryBillingMode(inventory));
          purchaseItems.add(purchaseItem);
        } else {
          // Positive quantity - normal flow with stock validation
          Inventory inventory = inventoryRepository.findById(item.getId())
              .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", item.getId()));

          // Verify the inventory belongs to the shop
          if (!shopId.equals(inventory.getShopId())) {
            throw new ValidationException("Inventory lot " + item.getId() + " does not belong to shop " + shopId);
          }
          String saleUnit = normalizeSaleUnit(item.getUnit(), inventory);
          int factor = getConversionFactorToBase(inventory, saleUnit);
          int pricingFactor = getDisplayToBaseFactor(inventory);
          int baseQuantity = resolveRequestedBaseQuantity(item, inventory, saleUnit, factor);
          int displayQty = item.getQuantity() != null ? Math.abs(item.getQuantity()) : 0;
          if (displayQty <= 0 && factor > 0 && baseQuantity > 0) {
            displayQty = Math.abs(baseQuantity) / factor;
          } else if (displayQty <= 0) {
            displayQty = Math.abs(baseQuantity);
          }
          packagingUnitService.validateSaleQuantity(
              inventory.getBaseUnit(),
              getConfiguredUnitConversion(inventory),
              saleUnit,
              displayQty,
              Math.abs(baseQuantity));
          BigDecimal pricingQuantity = BigDecimal.valueOf(baseQuantity)
              .divide(BigDecimal.valueOf(pricingFactor), 4, RoundingMode.HALF_UP);
          BigDecimal maximumRetailPrice = inventory.getMaximumRetailPrice();

          // Check stock availability
          int availableStock = getCurrentBaseCount(inventory);
          if (availableStock < baseQuantity) {
            throw new InsufficientStockException("Insufficient stock for product: " + inventory.getName(),
                inventory.getBarcode(), availableStock, baseQuantity);
          }

          // Use mapper to create PurchaseItem
          PurchaseItem purchaseItem = purchaseMapper.toPurchaseItemFromCartItem(item, inventory);
          BillingMode itemBillingMode = CheckoutUtils.resolveInventoryBillingMode(inventory);
          CheckoutUtils.applyItemTaxMode(purchaseItem, itemBillingMode);
          CheckoutUtils.normalizeSchemeFields(purchaseItem);
          BigDecimal sellingPrice = inventory.getSellingPrice() != null ? inventory.getSellingPrice() : inventory.getPriceToRetail();
          BigDecimal costPrice = inventory.costForValuation();
          purchaseItem.setQuantity(toSaleQuantityDecimal(Math.abs(baseQuantity), factor));
          purchaseItem.setMaximumRetailPrice(maximumRetailPrice);
          purchaseItem.setPriceToRetail(sellingPrice);
          // Without a rateName the price is always the batch's; disc and scheme are the batch's
          // unless the request sent them. A line already in the cart keeps its own (merge).
          boolean schemeSent = item.getSchemePayFor() != null || item.getSchemeFree() != null
              || item.getSchemeType() != null || item.getSchemePercentage() != null;
          purchaseItem.setBatchFilledPrice(true);
          purchaseItem.setBatchFilledDisc(item.getSaleAdditionalDiscount() == null);
          purchaseItem.setBatchFilledScheme(!schemeSent);
          // A picked rate, or a default rate that is a rule rate, puts its base on the line with its
          // disc and scheme in place of the batch's own sale defaults (never 83.64 with them on top).
          Optional<CartRateResolver.LineTerms> rateTerms = cartRateResolver.resolve(inventory, item.getRateName());
          if (rateTerms.isPresent()) {
            if (StringUtils.hasText(item.getRateName())) {
              applyPickedRateTerms(purchaseItem, rateTerms.get(), inventory, saleUnit);
              purchaseItem.setRequestedRateName(item.getRateName().trim());
            } else {
              applyDefaultRateTerms(purchaseItem, rateTerms.get(), item, inventory, saleUnit);
            }
          }
          if (rateTerms.isPresent()) {
            // A rate's base went on per sale unit, so the line MRP is the one for that unit (a
            // tablet line's per-tablet MRP), also when a typed disc or scheme left no applied rate.
            maximumRetailPrice = purchaseItem.getMaximumRetailPrice();
          }
          BigDecimal linePrice = purchaseItem.getPriceToRetail();
          purchaseItem.setCostPrice(costPrice);
          purchaseItem.setUnitFactor(factor);
          BigDecimal perUnitDiscount = maximumRetailPrice
              .subtract(linePrice != null ? linePrice : BigDecimal.ZERO);
          if (perUnitDiscount.compareTo(BigDecimal.ZERO) > 0) {
            purchaseItem.setDiscount(perUnitDiscount.multiply(CheckoutUtils.getQuantityAsPricingUnits(purchaseItem)));
          } else {
            purchaseItem.setDiscount(BigDecimal.ZERO);
          }
          BigDecimal effectivePrice = CheckoutUtils.getEffectiveSellingPricePerUnit(purchaseItem);
          BigDecimal billableQty = CheckoutUtils.getBillableQuantityAsDecimal(purchaseItem);
          boolean includeTax = CheckoutUtils.isTaxApplicableForItem(purchaseItem, itemBillingMode);
          BigDecimal totalAmount = calculateItemTotalAmount(effectivePrice, purchaseItem.getSaleAdditionalDiscount(), billableQty,
              purchaseItem.getCgst(), purchaseItem.getSgst(), includeTax);
          purchaseItem.setTotalAmount(totalAmount);
          purchaseItem.setSaleUnit(saleUnit);
          purchaseItem.setBaseQuantity(baseQuantity);
          enrichCartItemPackaging(purchaseItem, inventory);
          purchaseMapper.enrichPurchaseItemMargin(purchaseItem);
          purchaseItems.add(purchaseItem);
        }

      } catch (ValidationException | InsufficientStockException | ResourceNotFoundException e) {
        log.warn("Item validation failed for lotId: {} - {}", item.getId(), e.getMessage());
        throw e;
      } catch (Exception e) {
        log.error("Unexpected error processing item with lotId: {}", item.getId(), e);
        throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
            "Error processing item with lotId " + item.getId() + ": " + e.getMessage(), e);
      }
    }

    return purchaseItems;
  }

  /**
   * Taxable value before the additional discount. A line sold at MRP contributes its price with
   * the GST inside it taken out, so subtotal − additional discount + tax is what the lines add
   * up to rather than overshooting it by that GST.
   */
  private BigDecimal calculateSubtotal(List<PurchaseItem> items) {
    if (items == null || items.isEmpty()) {
      return BigDecimal.ZERO;
    }
    return items.stream()
        .map(item -> {
          BigDecimal effectivePrice = CheckoutUtils.getTaxablePricePerUnit(item);
          BigDecimal billableQty = CheckoutUtils.getBillableQuantityAsDecimal(item);
          return effectivePrice.multiply(billableQty);
        })
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Tax on a sale, split by where it is going.
   *
   * <p>The customer's own GSTIN places them. An unregistered buyer has none to read and the place
   * of supply is the shop's own state, so they are local -- which is the ordinary counter sale.
   */
  private TaxCalculationResult calculateTax(List<PurchaseItem> purchaseItems, String shopId,
      BillingMode billingMode, String customerId) {
    boolean interstate = isInterstateSale(shopId, customerId);
    return calculateTax(purchaseItems, shopId, billingMode, interstate);
  }

  /**
   * Tax on purchase items at each item's own CGST/SGST rate (shop defaults where an item has
   * none), summed; on an interstate sale the same total is charged as IGST.
   */
  private TaxCalculationResult calculateTax(List<PurchaseItem> purchaseItems, String shopId,
      BillingMode billingMode, boolean interstate) {
    if (!CheckoutUtils.isTaxApplicable(billingMode)) {
      return new TaxCalculationResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
    BigDecimal totalSgstAmount = BigDecimal.ZERO;
    BigDecimal totalCgstAmount = BigDecimal.ZERO;
    
    // Get shop defaults for items without inventory-level rates
    String shopSgst = null;
    String shopCgst = null;
    if (shopId != null && !shopId.trim().isEmpty()) {
      Optional<Shop> shopOpt = shopRepository.findById(shopId);
      if (shopOpt.isPresent()) {
        Shop shop = shopOpt.get();
        shopSgst = shop.getSgst();
        shopCgst = shop.getCgst();
      }
    }
    
    // Calculate tax for each item based on its inventory-level CGST/SGST.
    // A line sold at MRP adds no GST on top, but the GST inside its price is still charged and is
    // stated here: its tax is worked on the price with that GST taken out, so it is the GST the
    // MRP already contains. Skipping it left the bill, the journal and the invoice saying no tax
    // was charged on goods whose price included it.
    for (PurchaseItem item : purchaseItems) {
      boolean sellingAtMrp = CheckoutUtils.isSellingAtMrp(item);
      // Item total for tax: use paid quantity when scheme is set (billing basis)
      BigDecimal itemTotal = BigDecimal.ZERO;
      if (item.getMaximumRetailPrice() != null && item.getQuantity() != null 
          && item.getPriceToRetail() != null) {
        // Shared with returns (SalesReturnValuation), so a return reverses this exact tax.
        itemTotal = CheckoutUtils.getTaxableLineValue(item, CheckoutUtils.getBillableQuantityAsDecimal(item));
      }
      
      // Use inventory-level rates if available, otherwise use shop defaults. A line sold at MRP
      // uses only its own rates: its taxable price was worked from them, and a shop default it
      // does not carry would charge tax that its price does not contain.
      String itemSgst = StringUtils.hasText(item.getSgst()) ? item.getSgst() : (sellingAtMrp ? null : shopSgst);
      String itemCgst = StringUtils.hasText(item.getCgst()) ? item.getCgst() : (sellingAtMrp ? null : shopCgst);
      
      // Calculate SGST for this item
      if (itemSgst != null && !itemSgst.trim().isEmpty()) {
        BigDecimal itemSgstAmount = CheckoutUtils.gstHeadAmount(itemTotal, itemSgst);
        totalSgstAmount = totalSgstAmount.add(itemSgstAmount);
      }
      
      // Calculate CGST for this item
      if (itemCgst != null && !itemCgst.trim().isEmpty()) {
        BigDecimal itemCgstAmount = CheckoutUtils.gstHeadAmount(itemTotal, itemCgst);
        totalCgstAmount = totalCgstAmount.add(itemCgstAmount);
      }
    }
    
    BigDecimal taxTotal = totalSgstAmount.add(totalCgstAmount);

    // A supply leaving the state is taxed as IGST at the full rate rather than split in two. The
    // rate is the same either way, so the total does not move -- only which head it sits under,
    // and the customer can only claim it under the head the invoice states.
    if (interstate) {
      return new TaxCalculationResult(
          BigDecimal.ZERO, BigDecimal.ZERO, taxTotal, taxTotal, true);
    }
    return new TaxCalculationResult(totalSgstAmount, totalCgstAmount, BigDecimal.ZERO,
        taxTotal, false);
  }
  
  /**
   * Whether a sale leaves the state the shop is registered in.
   *
   * <p>A registered buyer is placed by their GSTIN, which is the authority. An unregistered one
   * has none, and is placed by the address the shop holds for them -- an interstate supply is
   * interstate whether or not the buyer is registered, and the tax is due under IGST either way.
   *
   * <p>Anything unplaceable is treated as local. That is the far more common case -- a walk-in
   * with no address on record is standing in the shop -- and reporting a supply as interstate on
   * a guess would charge the customer under a head that does not apply to them.
   */
  private boolean isInterstateSale(String shopId, String customerId) {
    if (!StringUtils.hasText(customerId)) {
      return false;
    }
    SupplyPlacement p = supplyPlacement.getIfAvailable();
    return p != null && p.isInterstateSale(shopId, customerId);
  }

  /** Inner class to hold tax calculation results. */
  private static class TaxCalculationResult {
    private final BigDecimal sgstAmount;
    private final BigDecimal cgstAmount;
    private final BigDecimal igstAmount;
    private final BigDecimal taxTotal;
    private final boolean interstate;

    public TaxCalculationResult(BigDecimal sgstAmount, BigDecimal cgstAmount, BigDecimal taxTotal) {
      this(sgstAmount, cgstAmount, BigDecimal.ZERO, taxTotal, false);
    }

    public TaxCalculationResult(BigDecimal sgstAmount, BigDecimal cgstAmount,
        BigDecimal igstAmount, BigDecimal taxTotal, boolean interstate) {
      this.sgstAmount = sgstAmount;
      this.cgstAmount = cgstAmount;
      this.igstAmount = igstAmount;
      this.taxTotal = taxTotal;
      this.interstate = interstate;
    }

    public BigDecimal getIgstAmount() {
      return igstAmount;
    }

    public boolean isInterstate() {
      return interstate;
    }
    
    public BigDecimal getSgstAmount() {
      return sgstAmount;
    }
    
    public BigDecimal getCgstAmount() {
      return cgstAmount;
    }
    
    public BigDecimal getTaxTotal() {
      return taxTotal;
    }
  }

  private BigDecimal calculateTotalDiscount(List<PurchaseItem> items) {
    if (items == null || items.isEmpty()) {
      return BigDecimal.ZERO;
    }
    return items.stream()
        .map(item -> {
          BigDecimal mrp = item.getMaximumRetailPrice() != null ? item.getMaximumRetailPrice() : BigDecimal.ZERO;
          BigDecimal effectivePrice = CheckoutUtils.getEffectiveSellingPricePerUnit(item);
          BigDecimal billableQty = CheckoutUtils.getBillableQuantityAsDecimal(item);
          return mrp.subtract(effectivePrice).multiply(billableQty);
        })
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Round grand total to nearest whole rupee (e.g. 319.19 → 319, 319.50 → 320).
   */
  private BigDecimal roundOffToWholeRupee(BigDecimal amount) {
    if (amount == null) return BigDecimal.ZERO;
    return amount.setScale(0, RoundingMode.HALF_UP);
  }

  /**
   * Calculate totalAmount for a single purchase item.
   * Formula:
   * 1. Apply additionalDiscount to priceToRetail: priceToRetail * (1 - additionalDiscount/100)
   * 2. Multiply by quantity
   * 3. Add CGST and SGST: totalDiscountedAmount * (1 + cgst/100 + sgst/100)
   */
  private BigDecimal calculateItemTotalAmount(BigDecimal priceToRetail, BigDecimal additionalDiscount,
                                               BigDecimal billableQuantity, String cgst, String sgst,
                                               boolean includeTax) {
    if (priceToRetail == null || billableQuantity == null || billableQuantity.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    // Step 1: Calculate discounted/marked-up selling price per unit
    // Formula: price * (1 - additionalDiscount/100). Negative discount = markup (e.g. -2% => 1.02)
    // The % is divided at 6 dp, as rate rules and the purchase cost divide it (7.125% => 0.07125).
    BigDecimal discountedPricePerUnit = priceToRetail;
    if (additionalDiscount != null && additionalDiscount.compareTo(BigDecimal.ZERO) != 0) {
      BigDecimal discountMultiplier = BigDecimal.ONE.subtract(
          additionalDiscount.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
      );
      discountedPricePerUnit = priceToRetail.multiply(discountMultiplier);
    }
    // Step 2: Multiply by billable quantity (can be fractional for FIXED_UNITS scheme)
    BigDecimal totalDiscountedAmount = discountedPricePerUnit.multiply(billableQuantity);
    
    // Step 3: Add CGST and SGST when billing mode requires tax.
    if (!includeTax) {
      return totalDiscountedAmount.setScale(2, RoundingMode.HALF_UP);
    }
    BigDecimal taxMultiplier = BigDecimal.ONE;
    if (cgst != null && StringUtils.hasText(cgst)) {
      BigDecimal cgstRate = GstMath.parseGstRate(cgst).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
      taxMultiplier = taxMultiplier.add(cgstRate);
    }
    if (sgst != null && StringUtils.hasText(sgst)) {
      BigDecimal sgstRate = GstMath.parseGstRate(sgst).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
      taxMultiplier = taxMultiplier.add(sgstRate);
    }
    
    BigDecimal totalAmount = totalDiscountedAmount.multiply(taxMultiplier);
    return totalAmount.setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Calculate total additional discount amount.
   * Additional discount is a percentage applied on selling price.
   * Formula: (priceToRetail * quantity) * (additionalDiscount / 100)
   */
  private BigDecimal calculateAdditionalDiscountTotal(List<PurchaseItem> items) {
    if (items == null || items.isEmpty()) {
      return BigDecimal.ZERO;
    }
    return items.stream()
        .map(item -> {
          // On the taxable price, the same basis as the subtotal it is taken from.
          BigDecimal effectivePrice = CheckoutUtils.getTaxablePricePerUnit(item);
          BigDecimal additionalDiscount = item.getSaleAdditionalDiscount() != null ? item.getSaleAdditionalDiscount() : BigDecimal.ZERO;
          BigDecimal billableQty = CheckoutUtils.getBillableQuantityAsDecimal(item);
          BigDecimal itemTotal = effectivePrice.multiply(billableQty);
          BigDecimal discountAmount = itemTotal.multiply(additionalDiscount.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
          return discountAmount;
        })
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
  }

  private void recalculateLineTotalsForBillingMode(List<PurchaseItem> items, BillingMode billingMode) {
    if (items == null || items.isEmpty()) {
      return;
    }
    for (PurchaseItem item : items) {
      CheckoutUtils.applyItemTaxMode(item, billingMode);
      boolean includeTax = CheckoutUtils.isTaxApplicableForItem(item, billingMode);
      BigDecimal effectivePrice = CheckoutUtils.getEffectiveSellingPricePerUnit(item);
      BigDecimal billableQty = CheckoutUtils.getBillableQuantityAsDecimal(item);
      item.setTotalAmount(calculateItemTotalAmount(
          effectivePrice,
          item.getSaleAdditionalDiscount(),
          billableQty,
          item.getCgst(),
          item.getSgst(),
          includeTax));
      purchaseMapper.enrichPurchaseItemMargin(item);
    }
  }

  /**
   * Set purchase-level margin breakdown: totalCost, revenueBeforeTax, totalProfit, marginPercent.
   * revenueBeforeTax = subTotal − additionalDiscountTotal; totalProfit = revenueBeforeTax − totalCost;
   * marginPercent = totalProfit ÷ totalCost × 100, i.e. markup on cost.
   */
  private void setPurchaseMarginDetails(Purchase purchase) {
    if (purchase == null || purchase.getItems() == null || purchase.getItems().isEmpty()) {
      return;
    }
    BigDecimal totalCost = purchase.getItems().stream()
        .map(item -> item.getCostTotal() != null ? item.getCostTotal() : BigDecimal.ZERO)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
    purchase.setTotalCost(totalCost);
    BigDecimal subTotal = purchase.getSubTotal() != null ? purchase.getSubTotal() : BigDecimal.ZERO;
    BigDecimal additionalDiscountTotal = purchase.getSaleAdditionalDiscountTotal() != null ? purchase.getSaleAdditionalDiscountTotal() : BigDecimal.ZERO;
    BigDecimal revenueBeforeTax = subTotal.subtract(additionalDiscountTotal).setScale(2, RoundingMode.HALF_UP);
    purchase.setRevenueBeforeTax(revenueBeforeTax);
    BigDecimal revenueAfterTax = purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO;
    purchase.setRevenueAfterTax(revenueAfterTax.setScale(2, RoundingMode.HALF_UP));
    BigDecimal totalProfit = revenueBeforeTax.subtract(totalCost).setScale(2, RoundingMode.HALF_UP);
    purchase.setTotalProfit(totalProfit);
    // Against cost, not revenue. Both are ordinary measures -- profit over
    // revenue is margin, profit over cost is markup -- but the counter staff
    // read this number against the one their previous system showed, which is
    // markup. On a sale costing 368.22 and earning 30.01 the two read 7.5% and
    // 8.15%, and a percentage that disagrees with the till they have used for
    // years is not trusted, whichever definition is the tidier one.
    if (totalCost.compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal marginPercent = totalProfit.multiply(BigDecimal.valueOf(100))
          .divide(totalCost, 2, RoundingMode.HALF_UP);
      purchase.setMarginPercent(marginPercent);
    } else {
      purchase.setMarginPercent(null);
    }
  }

  private Purchase createCart(AddToCartRequest request, List<PurchaseItem> purchaseItems, String shopId, String userId,
                              String customerId, String customerName, BillingMode billingMode) {
    try {
      recalculateLineTotalsForBillingMode(purchaseItems, billingMode);
      // Calculate totals
      BigDecimal subTotal = calculateSubtotal(purchaseItems);
      TaxCalculationResult taxResult = calculateTax(purchaseItems, shopId, billingMode, customerId);
      BigDecimal discountTotal = calculateTotalDiscount(purchaseItems);
      BigDecimal additionalDiscountTotal = calculateAdditionalDiscountTotal(purchaseItems);
      BigDecimal calculatedTotal = subTotal.add(taxResult.getTaxTotal()).subtract(additionalDiscountTotal);
      BigDecimal grandTotal = roundOffToWholeRupee(calculatedTotal);

      // Create purchase with CREATED status using mapper
      // MongoDB will auto-generate the id as ObjectId
      Purchase purchase = purchaseMapper.toPurchaseForCart(
          request, purchaseItems, subTotal, taxResult.getTaxTotal(), discountTotal, grandTotal, shopId, userId, customerId, billingMode
      );
      // Invoice number is assigned only when purchase is completed (avoids wasting sequence on cart changes)
      // Set tax amounts, additional discount, and customerName
      purchase.setDocumentType(DocumentType.SALE);
      purchase.setSgstAmount(taxResult.getSgstAmount());
      purchase.setCgstAmount(taxResult.getCgstAmount());
      purchase.setIgstAmount(taxResult.getIgstAmount());
      purchase.setInterstate(taxResult.isInterstate());
      purchase.setSaleAdditionalDiscountTotal(additionalDiscountTotal);
      setPurchaseMarginDetails(purchase);

      if (StringUtils.hasText(customerName)) {
        purchase.setCustomerName(customerName);
      }

      return purchaseRepository.save(purchase);
    } catch (DataAccessException e) {
      log.error("Database error while creating cart for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error creating cart: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error while creating cart for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error creating cart: " + e.getMessage(), e);
    }
  }

  /**
   * Get or create customer ID from request.
   * - If only customer name is provided (no phone/email), return null
   * - If customer phone or email is present, find or create customer and optionally link to StockKart user
   */
  private String getOrCreateCustomerId(String shopId, AddToCartRequest request) {
    return customerService.resolvePurchaseCustomerId(
        shopId, request.getCustomerId(), PurchaseCustomerRequests.fromCart(request));
  }

  private Purchase updateCart(Purchase existingCart, List<PurchaseItem> newItems, String businessType,
                              String customerId, String customerName, BillingMode billingMode) {
    try {
      // Merge items - if same inventoryId exists, update quantity; otherwise add new
      List<PurchaseItem> mergedItems = new ArrayList<>(existingCart.getItems() != null ? existingCart.getItems() : new ArrayList<>());

      for (PurchaseItem newItem : newItems) {
        if (PurchaseItemRefs.isMenuLine(newItem)) {
          mergeMenuCartLine(mergedItems, newItem);
          continue;
        }
        boolean found = false;
        for (int i = 0; i < mergedItems.size(); i++) {
          PurchaseItem existingItem = mergedItems.get(i);
          if (sameCartLine(existingItem, newItem)) {
            String existingSaleUnit = existingItem.getSaleUnit() != null ? existingItem.getSaleUnit() : "UNIT";
            String incomingSaleUnit = newItem.getSaleUnit() != null ? newItem.getSaleUnit() : existingSaleUnit;
            if (!existingSaleUnit.equals(incomingSaleUnit)) {
              if (StringUtils.hasText(newItem.getRequestedRateName())) {
                throw new ValidationException("rateName cannot be sent with a unit change for "
                    + existingItem.getName() + ": switch the unit first, then pick the rate");
              }
              boolean wasAtMrp = CheckoutUtils.isSellingAtMrp(existingItem);
              String existingLotId = PurchaseItemRefs.stockLotId(existingItem);
              Inventory inventory = inventoryRepository.findById(existingLotId)
                  .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", existingLotId));
              int existingBaseQuantity = getBaseQuantityOrFallback(existingItem);
              int incomingBaseQuantity = getBaseQuantityOrFallback(newItem);
              int switchBaseQuantity = incomingBaseQuantity > 0 ? incomingBaseQuantity : existingBaseQuantity;
              int targetFactor = getConversionFactorToBase(inventory, incomingSaleUnit);
              if (targetFactor > 1) {
                int roundedWholeUnits = switchBaseQuantity / targetFactor;
                if (roundedWholeUnits <= 0) {
                  throw new ValidationException("Cannot switch from " + existingSaleUnit + " to " + incomingSaleUnit
                      + " because quantity is less than one whole " + incomingSaleUnit);
                }
                switchBaseQuantity = roundedWholeUnits * targetFactor;
              }
              BigDecimal saleQty = toSaleQuantityDecimal(switchBaseQuantity, targetFactor);
              // The line's own price is for its old unit: rescale it so the line total stays. A
              // price sent with the switch is already for the new unit (ScanSell rescales it
              // before sending), so it goes on as sent, not rescaled a second time.
              BigDecimal rescaledLinePrice = existingItem.getPriceToRetail();
              if (rescaledLinePrice != null
                  && existingItem.getQuantity() != null
                  && existingItem.getQuantity().compareTo(BigDecimal.ZERO) > 0
                  && saleQty.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal lineTotal = rescaledLinePrice.multiply(existingItem.getQuantity());
                rescaledLinePrice = lineTotal.divide(saleQty, 2, RoundingMode.HALF_UP);
              }
              boolean priceSent = newItem.getPriceToRetail() != null && !newItem.isBatchFilledPrice();
              BigDecimal priceToRetail = priceSent ? newItem.getPriceToRetail() : rescaledLinePrice;
              BigDecimal maximumRetailPrice = resolveInventoryMaximumRetailPrice(
                  inventory,
                  existingItem.getMaximumRetailPrice(),
                  newItem.getMaximumRetailPrice(),
                  priceToRetail);
              // A line sold at MRP has the GST inside its price. Rescaling the price while the
              // MRP stays the batch's would part the two and add GST on top, billing above MRP,
              // so it stays at MRP for the new unit instead.
              if (wasAtMrp && inventory.getMaximumRetailPrice() != null) {
                // Any price sent with the switch is ignored here: the line stays at MRP.
                maximumRetailPrice = resolveMaximumRetailPriceForSaleUnit(inventory, incomingSaleUnit);
                priceToRetail = maximumRetailPrice;
              }
              BigDecimal costPrice = inventory.costForValuation();
              BigDecimal unitPrice = priceToRetail != null ? priceToRetail : BigDecimal.ZERO;
              BigDecimal perUnitDiscount = maximumRetailPrice.subtract(unitPrice);

              PurchaseItem switchedItem = purchaseMapper.createPurchaseItem(
                  existingLotId,
                  existingItem.getName(),
                  saleQty,
                  maximumRetailPrice,
                  priceToRetail,
                  perUnitDiscount.compareTo(BigDecimal.ZERO) > 0
                      ? perUnitDiscount.multiply(saleQty)
                      : BigDecimal.ZERO
              );
              // Terms the batch filled in (the request sent none) never replace the line's own.
              PurchaseItem termsSource = newItem.isBatchFilledScheme() ? existingItem : newItem;
              switchedItem.setSaleAdditionalDiscount(newItem.getSaleAdditionalDiscount() != null && !newItem.isBatchFilledDisc()
                  ? newItem.getSaleAdditionalDiscount()
                  : existingItem.getSaleAdditionalDiscount());
              SchemeType switchedSchemeType = termsSource.getSchemeType() != null ? termsSource.getSchemeType() : existingItem.getSchemeType();
              switchedItem.setSchemeType(switchedSchemeType);
              switchedItem.setSchemePercentage(termsSource.getSchemePercentage() != null
                  ? termsSource.getSchemePercentage()
                  : existingItem.getSchemePercentage());
              if (switchedSchemeType == SchemeType.PERCENTAGE) {
                switchedItem.setSchemePayFor(null);
                switchedItem.setSchemeFree(null);
              } else {
                switchedItem.setSchemePayFor(termsSource.getSchemePayFor() != null ? termsSource.getSchemePayFor() : existingItem.getSchemePayFor());
                switchedItem.setSchemeFree(termsSource.getSchemeFree() != null ? termsSource.getSchemeFree() : existingItem.getSchemeFree());
                switchedItem.setSchemePercentage(null);
              }
              // A switch keeps the line's rate; a price, disc or scheme edit sent with it clears it.
              // A price sent to a line held at MRP is ignored above, so it edits nothing.
              boolean priceEdited = priceSent
                  && !sameMoney(newItem.getPriceToRetail(), rescaledLinePrice)
                  && !(wasAtMrp && inventory.getMaximumRetailPrice() != null);
              switchedItem.setAppliedRateName(priceEdited || discOrSchemeEdited(existingItem, newItem)
                  ? null : existingItem.getAppliedRateName());
              switchedItem.setSgst(existingItem.getSgst());
              switchedItem.setCgst(existingItem.getCgst());
              switchedItem.setCostPrice(costPrice);
              switchedItem.setSaleUnit(incomingSaleUnit);
              switchedItem.setBaseQuantity(switchBaseQuantity);
              switchedItem.setUnitFactor(targetFactor);
              enrichCartItemPackaging(switchedItem, inventory);
              CheckoutUtils.applyItemTaxMode(switchedItem, billingMode);
              BigDecimal effectivePrice = CheckoutUtils.getEffectiveSellingPricePerUnit(switchedItem);
              BigDecimal billableQty = CheckoutUtils.getBillableQuantityAsDecimal(switchedItem);
              boolean includeTaxForItem = CheckoutUtils.isTaxApplicableForItem(switchedItem, billingMode);
              BigDecimal totalAmount = calculateItemTotalAmount(
                  effectivePrice,
                  switchedItem.getSaleAdditionalDiscount(),
                  billableQty,
                  switchedItem.getCgst(),
                  switchedItem.getSgst(),
                  includeTaxForItem
              );
              switchedItem.setTotalAmount(totalAmount);
              purchaseMapper.enrichPurchaseItemMargin(switchedItem);

              mergedItems.set(i, switchedItem);
              found = true;
              break;
            }
            // Handle quantity update based on positive or negative
            boolean wasAtMrp = CheckoutUtils.isSellingAtMrp(existingItem);
            String existingLotId = PurchaseItemRefs.stockLotId(existingItem);
            Inventory inventory = inventoryRepository.findById(existingLotId)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", existingLotId));
            int existingBaseQuantity = getBaseQuantityOrFallback(existingItem);
            int incomingBaseQuantity = getBaseQuantityOrFallback(newItem);
            // FE always sends add/subtract deltas (including typed qty and +/-). Never replace.
            int newBaseQuantity = existingBaseQuantity + incomingBaseQuantity;

            // Case 3: If negative value is more negative or equal to current item quantity, remove the item
            if (newBaseQuantity <= 0) {
              mergedItems.remove(i);
              found = true;
              break;
            }

            // Case 1 & 2: Update quantity (positive adds, negative decreases). A line already in
            // the cart keeps its own price, disc and scheme unless the request sent them: a price
            // the batch filled in (every add without rateName) or a negative quantity never
            // replaces it, nor do disc or scheme the batch filled in.
            boolean priceSent = (newItem.getQuantity() == null || newItem.getQuantity().compareTo(BigDecimal.ZERO) >= 0)
                && newItem.getPriceToRetail() != null && !newItem.isBatchFilledPrice();
            BigDecimal priceToRetail = priceSent ? newItem.getPriceToRetail() : existingItem.getPriceToRetail();
            int saleUnitFactor = getConversionFactorToBase(inventory, existingSaleUnit);
            BigDecimal maximumRetailPrice = resolveInventoryMaximumRetailPrice(
                inventory,
                existingItem.getMaximumRetailPrice(),
                newItem.getMaximumRetailPrice(),
                priceToRetail);
            if (StringUtils.hasText(newItem.getRequestedRateName()) && newItem.getMaximumRetailPrice() != null) {
              // A picked rate brings the MRP for the line's sale unit with its price.
              maximumRetailPrice = newItem.getMaximumRetailPrice();
            }
            // A quantity change on a line sold at MRP keeps it at MRP for its sale unit. Taking the
            // batch's per-pack MRP (or the per-pack selling price) instead would drop a tablet line
            // off MRP and add GST on top. A price edit sends no quantity and is left as typed.
            // A rate picked with the quantity change (rule or legacy) brings its own price and is
            // not held at MRP.
            if (wasAtMrp && incomingBaseQuantity != 0
                && !StringUtils.hasText(newItem.getRequestedRateName())
                && inventory.getMaximumRetailPrice() != null) {
              maximumRetailPrice = resolveMaximumRetailPriceForSaleUnit(inventory, existingSaleUnit);
              priceToRetail = maximumRetailPrice;
            }
            BigDecimal costPrice = inventory.costForValuation();
            BigDecimal newQuantity = toSaleQuantityDecimal(newBaseQuantity, saleUnitFactor);
            BigDecimal unitPrice = priceToRetail != null ? priceToRetail : BigDecimal.ZERO;
            BigDecimal perUnitDiscount = maximumRetailPrice.subtract(unitPrice);
            BigDecimal newDiscount =
                perUnitDiscount.compareTo(BigDecimal.ZERO) > 0
                    ? perUnitDiscount.multiply(newQuantity)
                    : BigDecimal.ZERO;
            boolean discSent = newItem.getSaleAdditionalDiscount() != null && !newItem.isBatchFilledDisc();
            BigDecimal additionalDiscount = discSent
                ? newItem.getSaleAdditionalDiscount()
                : existingItem.getSaleAdditionalDiscount();
            Integer schemePayFor = existingItem.getSchemePayFor();
            Integer schemeFree = existingItem.getSchemeFree();
            SchemeType schemeType = existingItem.getSchemeType();
            BigDecimal schemePercentage = existingItem.getSchemePercentage();
            if (!newItem.isBatchFilledScheme()) {
              // Use payload scheme fields when provided; otherwise keep existing
              if (newItem.getSchemePayFor() != null) schemePayFor = newItem.getSchemePayFor();
              if (newItem.getSchemeFree() != null) schemeFree = newItem.getSchemeFree();
              if (newItem.getSchemeType() != null) schemeType = newItem.getSchemeType();
              if (newItem.getSchemePercentage() != null) schemePercentage = newItem.getSchemePercentage();
            }
            // A picked rate's terms are the line's terms: a scheme it lacks is cleared, not kept.
            if (newItem.isRateTermsPicked()) {
              additionalDiscount = newItem.getSaleAdditionalDiscount();
              schemePayFor = newItem.getSchemePayFor();
              schemeFree = newItem.getSchemeFree();
              schemeType = newItem.getSchemeType();
              schemePercentage = newItem.getSchemePercentage();
            }
            // A system pick (PTR, MRP, cost) leaves a rule rate's terms: the line goes back to the
            // batch's own sale disc and scheme. On any other line its disc and scheme stay.
            if (newItem.isSystemRatePicked()
                && cartRateResolver.isRuleRate(inventory, existingItem.getAppliedRateName())) {
              PurchaseItem defaults = purchaseMapper.toPurchaseItemFromCartItem(
                  new AddToCartRequest.CartItem(), inventory);
              additionalDiscount = defaults.getSaleAdditionalDiscount();
              schemePayFor = defaults.getSchemePayFor();
              schemeFree = defaults.getSchemeFree();
              schemeType = defaults.getSchemeType();
              schemePercentage = defaults.getSchemePercentage();
            }
            // The line keeps the rate it is billed at until a pick replaces it or a manual price,
            // disc or scheme edit (a sent value that differs from the line's) clears it.
            String appliedRateName = existingItem.getAppliedRateName();
            if (StringUtils.hasText(newItem.getRequestedRateName())) {
              appliedRateName = newItem.getAppliedRateName();
            } else if ((priceSent && !sameMoney(newItem.getPriceToRetail(), existingItem.getPriceToRetail()))
                || discOrSchemeEdited(existingItem, newItem)) {
              appliedRateName = null;
            }

            PurchaseItem updatedItem = purchaseMapper.createPurchaseItem(
                existingLotId,
                existingItem.getName(),
                newQuantity,
                maximumRetailPrice,
                priceToRetail,
                newDiscount.compareTo(BigDecimal.ZERO) > 0 ? newDiscount : BigDecimal.ZERO
            );
            updatedItem.setSaleAdditionalDiscount(additionalDiscount);
            updatedItem.setAppliedRateName(appliedRateName);
            updatedItem.setSchemeType(schemeType);
            updatedItem.setSchemePercentage(schemePercentage);
            // Normalize: PERCENTAGE -> payFor/free null; FIXED_UNITS -> schemePercentage null
            if (schemeType == SchemeType.PERCENTAGE) {
              updatedItem.setSchemePayFor(null);
              updatedItem.setSchemeFree(null);
            } else {
              updatedItem.setSchemePayFor(schemePayFor);
              updatedItem.setSchemeFree(schemeFree);
              updatedItem.setSchemePercentage(null);
            }
            updatedItem.setSgst(existingItem.getSgst());
            updatedItem.setCgst(existingItem.getCgst());
            updatedItem.setCostPrice(costPrice);
            updatedItem.setSaleUnit(existingSaleUnit);
            updatedItem.setBaseQuantity(newBaseQuantity);
            updatedItem.setUnitFactor(saleUnitFactor);
            enrichCartItemPackaging(updatedItem, inventory);
            CheckoutUtils.applyItemTaxMode(updatedItem, billingMode);
            BigDecimal perUnitDiscountAfterUpdate = maximumRetailPrice.subtract(unitPrice);
            if (perUnitDiscountAfterUpdate.compareTo(BigDecimal.ZERO) > 0) {
              updatedItem.setDiscount(
                  perUnitDiscountAfterUpdate.multiply(
                      CheckoutUtils.getQuantityAsPricingUnits(updatedItem)));
            } else {
              updatedItem.setDiscount(BigDecimal.ZERO);
            }
            BigDecimal effectivePrice = CheckoutUtils.getEffectiveSellingPricePerUnit(updatedItem);
            BigDecimal billableQty = CheckoutUtils.getBillableQuantityAsDecimal(updatedItem);
            boolean includeTaxForItem = CheckoutUtils.isTaxApplicableForItem(updatedItem, billingMode);
            BigDecimal totalAmount = calculateItemTotalAmount(effectivePrice, additionalDiscount, billableQty,
                updatedItem.getCgst(), updatedItem.getSgst(), includeTaxForItem);
            updatedItem.setTotalAmount(totalAmount);
            purchaseMapper.enrichPurchaseItemMargin(updatedItem);
            mergedItems.set(i, updatedItem);
            found = true;
            break;
          }
        }
        // Case 1: If item not found and quantity is positive, add new item
        if (!found && getBaseQuantityOrFallback(newItem) > 0) {
          CheckoutUtils.applyItemTaxMode(newItem, billingMode);
          mergedItems.add(newItem);
        }
        // A rate picked for a lot that is not in the cart has no line to price.
        if (!found && getBaseQuantityOrFallback(newItem) == 0
            && StringUtils.hasText(newItem.getRequestedRateName())) {
          throw new ResourceNotFoundException("Cart line", "lotId", PurchaseItemRefs.stockLotId(newItem));
        }
        // Case 2 & 3: If item not found and quantity is negative, throw error (can't remove what doesn't exist)
        if (!found && getBaseQuantityOrFallback(newItem) < 0) {
          throw new ValidationException("Cannot remove item with lotId " + PurchaseItemRefs.stockLotId(newItem) +
              " as it does not exist in the cart");
        }
      }

      // Update business type if provided
      if (StringUtils.hasText(businessType)) {
        existingCart.setBusinessType(businessType);
      }
      existingCart.setBillingMode(billingMode);

      // Update customer ID and bill-level display name (clear when walk-in / general)
      existingCart.setCustomerId(customerId);
      if (StringUtils.hasText(customerName)) {
        existingCart.setCustomerName(customerName.trim());
      } else {
        existingCart.setCustomerName(null);
      }

      // Update updatedAt timestamp
      existingCart.setUpdatedAt(Instant.now());

      // Recalculate totals
      recalculateLineTotalsForBillingMode(mergedItems, billingMode);
      existingCart.setItems(mergedItems);
      BigDecimal newSubTotal = calculateSubtotal(mergedItems);
      existingCart.setSubTotal(newSubTotal);
      
      TaxCalculationResult taxResult = calculateTax(
          mergedItems, existingCart.getShopId(), billingMode, existingCart.getCustomerId());
      existingCart.setTaxTotal(taxResult.getTaxTotal());
      existingCart.setSgstAmount(taxResult.getSgstAmount());
      existingCart.setCgstAmount(taxResult.getCgstAmount());
      existingCart.setIgstAmount(taxResult.getIgstAmount());
      existingCart.setInterstate(taxResult.isInterstate());
      
      BigDecimal discountTotal = calculateTotalDiscount(mergedItems);
      BigDecimal additionalDiscountTotal = calculateAdditionalDiscountTotal(mergedItems);
      existingCart.setDiscountTotal(discountTotal);
      existingCart.setSaleAdditionalDiscountTotal(additionalDiscountTotal);
      BigDecimal calculatedTotal = newSubTotal
          .add(taxResult.getTaxTotal())
          .subtract(additionalDiscountTotal);
      existingCart.setGrandTotal(roundOffToWholeRupee(calculatedTotal));
      setPurchaseMarginDetails(existingCart);

      // If cart is empty after updates, we can either delete it or keep it with empty items
      // For now, we'll keep it with empty items (status remains CREATED)
      // You can add logic here to delete the cart if needed

      return purchaseRepository.save(existingCart);
    } catch (ValidationException | ResourceNotFoundException e) {
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while updating cart: {}", existingCart.getId(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error updating cart: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error while updating cart: {}", existingCart.getId(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error updating cart: " + e.getMessage(), e);
    }
  }

  private void validateStockAvailabilityForCartUpdate(Purchase existingCart, List<PurchaseItem> newItems, String shopId) {
    // Create a map of existing cart items by inventoryId for quick lookup
    Map<String, PurchaseItem> existingItemsMap = new HashMap<>();
    if (existingCart.getItems() != null) {
      for (PurchaseItem item : existingCart.getItems()) {
        existingItemsMap.put(cartLineKey(item), item);
      }
    }

    // Validate each new item
    for (PurchaseItem newItem : newItems) {
      if ("menu".equalsIgnoreCase(newItem.getSellMode())) {
        continue;
      }
      // Only validate stock for positive quantities (adding items)
      if (getBaseQuantityOrFallback(newItem) > 0) {
        PurchaseItem existingItem = existingItemsMap.get(cartLineKey(newItem));
        int currentCartBaseQuantity = existingItem != null ? getBaseQuantityOrFallback(existingItem) : 0;
        int addingBaseQuantity = getBaseQuantityOrFallback(newItem);
        // Get inventory to check available stock
        String stockLotId = PurchaseItemRefs.stockLotId(newItem);
        Inventory inventory = inventoryRepository.findById(stockLotId)
            .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", stockLotId));

        if (!shopId.equals(inventory.getShopId())) {
          throw new ValidationException("Inventory lot " + stockLotId + " does not belong to shop " + shopId);
        }

        String existingSaleUnit = existingItem != null
            ? resolvePurchaseItemSaleUnit(existingItem, inventory)
            : null;
        String incomingSaleUnit = resolvePurchaseItemSaleUnit(newItem, inventory);
        boolean unitChanged = existingItem != null
            && existingSaleUnit != null
            && !existingSaleUnit.equals(incomingSaleUnit);

        int finalBaseQuantity;
        if (existingItem == null) {
          finalBaseQuantity = addingBaseQuantity;
        } else if (unitChanged) {
          int requestedBase = addingBaseQuantity > 0 ? addingBaseQuantity : currentCartBaseQuantity;
          int targetFactor = getConversionFactorToBase(inventory, incomingSaleUnit);
          if (targetFactor > 1) {
            requestedBase = (requestedBase / targetFactor) * targetFactor;
          }
          finalBaseQuantity = requestedBase;
        } else {
          finalBaseQuantity = currentCartBaseQuantity + addingBaseQuantity;
        }

        // Check if final quantity exceeds available stock (minus other open quotations)
        Map<String, Integer> quotedElsewhere =
            quotationService.quotedBaseQuantitiesByLot(
                shopId, existingCart != null ? existingCart.getId() : null);
        int reservedElsewhere = quotedElsewhere.getOrDefault(stockLotId, 0);
        int availableStock = getCurrentBaseCount(inventory) - reservedElsewhere;
        if (finalBaseQuantity > availableStock) {
          throw new InsufficientStockException(
              "Insufficient stock for product: " + inventory.getName() +
                  ". Available: " + availableStock +
                  " (reserved in other quotations: " + reservedElsewhere + ")" +
                  ", Requested final quantity in base units: " + finalBaseQuantity +
                  " (current in cart base units: " + currentCartBaseQuantity + ", adding base units: " + addingBaseQuantity + ")",
              inventory.getBarcode(), availableStock, finalBaseQuantity);
        }
      }
    }
  }

  /** Sale unit of the cart's line for this lot, if the lot is in the cart. */
  private static Optional<String> cartLineSaleUnit(Purchase cart, String lotId) {
    if (cart == null || cart.getItems() == null || !StringUtils.hasText(lotId)) {
      return Optional.empty();
    }
    String key = SellableRef.inventory(lotId).encode();
    return cart.getItems().stream()
        .filter(line -> key.equals(PurchaseItemRefs.lineKey(line)))
        .map(PurchaseItem::getSaleUnit)
        .filter(StringUtils::hasText)
        .findFirst();
  }

  private String normalizeSaleUnit(String requestedUnit, Inventory inventory) {
    if (StringUtils.hasText(requestedUnit)) {
      return requestedUnit.trim().toUpperCase();
    }
    UnitConversion conversion = getConfiguredUnitConversion(inventory);
    if (conversion != null && StringUtils.hasText(conversion.getUnit())) {
      return conversion.getUnit().trim().toUpperCase();
    }
    if (StringUtils.hasText(inventory.getBaseUnit())) {
      return inventory.getBaseUnit().trim().toUpperCase();
    }
    return "UNIT";
  }

  /**
   * Cafe and other verticals may register sell price without MRP; fall back to sell/PTR on cart lines.
   */
  private BigDecimal resolveInventoryMaximumRetailPrice(
      Inventory inventory, BigDecimal... fallbacks) {
    if (inventory.getMaximumRetailPrice() != null) {
      return inventory.getMaximumRetailPrice();
    }
    if (fallbacks != null) {
      for (BigDecimal fallback : fallbacks) {
        if (fallback != null) {
          return fallback;
        }
      }
    }
    if (inventory.getSellingPrice() != null) {
      return inventory.getSellingPrice();
    }
    if (inventory.getPriceToRetail() != null) {
      return inventory.getPriceToRetail();
    }
    return BigDecimal.ZERO;
  }

  private int resolveSaleQuantity(Integer requestedQuantity, String requestedUnit, Inventory inventory, String saleUnit) {
    return requestedQuantity != null ? requestedQuantity : 0;
  }

  private int resolveRequestedBaseQuantity(AddToCartRequest.CartItem item, Inventory inventory, String saleUnit, int defaultFactor) {
    if (item.getBaseQuantity() != null && item.getBaseQuantity() != 0) {
      return item.getBaseQuantity();
    }
    if (item.getQuantity() == null) {
      return 0;
    }
    if (!StringUtils.hasText(item.getUnit())) {
      return toBaseQuantity(item.getQuantity(), defaultFactor);
    }
    int factor = getConversionFactorToBase(inventory, saleUnit);
    return toBaseQuantity(item.getQuantity(), factor);
  }

  private int getDefaultFactor(Inventory inventory) {
    UnitConversion conversion = getConfiguredUnitConversion(inventory);
    if (conversion == null) {
      return 1;
    }
    if (conversion.getFactor() <= 0) {
      throw new ValidationException("Invalid unit conversion factor configured for product " + inventory.getName());
    }
    return conversion.getFactor();
  }

  /**
   * The batch MRP for one {@code saleUnit}. The batch MRP is per pack; a base unit's share of it
   * rounds down, so the units of a pack never bill above the pack's MRP (MRP 100 for 15 tablets
   * is 6.66 a tablet, where 6.67 would make the strip 100.05).
   */
  private BigDecimal resolveMaximumRetailPriceForSaleUnit(Inventory inventory, String saleUnit) {
    BigDecimal maximumRetailPrice = inventory.getMaximumRetailPrice() != null
        ? inventory.getMaximumRetailPrice()
        : BigDecimal.ZERO;
    return perPackToSaleUnit(inventory, saleUnit, maximumRetailPrice);
  }

  /**
   * A per-pack figure (the batch's pricing unit, e.g. a strip) for one {@code saleUnit}: the
   * same for the pack, divided by the pack factor and rounded down for the base unit, so the
   * units of a pack never bill above the pack's figure.
   */
  private BigDecimal perPackToSaleUnit(Inventory inventory, String saleUnit, BigDecimal perPack) {
    UnitConversion conversion = getConfiguredUnitConversion(inventory);
    if (conversion == null) {
      return perPack;
    }
    String conversionUnit = conversion.getUnit().trim().toUpperCase();
    int factor = conversion.getFactor();
    if (factor <= 0) {
      throw new ValidationException("Invalid unit conversion factor configured for product " + inventory.getName());
    }
    String baseUnit = StringUtils.hasText(inventory.getBaseUnit())
        ? inventory.getBaseUnit().trim().toUpperCase()
        : "UNIT";
    if (saleUnit.equals(conversionUnit)) {
      return perPack;
    }
    if (saleUnit.equals(baseUnit)) {
      return perPack.divide(BigDecimal.valueOf(factor), 2, RoundingMode.DOWN);
    }
    throw new ValidationException("Unit " + saleUnit + " is not configured for product " + inventory.getName());
  }

  private BigDecimal normalizePriceForSaleUnit(BigDecimal price, Inventory inventory, String saleUnit) {
    if (price == null) {
      return null;
    }
    UnitConversion conversion = getConfiguredUnitConversion(inventory);
    if (conversion == null) {
      return price;
    }
    int factor = conversion.getFactor();
    if (factor <= 0) {
      throw new ValidationException("Invalid unit conversion factor configured for product " + inventory.getName());
    }
    String conversionUnit = conversion.getUnit().trim().toUpperCase();
    String baseUnit = StringUtils.hasText(inventory.getBaseUnit())
        ? inventory.getBaseUnit().trim().toUpperCase()
        : "UNIT";
    if (saleUnit.equals(conversionUnit)) {
      return price;
    }
    if (saleUnit.equals(baseUnit)) {
      return price.divide(BigDecimal.valueOf(factor), 2, RoundingMode.HALF_UP);
    }
    throw new ValidationException("Unit " + saleUnit + " is not configured for product " + inventory.getName());
  }

  private UnitConversion getConfiguredUnitConversion(Inventory inventory) {
    UnitConversion conversion = inventory.getUnitConversions();
    if (conversion == null) {
      return null;
    }
    if (!StringUtils.hasText(conversion.getUnit()) || conversion.getFactor() == null) {
      return null;
    }
    return conversion;
  }

  private int getConversionFactorToBase(Inventory inventory, String saleUnit) {
    String baseUnit = StringUtils.hasText(inventory.getBaseUnit())
        ? inventory.getBaseUnit().trim().toUpperCase()
        : "UNIT";
    if (baseUnit.equals(saleUnit)) {
      return 1;
    }
    UnitConversion conversion = getConfiguredUnitConversion(inventory);
    if (conversion != null
        && saleUnit.equals(conversion.getUnit().trim().toUpperCase())) {
      if (conversion.getFactor() <= 0) {
        throw new ValidationException("Invalid unit conversion factor configured for unit: " + saleUnit);
      }
      return conversion.getFactor();
    }
    throw new ValidationException("Unit " + saleUnit + " is not configured for product " + inventory.getName());
  }

  private int toBaseQuantity(Integer quantity, int factor) {
    try {
      return Math.multiplyExact(quantity, factor);
    } catch (ArithmeticException e) {
      throw new ValidationException("Quantity is too large after unit conversion");
    }
  }

  private int getBaseQuantityOrFallback(PurchaseItem item) {
    if (item.getBaseQuantity() != null) {
      return item.getBaseQuantity();
    }
    return item.getQuantity() != null ? item.getQuantity().setScale(0, RoundingMode.HALF_UP).intValue() : 0;
  }

  private String resolvePurchaseItemSaleUnit(PurchaseItem item, Inventory inventory) {
    if (StringUtils.hasText(item.getSaleUnit())) {
      return item.getSaleUnit().trim().toUpperCase();
    }
    return normalizeSaleUnit(null, inventory);
  }

  private int getCurrentBaseCount(Inventory inventory) {
    if (inventory.getCurrentBaseCount() != null) {
      return inventory.getCurrentBaseCount();
    }
    if (inventory.getCurrentCount() == null) {
      return 0;
    }
    int factor = getDisplayToBaseFactor(inventory);
    return inventory.getCurrentCount()
        .multiply(BigDecimal.valueOf(factor))
        .setScale(0, RoundingMode.HALF_UP)
        .intValue();
  }

  private int getSoldBaseCount(Inventory inventory) {
    if (inventory.getSoldBaseCount() != null) {
      return inventory.getSoldBaseCount();
    }
    if (inventory.getSoldCount() == null) {
      return 0;
    }
    int factor = getDisplayToBaseFactor(inventory);
    return inventory.getSoldCount()
        .multiply(BigDecimal.valueOf(factor))
        .setScale(0, RoundingMode.HALF_UP)
        .intValue();
  }

  private BigDecimal getCurrentDisplayCount(Inventory inventory) {
    if (inventory.getCurrentCount() != null) {
      return inventory.getCurrentCount();
    }
    return toDisplayQuantity(getCurrentBaseCount(inventory), inventory);
  }

  private BigDecimal getSoldDisplayCount(Inventory inventory) {
    if (inventory.getSoldCount() != null) {
      return inventory.getSoldCount();
    }
    return toDisplayQuantity(getSoldBaseCount(inventory), inventory);
  }

  private BigDecimal toDisplayQuantity(int baseQuantity, Inventory inventory) {
    int factor = getDisplayToBaseFactor(inventory);
    return BigDecimal.valueOf(baseQuantity)
        .divide(BigDecimal.valueOf(factor), 4, RoundingMode.HALF_UP);
  }

  private int getDisplayToBaseFactor(Inventory inventory) {
    UnitConversion conversion = getConfiguredUnitConversion(inventory);
    if (conversion == null || conversion.getFactor() == null || conversion.getFactor() <= 0) {
      return 1;
    }
    return conversion.getFactor();
  }

  /** Sale quantity in {@code saleUnit} from canonical base quantity. */
  private BigDecimal toSaleQuantityDecimal(int baseQuantity, int saleUnitFactor) {
    int absBase = Math.abs(baseQuantity);
    int factor = saleUnitFactor > 0 ? saleUnitFactor : 1;
    BigDecimal qty = BigDecimal.valueOf(absBase)
        .divide(BigDecimal.valueOf(factor), 4, RoundingMode.HALF_UP);
    return baseQuantity < 0 ? qty.negate() : qty;
  }

  private List<AvailableUnit> mapAvailableUnits(Inventory inventory) {
    if (inventory == null || !StringUtils.hasText(inventory.getBaseUnit())) {
      return List.of();
    }
    return packagingUnitService
        .mapAvailableUnitsForSale(inventory.getBaseUnit(), getConfiguredUnitConversion(inventory))
        .stream()
        .map(dto -> new AvailableUnit(dto.getUnit(), dto.isBaseUnit()))
        .toList();
  }

  private void enrichCartItemPackaging(PurchaseItem item, Inventory inventory) {
    if (item == null || inventory == null) {
      return;
    }
    if (StringUtils.hasText(inventory.getBaseUnit())) {
      item.setBaseUnit(inventory.getBaseUnit().trim().toUpperCase());
    }
    UnitConversion conversion = getConfiguredUnitConversion(inventory);
    if (conversion != null && StringUtils.hasText(conversion.getUnit())) {
      item.setPackUnitUqc(conversion.getUnit().trim().toUpperCase());
    }
    item.setAvailableUnits(mapAvailableUnits(inventory));
  }

  /**
   * Records billing usage for a completed purchase (grand total and bill count).
   */
  private void recordBillingUsageForPurchase(String shopId, Purchase purchase) {
    if (usageService == null) {
      return;
    }
    BigDecimal grandTotal = purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO;
    RecordUsageRequest usageReq = new RecordUsageRequest();
    usageReq.setBillingAmount(grandTotal);
    usageReq.setBillCount(1);
    usageService.recordUsage(shopId, usageReq);
  }

  private void updateInventoryForCompletedPurchase(Purchase purchase) {
    if (purchase.getItems() == null || purchase.getItems().isEmpty()) {
      log.warn("Purchase {} has no items to process for inventory update", purchase.getId());
      return;
    }

    log.info("Updating inventory for {} items in purchase {}", purchase.getItems().size(), purchase.getId());

    for (PurchaseItem item : purchase.getItems()) {
      String stockLotId = PurchaseItemRefs.stockLotId(item);
      try {
        if ("menu".equalsIgnoreCase(item.getSellMode())) {
          continue;
        }
        if (!StringUtils.hasText(stockLotId)) {
          continue;
        }
        Inventory inventory = inventoryRepository.findById(stockLotId)
            .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId",
                "Inventory not found with lotId: " + stockLotId));

        if (!purchase.getShopId().equals(inventory.getShopId())) {
          throw new ValidationException("Inventory lot " + stockLotId +
              " does not belong to shop " + purchase.getShopId());
        }

        // Get current values (handle nulls)
        int currentCount = getCurrentBaseCount(inventory);
        int soldCount = getSoldBaseCount(inventory);
        BigDecimal currentDisplayCount = getCurrentDisplayCount(inventory);
        BigDecimal soldDisplayCount = getSoldDisplayCount(inventory);
        int baseQuantity = getBaseQuantityOrFallback(item);
        BigDecimal displayQuantity = toDisplayQuantity(baseQuantity, inventory);

        // Validate that we have enough stock
        if (currentCount < baseQuantity) {
          throw new InsufficientStockException(
              "Insufficient stock to complete purchase for product: " + inventory.getName() +
                  ". Available: " + currentCount + ", Required in base units: " + baseQuantity,
              inventory.getBarcode(), currentCount, baseQuantity);
        }

        // Update inventory counts
        inventory.setCurrentCount(currentDisplayCount.subtract(displayQuantity).setScale(4, RoundingMode.HALF_UP));
        inventory.setSoldCount(soldDisplayCount.add(displayQuantity).setScale(4, RoundingMode.HALF_UP));
        inventory.setCurrentBaseCount(currentCount - baseQuantity);
        inventory.setSoldBaseCount(soldCount + baseQuantity);

        // Save updated inventory
        inventoryRepository.save(inventory);
        Integer threshold = inventory.getThresholdCount() != null
          ? inventory.getThresholdCount()
          : 50;

        log.info(
          "Threshold check -> lotId={}, current={}, threshold={}",
          inventory.getId(),
          inventory.getCurrentBaseCount(),
          threshold
        );

        if (inventory.getCurrentBaseCount() <= threshold) {

          log.info("THRESHOLD REACHED — triggering INVENTORY_LOW event");

          InventoryEventDto dto =
            inventoryMapper.toInventoryLowEventDto(inventory, threshold);
          var eventDto = inventoryMapper.toNotificationEventDto(dto);
          eventService.recordAndBroadcastInventoryLow(eventDto);
        }

        log.info("Updated inventory for lotId: {} - decreased currentCount by {} (new: {}), increased soldCount by {} (new: {})",
            stockLotId, baseQuantity, inventory.getCurrentBaseCount(), baseQuantity, inventory.getSoldBaseCount());

      } catch (ResourceNotFoundException | ValidationException | InsufficientStockException e) {
        log.error("Error updating inventory for lotId: {} - {}", stockLotId, e.getMessage());
        throw e;
      } catch (Exception e) {
        log.error("Unexpected error updating inventory for lotId: {}", stockLotId, e);
        throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
            "Error updating inventory for lotId " + stockLotId + ": " + e.getMessage(), e);
      }
    }

    log.info("Successfully updated inventory for all items in purchase {}", purchase.getId());
  }

  /**
   * Uses {@code purchase.customerName} when set; otherwise loads the name from {@link Customer}
   * by {@code purchase.customerId}. Falls back to plain {@code Customer} (never show raw ids in
   * ledger labels).
   */
  private String resolveCustomerPartyDisplayNameForCredit(Purchase purchase) {
    if (purchase == null) {
      return "Customer";
    }
    if (StringUtils.hasText(purchase.getCustomerName())) {
      return purchase.getCustomerName().trim();
    }
    if (!StringUtils.hasText(purchase.getCustomerId())) {
      return "Customer";
    }
    String cid = purchase.getCustomerId().trim();
    return customerRepository
        .findById(cid)
        .map(Customer::getName)
        .filter(StringUtils::hasText)
        .map(String::trim)
        .orElse("Customer");
  }

  /**
   * Sale credit charge and accounting journal in one transaction; failures roll back both.
   */
  private String postCreditAndAccountingForCompletedSale(
      Purchase purchase,
      UpdatePurchaseStatusRequest request,
      String shopId,
      String userId) {
    String creditEntryId = postCreditChargeForCompletedSale(purchase, request, shopId, userId);
    postAccountingForSale(purchase, request, shopId, userId);
    return creditEntryId;
  }

  /**
   * Posts the canonical sale double-entry via {@link com.inventory.accounting.api.AccountingFacade}.
   * Idempotent on {@code sourceId == purchase.id}.
   */
  private void postAccountingForSale(
      Purchase purchase,
      UpdatePurchaseStatusRequest request,
      String shopId,
      String userId) {
    if (accountingFacade == null || purchase == null) {
      return;
    }
    BigDecimal saleTotal = nzMoney(purchase.getGrandTotal());
    if (saleTotal.signum() <= 0) {
      return;
    }

    BigDecimal revenue = nzMoney(purchase.getRevenueBeforeTax());
    if (revenue.signum() <= 0) {
      BigDecimal subTotal = nzMoney(purchase.getSubTotal());
      BigDecimal additional =
          nzMoney(purchase.getSaleAdditionalDiscountTotal());
      revenue = subTotal.subtract(additional).max(BigDecimal.ZERO);
    }

    BigDecimal cgst = nzMoney(purchase.getCgstAmount());
    BigDecimal sgst = nzMoney(purchase.getSgstAmount());
    // An interstate sale carries its tax under IGST alone, so the books have to say so too.
    // Posting it to the local heads would leave the ledger claiming a liability the return does
    // not declare, and the two have to agree at filing.
    BigDecimal igst = nzMoney(purchase.getIgstAmount());
    BigDecimal taxBeforeRound = revenue.add(cgst).add(sgst).add(igst);
    BigDecimal roundOff = saleTotal.subtract(taxBeforeRound).setScale(4, RoundingMode.HALF_UP);

    SalePaymentBreakdown payment =
        resolveSalePaymentBreakdown(saleTotal, request.getPaymentMethod(), request);

    java.time.LocalDate txnDate =
        purchase.getSoldAt() != null
            ? java.time.LocalDate.ofInstant(purchase.getSoldAt(), java.time.ZoneOffset.UTC)
            : (purchase.getUpdatedAt() != null
                ? java.time.LocalDate.ofInstant(purchase.getUpdatedAt(), java.time.ZoneOffset.UTC)
                : java.time.LocalDate.now());

    String customerId =
        StringUtils.hasText(purchase.getCustomerId()) ? purchase.getCustomerId().trim() : null;

    com.inventory.accounting.api.SaleInvoicePostingRequest req =
        com.inventory.accounting.api.SaleInvoicePostingRequest.builder()
            .sourceId(purchase.getId())
            .invoiceNo(purchase.getInvoiceNo())
            .txnDate(txnDate)
            .customerId(customerId)
            .customerDisplayName(resolveCustomerPartyDisplayNameForCredit(purchase))
            .taxableRevenue(revenue)
            .outputCgst(cgst)
            .outputSgst(sgst)
            .outputIgst(igst)
            .saleTotal(saleTotal)
            .paidCash(payment.cash())
            .paidOnline(payment.online())
            .receivableAmount(payment.receivable())
            .paymentMethod(payment.method())
            .cogsAmount(nzMoney(purchase.getTotalCost()))
            .roundOff(roundOff)
            .build();
    accountingFacade.postSale(shopId, userId, req);
  }

  private String postCreditChargeForCompletedSale(
      Purchase purchase,
      UpdatePurchaseStatusRequest request,
      String shopId,
      String userId) {
    if (purchase == null) {
      return null;
    }
    if (creditChargeFacade == null && creditService == null) {
      return null;
    }
    BigDecimal total = nzMoney(purchase.getGrandTotal());
    if (total.signum() <= 0) {
      return null;
    }

    SalePaymentBreakdown payment =
        resolveSalePaymentBreakdown(total, request.getPaymentMethod(), request);
    if (payment.receivable().signum() <= 0) {
      return null;
    }

    if (!StringUtils.hasText(purchase.getCustomerId())) {
      throw new ValidationException(
          "Customer must be selected for credit/split sale so due can be tracked.");
    }

    com.inventory.credit.rest.dto.request.CreateCreditChargeRequest charge =
        new com.inventory.credit.rest.dto.request.CreateCreditChargeRequest();
    charge.setPartyType(com.inventory.credit.domain.model.CreditPartyType.CUSTOMER);
    charge.setPartyId(purchase.getCustomerId().trim());
    charge.setPartyDisplayName(resolveCustomerPartyDisplayNameForCredit(purchase));
    charge.setPartyPhone(null);
    charge.setAmount(payment.receivable());
    charge.setReferenceType("SALE");
    charge.setReferenceId(purchase.getId());
    charge.setSourceKey("SALE:CREDIT:" + purchase.getId());
    charge.setNote(
        "Sale due"
            + (StringUtils.hasText(purchase.getInvoiceNo())
                ? " · Inv " + purchase.getInvoiceNo().trim()
                : ""));

    com.inventory.credit.domain.model.CreditEntry entry =
        creditChargeFacade != null
            ? creditChargeFacade.createCharge(shopId, userId, charge)
            : creditService.createCharge(shopId, userId, charge);
    return entry != null ? entry.getId() : null;
  }

  private static void applyPaymentSplitToPurchase(
      Purchase purchase, UpdatePurchaseStatusRequest request) {
    SalePaymentBreakdown payment =
        resolveSalePaymentBreakdown(
            nzMoney(purchase.getGrandTotal()), request.getPaymentMethod(), request);
    purchase.setCashAmount(payment.cash());
    purchase.setOnlineAmount(payment.online());
    purchase.setCreditAmount(payment.receivable());
  }

  /**
   * Resolves cash / online / credit legs for the six canonical checkout methods. Prefers explicit
   * {@code cashAmount}/{@code onlineAmount}/{@code creditAmount} from the client; falls back to
   * {@code creditPaidAmount} and method heuristics for older clients.
   */
  private static SalePaymentBreakdown resolveSalePaymentBreakdown(
      BigDecimal saleTotal,
      String rawMethod,
      UpdatePurchaseStatusRequest request) {
    String method = normalizePaymentMethod(rawMethod);
    boolean hasExplicitSplit =
        request.getCashAmount() != null
            || request.getOnlineAmount() != null
            || request.getCreditAmount() != null;
    if (hasExplicitSplit) {
      BigDecimal cash = capTender(nzMoney(request.getCashAmount()), saleTotal);
      BigDecimal online = capTender(nzMoney(request.getOnlineAmount()), saleTotal);
      BigDecimal credit = capTender(nzMoney(request.getCreditAmount()), saleTotal);
      BigDecimal sum = cash.add(online).add(credit);
      if (sum.compareTo(saleTotal) > 0) {
        throw new ValidationException(
            "Payment split (cash + online + credit) cannot exceed sale total");
      }
      BigDecimal receivable = credit;
      if (credit.signum() == 0 && sum.compareTo(saleTotal) < 0) {
        receivable = saleTotal.subtract(cash).subtract(online);
      }
      return new SalePaymentBreakdown(method, cash, online, receivable);
    }

    BigDecimal paidNow = resolveLegacyPaidNow(saleTotal, method, request.getCreditPaidAmount());
    if (paidNow.compareTo(saleTotal) > 0) {
      throw new ValidationException("Paid now amount cannot exceed grand total");
    }
    BigDecimal receivable = saleTotal.subtract(paidNow).setScale(4, RoundingMode.HALF_UP);
    return switch (method) {
      case "ONLINE", "UPI", "BANK", "CARD" -> new SalePaymentBreakdown(method, BigDecimal.ZERO, paidNow, receivable);
      case "CREDIT" -> new SalePaymentBreakdown(method, BigDecimal.ZERO, BigDecimal.ZERO, receivable);
      case "CASH_ONLINE" -> splitLegacyPaidEvenly(method, paidNow, receivable);
      case "ONLINE_CREDIT" -> new SalePaymentBreakdown(method, BigDecimal.ZERO, paidNow, receivable);
      case "CREDIT_CASH" -> new SalePaymentBreakdown(method, paidNow, BigDecimal.ZERO, receivable);
      default -> new SalePaymentBreakdown(method, paidNow, BigDecimal.ZERO, receivable);
    };
  }

  private static SalePaymentBreakdown splitLegacyPaidEvenly(
      String method, BigDecimal paidNow, BigDecimal receivable) {
    if (paidNow.signum() <= 0) {
      return new SalePaymentBreakdown(method, BigDecimal.ZERO, BigDecimal.ZERO, receivable);
    }
    BigDecimal half =
        paidNow.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    BigDecimal cash = half;
    BigDecimal online = paidNow.subtract(half).setScale(4, RoundingMode.HALF_UP);
    return new SalePaymentBreakdown(method, cash, online, receivable);
  }

  private static BigDecimal capTender(BigDecimal amount, BigDecimal saleTotal) {
    if (amount.compareTo(saleTotal) > 0) {
      return saleTotal;
    }
    return amount;
  }

  private static BigDecimal resolveLegacyPaidNow(
      BigDecimal total,
      String paymentMethod,
      BigDecimal requestedPaidNow) {
    if ("CREDIT".equals(paymentMethod)
        || "ONLINE_CREDIT".equals(paymentMethod)
        || "CREDIT_CASH".equals(paymentMethod)) {
      return nzMoney(requestedPaidNow);
    }
    if (requestedPaidNow != null && requestedPaidNow.signum() >= 0) {
      return requestedPaidNow.setScale(4, RoundingMode.HALF_UP);
    }
    return total;
  }

  private static String normalizePaymentMethod(String raw) {
    if (!StringUtils.hasText(raw)) {
      return "CASH";
    }
    return raw.trim().toUpperCase();
  }

  private static BigDecimal nzMoney(BigDecimal v) {
    return (v == null ? BigDecimal.ZERO : v).setScale(4, RoundingMode.HALF_UP);
  }

  private record SalePaymentBreakdown(
      String method, BigDecimal cash, BigDecimal online, BigDecimal receivable) {}

  /** Merge-only line for cafe menu decrement/remove (cart upsert adds this delta to existing qty). */
  private PurchaseItem buildMenuQuantityDeltaItem(SellableRef sellable, int deltaQty) {
    PurchaseItem item = new PurchaseItem();
    item.setSellableRef(sellable.encode());
    item.setSellMode("menu");
    item.setBaseQuantity(deltaQty);
    item.setQuantity(BigDecimal.valueOf(deltaQty));
    item.setUnitFactor(1);
    PurchaseItemRefs.normalize(item);
    return item;
  }

  /**
   * Puts a picked rate on the line. A rule rate's base, disc and scheme replace whatever the line
   * had -- the batch's sale defaults and any disc/scheme sent with the pick. A system pick (PTR,
   * MRP, cost) puts that batch field on the line; its disc and scheme go back to the batch's own
   * sale defaults only when the line was at a rule rate (see the cart merge), so a typed disc on
   * any other line survives. A legacy rate gives only its price.
   *
   * <p>A rate's figures are per pack (the batch's pricing unit). On a line sold by another unit
   * (a tablet line) the price goes on for that unit, rounded down as the per-unit MRP is, and the
   * line MRP becomes the MRP for that unit; disc and scheme apply unchanged.
   */
  private void applyPickedRateTerms(PurchaseItem line, CartRateResolver.LineTerms terms,
      Inventory inventory, String saleUnit) {
    if (terms.price() != null) {
      line.setPriceToRetail(perPackToSaleUnit(inventory, saleUnit, terms.price()));
      if (inventory.getMaximumRetailPrice() != null) {
        line.setMaximumRetailPrice(resolveMaximumRetailPriceForSaleUnit(inventory, saleUnit));
      }
      line.setBatchFilledPrice(false);
    }
    line.setAppliedRateName(terms.name());
    if (terms.isSystem()) {
      // Disc and scheme are left as the request and batch set them; the merge resets them to
      // the batch's sale defaults only when the line in the cart is at a rule rate.
      line.setSystemRatePicked(true);
      return;
    }
    if (!terms.replacesTerms()) {
      return;
    }
    line.setSaleAdditionalDiscount(terms.discountPercent());
    setRateScheme(line, terms);
    line.setRateTermsPicked(true);
  }

  /**
   * Puts the batch's default rule rate on a new line: its base (per sale unit, as a pick), and
   * its disc and scheme in place of the batch's sale defaults. Disc or scheme sent with the item
   * still win, as they do today, and then the line is not at the rate's terms, so it records no
   * applied rate. The price stays marked batch-filled: if the lot is already in the cart, the
   * merge keeps that line's own price and any disc/scheme the request did not send.
   */
  private void applyDefaultRateTerms(PurchaseItem line, CartRateResolver.LineTerms terms,
      AddToCartRequest.CartItem item, Inventory inventory, String saleUnit) {
    if (terms.price() != null) {
      line.setPriceToRetail(perPackToSaleUnit(inventory, saleUnit, terms.price()));
      if (inventory.getMaximumRetailPrice() != null) {
        line.setMaximumRetailPrice(resolveMaximumRetailPriceForSaleUnit(inventory, saleUnit));
      }
    }
    boolean discSent = item.getSaleAdditionalDiscount() != null;
    if (!discSent) {
      line.setSaleAdditionalDiscount(terms.discountPercent());
    }
    boolean schemeSent = item.getSchemePayFor() != null || item.getSchemeFree() != null
        || item.getSchemeType() != null || item.getSchemePercentage() != null;
    if (!schemeSent) {
      setRateScheme(line, terms);
    }
    line.setAppliedRateName(discSent || schemeSent ? null : terms.name());
  }

  /**
   * Whether an incoming line edits the disc or scheme of the line in the cart: it sent one (not
   * batch-filled, not a pick) that differs from the line's. ScanSell's + resends the line's own
   * disc and scheme, which is no edit.
   */
  private static boolean discOrSchemeEdited(PurchaseItem existing, PurchaseItem incoming) {
    if (StringUtils.hasText(incoming.getRequestedRateName())) {
      return false;
    }
    if (incoming.getSaleAdditionalDiscount() != null && !incoming.isBatchFilledDisc()
        && !sameMoney(incoming.getSaleAdditionalDiscount(), existing.getSaleAdditionalDiscount())) {
      return true;
    }
    if (incoming.isBatchFilledScheme()) {
      return false;
    }
    return (incoming.getSchemeType() != null && incoming.getSchemeType() != existing.getSchemeType())
        || (incoming.getSchemePayFor() != null && !incoming.getSchemePayFor().equals(existing.getSchemePayFor()))
        || (incoming.getSchemeFree() != null && !incoming.getSchemeFree().equals(existing.getSchemeFree()))
        || (incoming.getSchemePercentage() != null
            && !sameMoney(incoming.getSchemePercentage(), existing.getSchemePercentage()));
  }

  private static boolean sameMoney(BigDecimal a, BigDecimal b) {
    BigDecimal x = a != null ? a : BigDecimal.ZERO;
    BigDecimal y = b != null ? b : BigDecimal.ZERO;
    return x.compareTo(y) == 0;
  }

  private static void setRateScheme(PurchaseItem line, CartRateResolver.LineTerms terms) {
    line.setSchemeType(terms.schemeType() != null ? SchemeType.valueOf(terms.schemeType()) : null);
    line.setSchemePayFor(terms.payFor());
    line.setSchemeFree(terms.free());
    line.setSchemePercentage(terms.percentage());
  }

  private CartLineInput toCartLineInput(AddToCartRequest.CartItem item) {
    String sellableRef =
        PurchaseItemRefs.resolveSellableRefFromCartInput(
            item.getSellableRef(), item.getId(), item.getMenuItemId());
    return CartLineInput.builder()
        .sellableRef(sellableRef)
        .quantity(item.getQuantity())
        .baseQuantity(item.getBaseQuantity())
        .unit(item.getUnit())
        .priceToRetail(item.getPriceToRetail())
        .saleAdditionalDiscount(item.getSaleAdditionalDiscount())
        .schemeType(item.getSchemeType() != null ? item.getSchemeType().name() : null)
        .schemePayFor(item.getSchemePayFor())
        .schemeFree(item.getSchemeFree())
        .schemePercentage(item.getSchemePercentage())
        .build();
  }

  private static boolean sameCartLine(PurchaseItem a, PurchaseItem b) {
    return cartLineKey(a).equals(cartLineKey(b));
  }

  private static String cartLineKey(PurchaseItem item) {
    return PurchaseItemRefs.lineKey(item);
  }

  private void mergeMenuCartLine(List<PurchaseItem> mergedItems, PurchaseItem newItem) {
    for (int i = 0; i < mergedItems.size(); i++) {
      PurchaseItem existing = mergedItems.get(i);
      if (!sameCartLine(existing, newItem)) {
        continue;
      }
      int existingQty = existing.getBaseQuantity() != null ? existing.getBaseQuantity() : 0;
      int addQty = newItem.getBaseQuantity() != null ? newItem.getBaseQuantity() : 0;
      int combined = existingQty + addQty;
      if (combined <= 0) {
        mergedItems.remove(i);
        return;
      }
      existing.setBaseQuantity(combined);
      existing.setQuantity(BigDecimal.valueOf(combined));
      if (newItem.getTotalAmount() != null && existing.getPriceToRetail() != null) {
        existing.setTotalAmount(
            existing
                .getPriceToRetail()
                .multiply(BigDecimal.valueOf(combined))
                .setScale(2, RoundingMode.HALF_UP));
      }
      purchaseMapper.enrichPurchaseItemMargin(existing);
      mergedItems.set(i, existing);
      return;
    }
    if (newItem.getBaseQuantity() != null && newItem.getBaseQuantity() > 0) {
      mergedItems.add(newItem);
    }
  }

  private static final Set<String> CHECKOUT_VERTICALS = Set.of("grocery", "cafe", "sports", "medical");

  private String checkoutVertical(String shopId) {
    try {
      return shopRepository
          .findById(shopId)
          .map(Shop::getVerticalId)
          .filter(CHECKOUT_VERTICALS::contains)
          .orElse(null);
    } catch (Exception ignored) {
      return null;
    }
  }

}
