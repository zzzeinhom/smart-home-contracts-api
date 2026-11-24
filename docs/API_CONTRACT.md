# API Contract

## 1. Extract Contract
`POST /api/v1/contracts/extract`
- **Consumes:** `multipart/form-data` (`file`)
- **Produces:** `202 Accepted`
- **Response:**
```json
{
  "jobId": "ext_98a71b2c",
  "status": "PROCESSING"
}
```

## 2. Get Extraction Result
`GET /api/v1/contracts/extract/{jobId}`
- **Produces:** `200 OK`
- **Lifecycle Statuses:** `PROCESSING`, `NEEDS_REVIEW`, `APPROVED`, `FAILED`
- **Payload Structure (Mapped via Spring AI `BeanOutputConverter`):**
```json
{
  "extractionId": "ext_98a71b2c",
  "status": "NEEDS_REVIEW",
  "clientProfile": {
    "clientName": "أحمد محمود",
    "phone": "+2010XXXXXXXX",
    "projectLocation": "التجمع الخامس - فيلا 14",
    "contractDate": "2026-11-15"
  },
  "financialCommitments": {
    "totalAmount": 185000.00,
    "currency": "EGP",
    "isMathVerified": true,
    "installments": [
      {
        "stage": "دفعة تعاقد (50%)",
        "amount": 92500.00,
        "dueCondition": "عند التوقيع"
      }
    ]
  },
  "billOfMaterials": [
    {
      "category": "NETWORKING",
      "rawName": "TP-Link Deco X50",
      "normalizedDevice": "Mesh Wi-Fi Unit",
      "protocol": "WI-FI",
      "quantity": 3,
      "notes": "تغطية الدور الأرضي والأول"
    }
  ],
  "warnings": [
    "بند 'تأسيس كابلات' مذكور بدون تحديد عدد الأمتار."
  ]
}
```
