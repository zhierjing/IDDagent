package com.IDDagent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告任务存储回归测试：已被 report-completed 消费推进的任务（consumed）
 * 不得再出现在 /active、/pending 轮询结果中，否则 3s 轮询会对已完成报告
 * 反复调用推进接口，导致后端日志刷屏（REPORT_COMPLETED_CALL 无限循环）。
 */
class ReportTaskStoreTest {

    private ReportTaskStore store;

    @BeforeEach
    void setUp() {
        store = new ReportTaskStore();
    }

    private ReportTaskStore.ReportTask completeTask(String companyName, String code, String userId, String convId) {
        ReportTaskStore.ReportTask task = store.createTask("tpl", "模板", companyName, code,
                userId, null, "", convId, List.of(), List.of());
        task.setStatus("completed");
        task.setCompletedAt(Instant.now());
        return task;
    }

    @Test
    void consumedTasksExcludedFromRecentByUser() {
        ReportTaskStore.ReportTask t1 = completeTask("公司A", "code1", "u1", "conv1");
        ReportTaskStore.ReportTask t2 = completeTask("公司B", "code2", "u1", "conv1");
        // 报告 A 已被 report-completed 推进消费 → 不再返回
        t1.markConsumed();
        List<ReportTaskStore.ReportTask> recent = store.getRecentTasksByUser("u1", 10);
        assertEquals(1, recent.size());
        assertEquals(t2.getReportId(), recent.get(0).getReportId());
        // 消费标记不丢失，再次查询仍不返回
        assertTrue(store.getRecentTasksByUser("u1", 10).stream()
                .noneMatch(t -> t.getReportId().equals(t1.getReportId())));
    }

    @Test
    void consumedTasksExcludedFromConversationPending() {
        ReportTaskStore.ReportTask t = completeTask("公司A", "code1", "u1", "conv1");
        assertTrue(store.getTasksByConversation("conv1").stream()
                .anyMatch(task -> task.getReportId().equals(t.getReportId())));
        t.markConsumed();
        assertTrue(store.getTasksByConversation("conv1").isEmpty());
    }

    @Test
    void generatingTasksNeverConsumedStillReturned() {
        ReportTaskStore.ReportTask t = store.createTask("tpl", "模板", "公司A", "code1",
                "u1", null, "", "conv1", List.of(), List.of());
        // 生成中任务不受 consumed 影响
        assertEquals(1, store.getRecentTasksByUser("u1", 10).size());
        assertEquals(1, store.getTasksByConversation("conv1").size());
    }
}
