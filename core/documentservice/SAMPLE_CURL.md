# Sample cURL for Invoice Generation API

## POST /api/v1/documents/invoice

```bash
curl -X POST http://localhost:8080/api/v1/documents/invoice \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  --output invoice.pdf \
  -d '{
  "invoiceNo": "T001669",
  "invoiceDate": "23-12-2025",
  "invoiceTime": "12:19",
  "shopName": "KUBER PHARMA",
  "shopAddress": "A.N. ROAD, GAYA 823001",
  "shopDlNo": "164806/164807",
  "shopFssai": "20418201000129",
  "shopGstin": "10AFBPL7000H128",
  "shopPhone": "9910697979",
  "shopEmail": "kuberpharma4u@gmail.com",
  "customerName": "SRI CHANDRA MEDICAL HALL",
  "customerAddress": "RAFIGANJ",
  "customerState": "10-BIHAR",
  "customerDlNo": "4/4A",
  "customerGstin": "",
  "customerPan": "",
  "customerPhone": "",
  "customerEmail": "",
  "items": [
    {
      "quantity": 1,
      "pack": "1X16",
      "name": "PIGMENTO TAB 60",
      "hsn": "30049011",
      "sac": null,
      "mfgExpDate": "CHARAK P1 8/28",
      "batchNo": "TP10834",
      "maximumRetailPrice": 258.00,
      "sellingPrice": 196.42,
      "scheme": "5.00 2.5% 2.5%",
      "inventoryId": "24-WG09"
    },
    {
      "quantity": 1,
      "pack": "1X60",
      "name": "ASHVAGANDHA TABLETS 60'\''S",
      "hsn": "30049036",
      "sac": null,
      "mfgExpDate": "HIMALAYA 1/28",
      "batchNo": "106250205",
      "maximumRetailPrice": 240.00,
      "sellingPrice": 182.86,
      "scheme": "4.00 2.5% 2.5%",
      "inventoryId": "24-MB02"
    },
    {
      "quantity": 1,
      "pack": "1X20",
      "name": "M2 TONE TAB 30",
      "hsn": "30049011",
      "sac": null,
      "mfgExpDate": "CHARAK P: 8/28",
      "batchNo": "TMT0151",
      "maximumRetailPrice": 210.00,
      "sellingPrice": 150.02,
      "scheme": "5.00 2.5% 2.5%",
      "inventoryId": "24-TB03"
    }
  ],
  "subTotal": 1012.20,
  "discountTotal": 46.95,
  "sgstAmount": 24.14,
  "cgstAmount": 24.14,
  "sgstPercent": 2.5,
  "cgstPercent": 2.5,
  "taxTotal": 48.28,
  "roundOff": 0.47,
  "grandTotal": 1014.00,
  "paymentMethod": "Check / Money order",
  "amountInWords": "One Thousand and Fourteen only",
  "footerNote": "MARS ERP NAND for Chemist @Rs.5409: Stock, Accounts, GST, Expiry | Online Purchase Import: Call 06312222531"
}'
```

## Alternative: Pretty formatted JSON file

Save the following as `invoice-request.json`:

```json
{
  "invoiceNo": "T001669",
  "invoiceDate": "23-12-2025",
  "invoiceTime": "12:19",
  "shopName": "KUBER PHARMA",
  "shopAddress": "A.N. ROAD, GAYA 823001",
  "shopDlNo": "164806/164807",
  "shopFssai": "20418201000129",
  "shopGstin": "10AFBPL7000H128",
  "shopPhone": "9910697979",
  "shopEmail": "kuberpharma4u@gmail.com",
  "customerName": "SRI CHANDRA MEDICAL HALL",
  "customerAddress": "RAFIGANJ",
  "customerState": "10-BIHAR",
  "customerDlNo": "4/4A",
  "customerGstin": "",
  "customerPan": "",
  "customerPhone": "",
  "customerEmail": "",
  "items": [
    {
      "quantity": 1,
      "pack": "1X16",
      "name": "PIGMENTO TAB 60",
      "hsn": "30049011",
      "sac": null,
      "mfgExpDate": "CHARAK P1 8/28",
      "batchNo": "TP10834",
      "maximumRetailPrice": 258.00,
      "sellingPrice": 196.42,
      "scheme": "5.00 2.5% 2.5%",
      "inventoryId": "24-WG09"
    },
    {
      "quantity": 1,
      "pack": "1X60",
      "name": "ASHVAGANDHA TABLETS 60'S",
      "hsn": "30049036",
      "sac": null,
      "mfgExpDate": "HIMALAYA 1/28",
      "batchNo": "106250205",
      "maximumRetailPrice": 240.00,
      "sellingPrice": 182.86,
      "scheme": "4.00 2.5% 2.5%",
      "inventoryId": "24-MB02"
    },
    {
      "quantity": 1,
      "pack": "1X20",
      "name": "M2 TONE TAB 30",
      "hsn": "30049011",
      "sac": null,
      "mfgExpDate": "CHARAK P: 8/28",
      "batchNo": "TMT0151",
      "maximumRetailPrice": 210.00,
      "sellingPrice": 150.02,
      "scheme": "5.00 2.5% 2.5%",
      "inventoryId": "24-TB03"
    }
  ],
  "subTotal": 1012.20,
  "discountTotal": 46.95,
  "sgstAmount": 24.14,
  "cgstAmount": 24.14,
  "sgstPercent": 2.5,
  "cgstPercent": 2.5,
  "taxTotal": 48.28,
  "roundOff": 0.47,
  "grandTotal": 1014.00,
  "paymentMethod": "Check / Money order",
  "amountInWords": "One Thousand and Fourteen only",
  "footerNote": "MARS ERP NAND for Chemist @Rs.5409: Stock, Accounts, GST, Expiry | Online Purchase Import: Call 06312222531"
}
```

Then use:
```bash
curl -X POST http://localhost:8080/api/v1/documents/invoice \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  --output invoice.pdf \
  -d @invoice-request.json
```

