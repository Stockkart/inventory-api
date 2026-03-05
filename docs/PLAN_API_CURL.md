# Plan Module - cURL Test Commands

Base URL: `http://localhost:8080` (adjust if your server runs on a different port)

---

## 1. Public Endpoints (No Auth Required)

### List All Plans
Shows plans on pricing page before login.

```bash
curl -X GET "http://localhost:8080/api/v1/plans" \
  -H "Content-Type: application/json"
```

### Get Plan by ID
Replace `{planId}` with actual MongoDB ObjectId from list response.

```bash
curl -X GET "http://localhost:8080/api/v1/plans/{planId}" \
  -H "Content-Type: application/json"
```

---

## 2. Authenticated Endpoints

**Prerequisites:**
- Login to get access token: `TOKEN=$(curl -s -X POST "http://localhost:8080/api/v1/auth/login" -H "Content-Type: application/json" -d '{"username":"your@email.com","password":"yourpassword"}' | jq -r '.data.accessToken')`
- Replace `YOUR_ACCESS_TOKEN` and `YOUR_SHOP_ID` below.

### Get Current Shop Plan Status
Uses `shopId` from request (X-Shop-Id header or active shop).

```bash
curl -X GET "http://localhost:8080/api/v1/plans/shop/status" \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "X-Shop-Id: YOUR_SHOP_ID" \
  -H "Content-Type: application/json"
```

### Get Shop Plan Status by Shop ID

```bash
curl -X GET "http://localhost:8080/api/v1/plans/shop/YOUR_SHOP_ID/status" \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "Content-Type: application/json"
```

### Get Suggested Plan (Upsell)
Returns next higher plan via linked list.

```bash
curl -X GET "http://localhost:8080/api/v1/plans/shop/YOUR_SHOP_ID/suggested" \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "Content-Type: application/json"
```

### Assign Plan to Shop
Simulates plan purchase. Sets `planId` and `expiryDate` on shop.

```bash
curl -X POST "http://localhost:8080/api/v1/plans/shop/YOUR_SHOP_ID/assign" \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "planId": "PLAN_ID_FROM_LIST",
    "durationMonths": 1
  }'
```

### Get Current Month Usage

```bash
curl -X GET "http://localhost:8080/api/v1/plans/shop/usage" \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "X-Shop-Id: YOUR_SHOP_ID" \
  -H "Content-Type: application/json"
```

### Record Usage
Typically called internally when bill/SMS/WhatsApp is used. For manual testing:

```bash
curl -X PUT "http://localhost:8080/api/v1/plans/shop/usage" \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "X-Shop-Id: YOUR_SHOP_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "billingAmount": 5000,
    "billCount": 1
  }'
```

---

## 3. Full Flow Example

```bash
# 1. List plans (public)
curl -s -X GET "http://localhost:8080/api/v1/plans" | jq

# 2. Login
TOKEN=$(curl -s -X POST "http://localhost:8080/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin@example.com","password":"password"}' | jq -r '.data.accessToken')

# 3. Get shop ID (from login response or user profile)
SHOP_ID="your_shop_id_here"

# 4. Check shop plan status (trial: planId=null, expiryDate=now+30d)
curl -s -X GET "http://localhost:8080/api/v1/plans/shop/status" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Shop-Id: $SHOP_ID" | jq

# 5. Get suggested plan
curl -s -X GET "http://localhost:8080/api/v1/plans/shop/$SHOP_ID/suggested" \
  -H "Authorization: Bearer $TOKEN" | jq

# 6. Assign plan (after payment)
PLAN_ID="paste_plan_id_from_step_1"
curl -s -X POST "http://localhost:8080/api/v1/plans/shop/$SHOP_ID/assign" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"planId\":\"$PLAN_ID\",\"durationMonths\":1}" | jq

# 7. Check usage
curl -s -X GET "http://localhost:8080/api/v1/plans/shop/usage" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Shop-Id: $SHOP_ID" | jq
```

---

## 4. Shop Registration (Trial Flow)

On shop registration via `/api/v1/shops/register`, the shop gets:
- `planId`: null (trial)
- `expiryDate`: now + 30 days

After trial expires, `trialExpired: true` in plan status. UI shows payment option.
