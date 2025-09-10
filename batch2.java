// ============= OPTION 1: READER/WRITER GENERIQUES DANS LE STARTER =============

// ApiJsonReader.java (VERSION @Component)
package com.sgcib.financing.lib.job.core.reader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sgcib.financing.lib.job.core.client.SourceApiClient;
import com.sgcib.financing.lib.job.core.config.JobSettings;
import com.sgcib.financing.lib.job.core.model.JsonBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "batch-job.source.api.enabled", havingValue = "true")
public class ApiJsonReader implements ItemStreamReader<JsonBean> {
    
    @Autowired
    private SourceApiClient sourceApiClient;
    
    @Autowired
    private JobSettings jobSettings;
    
    @Value("${batch-job.bean-class}")
    private String beanClassName;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Iterator<JsonBean> dataIterator;
    private boolean dataFetched = false;
    private Class<? extends JsonBean> beanClass;
    
    @PostConstruct
    public void init() throws ClassNotFoundException {
        if (beanClassName != null) {
            this.beanClass = (Class<? extends JsonBean>) Class.forName(beanClassName);
            log.info("ApiJsonReader initialized with bean class: {}", beanClass.getSimpleName());
        }
    }
    
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        log.info("Opening ApiJsonReader");
    }
    
    @Override
    public JsonBean read() throws Exception {
        if (!dataFetched) {
            fetchData();
            dataFetched = true;
        }
        
        if (dataIterator != null && dataIterator.hasNext()) {
            JsonBean item = dataIterator.next();
            log.debug("Read item: {}", item);
            return item;
        }
        
        return null;
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
        
        List<JsonBean> items = parseResponse(response);
        this.dataIterator = items.iterator();
        
        log.info("Fetched {} items from API", items.size());
    }
    
    private List<JsonBean> parseResponse(String jsonResponse) throws Exception {
        List<JsonBean> items = new ArrayList<>();
        
        var rootNode = objectMapper.readTree(jsonResponse);
        
        if (rootNode.isArray()) {
            for (var node : rootNode) {
                JsonBean item = objectMapper.treeToValue(node, beanClass);
                items.add(item);
            }
        } else if (rootNode.has("data") && rootNode.get("data").isArray()) {
            for (var node : rootNode.get("data")) {
                JsonBean item = objectMapper.treeToValue(node, beanClass);
                items.add(item);
            }
        }
        
        return items;
    }
    
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
    }
    
    @Override
    public void close() throws ItemStreamException {
        log.info("Closing ApiJsonReader");
        dataFetched = false;
        dataIterator = null;
    }
}

// ApiJsonWriter.java (VERSION @Component)
package com.sgcib.financing.lib.job.core.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sgcib.financing.lib.job.core.client.DestinationApiClient;
import com.sgcib.financing.lib.job.core.config.JobSettings;
import com.sgcib.financing.lib.job.core.model.JsonBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.*;
import org.springframework.batch.item.support.AbstractItemStreamItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "batch-job.destination.api.enabled", havingValue = "true")
public class ApiJsonWriter extends AbstractItemStreamItemWriter<JsonBean> {
    
    @Autowired
    private DestinationApiClient destinationApiClient;
    
    @Autowired
    private JobSettings jobSettings;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public void write(List<? extends JsonBean> items) throws Exception {
        log.info("Writing {} items to destination API", items.size());
        
        String jsonData = objectMapper.writeValueAsString(items);
        
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
}

// StepsConfiguration.java (MISE À JOUR - pas de @Qualifier)
package com.sgcib.financing.lib.job.core.config.job;

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
            ApiJsonReader reader,  // Injection directe, pas de @Qualifier
            ApiJsonWriter writer) {
        
        var chunkSize = properties.getDestination().getApi().getChunkSize() != null 
            ? properties.getDestination().getApi().getChunkSize() 
            : 100;
        
        SimpleStepBuilder<JsonBean, JsonBean> ssb = stepFactory.get("read_json_write_json")
            .<JsonBean, JsonBean>chunk(chunkSize)
            .reader(reader)
            .writer(writer);
        
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

// ============= OPTION 2: READER/WRITER SPÉCIFIQUES DANS TON MICROSERVICE =============

// PositionApiReader.java (dans ton microservice)
package com.sgcib.position.inventory.task.reader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sgcib.financing.lib.job.core.client.SourceApiClient;
import com.sgcib.financing.lib.job.core.config.JobSettings;
import com.sgcib.position.inventory.task.model.PositionBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;

@Slf4j
@Component  // IMPORTANT !
public class PositionApiReader implements ItemStreamReader<PositionBean> {
    
    @Autowired
    private SourceApiClient sourceApiClient;
    
    @Autowired
    private JobSettings jobSettings;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Iterator<PositionBean> dataIterator;
    private boolean dataFetched = false;
    
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        log.info("Opening PositionApiReader");
    }
    
    @Override
    public PositionBean read() throws Exception {
        if (!dataFetched) {
            fetchData();
            dataFetched = true;
        }
        
        if (dataIterator != null && dataIterator.hasNext()) {
            return dataIterator.next();
        }
        
        return null;
    }
    
    private void fetchData() throws Exception {
        log.info("Fetching positions from source API");
        
        // Request body pour SHADOW_EQUITY
        Map<String, Object> requestBody = Map.of("assetType", "SHADOW_EQUITY");
        String response = sourceApiClient.postData(requestBody);
        
        // Parser la réponse
        List<PositionBean> positions = new ArrayList<>();
        var rootNode = objectMapper.readTree(response);
        
        if (rootNode.isArray()) {
            for (var node : rootNode) {
                PositionBean position = objectMapper.treeToValue(node, PositionBean.class);
                positions.add(position);
            }
        }
        
        this.dataIterator = positions.iterator();
        log.info("Fetched {} positions", positions.size());
    }
    
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
    }
    
    @Override
    public void close() throws ItemStreamException {
        log.info("Closing PositionApiReader");
    }
}

// AccountUpdateApiWriter.java (dans ton microservice)
package com.sgcib.position.inventory.task.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sgcib.financing.lib.job.core.client.DestinationApiClient;
import com.sgcib.position.inventory.task.model.AccountUpdateBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.*;
import org.springframework.batch.item.support.AbstractItemStreamItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.List;

@Slf4j
@Component  // IMPORTANT !
public class AccountUpdateApiWriter extends AbstractItemStreamItemWriter<AccountUpdateBean> {
    
    @Autowired
    private DestinationApiClient destinationApiClient;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public void write(List<? extends AccountUpdateBean> items) throws Exception {
        log.info("Writing {} account updates to destination API", items.size());
        
        String jsonData = objectMapper.writeValueAsString(items);
        String response = destinationApiClient.postData(jsonData);
        
        log.info("Successfully sent {} updates", items.size());
    }
}

// StepsConfiguration dans ton microservice
package com.sgcib.position.inventory.task.config;

import com.sgcib.position.inventory.task.model.PositionBean;
import com.sgcib.position.inventory.task.model.AccountUpdateBean;
import com.sgcib.position.inventory.task.processor.ShadowEquityProcessor;
import com.sgcib.position.inventory.task.reader.PositionApiReader;
import com.sgcib.position.inventory.task.writer.AccountUpdateApiWriter;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PositionInventoryStepsConfig {
    
    @Autowired
    private StepBuilderFactory stepFactory;
    
    @Autowired
    private PositionApiReader reader;  // Injecté car c'est un @Component
    
    @Autowired
    private AccountUpdateApiWriter writer;  // Injecté car c'est un @Component
    
    @Autowired
    private ShadowEquityProcessor processor;  // Injecté car c'est un @Component
    
    @Bean
    public Step shadowEquityProcessingStep() {
        return stepFactory.get("shadow_equity_processing")
                .<PositionBean, AccountUpdateBean>chunk(50)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .build();
    }
}
