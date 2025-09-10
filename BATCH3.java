// ============= DANS LE STARTER =============

// 1. Interface JSONBean.java (dans le starter)
package com.sgcib.financing.lib.job.core.model;

public interface JSONBean {
    // Interface marker pour tous les beans JSON
}

// 2. Interface ProcessorJsonBeanToJsonBean.java (dans le starter)
package com.sgcib.financing.lib.job.core.processor;

import com.sgcib.financing.lib.job.core.model.JSONBean;
import org.springframework.batch.item.ItemProcessor;

public interface ProcessorJsonBeanToJsonBean extends ItemProcessor<JSONBean, JSONBean> {
    // Interface pour les processors JSONBean to JSONBean
}

// 3. FeignItemReader.java (dans le starter) - Reader qui appelle l'API source
package com.sgcib.financing.lib.job.core.reader;

import com.sgcib.financing.lib.job.core.client.SourceApiClient;
import com.sgcib.financing.lib.job.core.config.JobSettings;
import com.sgcib.financing.lib.job.core.model.JSONBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

@Slf4j
@Component
public class FeignItemReader implements ItemReader<JSONBean> {
    
    @Autowired
    private SourceApiClient sourceApiClient;
    
    @Autowired
    private JobSettings jobSettings;
    
    private ObjectMapper objectMapper = new ObjectMapper();
    private List<JSONBean> items;
    private Iterator<JSONBean> iterator;
    
    @Override
    public JSONBean read() throws Exception {
        if (iterator == null) {
            fetchData();
        }
        
        if (iterator.hasNext()) {
            JSONBean item = iterator.next();
            log.debug("Reading item: {}", item);
            return item;
        }
        
        return null; // End of data
    }
    
    private void fetchData() throws Exception {
        log.info("Fetching data from source API");
        
        String response;
        if ("POST".equals(jobSettings.getSource().getApi().getMethod())) {
            String requestBody = jobSettings.getSource().getApi().getRequestBodyTemplate();
            response = sourceApiClient.postData(requestBody);
        } else {
            response = sourceApiClient.getData();
        }
        
        // Parse response to JSONBeans
        items = parseToJsonBeans(response);
        iterator = items.iterator();
        
        log.info("Fetched {} items", items.size());
    }
    
    private List<JSONBean> parseToJsonBeans(String jsonResponse) throws Exception {
        List<JSONBean> beans = new ArrayList<>();
        JsonNode rootNode = objectMapper.readTree(jsonResponse);
        
        // Get the bean class from config
        String beanClassName = jobSettings.getBeanClass();
        if (beanClassName == null) {
            throw new IllegalStateException("bean-class not configured in settings");
        }
        
        Class<?> beanClass = Class.forName(beanClassName);
        
        if (rootNode.isArray()) {
            for (JsonNode node : rootNode) {
                JSONBean bean = (JSONBean) objectMapper.treeToValue(node, beanClass);
                beans.add(bean);
            }
        } else if (rootNode.isObject()) {
            // Check for data field
            JsonNode dataNode = rootNode.get("data");
            if (dataNode != null && dataNode.isArray()) {
                for (JsonNode node : dataNode) {
                    JSONBean bean = (JSONBean) objectMapper.treeToValue(node, beanClass);
                    beans.add(bean);
                }
            } else {
                // Single object
                JSONBean bean = (JSONBean) objectMapper.treeToValue(rootNode, beanClass);
                beans.add(bean);
            }
        }
        
        return beans;
    }
}

// 4. FeignItemWriter.java (dans le starter) - Writer qui envoie vers l'API destination
package com.sgcib.financing.lib.job.core.writer;

import com.sgcib.financing.lib.job.core.client.DestinationApiClient;
import com.sgcib.financing.lib.job.core.model.JSONBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class FeignItemWriter implements ItemWriter<JSONBean> {
    
    @Autowired
    private DestinationApiClient destinationApiClient;
    
    private ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public void write(List<? extends JSONBean> items) throws Exception {
        log.info("Writing {} items to destination API", items.size());
        
        // Convert to JSON
        String jsonData = objectMapper.writeValueAsString(items);
        
        // Send to destination
        String response = destinationApiClient.postData(jsonData);
        
        log.info("Successfully sent {} items to destination API", items.size());
    }
}

// 5. StepsConfiguration.java (dans le starter) - Updated
package com.sgcib.financing.lib.job.core.config.job;

import com.sgcib.financing.lib.job.core.model.JSONBean;
import com.sgcib.financing.lib.job.core.processor.ProcessorJsonBeanToJsonBean;
import com.sgcib.financing.lib.job.core.reader.FeignItemReader;
import com.sgcib.financing.lib.job.core.writer.FeignItemWriter;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Getter
@AllArgsConstructor
@Configuration
public class StepsConfiguration {
    private final StepBuilderFactory stepFactory;
    private final JobSettings properties;
    private final BeanFactory beanFactory;
    
    @Bean
    @ConditionalOnProperty(value = {"batch-job.source.api.enabled", "batch-job.destination.api.enabled"}, 
                           havingValue = "true")
    public Step readJSONWriteJSONStep(
            FeignItemReader reader,
            FeignItemWriter writer) {
        
        // Get chunk size from config (default 100)
        int chunkSize = properties.getDestination().getApi().getChunkSize() != null ? 
                       properties.getDestination().getApi().getChunkSize() : 100;
        
        var ssb = stepFactory.get("read_JSON_write_JSON")
                .<JSONBean, JSONBean>chunk(chunkSize)
                .reader(reader)
                .writer(writer);
        
        // Add processor if configured
        if (properties.getProcessorClass() != null) {
            try {
                Class<?> processorClass = Class.forName(properties.getProcessorClass());
                ProcessorJsonBeanToJsonBean processor = 
                    (ProcessorJsonBeanToJsonBean) beanFactory.getBean(processorClass);
                ssb.processor(processor);
                log.info("Processor added to step: {}", properties.getProcessorClass());
            } catch (Exception e) {
                log.warn("No processor found or error loading: {}", e.getMessage());
            }
        }
        
        return ssb.build();
    }
}

// 6. JobSettings.java update (dans le starter)
package com.sgcib.financing.lib.job.core.config;

@Getter
@Setter
@ConfigurationProperties(prefix = "batch-job")
public class JobSettings {
    private String name;
    private String flowType;
    private String processorClass;
    private String beanClass;  // NEW: classe du bean JSON
    private SourceSettings source;
    private DestinationSettings destination;
}

// 7. DestinationApiSettings.java update (dans le starter)
@Getter
@Setter
public class DestinationApiSettings {
    // ... existing fields ...
    private Integer chunkSize;  // NEW: pour le batch size
}

// ============= DANS TON MICROSERVICE position-inventory-task =============

// 8. InventoryJSONBean.java - Le bean pour ton API
package com.sgcib.position.inventory.task.model;

import com.sgcib.financing.lib.job.core.model.JSONBean;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class InventoryJSONBean implements JSONBean {
    
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
    
    // Output fields (for transformation)
    @JsonProperty("acc_id")
    private String accId;
    
    @JsonProperty("qty")
    private Double qty;
    
    // Constructor
    public InventoryJSONBean() {}
    
    // Transform method
    public void transform() {
        this.accId = this.shadowAccount;
        this.qty = this.memoSeg;
    }
}

// 9. ShadowEquityProcessor.java - Updated pour JSONBean
package com.sgcib.position.inventory.task.processor;

import com.sgcib.financing.lib.job.core.model.JSONBean;
import com.sgcib.financing.lib.job.core.processor.ProcessorJsonBeanToJsonBean;
import com.sgcib.position.inventory.task.model.InventoryJSONBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ShadowEquityProcessor implements ProcessorJsonBeanToJsonBean {
    
    @Override
    public JSONBean process(JSONBean item) throws Exception {
        if (!(item instanceof InventoryJSONBean)) {
            log.warn("Unexpected bean type: {}", item.getClass());
            return null;
        }
        
        InventoryJSONBean inventory = (InventoryJSONBean) item;
        
        // FILTER: Keep only STANDALONE and CHILD
        String accountHierarchy = inventory.getAccountHierarchy();
        if (!"STANDALONE".equals(accountHierarchy) && !"CHILD".equals(accountHierarchy)) {
            log.debug("Filtering out record with accountHierarchy: {}", accountHierarchy);
            return null;  // Returning null filters out the item
        }
        
        // TRANSFORM
        inventory.transform();
        
        log.debug("Processed: acc_id={}, qty={}", inventory.getAccId(), inventory.getQty());
        
        return inventory;
    }
}

// 10. Api2ApiFlow.java - Updated flow
package com.sgcib.position.inventory.task.config;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
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
    private JobBuilderFactory jobBuilderFactory;
    
    @Autowired
    @Qualifier("readJSONWriteJSONStep")
    private Step readJSONWriteJSONStep;
    
    @Bean
    public Job api2ApiJob() {
        return jobBuilderFactory.get("api2api_job")
                .incrementer(new RunIdIncrementer())
                .start(readJSONWriteJSONStep)
                .build();
    }
}

// ============= APPLICATION.YML =============
/*
batch-job:
  name: shadow-equity-transfer
  flow-type: API_TO_API
  processor-class: com.sgcib.position.inventory.task.processor.ShadowEquityProcessor
  bean-class: com.sgcib.position.inventory.task.model.InventoryJSONBean
  
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
      chunk-size: 50  # Process 50 items at a time
*/
