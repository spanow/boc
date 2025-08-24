// ============= Configuration Classes =============

// 1. ApiEndpointSettings.java
package com.sgcib.financing.lib.job.core.config.api;

import io.micrometer.common.util.StringUtils;
import org.springframework.util.Assert;
import lombok.Getter;
import lombok.Setter;
import java.util.Map;

@Getter
@Setter
public class ApiEndpointSettings {
    private String baseUrl;
    private String endpoint;
    private String method = "GET"; // GET or POST
    private Map<String, String> headers;
    private Map<String, String> queryParams;
    private String requestBodyTemplate; // For POST requests
    private int timeout = 30000; // milliseconds
    private int retryCount = 3;
    private String authType; // BASIC, BEARER, API_KEY
    private String authToken;
    
    public void afterPropertiesSet() {
        Assert.isTrue(StringUtils.isNotBlank(baseUrl), "baseUrl property is required");
        Assert.isTrue(StringUtils.isNotBlank(endpoint), "endpoint property is required");
        Assert.isTrue("GET".equals(method) || "POST".equals(method), "method must be GET or POST");
    }
}

// 2. SourceApiSettings.java
package com.sgcib.financing.lib.job.core.config.api;

import org.springframework.util.Assert;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class SourceApiSettings {
    private boolean enabled;
    private List<ApiEndpointSettings> endpoints;
    private int batchSize = 100; // Nombre d'éléments par fichier temporaire
    private String tempDirectory = "/tmp/api-batch";
    private boolean pagination = false;
    private String paginationParam = "page";
    private int maxPages = 10;
    
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        Assert.isTrue(endpoints != null && !endpoints.isEmpty(), "endpoints property is required");
        endpoints.forEach(ApiEndpointSettings::afterPropertiesSet);
    }
}

// 3. DestinationApiSettings.java
package com.sgcib.financing.lib.job.core.config.api;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DestinationApiSettings {
    private boolean enabled;
    private ApiEndpointSettings endpoint;
    private int batchSize = 50; // Nombre d'éléments à envoyer par requête
    private boolean bulkMode = false; // Si true, envoie plusieurs items dans une seule requête
    
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        Assert.notNull(endpoint, "endpoint property is required");
        endpoint.afterPropertiesSet();
    }
}

// 4. ApiToApiConfiguration.java
package com.sgcib.financing.lib.job.core.config.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.util.Assert;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

@Configuration
@ConditionalOnProperty(value = "batch-job.flow-type", havingValue = "API_TO_API")
public class ApiToApiConfiguration {
    
    @Autowired
    public void setJobSettings(JobSettings settings) {
        SourceApiSettings sourceSettings = settings.getSource().getApi();
        DestinationApiSettings destSettings = settings.getDestination().getApi();
        
        Assert.notNull(sourceSettings, "source api settings is required in yml");
        Assert.notNull(destSettings, "destination api settings is required in yml");
        
        sourceSettings.afterPropertiesSet();
        destSettings.afterPropertiesSet();
    }
    
    @Bean
    public RestTemplate apiRestTemplate() {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(30000);
        factory.setReadTimeout(30000);
        return new RestTemplate(factory);
    }
}

// ============= Service Classes =============

// 5. ApiService.java
package com.sgcib.financing.lib.job.core.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@Slf4j
@Service
@ConditionalOnProperty(value = "batch-job.flow-type", havingValue = "API_TO_API")
public class ApiService {
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private SourceApiSettings sourceSettings;
    private DestinationApiSettings destinationSettings;
    
    @Autowired
    public ApiService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }
    
    @Autowired
    public void setJobSettings(JobSettings settings) {
        this.sourceSettings = settings.getSource().getApi();
        this.destinationSettings = settings.getDestination().getApi();
    }
    
    public List<File> fetchFromSource(ApiEndpointSettings endpoint, int pageNumber) throws IOException {
        List<File> tempFiles = new ArrayList<>();
        String url = buildUrl(endpoint, pageNumber);
        
        HttpHeaders headers = buildHeaders(endpoint);
        HttpEntity<?> entity = buildEntity(endpoint, headers);
        
        try {
            ResponseEntity<String> response = executeRequest(url, endpoint.getMethod(), entity);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                File tempFile = saveToTempFile(response.getBody(), endpoint.getEndpoint(), pageNumber);
                tempFiles.add(tempFile);
                log.info("Fetched data from {} and saved to {}", url, tempFile.getPath());
            }
        } catch (Exception e) {
            log.error("Error fetching from source API: {}", e.getMessage());
            throw new RuntimeException("Failed to fetch from API", e);
        }
        
        return tempFiles;
    }
    
    public void sendToDestination(File dataFile) throws IOException {
        String content = Files.readString(dataFile.toPath());
        List<Map<String, Object>> items = objectMapper.readValue(content, List.class);
        
        ApiEndpointSettings endpoint = destinationSettings.getEndpoint();
        
        if (destinationSettings.isBulkMode()) {
            // Envoie par batch
            for (int i = 0; i < items.size(); i += destinationSettings.getBatchSize()) {
                List<Map<String, Object>> batch = items.subList(i, 
                    Math.min(i + destinationSettings.getBatchSize(), items.size()));
                sendBatch(endpoint, batch);
            }
        } else {
            // Envoie item par item
            for (Map<String, Object> item : items) {
                sendSingleItem(endpoint, item);
            }
        }
    }
    
    private File saveToTempFile(String content, String endpointName, int pageNumber) throws IOException {
        Path tempDir = Paths.get(sourceSettings.getTempDirectory());
        Files.createDirectories(tempDir);
        
        String fileName = String.format("%s_%d_%s.json", 
            endpointName.replace("/", "_"), 
            pageNumber, 
            System.currentTimeMillis());
        
        Path tempFile = tempDir.resolve(fileName);
        Files.writeString(tempFile, content);
        
        return tempFile.toFile();
    }
    
    private void sendBatch(ApiEndpointSettings endpoint, List<Map<String, Object>> batch) {
        String url = endpoint.getBaseUrl() + endpoint.getEndpoint();
        HttpHeaders headers = buildHeaders(endpoint);
        HttpEntity<List<Map<String, Object>>> entity = new HttpEntity<>(batch, headers);
        
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, String.class);
            log.info("Sent batch of {} items to destination", batch.size());
        } catch (Exception e) {
            log.error("Failed to send batch to destination: {}", e.getMessage());
            throw new RuntimeException("Failed to send to destination API", e);
        }
    }
    
    private void sendSingleItem(ApiEndpointSettings endpoint, Map<String, Object> item) {
        String url = endpoint.getBaseUrl() + endpoint.getEndpoint();
        HttpHeaders headers = buildHeaders(endpoint);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(item, headers);
        
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.valueOf(endpoint.getMethod()), entity, String.class);
            log.debug("Sent item to destination");
        } catch (Exception e) {
            log.error("Failed to send item to destination: {}", e.getMessage());
        }
    }
    
    private String buildUrl(ApiEndpointSettings endpoint, int pageNumber) {
        StringBuilder url = new StringBuilder(endpoint.getBaseUrl() + endpoint.getEndpoint());
        
        if (endpoint.getQueryParams() != null && !endpoint.getQueryParams().isEmpty()) {
            url.append("?");
            endpoint.getQueryParams().forEach((k, v) -> 
                url.append(k).append("=").append(v).append("&"));
        }
        
        if (sourceSettings.isPagination()) {
            url.append(sourceSettings.getPaginationParam()).append("=").append(pageNumber);
        }
        
        return url.toString();
    }
    
    private HttpHeaders buildHeaders(ApiEndpointSettings endpoint) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        if (endpoint.getHeaders() != null) {
            endpoint.getHeaders().forEach(headers::add);
        }
        
        if ("BEARER".equals(endpoint.getAuthType())) {
            headers.add("Authorization", "Bearer " + endpoint.getAuthToken());
        } else if ("BASIC".equals(endpoint.getAuthType())) {
            headers.add("Authorization", "Basic " + endpoint.getAuthToken());
        } else if ("API_KEY".equals(endpoint.getAuthType())) {
            headers.add("X-API-Key", endpoint.getAuthToken());
        }
        
        return headers;
    }
    
    private HttpEntity<?> buildEntity(ApiEndpointSettings endpoint, HttpHeaders headers) {
        if ("POST".equals(endpoint.getMethod()) && endpoint.getRequestBodyTemplate() != null) {
            return new HttpEntity<>(endpoint.getRequestBodyTemplate(), headers);
        }
        return new HttpEntity<>(headers);
    }
    
    private ResponseEntity<String> executeRequest(String url, String method, HttpEntity<?> entity) {
        return restTemplate.exchange(url, HttpMethod.valueOf(method), entity, String.class);
    }
    
    public void cleanupTempFiles(List<File> files) {
        for (File file : files) {
            try {
                Files.deleteIfExists(file.toPath());
                log.debug("Deleted temp file: {}", file.getPath());
            } catch (IOException e) {
                log.warn("Failed to delete temp file: {}", file.getPath());
            }
        }
    }
}

// ============= Tasklet Classes =============

// 6. FetchApiTasklet.java
package com.sgcib.financing.lib.job.core.tasklet;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.util.*;

@Slf4j
public class FetchApiTasklet implements Tasklet {
    
    @Autowired
    private ApiService apiService;
    
    @Autowired
    private JobSettings jobSettings;
    
    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        List<File> allTempFiles = new ArrayList<>();
        SourceApiSettings sourceSettings = jobSettings.getSource().getApi();
        
        for (ApiEndpointSettings endpoint : sourceSettings.getEndpoints()) {
            if (sourceSettings.isPagination()) {
                for (int page = 1; page <= sourceSettings.getMaxPages(); page++) {
                    List<File> files = apiService.fetchFromSource(endpoint, page);
                    allTempFiles.addAll(files);
                    
                    // Si la réponse est vide, on arrête la pagination
                    if (files.isEmpty()) {
                        break;
                    }
                }
            } else {
                List<File> files = apiService.fetchFromSource(endpoint, 0);
                allTempFiles.addAll(files);
            }
        }
        
        // Stocke les fichiers temporaires dans le contexte pour les étapes suivantes
        chunkContext.getStepContext().getStepExecution()
            .getJobExecution().getExecutionContext()
            .put("tempFiles", allTempFiles);
        
        log.info("Fetched {} temporary files from source APIs", allTempFiles.size());
        return RepeatStatus.FINISHED;
    }
}

// 7. ProcessApiDataTasklet.java
package com.sgcib.financing.lib.job.core.tasklet;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

@Slf4j
public class ProcessApiDataTasklet implements Tasklet {
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        List<File> tempFiles = (List<File>) chunkContext.getStepContext()
            .getStepExecution().getJobExecution()
            .getExecutionContext().get("tempFiles");
        
        if (tempFiles == null || tempFiles.isEmpty()) {
            log.warn("No temporary files to process");
            return RepeatStatus.FINISHED;
        }
        
        List<File> processedFiles = new ArrayList<>();
        
        for (File file : tempFiles) {
            try {
                // Ici tu peux ajouter ta logique de transformation/processing
                String content = Files.readString(file.toPath());
                List<Map<String, Object>> data = objectMapper.readValue(content, List.class);
                
                // Exemple de processing : filtrage, transformation, enrichissement
                List<Map<String, Object>> processedData = processData(data);
                
                // Sauvegarde des données processées
                String processedContent = objectMapper.writeValueAsString(processedData);
                File processedFile = new File(file.getParent(), "processed_" + file.getName());
                Files.writeString(processedFile.toPath(), processedContent);
                
                processedFiles.add(processedFile);
                log.info("Processed file: {}", file.getName());
            } catch (Exception e) {
                log.error("Error processing file {}: {}", file.getName(), e.getMessage());
            }
        }
        
        // Met à jour le contexte avec les fichiers processés
        chunkContext.getStepContext().getStepExecution()
            .getJobExecution().getExecutionContext()
            .put("processedFiles", processedFiles);
        
        return RepeatStatus.FINISHED;
    }
    
    private List<Map<String, Object>> processData(List<Map<String, Object>> data) {
        // Logique de transformation personnalisée ici
        // Par exemple : filtrage, mapping, enrichissement
        return data.stream()
            .filter(item -> item != null && !item.isEmpty())
            .map(this::transformItem)
            .collect(Collectors.toList());
    }
    
    private Map<String, Object> transformItem(Map<String, Object> item) {
        // Transformation unitaire
        item.put("processedAt", new Date());
        return item;
    }
}

// 8. SendApiTasklet.java
package com.sgcib.financing.lib.job.core.tasklet;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.util.List;

@Slf4j
public class SendApiTasklet implements Tasklet {
    
    @Autowired
    private ApiService apiService;
    
    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        List<File> processedFiles = (List<File>) chunkContext.getStepContext()
            .getStepExecution().getJobExecution()
            .getExecutionContext().get("processedFiles");
        
        if (processedFiles == null || processedFiles.isEmpty()) {
            log.warn("No processed files to send");
            return RepeatStatus.FINISHED;
        }
        
        for (File file : processedFiles) {
            try {
                apiService.sendToDestination(file);
                log.info("Sent data from file: {}", file.getName());
            } catch (Exception e) {
                log.error("Failed to send file {}: {}", file.getName(), e.getMessage());
                // Décide si tu veux continuer ou throw l'exception
            }
        }
        
        return RepeatStatus.FINISHED;
    }
}

// 9. CleanupTempFilesTasklet.java
package com.sgcib.financing.lib.job.core.tasklet;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.util.*;

@Slf4j
public class CleanupTempFilesTasklet implements Tasklet {
    
    @Autowired
    private ApiService apiService;
    
    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        ExecutionContext context = chunkContext.getStepContext()
            .getStepExecution().getJobExecution().getExecutionContext();
        
        List<File> allFiles = new ArrayList<>();
        
        // Récupère tous les fichiers temporaires
        List<File> tempFiles = (List<File>) context.get("tempFiles");
        if (tempFiles != null) {
            allFiles.addAll(tempFiles);
        }
        
        List<File> processedFiles = (List<File>) context.get("processedFiles");
        if (processedFiles != null) {
            allFiles.addAll(processedFiles);
        }
        
        // Nettoie tous les fichiers
        apiService.cleanupTempFiles(allFiles);
        
        log.info("Cleaned up {} temporary files", allFiles.size());
        return RepeatStatus.FINISHED;
    }
}

// ============= Flow Configuration =============

// 10. ApiToApiFlow.java
package com.sgcib.financing.lib.job.core.config.flows;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(value = "batch-job.flow-type", havingValue = "API_TO_API")
public class ApiToApiFlow extends AbstractJobConfiguration {
    
    public ApiToApiFlow(JobSettings properties, JobBuilderFactory jobBuilderFactory, 
                        StepsConfiguration stepsConfiguration, BeanFactory beanFactory, 
                        JobEventLogger jobLogger) {
        super(properties, jobBuilderFactory, stepsConfiguration, beanFactory, jobLogger);
    }
    
    @Bean
    public Job apiToApiJob() {
        return jobFactory.get(settings.getName())
                .listener(jobExecutionListener())
                .incrementer(new RunIdIncrementer())
                .start(steps.fetchApiStep())
                .next(steps.processApiDataStep())
                .next(steps.sendApiStep())
                .next(steps.cleanupTempFilesStep())
                .build();
    }
}

// 11. Ajouts dans StepsConfiguration.java
// À ajouter dans ta classe StepsConfiguration existante :

@Bean
@ConditionalOnProperty(value = "batch-job.flow-type", havingValue = "API_TO_API")
public FetchApiTasklet fetchApiTasklet() {
    return new FetchApiTasklet();
}

@Bean
@ConditionalOnProperty(value = "batch-job.flow-type", havingValue = "API_TO_API")
public ProcessApiDataTasklet processApiDataTasklet() {
    return new ProcessApiDataTasklet();
}

@Bean
@ConditionalOnProperty(value = "batch-job.flow-type", havingValue = "API_TO_API")
public SendApiTasklet sendApiTasklet() {
    return new SendApiTasklet();
}

@Bean
@ConditionalOnProperty(value = "batch-job.flow-type", havingValue = "API_TO_API")
public CleanupTempFilesTasklet cleanupTempFilesTasklet() {
    return new CleanupTempFilesTasklet();
}

@Bean
@ConditionalOnProperty(value = "batch-job.flow-type", havingValue = "API_TO_API")
public Step fetchApiStep() {
    return stepFactory.get("fetch_api_data")
            .allowStartIfComplete(false)
            .tasklet(fetchApiTasklet())
            .build();
}

@Bean
@ConditionalOnProperty(value = "batch-job.flow-type", havingValue = "API_TO_API")
public Step processApiDataStep() {
    return stepFactory.get("process_api_data")
            .allowStartIfComplete(false)
            .tasklet(processApiDataTasklet())
            .build();
}

@Bean
@ConditionalOnProperty(value = "batch-job.flow-type", havingValue = "API_TO_API")
public Step sendApiStep() {
    return stepFactory.get("send_to_destination_api")
            .allowStartIfComplete(false)
            .tasklet(sendApiTasklet())
            .build();
}

@Bean
@ConditionalOnProperty(value = "batch-job.flow-type", havingValue = "API_TO_API")
public Step cleanupTempFilesStep() {
    return stepFactory.get("cleanup_temp_files")
            .allowStartIfComplete(true)
            .tasklet(cleanupTempFilesTasklet())
            .build();
}

// ============= Configuration YAML Example =============
/*
batch-job:
  name: api-to-api-job
  flow-type: API_TO_API
  
  source:
    api:
      enabled: true
      batch-size: 100
      temp-directory: /tmp/api-batch
      pagination: true
      pagination-param: page
      max-pages: 10
      endpoints:
        - base-url: https://api-source.example.com
          endpoint: /api/v1/data
          method: GET
          timeout: 30000
          retry-count: 3
          auth-type: BEARER
          auth-token: ${API_SOURCE_TOKEN}
          headers:
            Accept: application/json
          query-params:
            limit: 100
            include: details
  
  destination:
    api:
      enabled: true
      batch-size: 50
      bulk-mode: true
      endpoint:
        base-url: https://api-destination.example.com
        endpoint: /api/v1/import
        method: POST
        timeout: 60000
        retry-count: 5
        auth-type: API_KEY
        auth-token: ${API_DEST_KEY}
        headers:
          Content-Type: application/json
*/
