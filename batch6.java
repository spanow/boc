// ============= DANS LE STARTER =============

// ============= 1. INTERFACES BEAN =============

// JSONBean.java (interface marker)
package com.sgcib.financing.lib.job.core.model;

public interface JSONBean {
    // Interface marker pour tous les beans JSON
}

// ============= 2. ITEM PROCESSOR INTERFACE =============

// JsonToJsonProcessor.java
package com.sgcib.financing.lib.job.core.processor;

import com.sgcib.financing.lib.job.core.model.JSONBean;
import org.springframework.batch.item.ItemProcessor;

public interface JsonToJsonProcessor<I extends JSONBean, O extends JSONBean> extends ItemProcessor<I, O> {
    // Interface pour les processors JSON to JSON
}

// ============= 3. ITEM READER (FEIGN) =============

// ApiItemReader.java
package com.sgcib.financing.lib.job.core.reader;

import com.sgcib.financing.lib.job.core.client.SourceApiClient;
import com.sgcib.financing.lib.job.core.config.JobSettings;
import com.sgcib.financing.lib.job.core.model.JSONBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JavaType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemStreamSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Iterator;
import java.util.Map;

@Slf4j
@Component("apiReader")
public class ApiItemReader<T extends JSONBean> extends ItemStreamSupport implements ItemStreamReader<T> {
    
    @Autowired
    private SourceApiClient sourceApiClient;
    
    @Autowired
    private JobSettings jobSettings;
    
    private ObjectMapper objectMapper = new ObjectMapper();
    private Iterator<T> dataIterator;
    private Class<T> beanClass;
    private boolean dataFetched = false;
    
    @SuppressWarnings("unchecked")
    public void setBeanClass(String className) throws ClassNotFoundException {
        this.beanClass = (Class<T>) Class.forName(className);
    }
    
    @Override
    public T read() throws Exception {
        if (!dataFetched) {
            fetchData();
            dataFetched = true;
        }
        
        if (dataIterator != null && dataIterator.hasNext()) {
            T item = dataIterator.next();
            log.debug("Reading item: {}", item);
            return item;
        }
        
        return null; // End of data
    }
    
    private void fetchData() throws Exception {
        log.info("Fetching data from API");
        
        String response;
        if ("POST".equals(jobSettings.getSource().getApi().getMethod())) {
            String requestTemplate = jobSettings.getSource().getApi().getRequestBodyTemplate();
            Map<String, Object> requestBody = objectMapper.readValue(requestTemplate, Map.class);
            response = sourceApiClient.postData(requestBody);
        } else {
            response = sourceApiClient.getData();
        }
        
        // Parse response to List of beans
        JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, beanClass);
        List<T> items = objectMapper.readValue(response, type);
        
        log.info("Fetched {} items from API", items.size());
        this.dataIterator = items.iterator();
    }
    
    @Override
    public void open(org.springframework.batch.item.ExecutionContext executionContext) {
        super.open(executionContext);
        dataFetched = false;
    }
    
    @Override
    public void close() {
        super.close();
        dataIterator = null;
    }
}

// ============= 4. ITEM WRITER (FEIGN) =============

// ApiItemWriter.java
package com.sgcib.financing.lib.job.core.writer;

import com.sgcib.financing.lib.job.core.client.DestinationApiClient;
import com.sgcib.financing.lib.job.core.config.JobSettings;
import com.sgcib.financing.lib.job.core.model.JSONBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.ItemStreamSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component("apiWriter")
public class ApiItemWriter<T extends JSONBean> extends ItemStreamSupport implements ItemStreamWriter<T> {
    
    @Autowired
    private DestinationApiClient destinationApiClient;
    
    @Autowired
    private JobSettings jobSettings;
    
    private ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public void write(Chunk<? extends T> items) throws Exception {
        log.info("Writing {} items to destination API", items.size());
        
        // Convert items to JSON
        String jsonData = objectMapper.writeValueAsString(items.getItems());
        
        // Send to destination
        String method = jobSettings.getDestination().getApi().getMethod();
        String response;
        
        switch (method) {
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
                throw new IllegalArgumentException("Unsupported method: " + method);
        }
        
        log.info("Successfully wrote batch to destination API");
    }
}

// ============= 5. STEP CONFIGURATION =============

// StepsConfiguration.java (UPDATE)
package com.sgcib.financing.lib.job.core.config.job;

import org.springframework.batch.core.Step;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StepsConfiguration {
    
    @Autowired
    private StepBuilderFactory stepFactory;
    
    @Autowired
    private JobSettings properties;
    
    @Bean
    @ConditionalOnProperty(value = {"batch-job.source.api.enabled", "batch-job.destination.api.enabled"})
    public Step readJsonWriteJsonStep(
            @Qualifier("apiReader") ItemStreamReader<JSONBean> reader,
            @Autowired(required = false) JsonToJsonProcessor processor,
            @Qualifier("apiWriter") ItemStreamWriter<JSONBean> writer) {
        
        int chunkSize = properties.getDestination().getApi().getChunkSize();
        if (chunkSize <= 0) {
            chunkSize = 100; // Default
        }
        
        SimpleStepBuilder<JSONBean, JSONBean> stepBuilder = stepFactory
                .get("read_json_write_json_api")
                .<JSONBean, JSONBean>chunk(chunkSize)
                .reader(reader)
                .writer(writer);
        
        // Add processor if configured
        if (processor != null) {
            log.info("Adding processor to step");
            stepBuilder.processor(processor);
        }
        
        return stepBuilder.build();
    }
}

// ============= 6. JOB FLOW UPDATE =============

// Api2ApiFlow.java
package com.sgcib.financing.lib.job.core.config.flows;

@Configuration
@ConditionalOnProperty(value = "batch-job.flow-type", havingValue = "API_TO_API")
public class Api2ApiFlow extends AbstractJobConfiguration {
    
    @Autowired
    @Qualifier("readJsonWriteJsonStep")
    private Step readJsonWriteJsonStep;
    
    @Bean
    public Job api2ApiJob() {
        return jobFactory.get(settings.getName())
                .listener(jobExecutionListener())
                .incrementer(new RunIdIncrementer())
                .start(readJsonWriteJsonStep)  // Utilise le step au lieu du tasklet
                .build();
    }
}

// ============= 7. SETTINGS UPDATE =============

// DestinationApiSettings.java (ajout chunkSize)
@Getter
@Setter
public class DestinationApiSettings {
    // ... existing fields ...
    private int chunkSize = 100;  // Combien d'items envoyer à la fois
}

// JobSettings.java (ajout beanClass)
@Getter
@Setter
public class JobSettings {
    // ... existing fields ...
    private String beanClass;  // La classe du bean à utiliser
    private String processorClass;  // Le processor
}

// ============= DANS TON MICROSERVICE =============

// ============= 8. INVENTORY BEAN =============

// InventoryPositionBean.java
package com.sgcib.position.inventory.task.model;

import com.sgcib.financing.lib.job.core.model.JSONBean;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class InventoryPositionBean implements JSONBean {
    
    @JsonProperty("shadow_account")
    private String shadowAccount;
    
    @JsonProperty("memo_seg")
    private Double memoSeg;
    
    @JsonProperty("account_hierarchy")
    private String accountHierarchy;
    
    @JsonProperty("asset_type")
    private String assetType;
    
    // Constructeurs
    public InventoryPositionBean() {}
    
    public InventoryPositionBean(String shadowAccount, Double memoSeg, String accountHierarchy) {
        this.shadowAccount = shadowAccount;
        this.memoSeg = memoSeg;
        this.accountHierarchy = accountHierarchy;
    }
}

// TransformedPositionBean.java (pour la destination)
package com.sgcib.position.inventory.task.model;

import com.sgcib.financing.lib.job.core.model.JSONBean;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class TransformedPositionBean implements JSONBean {
    
    @JsonProperty("acc_id")
    private String accId;
    
    @JsonProperty("qty")
    private Double qty;
    
    public TransformedPositionBean() {}
    
    public TransformedPositionBean(String accId, Double qty) {
        this.accId = accId;
        this.qty = qty;
    }
}

// ============= 9. PROCESSOR AVEC BEANS =============

// ShadowEquityProcessor.java (REFACTORÉ)
package com.sgcib.position.inventory.task.processor;

import com.sgcib.financing.lib.job.core.processor.JsonToJsonProcessor;
import com.sgcib.position.inventory.task.model.InventoryPositionBean;
import com.sgcib.position.inventory.task.model.TransformedPositionBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ShadowEquityProcessor implements JsonToJsonProcessor<InventoryPositionBean, TransformedPositionBean> {
    
    @Override
    public TransformedPositionBean process(InventoryPositionBean item) throws Exception {
        // FILTRAGE
        if (!"STANDALONE".equals(item.getAccountHierarchy()) && 
            !"CHILD".equals(item.getAccountHierarchy())) {
            log.debug("Filtering out item with hierarchy: {}", item.getAccountHierarchy());
            return null;  // null = filtered out in Spring Batch
        }
        
        // TRANSFORMATION
        TransformedPositionBean transformed = new TransformedPositionBean();
        transformed.setAccId(item.getShadowAccount());
        transformed.setQty(item.getMemoSeg());
        
        log.debug("Transformed: {} -> acc_id={}, qty={}", 
                 item.getShadowAccount(), transformed.getAccId(), transformed.getQty());
        
        return transformed;
    }
}

// ============= 10. APPLICATION.YML =============
/*
batch-job:
  name: shadow-equity-transfer
  flow-type: API_TO_API
  
  # Bean classes
  bean-class: com.sgcib.position.inventory.task.model.InventoryPositionBean
  processor-class: com.sgcib.position.inventory.task.processor.ShadowEquityProcessor
  
  source:
    api:
      enabled: true
      url: https://api-prime-financing.socgen.com/v1/positions/search
      method: POST
      request-body-template: '{"assetType": "SHADOW_EQUITY"}'
      
  destination:
    api:
      enabled: true
      url: https://api-prime-financing.socgen.com/v1/accounts/update
      method: POST
      chunk-size: 50  # Envoyer par batch de 50
*/
