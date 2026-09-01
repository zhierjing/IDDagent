package com.IDDagent.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7（报告事件泛化）：挂起期间到达的异步完成事件（实施文档第 39 节 DeferredEvent、
 * 第 40 节处理原则）。目标帧 RUNNING → 按现有业务正常消费（不生成事件）；
 * 目标帧 SUSPENDED → 事件入帧（frame.deferredEvents.add），禁止推进被挂起帧；
 * 恢复该帧时由恢复路径消费（consumeDeferredEvents）后决定继续 Pipeline 或等待其他事件。
 *
 * <p>当前业务仅使用 REPORT_COMPLETED；其余类型为后续泛化预留。
 */
public record DeferredEvent(String eventId, String frameId, String externalTaskId,
                            EventType type, Object payload, Instant occurredAt) {

    /** 报告完成事件工厂：eventId 自动生成，载荷为 null（报告推进数据在 waitingReportTask） */
    public static DeferredEvent reportCompleted(String externalTaskId, String frameId) {
        return new DeferredEvent("EVT_" + UUID.randomUUID(), frameId, externalTaskId,
                EventType.REPORT_COMPLETED, null, Instant.now());
    }

    /** 异步事件类型（第 39 节：REPORT_COMPLETED 为首个落地类型，其余预留） */
    public enum EventType {
        REPORT_COMPLETED,
        ASYNC_QUERY_COMPLETED,
        EXPORT_COMPLETED,
        APPROVAL_COMPLETED,
        CALLBACK_RECEIVED
    }
}
