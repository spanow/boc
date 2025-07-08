
// 1. Dependencies in pom.xml
/*
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-amqp</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
</dependencies>
*/

// 2. Application Properties (application.yml)
/*
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
    virtual-host: /
    connection-timeout: 60000
    publisher-confirm-type: correlated
    publisher-returns: true
    listener:
      simple:
        acknowledge-mode: manual
        retry:
          enabled: true
          initial-interval: 1000
          max-attempts: 3
          multiplier: 2
*/

// 3. RabbitMQ Configuration
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableRabbit
public class RabbitMQConfig {
    
    // Queue names
    public static final String DIRECT_QUEUE = "direct.queue";
    public static final String TOPIC_QUEUE_1 = "topic.queue.1";
    public static final String TOPIC_QUEUE_2 = "topic.queue.2";
    public static final String FANOUT_QUEUE_1 = "fanout.queue.1";
    public static final String FANOUT_QUEUE_2 = "fanout.queue.2";
    public static final String DLQ = "dead.letter.queue";
    
    // Exchange names
    public static final String DIRECT_EXCHANGE = "direct.exchange";
    public static final String TOPIC_EXCHANGE = "topic.exchange";
    public static final String FANOUT_EXCHANGE = "fanout.exchange";
    public static final String DLX = "dead.letter.exchange";
    
    // Routing keys
    public static final String DIRECT_ROUTING_KEY = "direct.routing.key";
    public static final String TOPIC_ROUTING_KEY_1 = "topic.order.created";
    public static final String TOPIC_ROUTING_KEY_2 = "topic.user.updated";
    
    // Message converter
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
    
    // RabbitTemplate with converter
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                System.out.println("Message delivered successfully");
            } else {
                System.err.println("Message delivery failed: " + cause);
            }
        });
        template.setReturnsCallback(returned -> {
            System.err.println("Message returned: " + returned.getMessage());
        });
        return template;
    }
    
    // Listener container factory
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter());
        factory.setConcurrentConsumers(3);
        factory.setMaxConcurrentConsumers(10);
        return factory;
    }
    
    // Dead Letter Exchange and Queue
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX);
    }
    
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }
    
    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with(DLQ);
    }
    
    // Direct Exchange Configuration
    @Bean
    public DirectExchange directExchange() {
        return new DirectExchange(DIRECT_EXCHANGE);
    }
    
    @Bean
    public Queue directQueue() {
        return QueueBuilder.durable(DIRECT_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
    }
    
    @Bean
    public Binding directBinding() {
        return BindingBuilder.bind(directQueue())
                .to(directExchange())
                .with(DIRECT_ROUTING_KEY);
    }
    
    // Topic Exchange Configuration
    @Bean
    public TopicExchange topicExchange() {
        return new TopicExchange(TOPIC_EXCHANGE);
    }
    
    @Bean
    public Queue topicQueue1() {
        return QueueBuilder.durable(TOPIC_QUEUE_1)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
    }
    
    @Bean
    public Queue topicQueue2() {
        return QueueBuilder.durable(TOPIC_QUEUE_2)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
    }
    
    @Bean
    public Binding topicBinding1() {
        return BindingBuilder.bind(topicQueue1())
                .to(topicExchange())
                .with("topic.order.*");
    }
    
    @Bean
    public Binding topicBinding2() {
        return BindingBuilder.bind(topicQueue2())
                .to(topicExchange())
                .with("topic.user.*");
    }
    
    // Fanout Exchange Configuration
    @Bean
    public FanoutExchange fanoutExchange() {
        return new FanoutExchange(FANOUT_EXCHANGE);
    }
    
    @Bean
    public Queue fanoutQueue1() {
        return QueueBuilder.durable(FANOUT_QUEUE_1).build();
    }
    
    @Bean
    public Queue fanoutQueue2() {
        return QueueBuilder.durable(FANOUT_QUEUE_2).build();
    }
    
    @Bean
    public Binding fanoutBinding1() {
        return BindingBuilder.bind(fanoutQueue1()).to(fanoutExchange());
    }
    
    @Bean
    public Binding fanoutBinding2() {
        return BindingBuilder.bind(fanoutQueue2()).to(fanoutExchange());
    }
}

// 4. Message DTOs
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public class OrderMessage {
    @JsonProperty("orderId")
    private String orderId;
    
    @JsonProperty("customerId")
    private String customerId;
    
    @JsonProperty("amount")
    private Double amount;
    
    @JsonProperty("timestamp")
    private LocalDateTime timestamp;
    
    // Constructors
    public OrderMessage() {}
    
    public OrderMessage(String orderId, String customerId, Double amount) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.timestamp = LocalDateTime.now();
    }
    
    // Getters and Setters
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    
    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }
    
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    
    @Override
    public String toString() {
        return "OrderMessage{" +
                "orderId='" + orderId + '\'' +
                ", customerId='" + customerId + '\'' +
                ", amount=" + amount +
                ", timestamp=" + timestamp +
                '}';
    }
}

// 5. Message Publisher Service
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MessagePublisher {
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    // Direct exchange publishing
    public void publishDirectMessage(OrderMessage message) {
        try {
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.DIRECT_EXCHANGE,
                RabbitMQConfig.DIRECT_ROUTING_KEY,
                message
            );
            System.out.println("Published direct message: " + message);
        } catch (Exception e) {
            System.err.println("Failed to publish direct message: " + e.getMessage());
        }
    }
    
    // Topic exchange publishing
    public void publishTopicMessage(String routingKey, OrderMessage message) {
        try {
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.TOPIC_EXCHANGE,
                routingKey,
                message
            );
            System.out.println("Published topic message with routing key " + routingKey + ": " + message);
        } catch (Exception e) {
            System.err.println("Failed to publish topic message: " + e.getMessage());
        }
    }
    
    // Fanout exchange publishing
    public void publishFanoutMessage(OrderMessage message) {
        try {
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.FANOUT_EXCHANGE,
                "", // Routing key is ignored for fanout
                message
            );
            System.out.println("Published fanout message: " + message);
        } catch (Exception e) {
            System.err.println("Failed to publish fanout message: " + e.getMessage());
        }
    }
    
    // Publish with custom properties
    public void publishWithProperties(String exchange, String routingKey, Object message, 
                                    int priority, long ttl) {
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, message, messagePostProcessor -> {
                messagePostProcessor.getMessageProperties().setPriority(priority);
                messagePostProcessor.getMessageProperties().setExpiration(String.valueOf(ttl));
                messagePostProcessor.getMessageProperties().setHeader("custom-header", "custom-value");
                return messagePostProcessor;
            });
            System.out.println("Published message with custom properties");
        } catch (Exception e) {
            System.err.println("Failed to publish message with properties: " + e.getMessage());
        }
    }
}

// 6. Message Consumer Service
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import java.io.IOException;

@Service
public class MessageConsumer {
    
    // Direct queue listener
    @RabbitListener(queues = RabbitMQConfig.DIRECT_QUEUE)
    public void handleDirectMessage(@Payload OrderMessage message, 
                                  Channel channel, 
                                  @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            System.out.println("Received direct message: " + message);
            
            // Process the message
            processOrder(message);
            
            // Manual acknowledgment
            channel.basicAck(deliveryTag, false);
            System.out.println("Message acknowledged successfully");
            
        } catch (Exception e) {
            System.err.println("Error processing direct message: " + e.getMessage());
            try {
                // Reject and requeue (or send to DLQ if max retries exceeded)
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ioException) {
                System.err.println("Error rejecting message: " + ioException.getMessage());
            }
        }
    }
    
    // Topic queue listeners
    @RabbitListener(queues = RabbitMQConfig.TOPIC_QUEUE_1)
    public void handleOrderTopicMessage(@Payload OrderMessage message,
                                       Channel channel,
                                       @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            System.out.println("Received order topic message: " + message);
            processOrder(message);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            System.err.println("Error processing order topic message: " + e.getMessage());
            handleError(channel, deliveryTag);
        }
    }
    
    @RabbitListener(queues = RabbitMQConfig.TOPIC_QUEUE_2)
    public void handleUserTopicMessage(@Payload OrderMessage message,
                                      Channel channel,
                                      @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            System.out.println("Received user topic message: " + message);
            processOrder(message);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            System.err.println("Error processing user topic message: " + e.getMessage());
            handleError(channel, deliveryTag);
        }
    }
    
    // Fanout queue listeners
    @RabbitListener(queues = RabbitMQConfig.FANOUT_QUEUE_1)
    public void handleFanoutMessage1(@Payload OrderMessage message,
                                    Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            System.out.println("Received fanout message in queue 1: " + message);
            processOrder(message);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            System.err.println("Error processing fanout message 1: " + e.getMessage());
            handleError(channel, deliveryTag);
        }
    }
    
    @RabbitListener(queues = RabbitMQConfig.FANOUT_QUEUE_2)
    public void handleFanoutMessage2(@Payload OrderMessage message,
                                    Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            System.out.println("Received fanout message in queue 2: " + message);
            processOrder(message);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            System.err.println("Error processing fanout message 2: " + e.getMessage());
            handleError(channel, deliveryTag);
        }
    }
    
    // Dead letter queue listener
    @RabbitListener(queues = RabbitMQConfig.DLQ)
    public void handleDeadLetterMessage(@Payload OrderMessage message,
                                       Channel channel,
                                       @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                                       Message amqpMessage) {
        try {
            System.err.println("Received dead letter message: " + message);
            System.err.println("Original exchange: " + 
                amqpMessage.getMessageProperties().getHeaders().get("x-first-death-exchange"));
            System.err.println("Original routing key: " + 
                amqpMessage.getMessageProperties().getHeaders().get("x-first-death-queue"));
            
            // Handle dead letter (log, alert, manual intervention)
            handleDeadLetter(message);
            
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            System.err.println("Error processing dead letter message: " + e.getMessage());
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ioException) {
                System.err.println("Error rejecting dead letter message: " + ioException.getMessage());
            }
        }
    }
    
    // Helper methods
    private void processOrder(OrderMessage message) throws Exception {
        // Simulate processing time
        Thread.sleep(100);
        
        // Simulate occasional failures for testing
        if (message.getOrderId().contains("fail")) {
            throw new RuntimeException("Simulated processing failure");
        }
        
        System.out.println("Successfully processed order: " + message.getOrderId());
    }
    
    private void handleError(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException e) {
            System.err.println("Error rejecting message: " + e.getMessage());
        }
    }
    
    private void handleDeadLetter(OrderMessage message) {
        // Implement dead letter handling logic
        // - Send alert to monitoring system
        // - Log to special dead letter log
        // - Store in database for manual processing
        System.err.println("Dead letter processed for order: " + message.getOrderId());
    }
}

// 7. REST Controller for Testing
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/messages")
public class MessageController {
    
    @Autowired
    private MessagePublisher messagePublisher;
    
    @PostMapping("/direct")
    public ResponseEntity<String> sendDirectMessage(@RequestBody OrderMessage message) {
        messagePublisher.publishDirectMessage(message);
        return ResponseEntity.ok("Direct message sent successfully");
    }
    
    @PostMapping("/topic/{routingKey}")
    public ResponseEntity<String> sendTopicMessage(@PathVariable String routingKey, 
                                                  @RequestBody OrderMessage message) {
        messagePublisher.publishTopicMessage(routingKey, message);
        return ResponseEntity.ok("Topic message sent successfully");
    }
    
    @PostMapping("/fanout")
    public ResponseEntity<String> sendFanoutMessage(@RequestBody OrderMessage message) {
        messagePublisher.publishFanoutMessage(message);
        return ResponseEntity.ok("Fanout message sent successfully");
    }
    
    @PostMapping("/custom")
    public ResponseEntity<String> sendCustomMessage(@RequestBody OrderMessage message,
                                                   @RequestParam String exchange,
                                                   @RequestParam String routingKey,
                                                   @RequestParam(defaultValue = "0") int priority,
                                                   @RequestParam(defaultValue = "60000") long ttl) {
        messagePublisher.publishWithProperties(exchange, routingKey, message, priority, ttl);
        return ResponseEntity.ok("Custom message sent successfully");
    }
}

// 8. Main Application Class
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RabbitMQApplication {
    public static void main(String[] args) {
        SpringApplication.run(RabbitMQApplication.class, args);
    }
}