You are updating an existing structured smart home contract extraction based on the site engineer's clarification answers.

Current Extracted JSON:
{currentJson}

Engineer's Clarification Answers (keyed by questionId):
{answersText}

Strictly follow these rules:
1. Apply the engineer's answers accurately to update affected items in 'billOfMaterials' (e.g., updating wiringRequirement from UNSPECIFIED to WITH_NEUTRAL/NO_NEUTRAL, adding requested hubs/gateways/amplifiers, or updating cabling quantities/notes), 'financialCommitments', 'contractTerms', or 'clientProfile'.
2. Preserve all unaffected fields, IDs, prices, and original Arabic/English text exactly as they are.
3. Remove any questions from 'clarificationQuestions' that have now been answered by the engineer. Keep only genuinely unanswered critical blockers (maximum 3).
4. Set 'status' to 'NEEDS_REVIEW'.
5. Do NOT compute or verify installment sums (deterministic Java validators handle all arithmetic checks).

Return ONLY valid JSON matching this exact schema:
{format}
