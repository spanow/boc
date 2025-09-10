// ============= DANS LE STARTER (le minimum) =============

// 1. JSONBean.java
package com.sgcib.financing.lib.job.core.model;

public interface JSONBean {
    // Marker interface pour les beans
}

// 2. ProcessorJsonBeanToJsonBean.java
package com.sgcib.financing.lib.job.core.processor;

import com.sgcib.financing.lib.job.core.model.JSONBean;
import org.springframework.batch.item.ItemProcessor;

public interface ProcessorJsonBeanToJsonBean extends ItemProcessor<JSONBean, JSONBean> {
    // Interface pour les processors
}

// 3. JobSettings.java - UPDATE
package com.sgcib.financing.lib.job.core.config;

@Getter
@Setter
@ConfigurationProperties(prefix = "batch-job")
public class JobSettings {
    // ... existing fields ...
    private String processorClass;
    private String beanClass;  // AJOUTE ÇA
    // ... rest ...
}

// ============= DANS TON MICROSERVICE =============

// 4. InventoryJSONBean.java - TON BEAN
package com.sgcib.position.inventory.task.model;

import com.sgcib.financing.lib.job.core.model.JSONBean;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class InventoryJSONBean implements JSONBean {
    
    // Input fields (from source API)
    @JsonProperty("shadowAccount")
    private String shadowAccount;
    
    @JsonProperty("memoSeg")
    private Double memoSeg;
    
    @JsonProperty("accountHierarchy")
    private String accountHierarchy;
    
    @JsonProperty("assetType")
    private String assetType;
    
    // Output fields (for destination API)
    @JsonProperty("acc_id")
    private String accId;
    
    @JsonProperty("qty")
    private Double qty;
}

// 5. ShadowEquityProcessor.java - TON PROCESSOR
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
            return null;
        }
        
        InventoryJSONBean inventory = (InventoryJSONBean) item;
        
        // FILTER: Only STANDALONE and CHILD
        String hierarchy = inventory.getAccountHierarchy();
        if (!"STANDALONE".equals(hierarchy) && !"CHILD".equals(hierarchy)) {
            return null;  // Filter out
        }
        
        // TRANSFORM: Map fields
        inventory.setAccId(inventory.getShadowAccount());
        inventory.setQty(inventory.getMemoSeg());
        
        return inventory;
    }
}

// 6. READER/WRITER TEMPORAIRES (tu les remplaceras)
// TempJsonReader.java - TEMPORAIRE
package com.sgcib.position.inventory.task.reader;

import com.sgcib.financing.lib.job.core.model.JSONBean;
import org.springframework.batch.item.ItemReader;
import org.springframework.stereotype.Component;

@Component("jsonReader")
public class TempJsonReader implements ItemReader<JSONBean> {
    @Override
    public JSONBean read() {
        // TEMPORAIRE - tu implémenteras le vrai reader
        return null;
    }
}

// TempJsonWriter.java - TEMPORAIRE
package com.sgcib.position.inventory.task.writer;

import com.sgcib.financing.lib.job.core.model.JSONBean;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;
import java.util.List;

@Component("jsonWriter")
public class TempJsonWriter implements ItemWriter<JSONBean> {
    @Override
    public void write(List<? extends JSONBean> items) {
        // TEMPORAIRE - tu implémenteras le vrai writer
    }
}

// ============= APPLICATION.YML =============
/*
batch-job:
  name: shadow-equity-transfer
  flow-type: API_TO_API
  bean-class: com.sgcib.position.inventory.task.model.InventoryJSONBean
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
*/
