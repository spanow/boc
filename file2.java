// ============= 1. APPLICATION.YML =============
/*
# application.yml dans position-inventory-task
spring:
  application:
    name: position-inventory-task
  batch:
    job:
      enabled: true

batch-job:
  name: position-inventory-api-transfer
  flow-type: API_TO_API
  
  source-api:
    enabled: true
    url: ${SOURCE_API_URL:https://api-source.example.com/positions}
    method: GET
    headers:
      Authorization: Bearer ${SOURCE_API_TOKEN}
      Accept: application/json
    query-params:
      page-size: 500
      status: active
    connect-timeout: 30000
    read-timeout: 60000
    retry-count: 3
    retry-delay: 2000
    temp-file-prefix: positions_
    temp-file-suffix: .json
    
  destination-api:
    enabled: true
    url: ${DEST_API_URL:https://api-destination.example.com/import/positions}
    method: POST
    headers:
      Authorization: Bearer ${DEST_API_TOKEN}
      Content-Type: application/json
      X-Client-Id: position-inventory
    connect-timeout: 30000
    read-timeout: 120000
    retry-count: 5
    retry-delay: 3000
    delete-temp-file-after-use: true
*/

// ============= 2. CUSTOM TASKLET WITH TRANSFORMATION =============
// CustomApi2ApiTasklet.java
package com.sgcib.position.inventory.task.tasklet;

import com.sgcib.financing.lib.job.core.service.SourceApiService;
import com.sgcib.financing.lib.job.core.service.DestinationApiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.lang.NonNull;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

@Slf4j
@Component
public class CustomApi2ApiTasklet implements Tasklet {
    
    @Autowired
    private SourceApiService sourceApiService;
    
    @Autowired
    private DestinationApiService destinationApiService;
    
    @Autowired
    private DataTransformer dataTransformer;
    
    @Override
    public RepeatStatus execute(@NonNull StepContribution stepContribution, 
                                @NonNull ChunkContext chunkContext) throws Exception {
        
        log.info("Starting Position Inventory API transfer with transformation");
        
        try {
            // 1. Fetch data from source API
            File tempFile = sourceApiService.fetchData();
            log.info("Data fetched from source API");
            
            // 2. Transform the data
            File transformedFile = dataTransformer.transformData(tempFile);
            log.info("Data transformed successfully");
            
            // 3. Send transformed data to destination
            destinationApiService.sendData(transformedFile);
            log.info("Data sent to destination API");
            
            // Store in context if needed
            chunkContext.getStepContext()
                .getStepExecution()
                .getJobExecution()
                .getExecutionContext()
                .put("records_processed", dataTransformer.getRecordsProcessed());
            
            log.info("Position Inventory API transfer completed. Records processed: {}", 
                    dataTransformer.getRecordsProcessed());
            
        } catch (Exception e) {
            log.error("Error during Position Inventory API transfer", e);
            throw e;
        }
        
        return RepeatStatus.FINISHED;
    }
}

// ============= 3. DATA TRANSFORMER SERVICE =============
// DataTransformer.java
package com.sgcib.position.inventory.task.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Map;

@Slf4j
@Service
public class DataTransformer {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Getter
    private int recordsProcessed = 0;
    
    public File transformData(File sourceFile) throws Exception {
        // Read source data
        String jsonContent = Files.readString(sourceFile.toPath());
        JsonNode rootNode = objectMapper.readTree(jsonContent);
        
        // Transform the data
        JsonNode transformedNode = transformNode(rootNode);
        
        // Create new temp file for transformed data
        Path transformedFile = Files.createTempFile("transformed_positions_", ".json");
        String transformedJson = objectMapper.writeValueAsString(transformedNode);
        Files.write(transformedFile, transformedJson.getBytes(), StandardOpenOption.WRITE);
        
        log.info("Transformation complete. Original file: {}, Transformed file: {}", 
                sourceFile.getName(), transformedFile.getFileName());
        
        return transformedFile.toFile();
    }
    
    private JsonNode transformNode(JsonNode node) {
        if (node.isArray()) {
            ArrayNode arrayNode = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                arrayNode.add(transformNode(item));
                recordsProcessed++;
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
                String transformedFieldName = transformFieldName(fieldName);
                
                // Transform field values
                if (fieldValue.isTextual()) {
                    String text = fieldValue.asText();
                    String transformedText = transformText(text);
                    objectNode.put(transformedFieldName, transformedText);
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
    
    private String transformFieldName(String fieldName) {
        // Transform field names if needed
        return fieldName
            .replace("etudiant", "student")
            .replace("Etudiant", "Student");
    }
    
    private String transformText(String text) {
        // Replace "Etudiant" with "student" in text values
        return text
            .replace("Etudiant", "student")
            .replace("etudiant", "student")
            .replace("ETUDIANT", "STUDENT");
    }
}

// ============= 4. CUSTOM STEP CONFIGURATION =============
// PositionInventoryStepsConfig.java
package com.sgcib.position.inventory.task.config;

import com.sgcib.position.inventory.task.tasklet.CustomApi2ApiTasklet;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PositionInventoryStepsConfig {
    
    @Autowired
    private StepBuilderFactory stepBuilderFactory;
    
    @Autowired
    private CustomApi2ApiTasklet customApi2ApiTasklet;
    
    @Bean
    public Step positionTransferStep() {
        return stepBuilderFactory.get("position_transfer_with_transformation")
                .tasklet(customApi2ApiTasklet)
                .build();
    }
}

// ============= 5. CUSTOM JOB CONFIGURATION =============
// PositionInventoryJobConfig.java
package com.sgcib.position.inventory.task.config;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PositionInventoryJobConfig {
    
    @Autowired
    private JobBuilderFactory jobBuilderFactory;
    
    @Autowired
    @Qualifier("positionTransferStep")
    private Step positionTransferStep;
    
    @Bean
    public Job positionInventoryJob() {
        return jobBuilderFactory.get("position_inventory_api_job")
                .incrementer(new RunIdIncrementer())
                .start(positionTransferStep)
                .build();
    }
}

// ============= 6. MAIN APPLICATION CLASS =============
// PositionInventoryTaskApplication.java
package com.sgcib.position.inventory.task;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@EnableBatchProcessing
@ComponentScan(basePackages = {
    "com.sgcib.position.inventory.task",
    "com.sgcib.financing.lib.job.core"  // Pour scanner les composants du starter
})
public class PositionInventoryTaskApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(PositionInventoryTaskApplication.class, args);
    }
}

// ============= 7. OPTIONAL: SCHEDULER SI TU VEUX =============
// JobScheduler.java
package com.sgcib.position.inventory.task.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

@Slf4j
@Component
@EnableScheduling
public class JobScheduler {
    
    @Autowired
    private JobLauncher jobLauncher;
    
    @Autowired
    private Job positionInventoryJob;
    
    @Scheduled(cron = "${batch.schedule.cron:0 0 2 * * ?}") // 2h du matin tous les jours
    public void runPositionInventoryJob() {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addDate("runDate", new Date())
                    .toJobParameters();
                    
            jobLauncher.run(positionInventoryJob, params);
            log.info("Position Inventory Job started successfully");
            
        } catch (Exception e) {
            log.error("Failed to start Position Inventory Job", e);
        }
    }
}

// ============= 8. POM.XML DEPENDENCIES =============
/*
<dependencies>
    <!-- Ton starter -->
    <dependency>
        <groupId>com.sgcib.financing.lib</groupId>
        <artifactId>job-core-starter</artifactId>
        <version>1.0.0</version>
    </dependency>
    
    <!-- Spring Batch -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-batch</artifactId>
    </dependency>
    
    <!-- Spring Web pour RestTemplate -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    
    <!-- Jackson pour JSON -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
    
    <!-- Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>
*/
