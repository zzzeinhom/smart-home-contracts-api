Analyze the site engineer's raw Arabic/English smart home site survey notes and decompose every requested capability into structured requirements.

Strictly follow these decomposition and Egyptian smart home engineering rules:
1. Language Preservation: Keep free-text fields (clientName, projectLocation, roomOrNotes, warrantyPeriod, quoteValidity, exclusions) in the original language of the survey. Do NOT translate Arabic text into English.
2. Atomic Requirement Decomposition:
   - Break compound statements into atomic SurveyRequirementItem entries (for example, motorized curtains plus wall touch switches become two distinct items; separate rooms or distinct switch gang counts become distinct items or clear quantities).
   - Default missing quantities to 1.
   - Do NOT invent prices or model SKUs that are not mentioned in the survey notes.
3. Egyptian Smart Home Taxonomy & Wiring Inference:
   - Standard Magic wall boxes (علبة ماجيك) without a neutral wire -> NO_NEUTRAL; renovated/new boxes with neutral wire -> WITH_NEUTRAL; electrical panel breakers/KNX actuators -> DIN_RAIL; battery sensors/locks -> WIRELESS_BATTERY; passive cables -> NOT_APPLICABLE; unclear wall-box wiring -> UNSPECIFIED.
   - Split ACs controlled via IR blasters -> category CLIMATE_HVAC; smart locks/intercoms/gate motors -> ACCESS_CONTROL; circuit breakers/energy meters -> ENERGY_POWER; Zigbee hubs/panels -> CONTROLLERS_HUBS.
4. Client & Commercial Terms: Extract any mentioned clientProfile details (name, phone, location, ISO date if present) and contractTerms (scopeType, warrantyPeriod, quoteValidity, priceLinkedToExchangeRate, exclusions).
5. Critical Clarification Questions: Generate at most 3 high-impact clarificationQuestions in the survey's primary language ONLY for critical blockers (such as UNSPECIFIED neutral wire status in lighting switches, missing Zigbee/KNX gateway/power supply details, missing amplifier for passive speakers, or unspecified cable meter length). Return [] if none.

Return ONLY valid JSON matching this exact schema:
{format}

Site Survey Notes:
{surveyNotes}
