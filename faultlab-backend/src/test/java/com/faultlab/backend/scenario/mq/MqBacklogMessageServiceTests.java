package com.faultlab.backend.scenario.mq;

import com.faultlab.backend.config.RabbitMqConfig.MqBacklogProperties;
import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.trace.context.TraceContext;
import com.faultlab.backend.trace.context.TraceContextHolder;
import com.faultlab.backend.trace.context.TraceMessageHeaderNames;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MqBacklogMessageServiceTests {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final MetricService metricService = mock(MetricService.class);
    private final MqBacklogConsumerHandler consumerHandler = mock(MqBacklogConsumerHandler.class);
    private final MqBacklogProperties properties = properties();
    private final MqBacklogMessageService messageService = new MqBacklogMessageService(
            rabbitTemplate,
            properties,
            metricService,
            consumerHandler
    );

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    @Test
    void shouldPublishExpectedMessageCount() {
        messageService.publishOrders("exp-1", 3, 0);

        ArgumentCaptor<MqBacklogMessage> messageCaptor = ArgumentCaptor.forClass(MqBacklogMessage.class);
        verify(rabbitTemplate, times(3)).convertAndSend(
                eq("faultlab.mq.backlog.exchange"),
                eq("faultlab.mq.backlog.order"),
                messageCaptor.capture(),
                any(MessagePostProcessor.class)
        );
        assertThat(messageCaptor.getAllValues()).hasSize(3);
        assertThat(messageCaptor.getAllValues().get(0).getExperimentId()).isEqualTo("exp-1");
        verify(metricService).recordMetric("exp-1", "publishCount", 3, "count", "RabbitMQ");
    }

    @Test
    void shouldAttachTraceHeadersWhenPublishingMqMessage() throws Exception {
        TraceContextHolder.set(new TraceContext("trace-1", "span-1", "exp-1"));

        messageService.publishOrders("exp-1", 1, 0);

        ArgumentCaptor<MessagePostProcessor> processorCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(
                eq("faultlab.mq.backlog.exchange"),
                eq("faultlab.mq.backlog.order"),
                any(MqBacklogMessage.class),
                processorCaptor.capture()
        );
        Message message = processorCaptor.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        Object traceId = message.getMessageProperties().getHeader(TraceMessageHeaderNames.TRACE_ID);
        Object parentSpanId = message.getMessageProperties().getHeader(TraceMessageHeaderNames.PARENT_SPAN_ID);
        Object experimentId = message.getMessageProperties().getHeader(TraceMessageHeaderNames.EXPERIMENT_ID);
        assertThat(traceId).isEqualTo("trace-1");
        assertThat(parentSpanId).isEqualTo("span-1");
        assertThat(experimentId).isEqualTo("exp-1");
    }

    @Test
    void shouldRestoreTraceContextWhenConsumingMqMessage() {
        AtomicReference<TraceContext> contextInHandler = new AtomicReference<>();
        MqBacklogMessage message = new MqBacklogMessage("exp-1", 1, 0, LocalDateTime.now());
        Map<String, Object> headers = Map.of(
                TraceMessageHeaderNames.TRACE_ID, "trace-1",
                TraceMessageHeaderNames.PARENT_SPAN_ID, "span-1",
                TraceMessageHeaderNames.EXPERIMENT_ID, "exp-1"
        );
        doAnswer(invocation -> {
            contextInHandler.set(TraceContextHolder.get());
            return null;
        }).when(consumerHandler).consumeOrder(message);

        messageService.consumeOrder(message, headers);

        assertThat(contextInHandler.get()).isNotNull();
        assertThat(contextInHandler.get().getTraceId()).isEqualTo("trace-1");
        assertThat(contextInHandler.get().getSpanId()).isEqualTo("span-1");
        assertThat(contextInHandler.get().getExperimentId()).isEqualTo("exp-1");
        assertThat(TraceContextHolder.get()).isNull();
    }

    @Test
    void shouldFallbackWhenMqTraceHeaderMissing() {
        MqBacklogMessage message = new MqBacklogMessage("exp-1", 1, 0, LocalDateTime.now());

        messageService.consumeOrder(message, Map.of());

        verify(consumerHandler).consumeOrder(message);
        assertThat(TraceContextHolder.get()).isNull();
    }

    private MqBacklogProperties properties() {
        MqBacklogProperties mqBacklogProperties = new MqBacklogProperties();
        mqBacklogProperties.setExchange("faultlab.mq.backlog.exchange");
        mqBacklogProperties.setQueue("faultlab.mq.backlog.queue");
        mqBacklogProperties.setRoutingKey("faultlab.mq.backlog.order");
        return mqBacklogProperties;
    }
}
