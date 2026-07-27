package com.faultlab.backend.trace.aspect;

import com.faultlab.backend.trace.annotation.TraceSpan;
import com.faultlab.backend.trace.context.TraceContext;
import com.faultlab.backend.trace.context.TraceContextHolder;
import com.faultlab.backend.trace.manager.TraceManager;
import com.faultlab.backend.trace.model.TraceSpanRecord;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class TraceAspect {

    private final TraceManager traceManager;

    public TraceAspect(TraceManager traceManager) {
        this.traceManager = traceManager;
    }

    @Around("@annotation(traceSpan)")
    public Object aroundTraceSpan(ProceedingJoinPoint joinPoint, TraceSpan traceSpan) throws Throwable {
        TraceContext previousContext = TraceContextHolder.get();
        TraceSpanRecord spanRecord = previousContext == null
                ? traceManager.startRootSpan(null, traceSpan.operationName(), traceSpan.component(), traceSpan.tags())
                : traceManager.startChildSpan(traceSpan.operationName(), traceSpan.component(), traceSpan.tags());

        try {
            Object result = joinPoint.proceed();
            traceManager.finishSpan(spanRecord);
            return result;
        } catch (Throwable throwable) {
            traceManager.finishSpan(spanRecord, throwable);
            throw throwable;
        } finally {
            if (previousContext == null) {
                TraceContextHolder.clear();
            } else {
                TraceContextHolder.set(previousContext);
            }
        }
    }
}
