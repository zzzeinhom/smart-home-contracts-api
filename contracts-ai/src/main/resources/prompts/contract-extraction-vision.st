Inspect all attached pages/images of the Egyptian smart home contract or technical quotation carefully (including mixed Arabic/English text, RTL tables, company stamps, and handwritten margin notes), and extract structured data.

Strictly follow these extraction, market taxonomy, and engineering audit rules:

1. Language Preservation:
   - Keep extracted free-text values (clientName, projectLocation, stage, dueCondition, rawName, warrantyPeriod, quoteValidity, exclusions, notes) in their original language as written on the pages. Do NOT translate Arabic values into English.

2. Egyptian Smart Home Market Taxonomy & Device Mapping:
   - Tier 1 (Economic / Retrofitted Apartments — Wi-Fi / No-Hub): Brands like Sonoff (e.g., Mini R4M hidden inside Magic wall boxes / علبة ماجيك), Tuya Wi-Fi, and local brands like Cordless. Pay close attention to wiringRequirement: distinguish WITH_NEUTRAL vs. NO_NEUTRAL (or capacitor-assisted modules).
   - Tier 2 (Mid-to-High Tier / Villas — Zigbee / Matter / Hybrid): Brands like Aqara, Sonoff Zigbee, Tuya Zigbee, and In-Wall Control Panels (e.g., AVATTO 5" / 8", Android voice-enabled wall panels).
   - Tier 3 (Luxury Villas & Large Projects — Wired Bus): KNX systems (Actuators, Dimming Actuators, Shutter Actuators, KNX Power Supplies) and Legrand -> map protocol to KNX and wiringRequirement to DIN_RAIL where applicable.
   - Specialized Categories:
     * CLIMATE_HVAC: AC IR Blasters (BroadLink RM4C Pro, Tuya IR) for split ACs, or Smart Thermostats for Central/VRF HVAC -> use protocol IR_RF for IR blasters.
     * ACCESS_CONTROL: Smart fingerprint/video door locks (Eufy, Aqara, Cordless), Smart Video Intercoms, and sliding/swing gate motors.
     * ENERGY_POWER: Smart circuit breakers, DIN-Rail protection/relay modules, and energy consumption sensors.
   - Read RTL table columns carefully without swapping quantity, unitPrice, and lineTotal. Split compound lines into distinct BoM items (e.g., curtain motors + separate smart curtain touch switches become two separate items). Default missing quantities to 1.

3. Financial Commitments & Guardrail Separation:
   - Extract subtotal, discountAmount, isVatIncluded (14% Egyptian VAT), vatAmount, totalAmount, currency (default EGP), and all installments.
   - Extract per-item unitPrice and lineTotal if present in a pricing table; otherwise leave them null.
   - Do NOT sum installments or line items to verify totals (deterministic Java code performs all arithmetic checks).
   - Only add financial items to 'warnings' for semantic/contractual risks (e.g., 14% VAT explicitly excluded from quoted prices, percentage vs. fixed amount discrepancy in an installment line, USD/EGP exchange rate fluctuation clauses, or vague payment milestones).

4. Contract Commercial Terms ('contractTerms'):
   - Classify scopeType (SUPPLY_ONLY, SUPPLY_AND_INSTALLATION, PROGRAMMING_AND_COMMISSIONING, or UNSPECIFIED).
   - Extract warrantyPeriod (e.g., 1-year manufacturing defect warranty), quoteValidity (e.g., 7 or 10 days), priceLinkedToExchangeRate (boolean), and all exclusions (e.g., civil chiseling/تكسير, conduits/خراطيم, cables, network racks).

5. Smart Home Engineering Audit ('clarificationQuestions' vs 'warnings'):
   - Generate at most 3 items in 'clarificationQuestions' (in the contract's primary language) ONLY for critical site/engineering blockers requiring the engineer's confirmation:
     * Smart switches/relays present with UNSPECIFIED wiringRequirement (ask whether site electrical boxes have a Neutral wire or require No-Neutral/capacitor units).
     * Zigbee devices present without a Zigbee Hub/Gateway, or KNX actuators without a KNX Power Supply/IP Interface.
     * IP cameras without an NVR or exceeding NVR channel count, or passive ceiling speakers without an amplifier.
     * Cabling items with unspecified meter lengths.
   - Put minor assumptions, illegible handwriting warnings, or inferred protocols into 'warnings', NOT in 'clarificationQuestions'.
   - Return an empty list [] for 'clarificationQuestions' if no critical blockers exist.

Return ONLY valid JSON matching this exact schema:
{format}
