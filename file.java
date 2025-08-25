// ============= Settings Classes =============

// 1. SourceApiSettings.java
package com.sgcib.financing.lib.job.core.config.api;

import io.micrometer.common.util.StringUtils;
import org.springframework.util.Assert;
import lombok.Getter;
import lombok.Setter;
import java.util.Map;

@Getter
@Setter
public class SourceApiSettings {
    private boolean enabled;
    private String url;
    private String method = "GET"; // GET or POST
    private Map<String, String> headers;
    private Map<String, String> queryParams;
    private String requestBodyTemplate; // For POST requests
    private int connectTimeout = 30000; // ms
    private int readTimeout = 60000; // ms
    private int retryCount = 3;
    private long retryDelay = 1000; // ms
    private String tempFilePrefix = "api_source_";
    private String tempFileSuffix = ".json";
    
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        Assert.isTrue(StringUtils.isNotBlank(url), "source-api url property is required");
        Assert.isTrue("GET".equals(method) || "POST".equals(method), 
            "source-api method must be GET or POST");
    }
}

// 2. DestinationApiSettings.java
package com.sgcib.financing.lib.job.core.config.api;

import io.micrometer.common.util.StringUtils;
import org.springframework.util.Assert;
import lombok.Getter;
import lombok.Setter;
import java.util.Map;

@Getter
@Setter
public class DestinationApiSettings {
    private boolean enabled;
    private String url;
    private String method = "POST"; // Default POST for destination
    private Map<String, String> headers;
    private Map<String, String> queryParams;
    private int connectTimeout = 30000; // ms
    private int readTimeout = 120000; // ms - longer for destination
    private int retryCount = 5; // More retries for destination
    private long retryDelay = 2000; // ms
    private boolean deleteTempFileAfterUse = true;
    
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        Assert.isTrue(StringUtils.isNotBlank(url), "destination-api url property is required");
        Assert.isTrue("GET".equals(method) || "POST".equals(method) || 
                     "PUT".equals(method) || "PATCH".equals(method), 
            "destination-api method must be GET, POST, PUT or PATCH");
    }
}

// ============= Configuration Classes =============

// 3. SourceApiConfiguration.java
package com.sgcib.financing.lib.job.core.config.api;

import com.sgcib.financing.lib.job.core.config.JobSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

@Configuration
@ConditionalOnProperty(value = "batch-job.source-api.enabled", havingValue = "true")
public class SourceApiConfiguration {
    
    @Autowired
    public void setJobSettings(JobSettings settings) {
        SourceApiSettings sourceApi = settings.getSourceApi();
        Assert.notNull(sourceApi, "source-api settings is required in yml");
        sourceApi.afterPropertiesSet();
    }
}

// 4. DestinationApiConfiguration.java
package com.sgcib.financing.lib.job.core.config.api;

import com.sgcib.financing.lib.job.core.config.JobSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

@Configuration
@ConditionalOnProperty(value = "batch-job.destination-api.enabled", havingValue = "true")
public class DestinationApiConfiguration {
    
    @Autowired
    public void setJobSettings(JobSettings settings) {
        DestinationApiSettings destinationApi = settings.getDestinationApi();
        Assert.notNull(destinationApi, "destination-api settings is required in yml");
        destinationApi.afterPropertiesSet();
    }
}

// ============= Service Classes =============

// 5. SourceApiService.java
package com.sgcib.financing.lib.job.core.service;

import com.sgcib.financing.lib.job.core.config.JobSettings;
import com.sgcib.financing.lib.job.core.config.api.SourceApiSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
@ConditionalOnProperty(name = "batch-job.source-api.enabled", havingValue = "true")
public class SourceApiService {
    
    private SourceApiSettings sourceApi;
    private RestTemplate restTemplate;
    
    @Autowired
    public void setJobSettings(JobSettings settings) {
        this.sourceApi = settings.getSourceApi();
        Assert.notNull(sourceApi, "Source API settings is required");
        
        // Configure RestTemplate with timeouts
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(sourceApi.getConnectTimeout());
        factory.setReadTimeout(sourceApi.getReadTimeout());
        
        this.restTemplate = new RestTemplate(factory);
    }
    
    public File fetchData() throws IOException {
        // Build URL with query parameters
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(sourceApi.getUrl());
        if (sourceApi.getQueryParams() != null) {
            sourceApi.getQueryParams().forEach(uriBuilder::queryParam);
        }
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (sourceApi.getHeaders() != null) {
            sourceApi.getHeaders().forEach(headers::add);
        }
        
        ResponseEntity<String> response = null;
        int attempts = 0;
        
        while (attempts < sourceApi.getRetryCount()) {
            try {
                if ("GET".equals(sourceApi.getMethod())) {
                    HttpEntity<String> entity = new HttpEntity<>(headers);
                    response = restTemplate.exchange(
                        uriBuilder.toUriString(),
                        HttpMethod.GET,
                        entity,
                        String.class
                    );
                } else if ("POST".equals(sourceApi.getMethod())) {
                    HttpEntity<String> entity = new HttpEntity<>(
                        sourceApi.getRequestBodyTemplate(), 
                        headers
                    );
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
                if (attempts >= sourceApi.getRetryCount()) {
                    throw new RuntimeException("Failed to fetch from source API after " + attempts + " attempts", e);
                }
                try {
                    Thread.sleep(sourceApi.getRetryDelay());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        // Save response to temp file
        Path tempFile = Files.createTempFile(
            sourceApi.getTempFilePrefix(),
            sourceApi.getTempFileSuffix()
        );
        
        Files.write(tempFile, response.getBody().getBytes(), StandardOpenOption.WRITE);
        log.info("Source API response saved to temp file: {}", tempFile);
        
        return tempFile.toFile();
    }
}

// 6. DestinationApiService.java
package com.sgcib.financing.lib.job.core.service;

import com.sgcib.financing.lib.job.core.config.JobSettings;
import com.sgcib.financing.lib.job.core.config.api.DestinationApiSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.*;
import java.nio.file.Files;

@Slf4j
@Component
@ConditionalOnProperty(name = "batch-job.destination-api.enabled", havingValue = "true")
public class DestinationApiService {
    
    private DestinationApiSettings destinationApi;
    private RestTemplate restTemplate;
    
    @Autowired
    public void setJobSettings(JobSettings settings) {
        this.destinationApi = settings.getDestinationApi();
        Assert.notNull(destinationApi, "Destination API settings is required");
        
        // Configure RestTemplate with timeouts
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(destinationApi.getConnectTimeout());
        factory.setReadTimeout(destinationApi.getReadTimeout());
        
        this.restTemplate = new RestTemplate(factory);
    }
    
    public void sendData(File tempFile) throws IOException {
        // Read data from temp file
        String jsonContent = Files.readString(tempFile.toPath());
        
        // Send to destination
        sendRequest(jsonContent);
        
        // Clean up temp file
        if (destinationApi.isDeleteTempFileAfterUse()) {
            boolean deleted = tempFile.delete();
            log.info("Temp file {} deleted: {}", tempFile.getName(), deleted);
        }
    }
    
    private void sendRequest(String payload) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(destinationApi.getUrl());
        if (destinationApi.getQueryParams() != null) {
            destinationApi.getQueryParams().forEach(uriBuilder::queryParam);
        }
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (destinationApi.getHeaders() != null) {
            destinationApi.getHeaders().forEach(headers::add);
        }
        
        int attempts = 0;
        while (attempts < destinationApi.getRetryCount()) {
            try {
                ResponseEntity<String> response;
                HttpMethod method = HttpMethod.valueOf(destinationApi.getMethod());
                
                if ("GET".equals(destinationApi.getMethod())) {
                    HttpEntity<String> entity = new HttpEntity<>(headers);
                    response = restTemplate.exchange(
                        uriBuilder.toUriString(),
                        method,
                        entity,
                        String.class
                    );
                } else {
                    // POST, PUT, PATCH
                    HttpEntity<String> entity = new HttpEntity<>(payload, headers);
                    response = restTemplate.exchange(
                        uriBuilder.toUriString(),
                        method,
                        entity,
                        String.class
                    );
                }
                
                if (response.getStatusCode().is2xxSuccessful()) {
                    log.info("Successfully sent data to destination API with status: {}", response.getStatusCode());
                    return;
                }
            } catch (Exception e) {
                attempts++;
                log.warn("Attempt {} failed for destination API: {}", attempts, e.getMessage());
                if (attempts >= destinationApi.getRetryCount()) {
                    throw new RuntimeException("Failed to send to destination API after " + attempts + " attempts", e);
                }
                try {
                    Thread.sleep(destinationApi.getRetryDelay());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}

// ============= Tasklet Class =============

// 7. Api2ApiTasklet.java
package com.sgcib.financing.lib.job.core.tasklet;

import com.sgcib.financing.lib.job.core.service.SourceApiService;
import com.sgcib.financing.lib.job.core.service.DestinationApiService;
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
    private SourceApiService sourceApiService;
    
    @Autowired
    private DestinationApiService destinationApiService;
    
    @Override
    public RepeatStatus execute(@NonNull StepContribution stepContribution, 
                                @NonNull ChunkContext chunkContext) throws Exception {
        
        log.info("Starting API to API transfer");
        
        try {
            // Fetch data from source API and save to temp file
            File tempFile = sourceApiService.fetchData();
            
            // Store temp file path in context if needed for next steps
            chunkContext.getStepContext()
                .getStepExecution()
                .getJobExecution()
                .getExecutionContext()
                .put("api_temp_file", tempFile.getAbsolutePath());
            
            // Send to destination API
            destinationApiService.sendData(tempFile);
            
            log.info("API to API transfer completed successfully");
            
        } catch (Exception e) {
            log.error("Error during API to API transfer", e);
            throw e;
        }
        
        return RepeatStatus.FINISHED;
    }
}

// ============= Flow Configuration =============

// 8. JobFlowType.java
package com.sgcib.financing.lib.job.core.config.flows;

public enum JobFlowType {
    API_TO_API
    // Add other flow types as needed
}

// 9. Api2ApiFlow.java
package com.sgcib.financing.lib.job.core.config.flows;

import com.sgcib.financing.lib.job.core.config.JobSettings;
import com.sgcib.financing.lib.job.core.config.job.StepsConfiguration;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.job.builder.JobBuilderFactory;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.beans.factory.BeanFactory;
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
        return jobFactory.get(settings.getName())
                .listener(jobExecutionListener())
                .incrementer(new RunIdIncrementer())
                .start(steps.api2ApiTransferStep())
                .build();
    }
}

// 10. StepsConfiguration.java (Add these methods to your existing class)
package com.sgcib.financing.lib.job.core.config.job;

// ... existing imports and class declaration ...

@Bean
@ConditionalOnProperty(value = {"batch-job.source-api.enabled", "batch-job.destination-api.enabled"}, 
                       havingValue = "true")
public Api2ApiTasklet api2ApiTasklet() {
    return new Api2ApiTasklet();
}

@Bean
@ConditionalOnProperty(value = {"batch-job.source-api.enabled", "batch-job.destination-api.enabled"}, 
                       havingValue = "true")
public Step api2ApiTransferStep() {
    return stepFactory.get("api2api_transfer")
            .allowStartIfComplete(false)
            .tasklet(api2ApiTasklet())
            .build();
}

// 11. JobSettings.java (Update your existing JobSettings)
package com.sgcib.financing.lib.job.core.config;

import com.sgcib.financing.lib.job.core.config.api.SourceApiSettings;
import com.sgcib.financing.lib.job.core.config.api.DestinationApiSettings;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "batch-job")
public class JobSettings {
    private String name;
    private JobFlowType flowType;
    
    // Direct API settings - no wrapper classes!
    private SourceApiSettings sourceApi;
    private DestinationApiSettings destinationApi;
    
    // ... your other existing properties ...
    
    public void afterPropertiesSet() {
        if (sourceApi != null) {
            sourceApi.afterPropertiesSet();
        }
        if (destinationApi != null) {
            destinationApi.afterPropertiesSet();
        }
    }
}

// ============= Sample application.yml configuration =============
/*
batch-job:
  name: my-api-transfer-job
  flow-type: API_TO_API
  
  source-api:
    enabled: true
    url: https://api.source.com/data
    method: GET
    headers:
      X-API-Key: ${SOURCE_API_KEY}
      Accept: application/json
    query-params:
      limit: 1000
      format: json
    connect-timeout: 30000
    read-timeout: 10      # 10ms si tu veux vraiment
    retry-count: 3
    retry-delay: 1000
    temp-file-prefix: api_source_
    temp-file-suffix: .json
      
  destination-api:
    enabled: true
    url: https://api.destination.com/import
    method: POST
    headers:
      X-API-Key: ${DEST_API_KEY}
      Content-Type: application/json
    connect-timeout: 30000
    read-timeout: 120000
    retry-count: 5
    retry-delay: 2000
    delete-temp-file-after-use: true
*/
