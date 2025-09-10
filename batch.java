// ============= DANS LE STARTER =============

// ============= 1. INTERFACES DE BASE =============

// JsonBean.java
package com.sgcib.financing.lib.job.core.model;

import java.io.Serializable;

/**
 * Interface marker pour tous les beans JSON
 */
public interface JsonBean extends Serializable {
}

// ProcessorJsonBeanToJsonBean.java
package com.sgcib.financing.lib.job.core.processor;

import com.sgcib.financing.lib.job.core.model.JsonBean;
import org.springframework.batch.item.ItemProcessor;

/**
 * Interface pour les processors qui transforment JsonBean en JsonBean
 */
public interface ProcessorJsonBeanToJsonBean<I extends JsonBean, O extends JsonBean> 
    extends ItemProcessor<I, O> {
}

// ============= 2. READER POUR API SOURCE =============

// ApiJsonReader.java
package com.sgcib.financing.lib.job.core.reader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sgcib.financing.lib.job.core.client.SourceApiClient;
import com.sgcib.financing.lib.job.core.config.JobSettings;
import com.sgcib.financing.lib.job.core.model.JsonBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

@Slf4j
public class ApiJsonReader<T extends JsonBean> implements ItemStreamReader<T> {
    
    @Autowired
    private SourceApiClient sourceApiClient;
    
    @Autowired
    private JobSettings jobSettings;
    
    private final Class<T> beanClass;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Iterator<T> dataIterator;
    private boolean dataFetched = false;
    
    public ApiJsonReader(Class<T> beanClass) {
        this.beanClass = beanClass;
    }
    
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        log.info("Opening ApiJsonReader for {}", beanClass.getSimpleName());
    }
    
    @Override
    public T read() throws Exception {
        if (!dataFetched) {
            fetchData();
            dataFetched = true;
        }
        
        if (dataIterator != null && dataIterator.hasNext()) {
            T item = dataIterator.next();
            log.debug("Read item: {}", item);
            return item;
        }
        
        return null; // Fin du batch
    }
    
    private void fetchData() throws Exception {
        log.info("Fetching data from source API");
        
        String response;
        if ("POST".equals(jobSettings.getSource().getApi().getMethod())) {
            String requestTemplate = jobSettings.getSource().getApi().getRequestBodyTemplate();
            Map<String, Object> requestBody = objectMapper.readValue(requestTemplate, Map.class);
            response = sourceApiClient.postData(requestBody);
        } else {
            response = sourceApiClient.getData();
        }
        
        // Parser la réponse en liste de beans
        List<T> items = parseResponse(response);
        this.dataIterator = items.iterator();
        
        log.info("Fetched {} items from API", items.size());
    }
    
    @SuppressWarnings("unchecked")
    private List<T> parseResponse(String jsonResponse) throws Exception {
        List<T> items = new ArrayList<>();
        
        // Parser selon la structure de la réponse
        var rootNode = objectMapper.readTree(jsonResponse);
        
        if (rootNode.isArray()) {
            for (var node : rootNode) {
                T item = objectMapper.treeToValue(node, beanClass);
                items.add(item);
            }
        } else if (rootNode.has("data") && rootNode.get("data").isArray()) {
            for (var node : rootNode.get("data")) {
                T item = objectMapper.treeToValue(node, beanClass);
                items.add(item);
            }
        }
        
        return items;
    }
    
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // Sauvegarder l'état si nécessaire
    }
    
    @Override
    public void close() throws ItemStreamException {
        log.info("Closing ApiJsonReader");
    }
}

// ============= 3. WRITER POUR API DESTINATION =============

// ApiJsonWriter.java
package com.sgcib.financing.lib.job.core.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sgcib.financing.lib.job.core.client.DestinationApiClient;
import com.sgcib.financing.lib.job.core.config.JobSettings;
import com.sgcib.financing.lib.job.core.model.JsonBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.*;
import org.springframework.batch.item.support.AbstractItemStreamItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;

@Slf4j
public class ApiJsonWriter<T extends JsonBean> extends AbstractItemStreamItemWriter<T> {
    
    @Autowired
    private DestinationApiClient destinationApiClient;
    
    @Autowired
    private JobSettings jobSettings;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public void write(List<? extends T> items) throws Exception {
        log.info("Writing {} items to destination API", items.size());
        
        // Convertir les items en JSON
        String jsonData = objectMapper.writeValueAsString(items);
        
        // Envoyer à l'API destination
        String response;
        String method = jobSettings.getDestination().getApi().getMethod();
        
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
        
        log.info("Successfully sent {} items to destination API", items.size());
    }
    
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        super.open(executionContext);
        log.info("Opening ApiJsonWriter");
    }
    
    @Override
    public void close() throws ItemStreamException {
        super.close();
        log.info("Closing ApiJsonWriter");
    }
}

// ============= 4. STEPS CONFIGURATION MISE À JOUR =============

// StepsConfiguration.java
package com.sgcib.financing.lib.job.core.config.job;

import com.sgcib.financing.lib.job.core.model.JsonBean;
import com.sgcib.financing.lib.job.core.processor.ProcessorJsonBeanToJsonBean;
import com.sgcib.financing.lib.job.core.reader.ApiJsonReader;
import com.sgcib.financing.lib.job.core.writer.ApiJsonWriter;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;

@Configuration
public class StepsConfiguration {
    
    @Autowired
    private StepBuilderFactory stepFactory;
    
    @Autowired
    private JobSettings properties;
    
    @Autowired
    private BeanFactory beanFactory;
    
    @Bean
    @ConditionalOnProperty(value = {"batch-job.source.api.enabled", "batch-job.destination.api.enabled"}, 
                           havingValue = "true")
    public Step readJsonWriteJsonStep(
            @Qualifier("apiJsonReader") ItemStreamReader<? extends JsonBean> reader,
            @Qualifier("apiJsonWriter") ItemStreamWriter<? extends JsonBean> writer) {
        
        var chunkSize = properties.getDestination().getApi().getChunkSize() != null 
            ? properties.getDestination().getApi().getChunkSize() 
            : 100;
        
        SimpleStepBuilder<JsonBean, JsonBean> ssb = stepFactory.get("read_json_write_json")
            .<JsonBean, JsonBean>chunk(chunkSize)
            .reader((ItemStreamReader<JsonBean>) reader)
            .writer((ItemStreamWriter<JsonBean>) writer);
        
        // Ajouter le processor si configuré
        if (properties.getProcessorClass() != null) {
            try {
                Class<?> processorClass = Class.forName(properties.getProcessorClass());
                ItemProcessor<JsonBean, JsonBean> processor = 
                    (ItemProcessor<JsonBean, JsonBean>) beanFactory.getBean(processorClass);
                ssb.processor(processor);
                log.info("Processor {} added to step", processorClass.getSimpleName());
            } catch (Exception e) {
                log.warn("Could not load processor: {}", e.getMessage());
            }
        }
        
        return ssb.build();
    }
}

// ============= 5. FLOW CONFIGURATION MISE À JOUR =============

// Api2ApiFlow.java
package com.sgcib.financing.lib.job.core.config.flows;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilderFactory;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(value = "batch-job.flow-type", havingValue = "API_TO_API")
public class Api2ApiFlow {
    
    @Autowired
    private JobBuilderFactory jobFactory;
    
    @Autowired
    private JobSettings settings;
    
    @Autowired
    @Qualifier("readJsonWriteJsonStep")
    private Step readJsonWriteJsonStep;
    
    @Bean
    public Job api2ApiJob() {
        return jobFactory.get(settings.getName())
                .incrementer(new RunIdIncrementer())
                .start(readJsonWriteJsonStep)
                .build();
    }
}

// ============= DANS TON MICROSERVICE =============

// ============= 6. BEAN MODELS =============

// PositionBean.java (Input)
package com.sgcib.position.inventory.task.model;

import com.sgcib.financing.lib.job.core.model.JsonBean;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class PositionBean implements JsonBean {
    
    @JsonProperty("shadowAccount")
    private String shadowAccount;
    
    @JsonProperty("memoSeg")
    private Double memoSeg;
    
    @JsonProperty("accountHierarchy")
    private String accountHierarchy;
    
    @JsonProperty("assetType")
    private String assetType;
    
    @JsonProperty("currency")
    private String currency;
    
    @JsonProperty("lastUpdate")
    private String lastUpdate;
}

// AccountUpdateBean.java (Output)
package com.sgcib.position.inventory.task.model;

import com.sgcib.financing.lib.job.core.model.JsonBean;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class AccountUpdateBean implements JsonBean {
    
    @JsonProperty("acc_id")
    private String accId;
    
    @JsonProperty("qty")
    private Double qty;
}

// ============= 7. PROCESSOR AVEC BEANS TYPÉS =============

// ShadowEquityProcessor.java
package com.sgcib.position.inventory.task.processor;

import com.sgcib.financing.lib.job.core.processor.ProcessorJsonBeanToJsonBean;
import com.sgcib.position.inventory.task.model.PositionBean;
import com.sgcib.position.inventory.task.model.AccountUpdateBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ShadowEquityProcessor 
    implements ProcessorJsonBeanToJsonBean<PositionBean, AccountUpdateBean> {
    
    @Override
    public AccountUpdateBean process(PositionBean position) throws Exception {
        // Filtrage
        String hierarchy = position.getAccountHierarchy();
        if (!"STANDALONE".equals(hierarchy) && !"CHILD".equals(hierarchy)) {
            log.debug("Filtering out position with hierarchy: {}", hierarchy);
            return null; // null = filtré dans Spring Batch
        }
        
        // Transformation
        AccountUpdateBean updateBean = new AccountUpdateBean();
        updateBean.setAccId(position.getShadowAccount());
        updateBean.setQty(position.getMemoSeg());
        
        log.debug("Transformed: {} -> acc_id={}, qty={}", 
                 position.getShadowAccount(), 
                 updateBean.getAccId(), 
                 updateBean.getQty());
        
        return updateBean;
    }
}

// ============= 8. CONFIGURATION DES BEANS READER/WRITER =============

// BatchConfiguration.java
package com.sgcib.position.inventory.task.config;

import com.sgcib.financing.lib.job.core.reader.ApiJsonReader;
import com.sgcib.financing.lib.job.core.writer.ApiJsonWriter;
import com.sgcib.position.inventory.task.model.PositionBean;
import com.sgcib.position.inventory.task.model.AccountUpdateBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BatchConfiguration {
    
    @Bean
    public ApiJsonReader<PositionBean> apiJsonReader() {
        return new ApiJsonReader<>(PositionBean.class);
    }
    
    @Bean
    public ApiJsonWriter<AccountUpdateBean> apiJsonWriter() {
        return new ApiJsonWriter<>();
    }
}

// ============= 9. APPLICATION.YML =============
/*
batch-job:
  name: shadow-equity-transfer
  flow-type: API_TO_API
  processor-class: com.sgcib.position.inventory.task.processor.ShadowEquityProcessor
  bean-class: com.sgcib.position.inventory.task.model.PositionBean  # Input bean
  
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
      chunk-size: 50  # Process par batch de 50
*/
