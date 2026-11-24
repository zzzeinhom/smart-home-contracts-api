package com.zzzeinhom.contracts.domain.pricing;

import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;
import com.zzzeinhom.contracts.domain.model.BillOfMaterialsItem;
import com.zzzeinhom.contracts.domain.model.ClientProfile;
import com.zzzeinhom.contracts.domain.model.ContractTerms;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import com.zzzeinhom.contracts.domain.model.Installment;
import com.zzzeinhom.contracts.domain.validation.FinancialGuardrailValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotationPricingCalculatorTest {

    private final QuotationPricingCalculator calculator = new QuotationPricingCalculator();

    @Test
    void applyStrictCatalogPricing_enforcesCatalogPricesAddsInstallDiscountVatAndBalancedInstallments() {
        UUID relayId = UUID.randomUUID();
        UUID hubId = UUID.randomUUID();

        // LLM tried to put bogus unitPrice/lineTotal; calculator must overwrite strictly with catalogReferencePrice
        List<BillOfMaterialsItem> draftBom = List.of(
                new BillOfMaterialsItem(
                        DeviceCategory.SMART_LIGHTING,
                        "Sonoff ZBMINI-L2 Switch",
                        "Sonoff ZBMINI-L2 No-Neutral Relay",
                        CommunicationProtocol.ZIGBEE_WIRELESS,
                        WiringRequirement.NO_NEUTRAL,
                        4,
                        new BigDecimal("9999.00"),
                        new BigDecimal("9999.00"),
                        "Living room lights",
                        relayId,
                        0.95,
                        new BigDecimal("1000.00")
                ),
                new BillOfMaterialsItem(
                        DeviceCategory.CONTROLLERS_HUBS,
                        "Sonoff Zigbee Bridge",
                        "Sonoff ZBBridge-P Gateway",
                        CommunicationProtocol.ZIGBEE_WIRELESS,
                        WiringRequirement.WITH_NEUTRAL,
                        1,
                        new BigDecimal("500.00"),
                        new BigDecimal("500.00"),
                        "Main hub",
                        hubId,
                        0.92,
                        new BigDecimal("2000.00")
                )
        );

        ExtractionResult draft = new ExtractionResult(
                "quo_test123456",
                ExtractionStatus.NEEDS_REVIEW,
                new ClientProfile("Khaled Ali", "+201012345678", "Sheikh Zayed", LocalDate.of(2026, 11, 23)),
                null,
                new ContractTerms("SUPPLY_AND_INSTALLATION", "1 year", "7 days", false, List.of()),
                draftBom,
                List.of(),
                List.of()
        );

        // Hardware = (4 * 1000.00) + (1 * 2000.00) = 6000.00
        // Install (15%) = 900.00 => Subtotal = 6900.00
        // Discount = 400.00 => Taxable = 6500.00
        // 14% VAT = 910.00 => Total = 7410.00
        // Splits [33, 33, 34] on 7410.00 => 2445.30 + 2445.30 + 2519.40 = 7410.00
        QuotationPricingCalculator.PricingOptions options = new QuotationPricingCalculator.PricingOptions(
                true,
                new BigDecimal("400.00"),
                new BigDecimal("15"),
                List.of(33, 33, 34)
        );

        ExtractionResult priced = calculator.applyStrictCatalogPricing(draft, options);
        ExtractionResult validated = FinancialGuardrailValidator.validateAndFlag(priced);

        assertNotNull(validated);
        assertEquals(3, validated.billOfMaterials().size());
        assertEquals(new BigDecimal("1000.00"), validated.billOfMaterials().get(0).unitPrice());
        assertEquals(new BigDecimal("4000.00"), validated.billOfMaterials().get(0).lineTotal());
        assertEquals(new BigDecimal("2000.00"), validated.billOfMaterials().get(1).unitPrice());
        assertEquals(new BigDecimal("2000.00"), validated.billOfMaterials().get(1).lineTotal());

        BillOfMaterialsItem installItem = validated.billOfMaterials().get(2);
        assertEquals(DeviceCategory.OTHER, installItem.category());
        assertEquals("Installation, Testing & Commissioning", installItem.normalizedDevice());
        assertEquals(new BigDecimal("900.00"), installItem.lineTotal());
        assertTrue(installItem.rawName().contains("15%"));

        assertEquals(new BigDecimal("6900.00"), validated.financialCommitments().subtotal());
        assertEquals(new BigDecimal("400.00"), validated.financialCommitments().discountAmount());
        assertEquals(Boolean.TRUE, validated.financialCommitments().isVatIncluded());
        assertEquals(new BigDecimal("910.00"), validated.financialCommitments().vatAmount());
        assertEquals(new BigDecimal("7410.00"), validated.financialCommitments().totalAmount());
        assertEquals("EGP", validated.financialCommitments().currency());
        assertTrue(validated.financialCommitments().isMathVerified());

        BigDecimal installmentSum = validated.financialCommitments().installments().stream()
                .map(Installment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("7410.00").compareTo(installmentSum));
    }

    @Test
    void applyStrictCatalogPricing_stripsHallucinatedPricesOnUncataloguedItemsAndAddsWarning() {
        List<BillOfMaterialsItem> draftBom = List.of(
                new BillOfMaterialsItem(
                        DeviceCategory.ACCESS_CONTROL,
                        "Aqara U100 Smart Lock",
                        "Smart Door Lock",
                        CommunicationProtocol.ZIGBEE_WIRELESS,
                        WiringRequirement.WIRELESS_BATTERY,
                        2,
                        new BigDecimal("15000.00"),
                        new BigDecimal("30000.00"),
                        "Main villa door",
                        null,
                        null,
                        new BigDecimal("14000.00")
                )
        );

        ExtractionResult draft = new ExtractionResult(
                "quo_uncat",
                ExtractionStatus.NEEDS_REVIEW,
                null,
                null,
                null,
                draftBom,
                List.of("Existing note"),
                List.of()
        );

        ExtractionResult priced = calculator.applyStrictCatalogPricing(
                draft, new QuotationPricingCalculator.PricingOptions(false, null, null, null));

        assertNotNull(priced);
        BillOfMaterialsItem uncatalogued = priced.billOfMaterials().get(0);
        assertNull(uncatalogued.matchedCatalogId());
        assertNull(uncatalogued.unitPrice());
        assertNull(uncatalogued.lineTotal());
        assertEquals(new BigDecimal("0.00"), priced.financialCommitments().subtotal());
        assertNull(priced.financialCommitments().vatAmount());
        assertEquals(new BigDecimal("0.00"), priced.financialCommitments().totalAmount());
        assertTrue(priced.warnings().contains("Existing note"));
        assertTrue(priced.warnings().stream().anyMatch(w ->
                w.startsWith("Uncatalogued Item:") && w.contains("Aqara U100 Smart Lock")));
    }
}
