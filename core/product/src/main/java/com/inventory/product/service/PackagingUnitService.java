package com.inventory.product.service;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.PackagingUnitDefinition;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.model.enums.SellUnitRule;
import com.inventory.product.rest.dto.response.AvailableUnitDto;
import com.inventory.product.rest.dto.response.PackagingUnitDto;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class PackagingUnitService {

  public List<PackagingUnitDto> listAll() {
    return PackagingUnitCatalog.all().stream()
        .map(this::toDto)
        .collect(Collectors.toList());
  }

  public PackagingUnitDefinition resolveDefinition(String baseUnitUqc) {
    String normalized = normalizeUqc(baseUnitUqc);
    return PackagingUnitCatalog.find(normalized)
        .orElseGet(() -> legacyUnitDefinition(normalized));
  }

  /** Pre-UQC data (e.g. BASE UNIT, UNIT) — treat as fractional generic unit. */
  private PackagingUnitDefinition legacyUnitDefinition(String normalized) {
    return new PackagingUnitDefinition(
        normalized,
        normalized,
        "OTHER",
        SellUnitRule.FRACTIONAL_BASE,
        "PAC",
        true,
        "Legacy unit; prefer a GST UQC from the catalog.",
        "Sell by " + normalized + " or pack.");
  }

  public SellUnitRule resolveSellRule(String baseUnitUqc) {
    return resolveDefinition(baseUnitUqc).getSellUnitRule();
  }

  /**
   * Build pack conversion from base UQC + units per pack (registration).
   * Returns null when no pack or factor &lt;= 1 without pack UQC.
   */
  public UnitConversion buildUnitConversion(String baseUnitUqc, Integer unitsPerPack) {
    PackagingUnitDefinition def = resolveDefinition(baseUnitUqc);
    if (!def.isAllowsUnitsPerPack()) {
      return null;
    }
    if (unitsPerPack == null || unitsPerPack <= 0) {
      return null;
    }
    String packUqc = def.getDefaultPackUqc();
    if (!StringUtils.hasText(packUqc)) {
      packUqc = "PAC";
    }
    if (packUqc.equalsIgnoreCase(normalizeUqc(baseUnitUqc))) {
      return null;
    }
    return new UnitConversion(packUqc.trim().toUpperCase(), unitsPerPack);
  }

  public void validateBaseUnit(String baseUnit) {
    if (!StringUtils.hasText(baseUnit)) {
      throw new ValidationException("baseUnit (UQC) is required");
    }
    resolveDefinition(baseUnit);
  }

  public void validateUnitsPerPack(String baseUnitUqc, Integer unitsPerPack) {
    PackagingUnitDefinition def = resolveDefinition(baseUnitUqc);
    if (def.isAllowsUnitsPerPack()) {
      if (unitsPerPack == null || unitsPerPack <= 0) {
        throw new ValidationException(
            "unitsPerPack is required and must be > 0 for UQC " + def.getUqc()
                + " (" + def.getLabel() + ")");
      }
      return;
    }
    if (unitsPerPack != null && unitsPerPack > 0) {
      throw new ValidationException(
          "unitsPerPack is not used for UQC " + def.getUqc() + "; remove packaging factor");
    }
  }

  /**
   * Units offered at sale: PACK_ONLY → pack UQC only; FRACTIONAL → base + optional pack.
   */
  public List<AvailableUnitDto> mapAvailableUnitsForSale(
      String baseUnitUqc,
      UnitConversion unitConversions) {
    PackagingUnitDefinition def = resolveDefinition(baseUnitUqc);
    String base = normalizeUqc(baseUnitUqc);

    if (def.getSellUnitRule() == SellUnitRule.PACK_ONLY && hasPack(unitConversions)) {
      return List.of(new AvailableUnitDto(unitConversions.getUnit().trim().toUpperCase(), false));
    }

    List<AvailableUnitDto> units = new java.util.ArrayList<>();
    units.add(new AvailableUnitDto(base, true));
    if (hasPack(unitConversions)) {
      String pack = unitConversions.getUnit().trim().toUpperCase();
      if (!pack.equals(base)) {
        units.add(new AvailableUnitDto(pack, false));
      }
    }
    return units;
  }

  public void validateSaleQuantity(
      String baseUnitUqc,
      UnitConversion unitConversions,
      String saleUnit,
      int quantity,
      int baseQuantity) {
    if (quantity <= 0) {
      throw new ValidationException("Quantity must be greater than zero");
    }
    PackagingUnitDefinition def = resolveDefinition(baseUnitUqc);
    if (def.getSellUnitRule() != SellUnitRule.PACK_ONLY || !hasPack(unitConversions)) {
      return;
    }
    String packUqc = unitConversions.getUnit().trim().toUpperCase();
    String normalizedSale = saleUnit != null ? saleUnit.trim().toUpperCase() : packUqc;
    if (!packUqc.equals(normalizedSale)) {
      throw new ValidationException(
          def.getUqc() + " products must be sold in " + packUqc
              + " only (" + def.getSellHint() + ")");
    }
    int factor = unitConversions.getFactor();
    if (baseQuantity % factor != 0) {
      throw new ValidationException(
          "Quantity must be whole " + packUqc + " (each " + packUqc + " = "
              + factor + " " + def.getUqc() + ")");
    }
  }

  public Integer resolveUnitsPerPack(UnitConversion unitConversions) {
    if (!hasPack(unitConversions)) {
      return null;
    }
    return unitConversions.getFactor();
  }

  public String resolvePackUnitUqc(UnitConversion unitConversions) {
    if (!hasPack(unitConversions)) {
      return null;
    }
    return unitConversions.getUnit().trim().toUpperCase();
  }

  private boolean hasPack(UnitConversion unitConversions) {
    return unitConversions != null
        && StringUtils.hasText(unitConversions.getUnit())
        && unitConversions.getFactor() != null
        && unitConversions.getFactor() > 0;
  }

  private PackagingUnitDto toDto(PackagingUnitDefinition d) {
    PackagingUnitDto dto = new PackagingUnitDto();
    dto.setUqc(d.getUqc());
    dto.setLabel(d.getLabel());
    dto.setCategory(d.getCategory());
    dto.setSellUnitRule(d.getSellUnitRule());
    dto.setDefaultPackUqc(d.getDefaultPackUqc());
    dto.setAllowsUnitsPerPack(d.isAllowsUnitsPerPack());
    dto.setRegistrationHint(d.getRegistrationHint());
    dto.setSellHint(d.getSellHint());
    return dto;
  }

  public static String normalizeUqc(String uqc) {
    if (!StringUtils.hasText(uqc)) {
      return "UNT";
    }
    return uqc.trim().toUpperCase();
  }
}
