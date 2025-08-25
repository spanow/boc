// ============= Settings Classes =============

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
    private String url;
    private String method = "GET"; // GET or POST
    private Map<String, String> headers;
    private Map<String, String> queryParams;
    private String requestBodyTemplate; // For POST requests
    private int connectTimeout = 30000; // ms
    private int readTimeout = 60000; // ms
    private int retryCount = 3;
    private long retryDelay = 1000; // ms
    
    public void afterPropertiesSet() {
        Assert.isTrue(StringUtils.isNotBlank(url), "url property is required");
        Assert.isTrue("GET".equals(method) || "POST".equals(method), 
            "method must be GET or POST");
    }
}

// 2. Api2ApiSettings.java
package com.sgcib.financing.lib.job.core.config.api;

import lombok.Getter;
import lombok.Setter;
import org.springframework.util.Assert;

@Getter
@Setter
public class Api2ApiSettings {
    private boolean enabled;
    private ApiEndpointSettings source;
    private ApiEndpointSettings destination;
    private String tempFilePrefix = "api2api_";
    private String tempFileSuffix = ".json";
    private boolean deleteTempFileAfterUse = true;
    
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        
        Assert.notNull(source, "source API settings is required");
        Assert.notNull(destination, "destination API settings is required");
        source.afterPropertiesSet();
        destination.afterPropertiesSet();
    }
}

// ============= Configuration Classes =============

// 3. Api2ApiConfiguration.java
package com.sgcib.financing.lib.job.core.config.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

@Configuration
@ConditionalOnProperty(value = "batch-job.api2api.enabled", havingValue = "true")
public class Api2ApiConfiguration {
    
    @Autowired
    public void setJobSettings(JobSettings settings) {
        Api2ApiSettings api2ApiSettings = settings.getApi2api();
        Assert.notNull(api2ApiSettings, "api2api settings is required in yml");
        api2ApiSettings.afterPropertiesSet();
    }
}

// ============= Service Class =============

// 4. Api2ApiService.java
package com.sgcib.financing.lib.job.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Slf4j
@Component
@ConditionalOnProperty(name = "batch-job.api2api.enabled", havingValue = "true")
public class Api2ApiService {
    
    private Api2ApiSettings api2ApiSettings;
    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;
    
    @Autowired
    public void setJobSettings(JobSettings settings) {
        this.api2ApiSettings = settings.getApi2api();
        Assert.notNull(api2ApiSettings, "API2API settings is required");
        
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        
        // Configure RestTemplate timeouts
        restTemplate.getRequestFactory().setConnectTimeout(api2ApiSettings.getSource().getConnectTimeout());
        restTemplate.getRequestFactory().setReadTimeout(api2ApiSettings.getSource().getReadTimeout());
    }
    
    public File fetchFromSourceApi() throws IOException {
        ApiEndpointSettings source = api2ApiSettings.getSource();
        
        // Build URL with query parameters
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(source.getUrl());
        if (source.getQueryParams() != null) {
            source.getQueryParams().forEach(uriBuilder::queryParam);
        }
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (source.getHeaders() != null) {
            source.getHeaders().forEach(headers::add);
        }
        
        ResponseEntity<String> response = null;
        int attempts = 0;
        
        while (attempts < source.getRetryCount()) {
            try {
                if ("GET".equals(source.getMethod())) {
                    HttpEntity<String> entity = new HttpEntity<>(headers);
                    response = restTemplate.exchange(
                        uriBuilder.toUriString(),
                        HttpMethod.GET,
                        entity,
                        String.class
                    );
                } else if ("POST".equals(source.getMethod())) {
                    HttpEntity<String> entity = new HttpEntity<>(source.getRequestBodyTemplate(), headers);
                    response = restTemplate.exchange(
                        uriBuilder.toUriString(),
                        HttpMethod.POST,
                        entity,
                        String.class
                    );
                }
                
                if (response.getStatusCode().is2xxSuccessful()) {
                    break;
                }
            } catch (Exception e) {
                attempts++;
                log.warn("Attempt {} failed for source API: {}", attempts, e.getMessage());
                if (attempts >= source.getRetryCount()) {
                    throw new RuntimeException("Failed to fetch from source API after " + attempts + " attempts", e);
                }
                try {
                    Thread.sleep(source.getRetryDelay());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        // Save response to temp file
        Path tempFile = Files.createTempFile(
            api2ApiSettings.getTempFilePrefix(),
            api2ApiSettings.getTempFileSuffix()
        );
        
        Files.write(tempFile, response.getBody().getBytes(), StandardOpenOption.WRITE);
        log.info("Source API response saved to temp file: {}", tempFile);
        
        return tempFile.toFile();
    }
    
    public void sendToDestinationApi(File tempFile) throws IOException {
        ApiEndpointSettings destination = api2ApiSettings.getDestination();
        
        // Read data from temp file
        String jsonContent = Files.readString(tempFile.toPath());
        
        // Send to destination
        sendRequest(jsonContent, destination);
        
        // Clean up temp file
        if (api2ApiSettings.isDeleteTempFileAfterUse()) {
            boolean deleted = tempFile.delete();
            log.info("Temp file {} deleted: {}", tempFile.getName(), deleted);
        }
    }
    
    private void sendRequest(String payload, ApiEndpointSettings destination) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(destination.getUrl());
        if (destination.getQueryParams() != null) {
            destination.getQueryParams().forEach(uriBuilder::queryParam);
        }
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (destination.getHeaders() != null) {
            destination.getHeaders().forEach(headers::add);
        }
        
        int attempts = 0;
        while (attempts < destination.getRetryCount()) {
            try {
                ResponseEntity<String> response;
                
                if ("GET".equals(destination.getMethod())) {
                    // For GET, you might want to send data as query params
                    HttpEntity<String> entity = new HttpEntity<>(headers);
                    response = restTemplate.exchange(
                        uriBuilder.toUriString(),
                        HttpMethod.GET,
                        entity,
                        String.class
                    );
                } else {
                    HttpEntity<String> entity = new HttpEntity<>(payload, headers);
                    response = restTemplate.exchange(
                        uriBuilder.toUriString(),
                        HttpMethod.POST,
                        entity,
                        String.class
                    );
                }
                
                if (response.getStatusCode().is2xxSuccessful()) {
                    log.info("Successfully sent data to destination API");
                    return;
                }
            } catch (Exception e) {
                attempts++;
                log.warn("Attempt {} failed for destination API: {}", attempts, e.getMessage());
                if (attempts >= destination.getRetryCount()) {
                    throw new RuntimeException("Failed to send to destination API after " + attempts + " attempts", e);
                }
                try {
                    Thread.sleep(destination.getRetryDelay());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}

// ============= Tasklet Class =============

// 5. Api2ApiTasklet.java
package com.sgcib.financing.lib.job.core.tasklet;

import com.sgcib.financing.lib.job.core.service.Api2ApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;

import java.io.File;

@Slf4j
public class Api2ApiTasklet implements Tasklet {
    
    @Autowired
    private Api2ApiService api2ApiService;
    
    @Override
    public RepeatStatus execute(@NonNull StepContribution stepContribution, 
                                @NonNull ChunkContext chunkContext) throws Exception {
        
        log.info("Starting API2API transfer");
        
        try {
            // Fetch data from source API and save to temp file
            File tempFile = api2ApiService.fetchFromSourceApi();
            
            // Store temp file path in context if needed for next steps
            chunkContext.getStepContext()
                .getStepExecution()
                .getJobExecution()
                .getExecutionContext()
                .put("api2api_temp_file", tempFile.getAbsolutePath());
            
            // Process and send to destination API
            api2ApiService.sendToDestinationApi(tempFile);
            
            log.info("API2API transfer completed successfully");
            
        } catch (Exception e) {
            log.error("Error during API2API transfer", e);
            throw e;
        }
        
        return RepeatStatus.FINISHED;
    }
}

// ============= Flow Configuration =============

// 6. Update JobFlowType.java (add new enum)
package com.sgcib.financing.lib.job.core.config.flows;

public enum JobFlowType {
    DOWNLOAD_AND_AWS,
    API_TO_API
}

// 7. Api2ApiFlow.java
package com.sgcib.financing.lib.job.core.config.flows;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(value = "batch-job.flow-type", havingValue = "API_TO_API")
public class Api2ApiFlow extends AbstractJobConfiguration {
    
    public Api2ApiFlow(JobSettings properties, JobBuilderFactory jobBuilderFactory, 
                       StepsConfiguration stepsConfiguration, BeanFactory beanFactory, 
                       JobEventLogger jobLogger) {
        super(properties, jobBuilderFactory, stepsConfiguration, beanFactory, jobLogger);
    }
    
    @Bean
    public Job api2ApiJob() {
        return jobFactory.get(settings.getName() + "_api2api")
                .listener(jobExecutionListener())
                .incrementer(new RunIdIncrementer())
                .start(steps.api2ApiTransferStep())
                .build();
    }
}

// 8. Update StepsConfiguration.java (add new bean and step)
// Add these methods to your existing StepsConfiguration class:

@Bean
@ConditionalOnProperty("batch-job.api2api.enabled")
public Api2ApiTasklet api2ApiTasklet() {
    return new Api2ApiTasklet();
}

@Bean
@ConditionalOnProperty("batch-job.api2api.enabled")
public Step api2ApiTransferStep() {
    return stepFactory.get("api2api_transfer")
            .allowStartIfComplete(false)
            .tasklet(api2ApiTasklet())
            .build();
}

// ============= Sample application.yml configuration =============
/*
batch-job:
  name: my-api-transfer-job
  flow-type: API_TO_API
  
  api2api:
    enabled: true
    temp-file-prefix: api_transfer_
    temp-file-suffix: .json
    delete-temp-file-after-use: true
    
    source:
      url: https://api.source.com/data
      method: GET
      headers:
        X-API-Key: ${SOURCE_API_KEY}
        Accept: application/json
      query-params:
        limit: 1000
        format: json
      connect-timeout: 30000
      read-timeout: 60000
      retry-count: 3
      retry-delay: 2000
      
    destination:
      url: https://api.destination.com/import
      method: POST
      headers:
        X-API-Key: ${DEST_API_KEY}
        Content-Type: application/json
      connect-timeout: 30000
      read-timeout: 120000
      retry-count: 5
      retry-delay: 3000
*/
