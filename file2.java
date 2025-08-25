// ============= 1. APPLICATION.YML =============
/*
# application.yml 
spring:
  application:
    name: position-inventory-task

batch-job:
  name: position-inventory-api-transfer
  flow-type: API_TO_API
  
  # ICI ON SPECIFIE LE PROCESSOR !
  processor-class: com.sgcib.position.inventory.task.processor.PositionDataProcessor
  
  source-api:
    enabled: true
    url: ${SOURCE_API_URL:https://api-source.example.com/positions}
    method: GET
    headers:
      Authorization: Bearer ${SOURCE_API_TOKEN}
      Accept: application/json
    connect-timeout: 30000
    read-timeout: 60000
    retry-count: 3
    retry-delay: 2000
    temp-file-prefix: positions_
    temp-file-suffix: .json
    
  destination-api:
    enabled: true
    url: ${DEST_API_URL:https://api-destination.example.com/import}
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

// ============= 2. LE PROCESSOR (C'EST TOUT CE DONT TU AS BESOIN!) =============
// PositionDataProcessor.java
package com.sgcib.position.inventory.task.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;

@Slf4j
@Component
public class PositionDataProcessor implements ItemProcessor<String, String> {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String process(String jsonContent) throws Exception {
        log.debug("Processing position data");
        
        // Parse JSON
        JsonNode rootNode = objectMapper.readTree(jsonContent);
        
        // Transform
        JsonNode transformedNode = transformNode(rootNode);
        
        // Return as string
        String result = objectMapper.writeValueAsString(transformedNode);
        log.info("Position data processed successfully");
        
        return result;
    }
    
    private JsonNode transformNode(JsonNode node) {
        if (node.isArray()) {
            ArrayNode arrayNode = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                arrayNode.add(transformNode(item));
            }
            return arrayNode;
        } else if (node.isObject()) {
            ObjectNode objectNode = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String fieldName = field.getKey();
                JsonNode fieldValue = field.getValue();
                
                // Transform field names
                String transformedFieldName = fieldName
                    .replace("etudiant", "student")
                    .replace("Etudiant", "Student");
                
                // Transform values
                if (fieldValue.isTextual()) {
                    String text = fieldValue.asText()
                        .replace("Etudiant", "student")
                        .replace("etudiant", "student")
                        .replace("ETUDIANT", "STUDENT");
                    objectNode.put(transformedFieldName, text);
                } else if (fieldValue.isObject() || fieldValue.isArray()) {
                    objectNode.set(transformedFieldName, transformNode(fieldValue));
                } else {
                    objectNode.set(transformedFieldName, fieldValue);
                }
            }
            return objectNode;
        }
        return node;
    }
}

// ============= 3. MISE À JOUR DU TASKLET GENERIC (dans le starter) =============
// Api2ApiTasklet.java (VERSION AMÉLIORÉE dans ton starter)
package com.sgcib.financing.lib.job.core.tasklet;

import com.sgcib.financing.lib.job.core.service.SourceApiService;
import com.sgcib.financing.lib.job.core.service.DestinationApiService;
import com.sgcib.financing.lib.job.core.config.JobSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Slf4j
public class Api2ApiTasklet implements Tasklet {
    
    @Autowired
    private SourceApiService sourceApiService;
    
    @Autowired
    private DestinationApiService destinationApiService;
    
    @Autowired
    private JobSettings jobSettings;
    
    @Autowired
    private BeanFactory beanFactory;
    
    @Override
    public RepeatStatus execute(@NonNull StepContribution stepContribution, 
                                @NonNull ChunkContext chunkContext) throws Exception {
        
        log.info("Starting API to API transfer");
        
        try {
            // 1. Fetch data from source API
            File tempFile = sourceApiService.fetchData();
            
            // 2. Apply processor if configured
            File fileToSend = tempFile;
            if (jobSettings.getProcessorClass() != null) {
                fileToSend = applyProcessor(tempFile);
            }
            
            // 3. Send to destination API
            destinationApiService.sendData(fileToSend);
            
            log.info("API to API transfer completed successfully");
            
        } catch (Exception e) {
            log.error("Error during API to API transfer", e);
            throw e;
        }
        
        return RepeatStatus.FINISHED;
    }
    
    @SuppressWarnings("unchecked")
    private File applyProcessor(File sourceFile) throws Exception {
        String processorClass = jobSettings.getProcessorClass();
        log.info("Applying processor: {}", processorClass);
        
        // Get processor bean by class name
        Class<?> clazz = Class.forName(processorClass);
        ItemProcessor<String, String> processor = 
            (ItemProcessor<String, String>) beanFactory.getBean(clazz);
        
        // Read source file
        String content = Files.readString(sourceFile.toPath());
        
        // Process content
        String processedContent = processor.process(content);
        
        // Write to new temp file
        Path processedFile = Files.createTempFile("processed_", ".json");
        Files.write(processedFile, processedContent.getBytes(), StandardOpenOption.WRITE);
        
        log.info("Processing complete. Processed file: {}", processedFile.getFileName());
        
        return processedFile.toFile();
    }
}

// ============= 4. MISE À JOUR JobSettings (dans le starter) =============
// JobSettings.java - Ajoute juste cette propriété
package com.sgcib.financing.lib.job.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "batch-job")
public class JobSettings {
    private String name;
    private JobFlowType flowType;
    private SourceApiSettings sourceApi;
    private DestinationApiSettings destinationApi;
    
    // NOUVELLE PROPRIÉTÉ pour le processor
    private String processorClass;
    
    // ... reste du code ...
}

// ============= 5. EXEMPLE DE PROCESSOR AVEC PROFILE (optionnel) =============
// Si tu veux différents processors selon les profiles
package com.sgcib.position.inventory.task.processor;

@Component
@Profile("eom-accounting")
public class EOMAccountingProcessor implements ItemProcessor<String, String> {
    
    @Override
    public String process(String jsonContent) throws Exception {
        // Logique spécifique pour EOM Accounting
        // Par exemple: ajouter des champs comptables, valider les montants, etc.
        return jsonContent;
    }
}

@Component
@Profile("intraday")
public class IntradayProcessor implements ItemProcessor<String, String> {
    
    @Override
    public String process(String jsonContent) throws Exception {
        // Logique spécifique pour Intraday
        // Par exemple: filtrer par date du jour, etc.
        return jsonContent;
    }
}

// ============= 6. SI TU VEUX PAS DE PROCESSOR (pass-through) =============
/*
# application.yml - Sans processor, les données passent directement
batch-job:
  name: position-inventory-api-transfer
  flow-type: API_TO_API
  # processor-class: # Pas de processor = pas de transformation
  
  source-api:
    enabled: true
    url: ...
*/
