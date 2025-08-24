// ==============================
// API2API – Add-on léger
// ==============================
// Objectif :
// - Appeler une API Source (GET/POST + query params + body)
// - Écrire les réponses sur disque en fichiers temporaires (chunkés) pour éviter la RAM
// - (Optionnel) un step de transformation peut lire/écrire ces fichiers
// - Envoyer ensuite ces fichiers vers l'API Destination (POST ou GET si besoin)
// - Nettoyer les fichiers temporaires
// - Gérer pagination, throttling, retries, timeouts, auth simple (API key / Basic / OAuth2 client_credentials)
// - Minimal en nombre de classes, plug & play avec ton starter
//
// Arbo (6 classes + 1 enum + 1 listener):
// 1) settings/Api2ApiSettings.java
// 2) http/ApiHttpClient.java
// 3) tasklet/FetchSourceApiTasklet.java
// 4) tasklet/SendToDestinationTasklet.java
// 5) tasklet/PurgeTempFilesTasklet.java
// 6) config/ApiToApiConfiguration.java (flow + steps)
// 7) config/JobFlowType.java (ajout API_TO_API)
// 8) config/CleanupOnFailureListener.java (sécurité nettoyage si échec)
// + snippet YAML d'exemple en bas

// =========================================================
// 1) settings/Api2ApiSettings.java
// =========================================================
package com.sgcib.financing.lib.job.core.config.api2api.settings;

import io.micrometer.common.util.StringUtils;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "batch-job.api2api")
public class Api2ApiSettings {
    private boolean enabled;
    private EndpointSettings source; // obligatoire si enabled
    private EndpointSettings destination; // obligatoire si enabled
    private TempFilesSettings tempFiles = new TempFilesSettings();

    private int maxRetries = 3;              // retries http
    private long retryBackoffMs = 1000;      // 1s
    private long throttleMs = 0;             // repose entre calls
    private int connectTimeoutMs = 10000;    // 10s
    private int readTimeoutMs = 60000;       // 60s

    public void afterPropertiesSet(){
        if(!enabled) return;
        Assert.notNull(source, "api2api.source settings required");
        Assert.notNull(destination, "api2api.destination settings required");
        source.afterPropertiesSet();
        destination.afterPropertiesSet();
        tempFiles.afterPropertiesSet();
    }

    @Getter
    @Setter
    public static class EndpointSettings {
        private String url;                   // e.g. https://api.example.com/items
        private HttpMethod method = HttpMethod.GET;
        private Map<String, String> headers;  // ex: Authorization, Accept, Idempotency-Key...
        private Map<String, String> query;    // ex: page, pageSize, fromDate
        private String bodyTemplate;          // JSON string avec placeholders simples ${...}
        private AuthSettings auth = new AuthSettings();
        private PaginationSettings pagination = new PaginationSettings(); // côté source seulement (ignoré côté dest)
        private String contentType = "application/json"; // pour POST

        public void afterPropertiesSet(){
            Assert.isTrue(StringUtils.isNotBlank(url), "url is required");
            Assert.notNull(method, "http method is required");
            auth.afterPropertiesSet();
            pagination.afterPropertiesSet();
        }
    }

    @Getter
    @Setter
    public static class AuthSettings {
        public enum Type { NONE, BASIC, API_KEY_HEADER, OAUTH2_CLIENT_CREDENTIALS }
        private Type type = Type.NONE;

        // BASIC
        private String username;
        private String password;

        // API KEY HEADER
        private String apiKeyHeaderName;  // ex: X-API-Key
        private String apiKey;            // ex: ${MY_SECRET}

        // OAUTH2 CLIENT CREDENTIALS
        private String tokenUrl;
        private String clientId;
        private String clientSecret;
        private String scope;             // optionnel
        private String audience;          // optionnel

        public void afterPropertiesSet(){
            if(type == Type.BASIC){
                Assert.isTrue(StringUtils.isNotBlank(username), "basic.username required");
                Assert.isTrue(StringUtils.isNotBlank(password), "basic.password required");
            }
            if(type == Type.API_KEY_HEADER){
                Assert.isTrue(StringUtils.isNotBlank(apiKeyHeaderName), "apiKeyHeaderName required");
                Assert.isTrue(StringUtils.isNotBlank(apiKey), "apiKey required");
            }
            if(type == Type.OAUTH2_CLIENT_CREDENTIALS){
                Assert.isTrue(StringUtils.isNotBlank(tokenUrl), "oauth.tokenUrl required");
                Assert.isTrue(StringUtils.isNotBlank(clientId), "oauth.clientId required");
                Assert.isTrue(StringUtils.isNotBlank(clientSecret), "oauth.clientSecret required");
            }
        }
    }

    @Getter
    @Setter
    public static class PaginationSettings {
        public enum Type { NONE, QUERY_PAGE_NUMBER, QUERY_CURSOR, JSON_PATH_NEXT_LINK }
        private Type type = Type.NONE;
        private String pageParam = "page";         // for QUERY_PAGE_NUMBER
        private int startPage = 1;
        private String pageSizeParam = "pageSize"; // optional if API supports
        private Integer pageSize;                   // optional
        private String cursorParam = "cursor";     // for QUERY_CURSOR
        private String jsonPathNextLink;            // for JSON_PATH_NEXT_LINK e.g. $.next
        private String stopWhenJsonPathEmpty;       // optional, e.g. $.data[*]
        public void afterPropertiesSet(){ /* no-op */ }
    }

    @Getter
    @Setter
    public static class TempFilesSettings {
        private String dir = System.getProperty("java.io.tmpdir") + "/api2api";
        private String prefix = "src_";
        private long maxChunkBytes = 10 * 1024 * 1024; // 10MB
        private boolean gzip = false; // compresser les fichiers intermédiaires
        public void afterPropertiesSet(){
            Assert.isTrue(StringUtils.isNotBlank(dir), "temp dir required");
            Assert.isTrue(maxChunkBytes > 0, "maxChunkBytes must be > 0");
        }
    }

    public enum HttpMethod { GET, POST }
}

// =========================================================
// 2) http/ApiHttpClient.java
// =========================================================
package com.sgcib.financing.lib.job.core.config.api2api.http;

import com.sgcib.financing.lib.job.core.config.api2api.settings.Api2ApiSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiHttpClient {

    public InputStream fetchToStream(Api2ApiSettings settings, Api2ApiSettings.EndpointSettings ep, Map<String,String> dynamicQuery, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(settings.getConnectTimeoutMs()))
                .build();

        Map<String,String> q = merge(ep.getQuery(), dynamicQuery);
        String urlWithQuery = appendQuery(ep.getUrl(), q);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(urlWithQuery))
                .timeout(Duration.ofMillis(settings.getReadTimeoutMs()));

        applyAuth(builder, ep);
        applyHeaders(builder, ep);

        if(ep.getMethod() == com.sgcib.financing.lib.job.core.config.api2api.settings.Api2ApiSettings.HttpMethod.POST){
            builder.header(HttpHeaders.CONTENT_TYPE, ep.getContentType());
            builder.POST(HttpRequest.BodyPublishers.ofString(body != null ? body : ""));
        } else {
            builder.GET();
        }

        return executeWithRetryToInputStream(client, builder.build(), settings);
    }

    public int sendBytes(Api2ApiSettings settings, Api2ApiSettings.EndpointSettings ep, byte[] content) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(settings.getConnectTimeoutMs()))
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(ep.getUrl()))
                .timeout(Duration.ofMillis(settings.getReadTimeoutMs()));

        applyAuth(builder, ep);
        applyHeaders(builder, ep);

        if(ep.getMethod() == com.sgcib.financing.lib.job.core.config.api2api.settings.Api2ApiSettings.HttpMethod.POST){
            builder.header(HttpHeaders.CONTENT_TYPE, ep.getContentType());
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(content));
        } else {
            builder.GET(); // GET sans body, cas rare côté destination
        }

        HttpResponse<Void> response = executeWithRetry(client, builder.build(), settings);
        return response.statusCode();
    }

    private void applyHeaders(HttpRequest.Builder builder, Api2ApiSettings.EndpointSettings ep){
        if(ep.getHeaders()!=null){
            ep.getHeaders().forEach(builder::header);
        }
    }

    private void applyAuth(HttpRequest.Builder builder, Api2ApiSettings.EndpointSettings ep){
        var a = ep.getAuth();
        switch (a.getType()){
            case BASIC -> {
                String basic = Base64.getEncoder().encodeToString((a.getUsername()+":"+a.getPassword()).getBytes(StandardCharsets.UTF_8));
                builder.header(HttpHeaders.AUTHORIZATION, "Basic "+basic);
            }
            case API_KEY_HEADER -> builder.header(a.getApiKeyHeaderName(), a.getApiKey());
            case OAUTH2_CLIENT_CREDENTIALS -> {
                // ultra light: fetch token synchronously (à améliorer si usage lourd)
                try {
                    String token = OAuth2TokenClient.fetch(a.getTokenUrl(), a.getClientId(), a.getClientSecret(), a.getScope(), a.getAudience(), (int) Duration.ofMillis(15000).toSeconds());
                    builder.header(HttpHeaders.AUTHORIZATION, "Bearer "+token);
                } catch (IOException e) {
                    throw new RuntimeException("OAuth2 token fetch failed", e);
                }
            }
            case NONE -> {}
        }
    }

    private static Map<String,String> merge(Map<String,String> base, Map<String,String> override){
        java.util.HashMap<String,String> m = new java.util.HashMap<>();
        if(base!=null) m.putAll(base);
        if(override!=null) m.putAll(override);
        return m;
    }

    private static String appendQuery(String url, Map<String,String> q){
        if(q==null || q.isEmpty()) return url;
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?")?"&":"?");
        sb.append(q.entrySet().stream().map(e -> e.getKey()+"="+encode(e.getValue())).reduce((a,b)->a+"&"+b).orElse(""));
        return sb.toString();
    }

    private static String encode(String v){
        try { return java.net.URLEncoder.encode(v, StandardCharsets.UTF_8); } catch (Exception e){ return v; }
    }

    private InputStream executeWithRetryToInputStream(HttpClient client, HttpRequest req, Api2ApiSettings settings) throws Exception {
        int attempt = 0;
        while(true){
            attempt++;
            try {
                HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int code = resp.statusCode();
                if(code>=200 && code<300){
                    if(settings.getThrottleMs()>0) TimeUnit.MILLISECONDS.sleep(settings.getThrottleMs());
                    return resp.body();
                }
                if(code==429 || (code>=500 && code<600)){
                    backoff(settings, attempt);
                    continue;
                }
                throw new IOException("HTTP "+code);
            } catch (Exception ex){
                if(attempt>=settings.getMaxRetries()) throw ex;
                backoff(settings, attempt);
            }
        }
    }

    private HttpResponse<Void> executeWithRetry(HttpClient client, HttpRequest req, Api2ApiSettings settings) throws Exception {
        int attempt = 0;
        while(true){
            attempt++;
            try {
                HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                int code = resp.statusCode();
                if(code>=200 && code<300){
                    if(settings.getThrottleMs()>0) TimeUnit.MILLISECONDS.sleep(settings.getThrottleMs());
                    return resp;
                }
                if(code==429 || (code>=500 && code<600)){
                    backoff(settings, attempt);
                    continue;
                }
                throw new IOException("HTTP "+code);
            } catch (Exception ex){
                if(attempt>=settings.getMaxRetries()) throw ex;
                backoff(settings, attempt);
            }
        }
    }

    private static void backoff(Api2ApiSettings settings, int attempt) throws InterruptedException {
        long sleep = settings.getRetryBackoffMs() * (long)Math.pow(2, Math.max(0, attempt-1));
        TimeUnit.MILLISECONDS.sleep(Math.min(sleep, 15000));
    }
}

// Un mini client token (inlined for brevité)
class OAuth2TokenClient {
    static String fetch(String tokenUrl, String clientId, String clientSecret, String scope, String audience, int timeoutSeconds) throws IOException {
        String body = "grant_type=client_credentials" +
                (scope!=null?"&scope="+java.net.URLEncoder.encode(scope, StandardCharsets.UTF_8):"") +
                (audience!=null?"&audience="+java.net.URLEncoder.encode(audience, StandardCharsets.UTF_8):"");
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(tokenUrl))
                .header("Content-Type","application/x-www-form-urlencoded")
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString((clientId+":"+clientSecret).getBytes(StandardCharsets.UTF_8)))
                .build();
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        try {
            var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if(resp.statusCode()>=200 && resp.statusCode()<300){
                com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
                return json.path("access_token").asText();
            }
            throw new IOException("Token fetch failed: "+resp.statusCode());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
}

// =========================================================
// 3) tasklet/FetchSourceApiTasklet.java
// =========================================================
package com.sgcib.financing.lib.job.core.config.api2api.tasklet;

import com.sgcib.financing.lib.job.core.config.api2api.http.ApiHttpClient;
import com.sgcib.financing.lib.job.core.config.api2api.settings.Api2ApiSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@RequiredArgsConstructor
public class FetchSourceApiTasklet implements Tasklet {

    public static final String CTX_FILES = "api2api.tempFiles";

    private final Api2ApiSettings settings;
    private final ApiHttpClient http;

    @Value("${batch-job.name:api2api}")
    private String jobName;

    @Override
    public RepeatStatus execute(@NonNull StepContribution contribution, @NonNull ChunkContext chunkContext) throws Exception {
        if(!settings.isEnabled()) return RepeatStatus.FINISHED;

        Files.createDirectories(new File(settings.getTempFiles().getDir()).toPath());
        List<String> files = new ArrayList<>();

        // gestion simple de pagination selon settings.source.pagination
        Map<String,String> dynamicQuery = new HashMap<>();
        var pag = settings.getSource().getPagination();
        int page = pag.getStartPage();
        String nextLink = null;
        String cursor = null;

        while(true){
            if(pag.getType()== Api2ApiSettings.PaginationSettings.Type.QUERY_PAGE_NUMBER){
                dynamicQuery.put(pag.getPageParam(), String.valueOf(page));
                if(pag.getPageSize()!=null){
                    dynamicQuery.put(pag.getPageSizeParam(), String.valueOf(pag.getPageSize()));
                }
            } else if(pag.getType()== Api2ApiSettings.PaginationSettings.Type.QUERY_CURSOR && cursor!=null){
                dynamicQuery.put(pag.getCursorParam(), cursor);
            } else if(pag.getType()== Api2ApiSettings.PaginationSettings.Type.JSON_PATH_NEXT_LINK && nextLink!=null){
                // dans ce mode, on écrase l'URL
                settings.getSource().setUrl(nextLink);
            }

            String renderedBody = render(settings.getSource().getBodyTemplate(), Map.of(
                    "runDate", LocalDate.now().toString()
            ));

            try(InputStream is = http.fetchToStream(settings, settings.getSource(), dynamicQuery, renderedBody)){
                // on écrit par chunks maxChunkBytes
                String base = settings.getTempFiles().getDir()+"/"+settings.getTempFiles().getPrefix()+System.currentTimeMillis()+"_"+page;
                long max = settings.getTempFiles().getMaxChunkBytes();
                long written = 0; int part = 0;
                byte[] buf = new byte[64*1024];
                int len;
                FileOutputStream fos = null;
                try {
                    while((len = is.read(buf))!=-1){
                        if(fos==null){
                            fos = new FileOutputStream(base+"_"+part+(settings.getTempFiles().isGzip()?".json.gz":".json"));
                        }
                        fos.write(buf, 0, len);
                        written += len;
                        if(written >= max){
                            fos.flush(); fos.close();
                            files.add(base+"_"+part+(settings.getTempFiles().isGzip()?".json.gz":".json"));
                            part++; written = 0; fos = null;
                        }
                    }
                } finally {
                    if(fos!=null){ fos.flush(); fos.close(); files.add(base+"_"+part+(settings.getTempFiles().isGzip()?".json.gz":".json")); }
                }

                // si pagination JSON_PATH_NEXT_LINK / CURSOR : extraire next
                byte[] preview = Files.readAllBytes(new File(files.get(files.size()-1)).toPath());
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(preview);
                if(pag.getType()== Api2ApiSettings.PaginationSettings.Type.JSON_PATH_NEXT_LINK && pag.getJsonPathNextLink()!=null){
                    nextLink = JsonPathLite.read(node, pag.getJsonPathNextLink());
                }
                if(pag.getType()== Api2ApiSettings.PaginationSettings.Type.QUERY_CURSOR && pag.getCursorParam()!=null){
                    String path = pag.getJsonPathNextLink(); // on réutilise ce champ pour lire le curseur suivant
                    cursor = JsonPathLite.read(node, path);
                }
                if(pag.getStopWhenJsonPathEmpty()!=null){
                    boolean empty = JsonPathLite.isEmpty(node, pag.getStopWhenJsonPathEmpty());
                    if(empty) break;
                }

            }

            if(pag.getType()== Api2ApiSettings.PaginationSettings.Type.NONE) break;
            if(pag.getType()== Api2ApiSettings.PaginationSettings.Type.QUERY_PAGE_NUMBER){
                page++;
            } else if(pag.getType()== Api2ApiSettings.PaginationSettings.Type.QUERY_CURSOR){
                if(cursor==null || cursor.isEmpty()) break;
            } else if(pag.getType()== Api2ApiSettings.PaginationSettings.Type.JSON_PATH_NEXT_LINK){
                if(nextLink==null || nextLink.isEmpty()) break;
            }
        }

        chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext().put(CTX_FILES, files);
        log.info("API2API: {} temp files written: {}", jobName, files.size());
        return RepeatStatus.FINISHED;
    }

    private static String render(String template, Map<String,String> model){
        if(template==null) return null;
        String s = template;
        for(var e: model.entrySet()){
            s = s.replace("${"+e.getKey()+"}", e.getValue());
        }
        return s;
    }
}

// Helpers ultra simples pour JSONPath lite (sans dépendance externe)
class JsonPathLite {
    static String read(com.fasterxml.jackson.databind.JsonNode root, String path){
        if(path==null) return null;
        // support basique: $.a.b.c ou $.a.next
        if(!path.startsWith("$.")) return null;
        String[] keys = path.substring(2).split("\\.");
        com.fasterxml.jackson.databind.JsonNode cur = root;
        for(String k: keys){ cur = cur.path(k); }
        return cur.isMissingNode() || cur.isNull() ? null : cur.asText();
    }
    static boolean isEmpty(com.fasterxml.jackson.databind.JsonNode root, String path){
        if(path==null) return false;
        if(!path.startsWith("$.")) return false;
        String[] keys = path.substring(2).split("\\.");
        com.fasterxml.jackson.databind.JsonNode cur = root;
        for(String k: keys){ cur = cur.path(k); }
        if(cur.isMissingNode() || cur.isNull()) return true;
        if(cur.isArray()) return cur.size()==0;
        if(cur.isObject()) return cur.size()==0;
        if(cur.isTextual()) return cur.asText().isEmpty();
        return false;
    }
}

// =========================================================
// 4) tasklet/SendToDestinationTasklet.java
// =========================================================
package com.sgcib.financing.lib.job.core.config.api2api.tasklet;

import com.sgcib.financing.lib.job.core.config.api2api.http.ApiHttpClient;
import com.sgcib.financing.lib.job.core.config.api2api.settings.Api2ApiSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.lang.NonNull;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class SendToDestinationTasklet implements Tasklet {

    private final Api2ApiSettings settings;
    private final ApiHttpClient http;

    @Override
    public RepeatStatus execute(@NonNull StepContribution contribution, @NonNull ChunkContext chunkContext) throws Exception {
        if(!settings.isEnabled()) return RepeatStatus.FINISHED;
        @SuppressWarnings("unchecked")
        List<String> files = (List<String>) chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext().get(FetchSourceApiTasklet.CTX_FILES);
        if(files==null || files.isEmpty()) return RepeatStatus.FINISHED;

        for(String p: files){
            byte[] content = Files.readAllBytes(new File(p).toPath()); // petit à moyen; sinon stream + chunk dest
            int code = http.sendBytes(settings, settings.getDestination(), content);
            if(code<200 || code>=300){
                throw new RuntimeException("Destination returned HTTP "+code+" for "+p);
            }
            log.info("API2API: sent {} ({} bytes) -> {}", p, content.length, code);
        }
        return RepeatStatus.FINISHED;
    }
}

// =========================================================
// 5) tasklet/PurgeTempFilesTasklet.java
// =========================================================
package com.sgcib.financing.lib.job.core.config.api2api.tasklet;

import com.sgcib.financing.lib.job.core.config.api2api.settings.Api2ApiSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.lang.NonNull;

import java.io.File;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class PurgeTempFilesTasklet implements Tasklet {

    private final Api2ApiSettings settings;

    @Override
    public RepeatStatus execute(@NonNull StepContribution contribution, @NonNull ChunkContext chunkContext) throws Exception {
        @SuppressWarnings("unchecked")
        List<String> files = (List<String>) chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext().get(FetchSourceApiTasklet.CTX_FILES);
        if(files!=null){
            for(String p: files){
                File f = new File(p);
                if(f.exists()){ if(!f.delete()){ log.warn("Failed to delete {}", p);} }
            }
        }
        // clean répertoire vide optionnel
        File dir = new File(settings.getTempFiles().getDir());
        if(dir.isDirectory() && dir.list()!=null && dir.list().length==0){ dir.delete(); }
        log.info("API2API: temp files purged");
        return RepeatStatus.FINISHED;
    }
}

// =========================================================
// 6) config/ApiToApiConfiguration.java (flow+steps)
// =========================================================
package com.sgcib.financing.lib.job.core.config.api2api.config;

import com.sgcib.financing.lib.job.core.config.api2api.http.ApiHttpClient;
import com.sgcib.financing.lib.job.core.config.api2api.settings.Api2ApiSettings;
import com.sgcib.financing.lib.job.core.config.api2api.tasklet.FetchSourceApiTasklet;
import com.sgcib.financing.lib.job.core.config.api2api.tasklet.PurgeTempFilesTasklet;
import com.sgcib.financing.lib.job.core.config.api2api.tasklet.SendToDestinationTasklet;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({Api2ApiSettings.class})
@ConditionalOnProperty(value = "batch-job.flow-type", havingValue = "API_TO_API")
@RequiredArgsConstructor
public class ApiToApiConfiguration {

    private final JobBuilderFactory jobBuilderFactory;
    private final StepBuilderFactory stepFactory;
    private final Api2ApiSettings settings;
    private final ApiHttpClient http;

    @Bean
    public Step fetchSourceStep(){
        return stepFactory.get("api2api_fetch").tasklet(new FetchSourceApiTasklet(settings, http)).build();
    }

    @Bean
    public Step sendDestStep(){
        return stepFactory.get("api2api_send").tasklet(new SendToDestinationTasklet(settings, http)).build();
    }

    @Bean
    public Step purgeStep(){
        return stepFactory.get("api2api_purge").tasklet(new PurgeTempFilesTasklet(settings)).build();
    }

    @Bean
    public Job api2apiJob(){
        return jobBuilderFactory.get("api2api")
                .incrementer(new RunIdIncrementer())
                .start(fetchSourceStep())
                // .next(transformStep()) // si tu veux en rajouter un plus tard
                .next(sendDestStep())
                .next(purgeStep())
                .listener(new CleanupOnFailureListener(settings))
                .build();
    }
}

// =========================================================
// 7) config/JobFlowType.java – ajouter API_TO_API
// =========================================================
package com.sgcib.financing.lib.job.core.config.flows;

public enum JobFlowType {
    DOWNLOAD_AND_AWS,
    API_TO_API
}

// =========================================================
// 8) config/CleanupOnFailureListener.java
// =========================================================
package com.sgcib.financing.lib.job.core.config.api2api.config;

import com.sgcib.financing.lib.job.core.config.api2api.settings.Api2ApiSettings;
import com.sgcib.financing.lib.job.core.config.api2api.tasklet.FetchSourceApiTasklet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

import java.io.File;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class CleanupOnFailureListener implements JobExecutionListener {
    private final Api2ApiSettings settings;

    @Override public void beforeJob(JobExecution jobExecution) { }

    @Override public void afterJob(JobExecution jobExecution) {
        if(jobExecution.getStatus().isUnsuccessful()){
            @SuppressWarnings("unchecked")
            List<String> files = (List<String>) jobExecution.getExecutionContext().get(FetchSourceApiTasklet.CTX_FILES);
            if(files!=null){
                for(String p: files){
                    File f = new File(p);
                    if(f.exists() && !f.delete()){
                        log.warn("Cleanup listener failed to delete {}", p);
                    }
                }
            }
        }
    }
}

// =========================================================
// EXEMPLE application.yml
// =========================================================
/*

batch-job:
  name: api2api-sample
  flow-type: API_TO_API
  api2api:
    enabled: true
    max-retries: 3
    retry-backoff-ms: 1000
    throttle-ms: 0
    connect-timeout-ms: 10000
    read-timeout-ms: 60000

    temp-files:
      dir: /var/tmp/job-core/api2api
      prefix: src_
      max-chunk-bytes: 10485760
      gzip: false

    source:
      url: https://api.source.com/v1/items
      method: GET
      headers:
        Accept: application/json
        X-Correlation-Id: "${spring.application.name}-${random.uuid}"
      query:
        fromDate: "${runDate}"         # variable rendue dans bodyTemplate/render si besoin
      auth:
        type: API_KEY_HEADER
        api-key-header-name: X-API-Key
        api-key: ${SOURCE_API_KEY}
      pagination:
        type: QUERY_PAGE_NUMBER
        page-param: page
        start-page: 1
        page-size-param: pageSize
        page-size: 1000
        stop-when-json-path-empty: $.data

    destination:
      url: https://api.dest.com/v2/bulk
      method: POST
      headers:
        Content-Type: application/json
        X-Idempotency-Key: "${run.id}"
      auth:
        type: BASIC
        username: ${DEST_USER}
        password: ${DEST_PASS}
*/

// =========================================================
// Notes & points d'attention (inclus):
// - Idempotency: tu peux injecter un header d'idempotence avec le runId pour éviter les doublons.
// - Logs: ne pas logger les bodies/headers sensibles (ici on ne log pas le contenu). Ajouter un mask si besoin.
// - TLS: si certificats custom, utiliser HttpClient avec SSLContext custom.
// - GZIP: si responses gzip, HttpClient gère. Pour stocker compressé, active tempFiles.gzip et adapte l'écriture (ici simplifiée, à étendre si nécessaire).
// - Very large files: si trop gros pour Files.readAllBytes côté envoi, remplacer par un streaming en chunk POST (HttpRequest.BodyPublishers.ofInputStream).
// - Transformation: insérer un step entre fetch et send qui lit/écrit les fichiers sans tout charger (Jackson streaming / NDJSON).
// - Retry/Backoff/429: gérés basiquement ici.
// - Metrics/Tracing: brancher micrometer + headers de corrélation.
// - Sécurité: secrets via Vault/ENV; jamais en clair dans logs.
// - Tests: simuler avec WireMock.


Top ! J’ai posé un module **API→API** ultra-léger dans le canvas : job, settings, 3 tasklets (fetch → send → purge), un client HTTP simple (GET/POST + headers + query + body), pagination, retries/backoff, throttling, auth (Basic / API-Key / OAuth2), fichiers temporaires chunkés (pour ménager la RAM), + cleanup en cas d’échec. Il y a aussi un **snippet YAML** prêt à coller.

Tu peux :

* changer `flow-type: API_TO_API` dans ton yml,
* remplir `batch-job.api2api.source` et `destination`,
* lancer le job : ça stream → écrit en `/tmp` → (optionnellement tu ajoutes ton step de conversion) → envoie → purge.

Si tu veux une variante (NDJSON, mapping champ-par-champ, envoi par paquets de N enregistrements, ou streaming direct sans `readAllBytes`) dis-moi, je te l’ajoute direct.

