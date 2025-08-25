// ============= 1. APPLICATION.YML =============
/*
batch-job:
  name: shadow-equity-transfer
  flow-type: API_TO_API
  processor-class: com.sgcib.position.inventory.task.processor.ShadowEquityProcessor
  
  source-api:
    enabled: true
    url: ${SOURCE_API_URL:https://api-source.com/positions/search}
    method: POST
    headers:
      Authorization: Bearer ${SOURCE_API_TOKEN}
      Content-Type: application/json
    # LE REQUEST BODY POUR API X
    request-body-template: '{"assetType": "SHADOW_EQUITY"}'
    connect-timeout: 30000
    read-timeout: 60000
    retry-count: 3
    retry-delay: 2000
    temp-file-prefix: shadow_equity_
    temp-file-suffix: .json
    
  destination-api:
    enabled: true
    url: ${DEST_API_URL:https://api-destination.com/accounts/update}
    method: POST
    headers:
      Authorization: Bearer ${DEST_API_TOKEN}
      Content-Type: application/json
    connect-timeout: 30000
    read-timeout: 120000
    retry-count: 5
    retry-delay: 3000
    delete-temp-file-after-use: true
*/

// ============= 2. LE PROCESSOR QUI FAIT TOUT LE TAFF =============
// ShadowEquityProcessor.java
package com.sgcib.position.inventory.task.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class ShadowEquityProcessor implements ItemProcessor<String, String> {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String process(String jsonContent) throws Exception {
        log.info("Processing Shadow Equity data");
        
        // Parse la réponse de l'API X
        JsonNode rootNode = objectMapper.readTree(jsonContent);
        
        // Liste pour stocker les données transformées
        List<ObjectNode> transformedRecords = new ArrayList<>();
        
        // Si c'est un array de résultats
        if (rootNode.isArray()) {
            for (JsonNode record : rootNode) {
                processRecord(record, transformedRecords);
            }
        } 
        // Si c'est un objet avec un field "data" ou "results"
        else if (rootNode.isObject()) {
            // Essaye de trouver le tableau de données
            JsonNode dataArray = rootNode.get("data");
            if (dataArray == null) {
                dataArray = rootNode.get("results");
            }
            if (dataArray == null) {
                dataArray = rootNode.get("items");
            }
            
            if (dataArray != null && dataArray.isArray()) {
                for (JsonNode record : dataArray) {
                    processRecord(record, transformedRecords);
                }
            } else {
                // Si c'est directement un objet unique
                processRecord(rootNode, transformedRecords);
            }
        }
        
        // Créer le JSON final pour l'API Y
        ArrayNode outputArray = objectMapper.createArrayNode();
        transformedRecords.forEach(outputArray::add);
        
        String result = objectMapper.writeValueAsString(outputArray);
        log.info("Processed {} records, filtered to {} records", 
                rootNode.size(), transformedRecords.size());
        
        return result;
    }
    
    private void processRecord(JsonNode record, List<ObjectNode> transformedRecords) {
        // Extraire accountHierarchy
        JsonNode accountHierarchyNode = record.get("accountHierarchy");
        if (accountHierarchyNode == null) {
            log.debug("Skipping record without accountHierarchy");
            return;
        }
        
        String accountHierarchy = accountHierarchyNode.asText();
        
        // FILTRER : Garder seulement STANDALONE et CHILD
        if (!"STANDALONE".equals(accountHierarchy) && !"CHILD".equals(accountHierarchy)) {
            log.debug("Skipping record with accountHierarchy: {}", accountHierarchy);
            return;
        }
        
        // Extraire les valeurs nécessaires
        JsonNode shadowAccountNode = record.get("shadowAccount");
        JsonNode memoSegNode = record.get("memoSeg");
        
        if (shadowAccountNode == null || memoSegNode == null) {
            log.warn("Skipping record missing shadowAccount or memoSeg");
            return;
        }
        
        // Créer le nouveau format pour API Y
        ObjectNode transformedRecord = objectMapper.createObjectNode();
        transformedRecord.put("acc_id", shadowAccountNode.asText());
        transformedRecord.put("qty", memoSegNode.asDouble());
        
        // Optionnel : ajouter d'autres champs si nécessaire
        // transformedRecord.put("hierarchy", accountHierarchy);
        // transformedRecord.put("timestamp", Instant.now().toString());
        
        transformedRecords.add(transformedRecord);
        log.debug("Transformed record: acc_id={}, qty={}", 
                shadowAccountNode.asText(), memoSegNode.asDouble());
    }
}

// ============= 3. EXEMPLE DE DONNEES =============
/*
// EXEMPLE DE REPONSE DE L'API X (ce que tu reçois):
[
  {
    "shadowAccount": "ACC123456",
    "memoSeg": 1500.50,
    "accountHierarchy": "STANDALONE",
    "assetType": "SHADOW_EQUITY",
    "otherField": "value1"
  },
  {
    "shadowAccount": "ACC789012",
    "memoSeg": 2300.00,
    "accountHierarchy": "PARENT",  // <-- Sera filtré
    "assetType": "SHADOW_EQUITY",
    "otherField": "value2"
  },
  {
    "shadowAccount": "ACC345678",
    "memoSeg": 890.25,
    "accountHierarchy": "CHILD",
    "assetType": "SHADOW_EQUITY",
    "otherField": "value3"
  }
]

// RESULTAT APRES PROCESSING (ce qui sera envoyé à API Y):
[
  {
    "acc_id": "ACC123456",
    "qty": 1500.50
  },
  {
    "acc_id": "ACC345678",
    "qty": 890.25
  }
]
*/

// ============= 4. VERSION AVEC VALIDATION ET STATS (OPTIONNEL) =============
// ShadowEquityProcessorWithStats.java
package com.sgcib.position.inventory.task.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import lombok.Getter;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class ShadowEquityProcessorWithStats implements ItemProcessor<String, String> {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Getter
    private final AtomicInteger totalRecords = new AtomicInteger(0);
    
    @Getter
    private final AtomicInteger filteredRecords = new AtomicInteger(0);
    
    @Getter
    private final AtomicInteger standaloneCount = new AtomicInteger(0);
    
    @Getter
    private final AtomicInteger childCount = new AtomicInteger(0);
    
    @Override
    public String process(String jsonContent) throws Exception {
        log.info("Processing Shadow Equity data with statistics");
        
        // Reset counters
        totalRecords.set(0);
        filteredRecords.set(0);
        standaloneCount.set(0);
        childCount.set(0);
        
        JsonNode rootNode = objectMapper.readTree(jsonContent);
        List<ObjectNode> transformedRecords = new ArrayList<>();
        
        if (rootNode.isArray()) {
            for (JsonNode record : rootNode) {
                totalRecords.incrementAndGet();
                processRecordWithStats(record, transformedRecords);
            }
        } else if (rootNode.isObject()) {
            JsonNode dataArray = findDataArray(rootNode);
            if (dataArray != null && dataArray.isArray()) {
                for (JsonNode record : dataArray) {
                    totalRecords.incrementAndGet();
                    processRecordWithStats(record, transformedRecords);
                }
            }
        }
        
        ArrayNode outputArray = objectMapper.createArrayNode();
        transformedRecords.forEach(outputArray::add);
        
        // Log statistics
        log.info("Processing complete - Total: {}, Filtered: {}, Kept: {} (Standalone: {}, Child: {})",
                totalRecords.get(),
                filteredRecords.get(),
                transformedRecords.size(),
                standaloneCount.get(),
                childCount.get());
        
        return objectMapper.writeValueAsString(outputArray);
    }
    
    private JsonNode findDataArray(JsonNode root) {
        // Cherche le tableau dans différents champs possibles
        String[] possibleFields = {"data", "results", "items", "records", "positions"};
        for (String field : possibleFields) {
            JsonNode node = root.get(field);
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return null;
    }
    
    private void processRecordWithStats(JsonNode record, List<ObjectNode> transformedRecords) {
        JsonNode accountHierarchyNode = record.get("accountHierarchy");
        if (accountHierarchyNode == null) {
            filteredRecords.incrementAndGet();
            log.debug("Filtered: missing accountHierarchy");
            return;
        }
        
        String accountHierarchy = accountHierarchyNode.asText();
        
        // Filter logic
        boolean isStandalone = "STANDALONE".equals(accountHierarchy);
        boolean isChild = "CHILD".equals(accountHierarchy);
        
        if (!isStandalone && !isChild) {
            filteredRecords.incrementAndGet();
            log.debug("Filtered: accountHierarchy = {}", accountHierarchy);
            return;
        }
        
        // Validation
        JsonNode shadowAccountNode = record.get("shadowAccount");
        JsonNode memoSegNode = record.get("memoSeg");
        
        if (shadowAccountNode == null || shadowAccountNode.asText().isEmpty()) {
            filteredRecords.incrementAndGet();
            log.warn("Filtered: missing or empty shadowAccount");
            return;
        }
        
        if (memoSegNode == null || !memoSegNode.isNumber()) {
            filteredRecords.incrementAndGet();
            log.warn("Filtered: missing or invalid memoSeg");
            return;
        }
        
        // Transform
        ObjectNode transformedRecord = objectMapper.createObjectNode();
        transformedRecord.put("acc_id", shadowAccountNode.asText());
        transformedRecord.put("qty", memoSegNode.asDouble());
        
        transformedRecords.add(transformedRecord);
        
        // Update stats
        if (isStandalone) {
            standaloneCount.incrementAndGet();
        } else {
            childCount.incrementAndGet();
        }
    }
}

// ============= 5. SI TU VEUX TRAITER EN BATCH (pas tout d'un coup) =============
/*
Si l'API X renvoie BEAUCOUP de données (genre 100k records), 
tu peux modifier le processor pour traiter par batch et écrire 
dans plusieurs fichiers temporaires, puis les merger.

Mais pour la plupart des cas, le processor simple au-dessus suffit.
*/
