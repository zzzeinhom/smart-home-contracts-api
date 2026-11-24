package com.zzzeinhom.contracts.service;

import com.zzzeinhom.contracts.ai.embedding.CatalogEmbeddingService;
import com.zzzeinhom.contracts.ai.quotation.CatalogCandidate;
import com.zzzeinhom.contracts.ai.quotation.GroundedRequirement;
import com.zzzeinhom.contracts.ai.quotation.QuotationGeneratorAgent;
import com.zzzeinhom.contracts.ai.quotation.SurveyDecomposition;
import com.zzzeinhom.contracts.ai.quotation.SurveyRequirementItem;
import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import com.zzzeinhom.contracts.domain.model.BillOfMaterialsItem;
import com.zzzeinhom.contracts.domain.model.ClarificationQuestion;
import com.zzzeinhom.contracts.domain.model.ClientProfile;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import com.zzzeinhom.contracts.domain.pricing.QuotationPricingCalculator;
import com.zzzeinhom.contracts.domain.validation.FinancialGuardrailValidator;
import com.zzzeinhom.contracts.dto.QuotationGenerateRequest;
import com.zzzeinhom.contracts.persistence.entity.ContractEntity;
import com.zzzeinhom.contracts.persistence.entity.ExtractedDataEntity;
import com.zzzeinhom.contracts.persistence.repository.ContractRepository;
import com.zzzeinhom.contracts.persistence.repository.DeviceCatalogVectorRepository;
import com.zzzeinhom.contracts.persistence.repository.ExtractedDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class QuotationGenerationService {

    private static final Logger log = LoggerFactory.getLogger(QuotationGenerationService.class);
    private static final int CANDIDATES_PER_REQUIREMENT = 3;

    private final QuotationGeneratorAgent quotationGeneratorAgent;
    private final CatalogEmbeddingService embeddingService;
    private final DeviceCatalogVectorRepository vectorRepository;
    private final ContractRepository contractRepository;
    private final ExtractedDataRepository extractedDataRepository;
    private final JobStatusCacheService jobStatusCacheService;
    private final TransactionTemplate transactionTemplate;
    private final double similarityThreshold;
    private final QuotationPricingCalculator pricingCalculator;

    public QuotationGenerationService(
            QuotationGeneratorAgent quotationGeneratorAgent,
            CatalogEmbeddingService embeddingService,
            DeviceCatalogVectorRepository vectorRepository,
            ContractRepository contractRepository,
            ExtractedDataRepository extractedDataRepository,
            JobStatusCacheService jobStatusCacheService,
            TransactionTemplate transactionTemplate,
            @Value("${app.catalog.similarity-threshold:0.80}") double similarityThreshold) {
        this.quotationGeneratorAgent = quotationGeneratorAgent;
        this.embeddingService = embeddingService;
        this.vectorRepository = vectorRepository;
        this.contractRepository = contractRepository;
        this.extractedDataRepository = extractedDataRepository;
        this.jobStatusCacheService = jobStatusCacheService;
        this.transactionTemplate = transactionTemplate;
        this.similarityThreshold = similarityThreshold;
        this.pricingCalculator = new QuotationPricingCalculator();
    }

    public ExtractionResult generateAndPersistQuotation(String tenantId, QuotationGenerateRequest request) {
        String enrichedSurveyText = buildEnrichedSurveyText(request);

        // 1. Decompose raw Arabic/English survey notes into atomic smart home requirements (outside DB transaction)
        SurveyDecomposition decomposition = quotationGeneratorAgent.decomposeSurvey(enrichedSurveyText);
        List<SurveyRequirementItem> requirements = (decomposition != null && decomposition.requirements() != null)
                ? decomposition.requirements()
                : List.of();

        // 2. Multi-Query PGVector Retrieval for each atomic requirement (outside DB transaction)
        Map<UUID, CatalogCandidate> retrievedCandidates = new HashMap<>();
        List<GroundedRequirement> groundedRequirements = new ArrayList<>(requirements.size());
        for (SurveyRequirementItem req : requirements) {
            List<CatalogCandidate> candidates = retrieveCandidates(tenantId, req);
            for (CatalogCandidate candidate : candidates) {
                retrievedCandidates.putIfAbsent(candidate.id(), candidate);
            }
            groundedRequirements.add(new GroundedRequirement(req, candidates));
        }

        // 3. Assemble Grounded BoM against retrieved catalog candidates (outside DB transaction)
        List<BillOfMaterialsItem> assembledBom = quotationGeneratorAgent.assembleGroundedBom(groundedRequirements);
        List<BillOfMaterialsItem> verifiedBom = enforceRetrievedCatalogIntegrity(assembledBom, retrievedCandidates);

        // 4. Merge ClientProfile overrides + apply deterministic pricing & financial guardrail checks
        String extractionId = "quo_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ClientProfile mergedProfile = mergeClientProfile(
                decomposition != null ? decomposition.clientProfile() : null, request);
        List<ClarificationQuestion> boundedQuestions = (decomposition != null
                && decomposition.clarificationQuestions() != null)
                ? decomposition.clarificationQuestions().stream().limit(3).toList()
                : List.of();

        ExtractionResult draft = new ExtractionResult(
                extractionId,
                ExtractionStatus.NEEDS_REVIEW,
                mergedProfile,
                null,
                decomposition != null ? decomposition.contractTerms() : null,
                verifiedBom,
                List.of(),
                boundedQuestions
        );

        boolean includeVat = request.includeVat() == null || request.includeVat();
        QuotationPricingCalculator.PricingOptions pricingOptions = new QuotationPricingCalculator.PricingOptions(
                includeVat,
                request.discountAmount(),
                request.installationPercentage(),
                request.paymentSplitPercentages()
        );

        ExtractionResult priced = pricingCalculator.applyStrictCatalogPricing(draft, pricingOptions);
        ExtractionResult finalResult = FinancialGuardrailValidator.validateAndFlag(priced);

        // 5. Persist inside short transaction boundary after all external AI calls complete
        transactionTemplate.executeWithoutResult(status -> {
            ContractEntity contract = ContractEntity.generatedQuotation(
                    extractionId, tenantId, "site-survey-quotation.txt", enrichedSurveyText);
            contractRepository.saveAndFlush(contract);
            extractedDataRepository.save(new ExtractedDataEntity(extractionId, tenantId, finalResult));
        });

        jobStatusCacheService.putStatus(tenantId, extractionId, ExtractionStatus.NEEDS_REVIEW, null);
        return finalResult;
    }

    private List<CatalogCandidate> retrieveCandidates(String tenantId, SurveyRequirementItem req) {
        if (req == null || req.requirementQuery() == null || req.requirementQuery().isBlank()) {
            return List.of();
        }
        String query = req.requirementQuery().trim()
                + " | " + req.category()
                + " | " + req.wiringRequirement();
        try {
            float[] vector = embeddingService.embed(query);
            String pgVectorLiteral = CatalogEmbeddingService.toPgVectorLiteral(vector);
            return vectorRepository.findTopMatches(tenantId, pgVectorLiteral, CANDIDATES_PER_REQUIREMENT).stream()
                    .filter(match -> match.similarity() >= similarityThreshold)
                    .map(match -> new CatalogCandidate(
                            match.id(),
                            match.normalizedName(),
                            match.brand(),
                            match.modelCode(),
                            match.category(),
                            match.protocol(),
                            match.wiringRequirement(),
                            match.referenceUnitPrice(),
                            Math.round(match.similarity() * 10000.0) / 10000.0
                    ))
                    .toList();
        } catch (Exception ex) {
            log.warn("Multi-query catalog retrieval failed for tenant={} requirement='{}': {}",
                    tenantId, req.requirementQuery(), ex.getMessage());
            return List.of();
        }
    }

    private static List<BillOfMaterialsItem> enforceRetrievedCatalogIntegrity(
            List<BillOfMaterialsItem> assembledBom,
            Map<UUID, CatalogCandidate> retrievedCandidates) {
        if (assembledBom == null || assembledBom.isEmpty()) {
            return List.of();
        }
        List<BillOfMaterialsItem> verified = new ArrayList<>(assembledBom.size());
        for (BillOfMaterialsItem item : assembledBom) {
            if (item == null) {
                continue;
            }
            int qty = item.quantity() > 0 ? item.quantity() : 1;
            CatalogCandidate candidate = item.matchedCatalogId() != null
                    ? retrievedCandidates.get(item.matchedCatalogId())
                    : null;

            if (candidate != null) {
                verified.add(new BillOfMaterialsItem(
                        item.category(),
                        item.rawName(),
                        candidate.normalizedName(),
                        item.protocol() != null ? item.protocol() : candidate.protocol(),
                        item.wiringRequirement() != null ? item.wiringRequirement() : candidate.wiringRequirement(),
                        qty,
                        null,
                        null,
                        item.notes(),
                        candidate.id(),
                        candidate.similarity(),
                        candidate.referenceUnitPrice()
                ));
            } else {
                verified.add(new BillOfMaterialsItem(
                        item.category(),
                        item.rawName(),
                        item.normalizedDevice(),
                        item.protocol(),
                        item.wiringRequirement(),
                        qty,
                        null,
                        null,
                        item.notes(),
                        null,
                        null,
                        null
                ));
            }
        }
        return verified;
    }

    private static ClientProfile mergeClientProfile(ClientProfile extracted, QuotationGenerateRequest request) {
        String name = firstNonBlank(request.clientName(), extracted != null ? extracted.clientName() : null);
        String phone = firstNonBlank(request.phone(), extracted != null ? extracted.phone() : null);
        String location = firstNonBlank(
                request.projectLocation(), extracted != null ? extracted.projectLocation() : null);
        LocalDate date = (extracted != null && extracted.contractDate() != null)
                ? extracted.contractDate()
                : LocalDate.now();
        return new ClientProfile(name, phone, location, date);
    }

    private static String buildEnrichedSurveyText(QuotationGenerateRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request.clientName() != null && !request.clientName().isBlank()) {
            sb.append("Client Name: ").append(request.clientName().trim()).append('\n');
        }
        if (request.phone() != null && !request.phone().isBlank()) {
            sb.append("Phone: ").append(request.phone().trim()).append('\n');
        }
        if (request.projectLocation() != null && !request.projectLocation().isBlank()) {
            sb.append("Project Location: ").append(request.projectLocation().trim()).append('\n');
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(request.siteSurveyNotes().trim());
        return sb.toString();
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return (fallback != null && !fallback.isBlank()) ? fallback.trim() : null;
    }
}
