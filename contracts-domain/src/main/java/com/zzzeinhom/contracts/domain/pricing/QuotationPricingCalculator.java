package com.zzzeinhom.contracts.domain.pricing;

import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;
import com.zzzeinhom.contracts.domain.model.BillOfMaterialsItem;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import com.zzzeinhom.contracts.domain.model.FinancialCommitments;
import com.zzzeinhom.contracts.domain.model.Installment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class QuotationPricingCalculator {

    private static final BigDecimal EGYPTIAN_VAT_RATE = new BigDecimal("0.14");
    private static final String UNCATEGORIZED_PREFIX = "Uncatalogued Item:";

    public record PricingOptions(
            boolean includeVat,
            BigDecimal discountAmount,
            BigDecimal installationPercentage,
            List<Integer> paymentSplitPercentages
    ) {
        public PricingOptions {
            if (paymentSplitPercentages == null || paymentSplitPercentages.isEmpty()) {
                paymentSplitPercentages = List.of(50, 30, 20);
            } else {
                int sum = 0;
                for (Integer pct : paymentSplitPercentages) {
                    if (pct == null || pct <= 0) {
                        throw new IllegalArgumentException("All payment split percentages must be positive integers.");
                    }
                    sum += pct;
                }
                if (sum != 100) {
                    throw new IllegalArgumentException("Payment split percentages must sum to 100, got: " + sum);
                }
                paymentSplitPercentages = List.copyOf(paymentSplitPercentages);
            }
        }
    }

    public ExtractionResult applyStrictCatalogPricing(ExtractionResult draft, PricingOptions options) {
        if (draft == null) {
            return null;
        }
        PricingOptions effectiveOptions = options != null
                ? options
                : new PricingOptions(true, null, null, null);

        List<String> updatedWarnings = draft.warnings() == null
                ? new ArrayList<>()
                : draft.warnings().stream()
                        .filter(w -> w != null && !w.startsWith(UNCATEGORIZED_PREFIX))
                        .collect(Collectors.toCollection(ArrayList::new));

        List<BillOfMaterialsItem> sourceBom = draft.billOfMaterials() != null
                ? draft.billOfMaterials()
                : List.of();
        List<BillOfMaterialsItem> pricedBom = new ArrayList<>(sourceBom.size() + 1);
        BigDecimal hardwareSubtotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        for (BillOfMaterialsItem item : sourceBom) {
            if (item == null) {
                continue;
            }
            int qty = item.quantity() > 0 ? item.quantity() : 1;
            if (item.matchedCatalogId() != null && item.catalogReferencePrice() != null) {
                BigDecimal unitPrice = item.catalogReferencePrice().setScale(2, RoundingMode.HALF_UP);
                BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
                hardwareSubtotal = hardwareSubtotal.add(lineTotal);

                pricedBom.add(new BillOfMaterialsItem(
                        item.category(),
                        item.rawName(),
                        item.normalizedDevice(),
                        item.protocol(),
                        item.wiringRequirement(),
                        qty,
                        unitPrice,
                        lineTotal,
                        item.notes(),
                        item.matchedCatalogId(),
                        item.matchConfidence(),
                        unitPrice
                ));
            } else {
                if (item.matchedCatalogId() == null) {
                    updatedWarnings.add(String.format(
                            "%s '%s' was not found in the tenant device catalog and requires manual pricing.",
                            UNCATEGORIZED_PREFIX, item.rawName()));
                } else {
                    updatedWarnings.add(String.format(
                            "%s '%s' matched catalog entry %s without a reference price and requires manual pricing.",
                            UNCATEGORIZED_PREFIX, item.rawName(), item.matchedCatalogId()));
                }

                pricedBom.add(new BillOfMaterialsItem(
                        item.category(),
                        item.rawName(),
                        item.normalizedDevice(),
                        item.protocol(),
                        item.wiringRequirement(),
                        qty,
                        null,
                        null,
                        item.notes(),
                        item.matchedCatalogId(),
                        item.matchConfidence(),
                        item.catalogReferencePrice()
                ));
            }
        }

        if (effectiveOptions.installationPercentage() != null
                && effectiveOptions.installationPercentage().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal installPercent = effectiveOptions.installationPercentage();
            BigDecimal installFee = hardwareSubtotal
                    .multiply(installPercent)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            pricedBom.add(new BillOfMaterialsItem(
                    DeviceCategory.OTHER,
                    "أعمال التركيب والبرمجة والتشغيل (" + installPercent.stripTrailingZeros().toPlainString() + "%)",
                    "Installation, Testing & Commissioning",
                    CommunicationProtocol.NOT_APPLICABLE,
                    WiringRequirement.NOT_APPLICABLE,
                    1,
                    installFee,
                    installFee,
                    null,
                    null,
                    null,
                    null
            ));
        }

        BigDecimal subtotal = pricedBom.stream()
                .map(BillOfMaterialsItem::lineTotal)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal discountAmount = (effectiveOptions.discountAmount() != null
                && effectiveOptions.discountAmount().compareTo(BigDecimal.ZERO) > 0)
                ? effectiveOptions.discountAmount().min(subtotal).setScale(2, RoundingMode.HALF_UP)
                : null;

        BigDecimal taxableBase = subtotal.subtract(discountAmount != null ? discountAmount : BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal vatAmount = effectiveOptions.includeVat()
                ? taxableBase.multiply(EGYPTIAN_VAT_RATE).setScale(2, RoundingMode.HALF_UP)
                : null;

        BigDecimal totalAmount = (vatAmount != null ? taxableBase.add(vatAmount) : taxableBase)
                .setScale(2, RoundingMode.HALF_UP);

        List<Installment> installments = buildInstallments(totalAmount, effectiveOptions.paymentSplitPercentages());

        FinancialCommitments commitments = new FinancialCommitments(
                subtotal,
                discountAmount,
                effectiveOptions.includeVat(),
                vatAmount,
                totalAmount,
                "EGP",
                true,
                installments
        );

        return new ExtractionResult(
                draft.extractionId(),
                draft.status(),
                draft.clientProfile(),
                commitments,
                draft.contractTerms(),
                List.copyOf(pricedBom),
                List.copyOf(updatedWarnings),
                draft.clarificationQuestions()
        );
    }

    private static List<Installment> buildInstallments(BigDecimal totalAmount, List<Integer> splits) {
        List<Installment> installments = new ArrayList<>(splits.size());
        BigDecimal allocated = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        for (int i = 0; i < splits.size(); i++) {
            int pct = splits.get(i);
            boolean isLast = (i == splits.size() - 1);
            BigDecimal amount = isLast
                    ? totalAmount.subtract(allocated).setScale(2, RoundingMode.HALF_UP)
                    : totalAmount.multiply(BigDecimal.valueOf(pct))
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            allocated = allocated.add(amount);

            installments.add(new Installment(
                    resolveStageLabel(i, splits.size(), pct),
                    amount,
                    resolveDueCondition(i, splits.size())
            ));
        }
        return List.copyOf(installments);
    }

    private static String resolveStageLabel(int index, int count, int pct) {
        if (count == 3) {
            return switch (index) {
                case 0 -> "دفعة تعاقد (" + pct + "%)";
                case 1 -> "دفعة توريد (" + pct + "%)";
                default -> "دفعة تسليم (" + pct + "%)";
            };
        }
        if (index == 0) {
            return "دفعة تعاقد (" + pct + "%)";
        }
        if (index == count - 1) {
            return "دفعة تسليم (" + pct + "%)";
        }
        return "دفعة مرحلية " + (index + 1) + " (" + pct + "%)";
    }

    private static String resolveDueCondition(int index, int count) {
        if (index == 0) {
            return "عند التوقيع";
        }
        if (index == count - 1) {
            return "عند التسليم والتشغيل";
        }
        if (count == 3 && index == 1) {
            return "عند التوريد";
        }
        return "حسب التقدم التنفيذي";
    }
}
