package com.zzzeinhom.contracts.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;
import com.zzzeinhom.contracts.domain.model.BillOfMaterialsItem;
import com.zzzeinhom.contracts.domain.model.ClientProfile;
import com.zzzeinhom.contracts.domain.model.ContractTerms;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import com.zzzeinhom.contracts.domain.model.FinancialCommitments;
import com.zzzeinhom.contracts.domain.model.Installment;
import com.zzzeinhom.contracts.persistence.entity.ExtractedDataEntity;
import com.zzzeinhom.contracts.persistence.repository.ExtractedDataRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContractExcelExportServiceTest {

    @Mock
    private ExtractedDataRepository extractedDataRepository;

    private ContractExcelExportService exportService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        exportService = new ContractExcelExportService(extractedDataRepository, objectMapper);
    }

    @Test
    void exportToExcel_producesValidStyledWorkbookWithClientBomTotalsAndWarnings() throws Exception {
        ExtractionResult payload = new ExtractionResult(
                "quo_abc123",
                ExtractionStatus.NEEDS_REVIEW,
                new ClientProfile("Eng. Tariq", "+201009998877", "Sheikh Zayed", LocalDate.of(2026, 11, 24)),
                new FinancialCommitments(
                        new BigDecimal("6000.00"),
                        BigDecimal.ZERO,
                        true,
                        new BigDecimal("840.00"),
                        new BigDecimal("6840.00"),
                        "EGP",
                        true,
                        List.of(
                                new Installment("Down Payment (50%)", new BigDecimal("3420.00"), "Upon contract signing"),
                                new Installment("Delivery (50%)", new BigDecimal("3420.00"), "Upon commissioning"))),
                new ContractTerms(
                        "SUPPLY_AND_INSTALLATION",
                        "2 years",
                        "14 days",
                        true,
                        List.of("Civil chiseling and conduits excluded")),
                List.of(
                        new BillOfMaterialsItem(
                                DeviceCategory.SMART_LIGHTING,
                                "Tuya 3-Gang",
                                "Tuya 3-Gang Switch",
                                CommunicationProtocol.ZIGBEE_WIRELESS,
                                WiringRequirement.WITH_NEUTRAL,
                                4,
                                new BigDecimal("1500.00"),
                                new BigDecimal("6000.00"),
                                "Reception",
                                UUID.randomUUID(),
                                0.98,
                                new BigDecimal("1500.00")),
                        new BillOfMaterialsItem(
                                DeviceCategory.SENSORS_SAFETY,
                                "Custom Rare Sensor",
                                "Custom Rare Sensor",
                                CommunicationProtocol.OTHER,
                                WiringRequirement.WIRELESS_BATTERY,
                                1,
                                null,
                                null,
                                "Garden gate",
                                null,
                                null,
                                null)),
                List.of("Uncatalogued Item: 'Custom Rare Sensor' requires manual pricing."),
                List.of());

        ExtractedDataEntity entity = new ExtractedDataEntity("quo_abc123", "smart-power", payload);
        when(extractedDataRepository.findByExtractionIdAndTenantId("quo_abc123", "smart-power"))
                .thenReturn(Optional.of(entity));

        byte[] xlsxBytes = exportService.exportToExcel("Smart-Power", "quo_abc123");
        assertThat(xlsxBytes).isNotEmpty();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            Sheet sheet = workbook.getSheet(ContractExcelExportService.SHEET_NAME);
            assertThat(sheet).isNotNull();

            List<String> allCells = extractAllCellText(sheet);
            assertThat(allCells).contains(
                    "Smart Home Contract & Quotation BoM",
                    "quo_abc123",
                    "DRAFT - NEEDS REVIEW",
                    "Eng. Tariq",
                    "Sheikh Zayed",
                    "Tuya 3-Gang Switch",
                    "Custom Rare Sensor",
                    "TBD",
                    "Down Payment (50%)",
                    "SUPPLY_AND_INSTALLATION",
                    "Civil chiseling and conduits excluded",
                    "Uncatalogued Item: 'Custom Rare Sensor' requires manual pricing.");
            assertThat(allCells).anyMatch(val -> val.contains("6840"));
            assertThat(allCells).anyMatch(val -> val.contains("1500"));
        }
    }

    @Test
    void exportToExcel_throwsNoSuchElementExceptionForDifferentTenantId() {
        when(extractedDataRepository.findByExtractionIdAndTenantId("quo_abc123", "other-tenant"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> exportService.exportToExcel("other-tenant", "quo_abc123"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("quo_abc123");
    }

    private static List<String> extractAllCellText(Sheet sheet) {
        List<String> values = new ArrayList<>();
        for (Row row : sheet) {
            for (Cell cell : row) {
                values.add(cell.toString());
            }
        }
        return values;
    }
}
