# GST Tax Module

Module for GST return filing support - generates GSTR-1 and GSTR-3B reports.

## API Endpoints

### 1. Get GST Summary

**GET** `/api/v1/gst/summary?period=2026-01`

**Response:**
```json
{
  "success": true,
  "data": {
    "shopId": "shop123",
    "period": "2026-01",
    "shopGstin": "29ABCDE1234F1Z5",
    "shopName": "My Shop",
    "totalTaxableValue": 150000.00,
    "totalCgst": 13500.00,
    "totalSgst": 13500.00,
    "totalIgst": 0.00,
    "totalCess": 0.00,
    "totalTaxLiability": 27000.00,
    "totalInvoices": 45,
    "ratewiseSummary": [
      {
        "rate": 18,
        "taxableValue": 100000.00,
        "cgstAmount": 9000.00,
        "sgstAmount": 9000.00,
        "igstAmount": 0.00,
        "cessAmount": 0.00,
        "invoiceCount": 30
      },
      {
        "rate": 12,
        "taxableValue": 50000.00,
        "cgstAmount": 3000.00,
        "sgstAmount": 3000.00,
        "igstAmount": 0.00,
        "cessAmount": 0.00,
        "invoiceCount": 15
      }
    ],
    "hsnSummary": [
      {
        "hsnCode": "30049099",
        "description": "Paracetamol 500mg",
        "uqc": "NOS",
        "totalQuantity": 500,
        "totalValue": 25000.00,
        "taxableValue": 25000.00,
        "cgstRate": 6,
        "cgstAmount": 1500.00,
        "sgstRate": 6,
        "sgstAmount": 1500.00,
        "igstRate": 0,
        "igstAmount": 0.00
      }
    ]
  }
}
```

---

### 2. Get GSTR-1 Report

**GET** `/api/v1/gst/gstr1?period=2026-01`

**Response:**
```json
{
  "success": true,
  "data": {
    "shopId": "shop123",
    "gstin": "29ABCDE1234F1Z5",
    "period": "012026",
    "legalName": "My Shop",
    "b2bInvoices": [
      {
        "buyerGstin": "29XYZAB5678C1D2",
        "buyerName": "ABC Traders",
        "invoiceNo": "INV-2026-001",
        "invoiceDate": "2026-01-15T10:30:00Z",
        "invoiceValue": 50000.00,
        "placeOfSupply": "29",
        "reverseCharge": false,
        "invoiceType": "Regular",
        "items": [
          {
            "rate": 18,
            "taxableValue": 42372.88,
            "cgstAmount": 3813.56,
            "sgstAmount": 3813.56,
            "igstAmount": 0.00,
            "cessAmount": 0.00
          }
        ]
      }
    ],
    "b2clInvoices": [],
    "b2csSummary": {
      "placeOfSupply": "29",
      "rate": 18,
      "taxableValue": 100000.00,
      "cgstAmount": 9000.00,
      "sgstAmount": 9000.00,
      "cessAmount": 0.00
    },
    "hsnSummary": [
      {
        "hsnCode": "30049099",
        "description": "Medicine",
        "uqc": "NOS",
        "totalQuantity": 500,
        "totalValue": 25000.00,
        "taxableValue": 25000.00,
        "cgstRate": 6,
        "cgstAmount": 1500.00,
        "sgstRate": 6,
        "sgstAmount": 1500.00,
        "igstRate": 0,
        "igstAmount": 0.00
      }
    ],
    "documentSummary": {
      "totalInvoicesIssued": 45,
      "fromInvoiceNo": "INV-2026-001",
      "toInvoiceNo": "INV-2026-045",
      "cancelledCount": 2
    }
  }
}
```

---

### 3. Get GSTR-3B Report

**GET** `/api/v1/gst/gstr3b?period=2026-01`

**Response:**
```json
{
  "success": true,
  "data": {
    "shopId": "shop123",
    "gstin": "29ABCDE1234F1Z5",
    "period": "012026",
    "legalName": "My Shop",
    "outwardSupplies": {
      "taxableSupplies": {
        "taxableValue": 150000.00,
        "igst": 0.00,
        "cgst": 13500.00,
        "sgst": 13500.00,
        "cess": 0.00
      },
      "zeroRatedSupplies": {
        "taxableValue": 0.00,
        "igst": 0.00,
        "cgst": 0.00,
        "sgst": 0.00,
        "cess": 0.00
      },
      "nilRatedSupplies": {
        "taxableValue": 0.00,
        "igst": 0.00,
        "cgst": 0.00,
        "sgst": 0.00,
        "cess": 0.00
      },
      "reverseChargeSupplies": {
        "taxableValue": 0.00,
        "igst": 0.00,
        "cgst": 0.00,
        "sgst": 0.00,
        "cess": 0.00
      },
      "nonGstSupplies": {
        "taxableValue": 0.00,
        "igst": 0.00,
        "cgst": 0.00,
        "sgst": 0.00,
        "cess": 0.00
      }
    },
    "interstateSupplies": [],
    "inputTaxCredit": {
      "itcAvailable": {
        "taxableValue": 0.00,
        "igst": 0.00,
        "cgst": 0.00,
        "sgst": 0.00,
        "cess": 0.00
      },
      "itcReversed": {
        "taxableValue": 0.00,
        "igst": 0.00,
        "cgst": 0.00,
        "sgst": 0.00,
        "cess": 0.00
      },
      "netItc": {
        "taxableValue": 0.00,
        "igst": 0.00,
        "cgst": 0.00,
        "sgst": 0.00,
        "cess": 0.00
      },
      "ineligibleItc": {
        "taxableValue": 0.00,
        "igst": 0.00,
        "cgst": 0.00,
        "sgst": 0.00,
        "cess": 0.00
      }
    },
    "exemptSupplies": {
      "interStateSupplies": 0.00,
      "intraStateSupplies": 0.00
    },
    "taxPayment": {
      "igstPayable": 0.00,
      "cgstPayable": 13500.00,
      "sgstPayable": 13500.00,
      "cessPayable": 0.00,
      "totalPayable": 27000.00
    }
  }
}
```

---

### 4. Generate & Save GST Return

**POST** `/api/v1/gst/returns?period=2026-01&returnType=GSTR1`

**Response:**
```json
{
  "success": true,
  "data": {
    "id": "65a1b2c3d4e5f6789",
    "shopId": "shop123",
    "returnType": "GSTR1",
    "period": "2026-01",
    "status": "GENERATED",
    "totalTaxableValue": 150000.00,
    "totalCgst": 13500.00,
    "totalSgst": 13500.00,
    "totalIgst": 0.00,
    "totalCess": 0.00,
    "totalTaxLiability": 27000.00,
    "filedBy": "user123",
    "filedAt": null,
    "createdAt": "2026-01-15T10:30:00Z",
    "updatedAt": "2026-01-15T10:30:00Z"
  }
}
```

---

### 5. List All GST Returns

**GET** `/api/v1/gst/returns`

**Response:**
```json
{
  "success": true,
  "data": {
    "returns": [
      {
        "id": "65a1b2c3d4e5f6789",
        "shopId": "shop123",
        "returnType": "GSTR1",
        "period": "2026-01",
        "status": "GENERATED",
        "totalTaxableValue": 150000.00,
        "totalCgst": 13500.00,
        "totalSgst": 13500.00,
        "totalIgst": 0.00,
        "totalCess": 0.00,
        "totalTaxLiability": 27000.00,
        "filedBy": "user123",
        "filedAt": null,
        "createdAt": "2026-01-15T10:30:00Z",
        "updatedAt": "2026-01-15T10:30:00Z"
      }
    ],
    "page": 1,
    "limit": 10,
    "total": 1,
    "totalPages": 1
  }
}
```

---

### 6. Get GST Return by ID

**GET** `/api/v1/gst/returns/{returnId}`

**Response:**
```json
{
  "success": true,
  "data": {
    "id": "65a1b2c3d4e5f6789",
    "shopId": "shop123",
    "returnType": "GSTR1",
    "period": "2026-01",
    "status": "GENERATED",
    "totalTaxableValue": 150000.00,
    "totalCgst": 13500.00,
    "totalSgst": 13500.00,
    "totalIgst": 0.00,
    "totalCess": 0.00,
    "totalTaxLiability": 27000.00,
    "filedBy": "user123",
    "filedAt": null,
    "createdAt": "2026-01-15T10:30:00Z",
    "updatedAt": "2026-01-15T10:30:00Z"
  }
}
```

---

### 7. Mark Return as Filed

**POST** `/api/v1/gst/returns/{returnId}/file`

**Response:**
```json
{
  "success": true,
  "data": {
    "id": "65a1b2c3d4e5f6789",
    "shopId": "shop123",
    "returnType": "GSTR1",
    "period": "2026-01",
    "status": "FILED",
    "totalTaxableValue": 150000.00,
    "totalCgst": 13500.00,
    "totalSgst": 13500.00,
    "totalIgst": 0.00,
    "totalCess": 0.00,
    "totalTaxLiability": 27000.00,
    "filedBy": "user123",
    "filedAt": "2026-01-20T14:00:00Z",
    "createdAt": "2026-01-15T10:30:00Z",
    "updatedAt": "2026-01-20T14:00:00Z"
  }
}
```

---

## Return Status Flow

```
DRAFT → GENERATED → EXPORTED → FILED → AMENDED
```

## Period Format

All APIs expect period in `YYYY-MM` format (e.g., `2026-01` for January 2026).

