// ============= 1. FEIGN CLIENTS (dans le starter) =============

// SourceApiClient.java
package com.sgcib.financing.lib.job.core.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@FeignClient(name = "SourceApiClient", url = "${batch-job.source.api.url}")
@ConditionalOnProperty(name = "batch-job.source.api.enabled", havingValue = "true")
public interface SourceApiClient {
    
    @PostMapping
    String postData(@RequestBody String request);
    
    @GetMapping
    String getData();
}

// DestinationApiClient.java
package com.sgcib.financing.lib.job.core.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@FeignClient(name = "DestinationApiClient", url = "${batch-job.destination.api.url}")
@ConditionalOnProperty(name = "batch-job.destination.api.enabled", havingValue = "true")
public interface DestinationApiClient {
    
    @PostMapping
    String postData(@RequestBody String data);
    
    @PutMapping
    String putData(@RequestBody String data);
    
    @PatchMapping
    String patchData(@RequestBody String data);
}

// ============= 2. SERVICES 100% FEIGN (dans le starter) =============

// SourceApiService.java
package com.sgcib.financing.lib.job.core.service;

import com.sgcib.financing.lib.job.core.client.SourceApiClient;
import com.sgcib.financing.lib.job.core.config.JobSettings;
import com.sgcib.financing.lib.job.core.config.api.SourceApiSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Slf4j
@Component
@ConditionalOnProperty(name = "batch-job.source.api.enabled", havingValue = "true")
public class SourceApiService {
    
    @Autowired
    private SourceApiClient sourceApiClient;
    
    @Autowired
    private JobSettings jobSettings;
    
    public File fetchData() throws IOException {
        SourceApiSettings sourceApi = jobSettings.getSource().getApi();
        log.info("Fetching data from source API using Feign");
        
        try {
            String response;
            
            if ("POST".equals(sourceApi.getMethod())) {
                String requestBody = sourceApi.getRequestBodyTemplate();
                log.debug("POST request with body: {}", requestBody);
                response = sourceApiClient.postData(requestBody);
            } else {
                log.debug("GET request");
                response = sourceApiClient.getData();
            }
            
            log.debug("Received response: {} characters", response.length());
            
            // Save to temp file
            Path tempFile = Files.createTempFile(
                sourceApi.getTempFilePrefix(),
                sourceApi.getTempFileSuffix()
            );
            
            Files.write(tempFile, response.getBytes(), StandardOpenOption.WRITE);
            log.info("Source API response saved to: {}", tempFile.toAbsolutePath());
            
            return tempFile.toFile();
            
        } catch (Exception e) {
            log.error("Error fetching data from source API", e);
            throw new IOException("Failed to fetch data from source API", e);
        }
    }
}

// DestinationApiService.java
package com.sgcib.financing.lib.job.core.service;

import com.sgcib.financing.lib.job.core.client.DestinationApiClient;
import com.sgcib.financing.lib.job.core.config.JobSettings;
import com.sgcib.financing.lib.job.core.config.api.DestinationApiSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;

@Slf4j
@Component
@ConditionalOnProperty(name = "batch-job.destination.api.enabled", havingValue = "true")
public class DestinationApiService {
    
    @Autowired
    private DestinationApiClient destinationApiClient;
    
    @Autowired
    private JobSettings jobSettings;
    
    public void sendData(File tempFile) throws IOException {
        DestinationApiSettings destinationApi = jobSettings.getDestination().getApi();
        log.info("Sending data to destination API using Feign");
        
        try {
            String jsonContent = Files.readString(tempFile.toPath());
            log.debug("Sending {} characters", jsonContent.length());
            
            String response;
            String method = destinationApi.getMethod();
            
            switch (method) {
                case "POST":
                    response = destinationApiClient.postData(jsonContent);
                    break;
                case "PUT":
                    response = destinationApiClient.putData(jsonContent);
                    break;
                case "PATCH":
                    response = destinationApiClient.patchData(jsonContent);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported method: " + method);
            }
            
            log.info("Data sent successfully to destination API");
            log.debug("Response: {}", response);
            
            // Clean up temp file
            if (destinationApi.isDeleteTempFileAfterUse()) {
                boolean deleted = tempFile.delete();
                log.info("Temp file deleted: {}", deleted);
            }
            
        } catch (Exception e) {
            log.error("Error sending data to destination API", e);
            throw new IOException("Failed to send data to destination API", e);
        }
    }
}

// ============= 3. ENABLE FEIGN DANS LE STARTER =============

// JobCoreStarterConfiguration.java (dans le starter)
package com.sgcib.financing.lib.job.core.config;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

@Configuration
@ConditionalOnClass(name = "org.springframework.cloud.openfeign.FeignClient")
@EnableFeignClients(basePackages = "com.sgcib.financing.lib.job.core.client")
public class JobCoreStarterConfiguration {
    // Auto-configuration du starter avec Feign
}

// ============= 4. POM.XML DU STARTER =============
/*
<!-- Dans le pom.xml du job-core-starter -->
<dependencies>
    <!-- Feign -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-openfeign</artifactId>
    </dependency>
    
    <!-- Spring Batch -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-batch</artifactId>
    </dependency>
    
    <!-- Autres dépendances... -->
</dependencies>
*/

// ============= 5. DANS TON MICROSERVICE =============

// PositionInventoryTaskApplication.java
package com.sgcib.position.inventory.task;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;

@SpringBootApplication
@EnableBatchProcessing
// Pas besoin de @EnableFeignClients, c'est dans le starter !
public class PositionInventoryTaskApplication {
    public static void main(String[] args) {
        SpringApplication.run(PositionInventoryTaskApplication.class, args);
    }
}

// ShadowEquityProcessor.java (reste identique)
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
        
        JsonNode rootNode = objectMapper.readTree(jsonContent);
        List<ObjectNode> transformedRecords = new ArrayList<>();
        
        if (rootNode.isArray()) {
            for (JsonNode record : rootNode) {
                processRecord(record, transformedRecords);
            }
        }
        
        ArrayNode outputArray = objectMapper.createArrayNode();
        transformedRecords.forEach(outputArray::add);
        
        String result = objectMapper.writeValueAsString(outputArray);
        log.info("Processed {} records", transformedRecords.size());
        
        return result;
    }
    
    private void processRecord(JsonNode record, List<ObjectNode> transformedRecords) {
        JsonNode accountHierarchyNode = record.get("accountHierarchy");
        if (accountHierarchyNode == null) return;
        
        String accountHierarchy = accountHierarchyNode.asText();
        
        // Filter: Keep only STANDALONE and CHILD
        if (!"STANDALONE".equals(accountHierarchy) && !"CHILD".equals(accountHierarchy)) {
            return;
        }
        
        JsonNode shadowAccountNode = record.get("shadowAccount");
        JsonNode memoSegNode = record.get("memoSeg");
        
        if (shadowAccountNode == null || memoSegNode == null) return;
        
        ObjectNode transformedRecord = objectMapper.createObjectNode();
        transformedRecord.put("acc_id", shadowAccountNode.asText());
        transformedRecord.put("qty", memoSegNode.asDouble());
        
        transformedRecords.add(transformedRecord);
    }
}

// ============= 6. APPLICATION.YML =============
/*
spring:
  application:
    name: position-inventory-task

# SPB/Feign config (configuré globalement dans votre environnement)
spb:
  feign:
    retrayer:
      enabled: true
      client-names: SourceApiClient,DestinationApiClient
  sgconnect:
    origin:
      scope: api.prime-financing.v1

# Batch config
batch-job:
  name: shadow-equity-transfer
  flow-type: API_TO_API
  processor-class: com.sgcib.position.inventory.task.processor.ShadowEquityProcessor
  
  source:
    api:
      enabled: true
      url: ${SOURCE_API_URL:https://api-prime-financing.socgen.com/v1/positions/search}
      method: POST
      request-body-template: '{"assetType": "SHADOW_EQUITY"}'
      temp-file-prefix: shadow_equity_
      temp-file-suffix: .json
      
  destination:
    api:
      enabled: true
      url: ${DEST_API_URL:https://api-prime-financing.socgen.com/v1/accounts/update}
      method: POST
      delete-temp-file-after-use: true

logging:
  level:
    com.sgcib: DEBUG
    feign: DEBUG
*/
