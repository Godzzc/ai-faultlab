package com.faultlab.backend.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Bean
    @ConfigurationProperties(prefix = "faultlab.mq-backlog")
    public MqBacklogProperties mqBacklogProperties() {
        return new MqBacklogProperties();
    }

    @Bean
    public DirectExchange mqBacklogExchange(MqBacklogProperties properties) {
        return new DirectExchange(properties.getExchange(), true, false);
    }

    @Bean
    public Queue mqBacklogQueue(MqBacklogProperties properties) {
        return new Queue(properties.getQueue(), true);
    }

    @Bean
    public Binding mqBacklogBinding(Queue mqBacklogQueue, DirectExchange mqBacklogExchange, MqBacklogProperties properties) {
        return BindingBuilder.bind(mqBacklogQueue)
                .to(mqBacklogExchange)
                .with(properties.getRoutingKey());
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    public static class MqBacklogProperties {

        private String exchange;
        private String queue;
        private String routingKey;

        public String getExchange() {
            return exchange;
        }

        public void setExchange(String exchange) {
            this.exchange = exchange;
        }

        public String getQueue() {
            return queue;
        }

        public void setQueue(String queue) {
            this.queue = queue;
        }

        public String getRoutingKey() {
            return routingKey;
        }

        public void setRoutingKey(String routingKey) {
            this.routingKey = routingKey;
        }
    }
}
