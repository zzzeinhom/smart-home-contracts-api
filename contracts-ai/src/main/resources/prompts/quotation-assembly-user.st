Assemble a grounded Bill of Materials (BoM) from the following list of GroundedRequirement JSON objects. Each object pairs one site survey requirement with up to 3 retrieved CatalogCandidate entries from the tenant's device catalog.

Grounded Requirements JSON:
{groundedRequirementsJson}

Strictly follow these grounding rules:
1. For each GroundedRequirement, evaluate whether any CatalogCandidate genuinely satisfies the technical specification (category, communication protocol, electrical wiring compatibility, and device function).
2. If a CatalogCandidate genuinely matches:
   - Set rawName to a concise descriptive label in the survey's language.
   - Set normalizedDevice = candidate.normalizedName().
   - Set protocol = candidate.protocol() and wiringRequirement = candidate.wiringRequirement().
   - Set matchedCatalogId = candidate.id(), matchConfidence = candidate.similarity(), and catalogReferencePrice = candidate.referenceUnitPrice().
   - Leave unitPrice = null and lineTotal = null (backend code calculates pricing deterministically).
3. If NO candidate genuinely matches (for example, candidates is empty, or the requirement asks for KNX / Aqara Lock / Amplifier and only unrelated products appear in candidates):
   - NEVER force an incompatible product match.
   - Set matchedCatalogId = null, matchConfidence = null, catalogReferencePrice = null, unitPrice = null, lineTotal = null.
   - Set category, rawName, normalizedDevice, protocol, and wiringRequirement accurately for the requested item.
4. Preserve the exact quantity from requirement.quantity (defaulting to 1 if missing) and write concise room/installation notes in the survey's language.

Return ONLY valid JSON matching this exact schema:
{format}
