// ============= ApiItemWriter CORRIGÉ (avec List) =============

// ApiItemWriter.java
package com.sgcib.financing.lib.job.core.writer;

import com.sgcib.financing.lib.job.core.client.DestinationApiClient;
import com.sgcib.financing.lib.job.core.config.JobSettings;
import com.sgcib.financing.lib.job.core.model.JSONBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.ItemStreamSupport;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component("apiWriter")
public class ApiItemWriter<T extends JSONBean> extends ItemStreamSupport implements ItemStreamWriter<T> {
    
    @Autowired
    private DestinationApiClient destinationApiClient;
    
    @Autowired
    private JobSettings jobSettings;
    
    private ObjectMapper objectMapper = new ObjectMapper();
    private int totalItemsWritten = 0;
    
    @Override
    public void write(List<? extends T> items) throws Exception {
        if (items == null || items.isEmpty()) {
            log.debug("No items to write");
            return;
        }
        
        log.info("Writing {} items to destination API", items.size());
        
        try {
            // Convert items to JSON
            String jsonData = objectMapper.writeValueAsString(items);
            log.debug("JSON payload size: {} characters", jsonData.length());
            
            // Send to destination based on method
            String method = jobSettings.getDestination().getApi().getMethod();
            String response;
            
            switch (method.toUpperCase()) {
                case "POST":
                    response = destinationApiClient.postData(jsonData);
                    break;
                case "PUT":
                    response = destinationApiClient.putData(jsonData);
                    break;
                case "PATCH":
                    response = destinationApiClient.patchData(jsonData);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported HTTP method: " + method);
            }
            
            totalItemsWritten += items.size();
            log.info("✅ Successfully wrote batch to destination API. Total items written: {}", totalItemsWritten);
            
        } catch (Exception e) {
            log.error("❌ Failed to write items to destination API", e);
            throw new Exception("Failed to write to destination API", e);
        }
    }
    
    @Override
    public void open(ExecutionContext executionContext) {
        super.open(executionContext);
        
        // Restore state if restarting
        if (executionContext.containsKey("totalItemsWritten")) {
            totalItemsWritten = executionContext.getInt("totalItemsWritten");
            log.info("Resuming from previous execution. Items already written: {}", totalItemsWritten);
        }
    }
    
    @Override
    public void update(ExecutionContext executionContext) {
        super.update(executionContext);
        
        // Save state for restart
        executionContext.putInt("totalItemsWritten", totalItemsWritten);
    }
    
    @Override
    public void close() {
        super.close();
        log.info("Writer closed. Total items written: {}", totalItemsWritten);
    }
}

// ============= ALTERNATIVE: Si tu veux gérer les deux versions =============

// UniversalApiItemWriter.java (compatible avec les deux versions)
package com.sgcib.financing.lib.job.core.writer;

import org.springframework.batch.item.ItemWriter;
import java.util.List;

@Slf4j
@Component("universalApiWriter")
public class UniversalApiItemWriter<T extends JSONBean> implements ItemWriter<T> {
    
    @Autowired
    private DestinationApiClient destinationApiClient;
    
    @Autowired
    private JobSettings jobSettings;
    
    private ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public void write(List<? extends T> items) throws Exception {
        // Cette signature marche pour TOUTES les versions de Spring Batch
        writeItems(items);
    }
    
    // Pour Spring Batch 5+ (avec Chunk)
    public void write(org.springframework.batch.item.Chunk<? extends T> chunk) throws Exception {
        writeItems(chunk.getItems());
    }
    
    private void writeItems(List<? extends T> items) throws Exception {
        if (items == null || items.isEmpty()) {
            return;
        }
        
        log.info("Writing {} items", items.size());
        
        String jsonData = objectMapper.writeValueAsString(items);
        String method = jobSettings.getDestination().getApi().getMethod();
        
        switch (method.toUpperCase()) {
            case "POST":
                destinationApiClient.postData(jsonData);
                break;
            case "PUT":
                destinationApiClient.putData(jsonData);
                break;
            case "PATCH":
                destinationApiClient.patchData(jsonData);
                break;
            default:
                throw new IllegalArgumentException("Unsupported method: " + method);
        }
        
        log.info("Successfully wrote {} items", items.size());
    }
}

// ============= Pour info: Les différentes versions =============
/*
Spring Batch 4.x et avant:
- write(List<? extends T> items)

Spring Batch 5.x:
- write(Chunk<? extends T> chunk)
- chunk.getItems() pour récupérer la List

Si tu es sur Spring Boot 2.x → Spring Batch 4.x → utilise List
Si tu es sur Spring Boot 3.x → Spring Batch 5.x → utilise Chunk
*/

// ============= Mise à jour du StepsConfiguration =============

// StepsConfiguration.java
@Configuration
public class StepsConfiguration {
    
    @Bean
    @ConditionalOnProperty(value = {"batch-job.source.api.enabled", "batch-job.destination.api.enabled"})
    public Step readJsonWriteJsonStep(
            @Qualifier("apiReader") ItemStreamReader<JSONBean> reader,
            @Autowired(required = false) JsonToJsonProcessor processor,
            @Qualifier("apiWriter") ItemWriter<JSONBean> writer) {  // ItemWriter au lieu de ItemStreamWriter
        
        int chunkSize = properties.getDestination().getApi().getChunkSize();
        if (chunkSize <= 0) {
            chunkSize = 100;
        }
        
        log.info("Creating step with chunk size: {}", chunkSize);
        
        SimpleStepBuilder<JSONBean, JSONBean> stepBuilder = stepFactory
                .get("read_json_write_json_api")
                .<JSONBean, JSONBean>chunk(chunkSize)
                .reader(reader)
                .writer(writer);  // Accepte ItemWriter
        
        if (processor != null) {
            stepBuilder.processor(processor);
        }
        
        return stepBuilder.build();
    }
}
