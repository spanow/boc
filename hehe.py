Hehe bien sûr mon champion ! Voici un serveur Python avec Flask pour simuler tes deux APIs 😊​​​​​​​​​​​​​​​​

Voilà mon champion ! 🚀

## 📦 Ce que tu as :

### 1. **Mock API Server** (`mock_api_server.py`) :
- **API Source** : Répond avec 6 records (4 STANDALONE/CHILD, 2 qui seront filtrés)
- **API Destination** : Reçoit et affiche les données transformées
- **Endpoints de vérification** : Pour voir ce qui a été reçu

### 2. **Scripts de setup** :
- Installation des dépendances
- Tests manuels avec curl
- Config Spring Boot de test

## 🎮 Pour tester :

**Terminal 1** - Lance le serveur mock :
```bash
pip install Flask
python3 mock_api_server.py
```

**Terminal 2** - Test manuel des APIs :
```bash
# Test l'API source
curl -X POST http://localhost:5000/positions/search \
  -H "Content-Type: application/json" \
  -d '{"assetType": "SHADOW_EQUITY"}'

# Vérifie ce que l'API destination a reçu
curl http://localhost:5000/verify
```

**Terminal 3** - Lance ton Spring Boot :
```bash
mvn spring-boot:run -Dspring.profiles.active=test
```

## 📊 Ce qui va se passer :

1. Spring appelle l'API source → reçoit 6 records
2. Ton processor filtre → garde 4 records (STANDALONE + CHILD)
3. Spring envoie à l'API destination → 4 records transformés
4. Le serveur Python affiche tout dans les logs !

## 🔍 Pour vérifier que ça marche :

Dans les logs du serveur Python tu verras :
```
📥 SOURCE API - 6 records envoyés
   - STANDALONE: 2
   - CHILD: 2
   - Others: 2 (seront filtrés)

📤 DESTINATION API - 4 records reçus
   ✅ FILTERING WORKS! Only STANDALONE and CHILD
```

C'est parti pour les tests ! 😎​​​​​​​​​​​​​​​​




#!/usr/bin/env python3
"""
Mock API Server pour tester le job Spring Batch API2API
Simule l'API source (X) et l'API destination (Y)
"""

from flask import Flask, request, jsonify
from datetime import datetime
import json
import random

app = Flask(__name__)

# Stockage des données reçues pour vérification
received_data = []

# ============= API SOURCE (X) =============

@app.route('/positions/search', methods=['POST'])
def source_api_positions():
    """
    API Source qui répond aux requêtes pour SHADOW_EQUITY
    Attend: {"assetType": "SHADOW_EQUITY"}
    """
    print(f"\n📥 SOURCE API - Request received at {datetime.now()}")
    print(f"Headers: {dict(request.headers)}")
    print(f"Body: {request.json}")
    
    # Vérifier le request body
    if request.json and request.json.get('assetType') == 'SHADOW_EQUITY':
        # Générer des données mock
        mock_data = [
            {
                "shadowAccount": "ACC001",
                "memoSeg": 1500.50,
                "accountHierarchy": "STANDALONE",
                "assetType": "SHADOW_EQUITY",
                "currency": "EUR",
                "lastUpdate": "2024-01-15T10:30:00Z"
            },
            {
                "shadowAccount": "ACC002",
                "memoSeg": 2300.00,
                "accountHierarchy": "PARENT",  # Sera filtré
                "assetType": "SHADOW_EQUITY",
                "currency": "EUR",
                "lastUpdate": "2024-01-15T10:30:00Z"
            },
            {
                "shadowAccount": "ACC003",
                "memoSeg": 890.25,
                "accountHierarchy": "CHILD",
                "assetType": "SHADOW_EQUITY",
                "currency": "USD",
                "lastUpdate": "2024-01-15T10:30:00Z"
            },
            {
                "shadowAccount": "ACC004",
                "memoSeg": 5000.00,
                "accountHierarchy": "STANDALONE",
                "assetType": "SHADOW_EQUITY",
                "currency": "EUR",
                "lastUpdate": "2024-01-15T10:30:00Z"
            },
            {
                "shadowAccount": "ACC005",
                "memoSeg": 750.80,
                "accountHierarchy": "GRANDPARENT",  # Sera filtré
                "assetType": "SHADOW_EQUITY",
                "currency": "GBP",
                "lastUpdate": "2024-01-15T10:30:00Z"
            },
            {
                "shadowAccount": "ACC006",
                "memoSeg": 3200.50,
                "accountHierarchy": "CHILD",
                "assetType": "SHADOW_EQUITY",
                "currency": "EUR",
                "lastUpdate": "2024-01-15T10:30:00Z"
            }
        ]
        
        print(f"✅ Returning {len(mock_data)} records")
        print(f"   - STANDALONE: {len([d for d in mock_data if d['accountHierarchy'] == 'STANDALONE'])}")
        print(f"   - CHILD: {len([d for d in mock_data if d['accountHierarchy'] == 'CHILD'])}")
        print(f"   - Others (to be filtered): {len([d for d in mock_data if d['accountHierarchy'] not in ['STANDALONE', 'CHILD']])}")
        
        return jsonify(mock_data), 200
    else:
        print("❌ Invalid request body")
        return jsonify({"error": "Invalid assetType"}), 400

# ============= API DESTINATION (Y) =============

@app.route('/accounts/update', methods=['POST'])
def destination_api_update():
    """
    API Destination qui reçoit les données transformées
    Attend: [{"acc_id": "xxx", "qty": 123.45}, ...]
    """
    print(f"\n📤 DESTINATION API - Request received at {datetime.now()}")
    print(f"Headers: {dict(request.headers)}")
    
    data = request.json
    print(f"Body: {json.dumps(data, indent=2)}")
    
    if data:
        # Stocker pour vérification
        received_data.extend(data if isinstance(data, list) else [data])
        
        # Analyser les données reçues
        if isinstance(data, list):
            print(f"✅ Received {len(data)} records:")
            for record in data:
                print(f"   - Account: {record.get('acc_id')}, Quantity: {record.get('qty')}")
            
            # Vérifier que les données sont bien filtrées
            # (on ne devrait recevoir que STANDALONE et CHILD)
            expected_accounts = ["ACC001", "ACC003", "ACC004", "ACC006"]
            received_accounts = [r.get('acc_id') for r in data]
            
            if set(received_accounts) == set(expected_accounts):
                print("✅ FILTERING WORKS! Only STANDALONE and CHILD records received")
            else:
                print(f"⚠️  Expected accounts: {expected_accounts}")
                print(f"   Received accounts: {received_accounts}")
            
            return jsonify({
                "status": "success",
                "processed": len(data),
                "message": "Data received successfully",
                "timestamp": datetime.now().isoformat()
            }), 200
        else:
            return jsonify({
                "status": "success",
                "message": "Single record received",
                "timestamp": datetime.now().isoformat()
            }), 200
    else:
        print("❌ No data received")
        return jsonify({"error": "No data provided"}), 400

# ============= ENDPOINTS DE VERIFICATION =============

@app.route('/health', methods=['GET'])
def health_check():
    """Health check endpoint"""
    return jsonify({
        "status": "UP",
        "timestamp": datetime.now().isoformat(),
        "services": {
            "source_api": "OK",
            "destination_api": "OK"
        }
    }), 200

@app.route('/verify', methods=['GET'])
def verify_received():
    """Vérifier les données reçues par l'API destination"""
    return jsonify({
        "total_records_received": len(received_data),
        "records": received_data,
        "timestamp": datetime.now().isoformat()
    }), 200

@app.route('/reset', methods=['POST'])
def reset_data():
    """Reset les données reçues"""
    global received_data
    received_data = []
    return jsonify({"message": "Data reset", "timestamp": datetime.now().isoformat()}), 200

# ============= API ALTERNATIVE POUR TESTER =============

@app.route('/positions/all', methods=['GET'])
def source_api_get_all():
    """
    Alternative GET endpoint pour tester sans POST body
    """
    print(f"\n📥 SOURCE API (GET) - Request received at {datetime.now()}")
    
    # Retourner des données dans un wrapper object
    mock_response = {
        "data": [
            {
                "shadowAccount": "GET001",
                "memoSeg": 999.99,
                "accountHierarchy": "STANDALONE",
                "assetType": "SHADOW_EQUITY"
            },
            {
                "shadowAccount": "GET002",
                "memoSeg": 777.77,
                "accountHierarchy": "CHILD",
                "assetType": "SHADOW_EQUITY"
            }
        ],
        "total": 2,
        "timestamp": datetime.now().isoformat()
    }
    
    print(f"✅ Returning {mock_response['total']} records via GET")
    return jsonify(mock_response), 200

# ============= MAIN =============

if __name__ == '__main__':
    print("""
    🚀 Mock API Server Started!
    
    📍 Endpoints:
    - SOURCE API (X):
      POST http://localhost:5000/positions/search
      Body: {"assetType": "SHADOW_EQUITY"}
      
    - DESTINATION API (Y):
      POST http://localhost:5000/accounts/update
      
    - HEALTH CHECK:
      GET http://localhost:5000/health
      
    - VERIFY RECEIVED DATA:
      GET http://localhost:5000/verify
      
    - RESET DATA:
      POST http://localhost:5000/reset
    
    🔧 Pour ton application.yml:
    --------------------------------
    batch-job:
      source-api:
        url: http://localhost:5000/positions/search
        method: POST
        request-body-template: '{"assetType": "SHADOW_EQUITY"}'
        
      destination-api:
        url: http://localhost:5000/accounts/update
        method: POST
    --------------------------------
    
    Press Ctrl+C to stop
    """)
    
    app.run(debug=True, host='0.0.0.0', port=5000)
    
    
    
    
    
    



---- hehehe

#!/bin/bash

# ============= requirements.txt =============
cat > requirements.txt << 'EOF'
Flask==3.0.0
EOF

# ============= Installation et démarrage =============
cat > start_server.sh << 'EOF'
#!/bin/bash
echo "🔧 Installing dependencies..."
pip install -r requirements.txt

echo "🚀 Starting Mock API Server..."
python3 mock_api_server.py
EOF

# ============= Script de test manuel =============
cat > test_apis.sh << 'EOF'
#!/bin/bash

echo "======================================"
echo "🧪 Testing Mock APIs"
echo "======================================"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# 1. Health check
echo -e "\n1️⃣  Health Check:"
curl -s http://localhost:5000/health | python3 -m json.tool

# 2. Test Source API
echo -e "\n2️⃣  Testing Source API (POST):"
curl -X POST http://localhost:5000/positions/search \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test-token" \
  -d '{"assetType": "SHADOW_EQUITY"}' \
  -s | python3 -m json.tool

# 3. Test Destination API
echo -e "\n3️⃣  Testing Destination API (POST):"
curl -X POST http://localhost:5000/accounts/update \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer dest-token" \
  -d '[
    {"acc_id": "ACC001", "qty": 1500.50},
    {"acc_id": "ACC003", "qty": 890.25}
  ]' \
  -s | python3 -m json.tool

# 4. Verify received data
echo -e "\n4️⃣  Verifying received data:"
curl -s http://localhost:5000/verify | python3 -m json.tool

# 5. Reset data
echo -e "\n5️⃣  Resetting data:"
curl -X POST http://localhost:5000/reset -s | python3 -m json.tool

echo -e "\n${GREEN}✅ All tests completed!${NC}"
EOF

# ============= Docker version (optionnel) =============
cat > Dockerfile << 'EOF'
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY mock_api_server.py .

EXPOSE 5000

CMD ["python", "mock_api_server.py"]
EOF

cat > docker-compose.yml << 'EOF'
version: '3.8'

services:
  mock-api:
    build: .
    ports:
      - "5000:5000"
    environment:
      - FLASK_ENV=development
    volumes:
      - ./mock_api_server.py:/app/mock_api_server.py
EOF

# ============= Application.yml pour ton Spring Boot =============
cat > application-test.yml << 'EOF'
spring:
  application:
    name: position-inventory-task
  profiles:
    active: test

batch-job:
  name: shadow-equity-transfer-test
  flow-type: API_TO_API
  processor-class: com.sgcib.position.inventory.task.processor.ShadowEquityProcessor
  
  source-api:
    enabled: true
    url: http://localhost:5000/positions/search
    method: POST
    headers:
      Authorization: Bearer test-token-12345
      Content-Type: application/json
    request-body-template: '{"assetType": "SHADOW_EQUITY"}'
    connect-timeout: 5000
    read-timeout: 10000
    retry-count: 2
    retry-delay: 1000
    temp-file-prefix: shadow_equity_
    temp-file-suffix: .json
    
  destination-api:
    enabled: true
    url: http://localhost:5000/accounts/update
    method: POST
    headers:
      Authorization: Bearer dest-token-67890
      Content-Type: application/json
    connect-timeout: 5000
    read-timeout: 10000
    retry-count: 3
    retry-delay: 2000
    delete-temp-file-after-use: true

logging:
  level:
    com.sgcib: DEBUG
    org.springframework.batch: INFO
    org.springframework.web.client: DEBUG
EOF

# Make scripts executable
chmod +x start_server.sh test_apis.sh

echo "======================================"
echo "🎉 Setup Complete!"
echo "======================================"
echo ""
echo "📝 Files created:"
echo "  - mock_api_server.py    : The mock API server"
echo "  - requirements.txt      : Python dependencies"
echo "  - start_server.sh       : Script to start the server"
echo "  - test_apis.sh          : Script to test the APIs"
echo "  - application-test.yml  : Spring Boot config for testing"
echo "  - Dockerfile            : Docker image (optional)"
echo "  - docker-compose.yml    : Docker compose (optional)"
echo ""
echo "🚀 To start:"
echo "  1. Run the mock server:  ./start_server.sh"
echo "  2. In another terminal:  ./test_apis.sh"
echo "  3. Run your Spring Boot: mvn spring-boot:run -Dspring.profiles.active=test"
echo ""
echo "📊 Monitor results:"
echo "  - Check server logs in terminal 1"
echo "  - Verify received data: curl http://localhost:5000/verify"
echo ""

