package com.IDDagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7（报告事件泛化）：DeferredEvent / PendingExternalTask 结构与工厂测试。
 */
class DeferredEventTest {

    @Test
    void reportCompletedFactoryBuildsTypedEvent() {
        DeferredEvent event = DeferredEvent.reportCompleted("R_1", "F_REPORT");

        assertNotNull(event.eventId());
        assertTrue(event.eventId().startsWith("EVT_"));
        assertEquals("F_REPORT", event.frameId());
        assertEquals("R_1", event.externalTaskId());
        assertSame(DeferredEvent.EventType.REPORT_COMPLETED, event.type());
        assertNull(event.payload());
        assertNotNull(event.occurredAt());
    }

    @Test
    void eventTypeEnumSupportsAllDocumentedKinds() {
        // 文档第 39 节：REPORT_COMPLETED 为首个落地类型，其余为泛化预留
        DeferredEvent.EventType[] types = DeferredEvent.EventType.values();
        assertEquals(5, types.length);
        assertEquals(DeferredEvent.EventType.REPORT_COMPLETED, types[0]);
        assertEquals(DeferredEvent.EventType.ASYNC_QUERY_COMPLETED, types[1]);
        assertEquals(DeferredEvent.EventType.EXPORT_COMPLETED, types[2]);
        assertEquals(DeferredEvent.EventType.APPROVAL_COMPLETED, types[3]);
        assertEquals(DeferredEvent.EventType.CALLBACK_RECEIVED, types[4]);
    }

    @Test
    void pendingExternalTaskRegistersReportGenerationAndWithStatusCopies() {
        PendingExternalTask task = new PendingExternalTask("R_1", "F_REPORT", "",
                PendingExternalTask.ExternalTaskType.REPORT_GENERATION,
                PendingExternalTask.ExternalTaskStatus.RUNNING, null);

        assertSame(PendingExternalTask.ExternalTaskType.REPORT_GENERATION, task.type());
        assertSame(PendingExternalTask.ExternalTaskStatus.RUNNING, task.status());

        // withStatus 复制更新：原任务不变，新任务状态为 COMPLETED
        PendingExternalTask done = task.withStatus(PendingExternalTask.ExternalTaskStatus.COMPLETED);
        assertSame(PendingExternalTask.ExternalTaskStatus.RUNNING, task.status());
        assertSame(PendingExternalTask.ExternalTaskStatus.COMPLETED, done.status());
        assertEquals("R_1", done.externalTaskId());
        assertEquals("F_REPORT", done.frameId());
    }
}
