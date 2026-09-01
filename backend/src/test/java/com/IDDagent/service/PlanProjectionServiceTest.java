package com.IDDagent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3（Plan 状态投影）单元测试：验证 PlanProjectionService 从 Pipeline 执行事实
 * 单向更新 PlanRuntime 状态视图（create/ensure/running/waiting/done/completed/abandoned），
 * 以及无 PlanRuntime（单技能独立执行）时全部投影方法静默跳过。
 */
class PlanProjectionServiceTest {

    private ContextMemoryService contextMemoryService;
    private PlanProjectionService projection;

    @BeforeEach
    void setUp() {
        contextMemoryService = new ContextMemoryService();
        projection = new PlanProjectionService(contextMemoryService);
    }

    /** 两步管道计划快照（每项含 skill/label/order，与 pipelinePlan 结构一致） */
    private List<Map<String, Object>> samplePlan() {
        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> t1 = new LinkedHashMap<>();
        t1.put("skill", "query_company_basic_info");
        t1.put("label", "企业基本信息");
        t1.put("order", 1);
        plan.add(t1);
        Map<String, Object> t2 = new LinkedHashMap<>();
        t2.put("skill", "generate_report");
        t2.put("label", "报告生成");
        t2.put("order", 2);
        plan.add(t2);
        return plan;
    }

    @Test
    void createPlanBuildsPendingStepsBoundToFrame() {
        PlanRuntime runtime = projection.createPlan("conv-p3", "F_1", samplePlan());

        assertNotNull(runtime.planId);
        assertTrue(runtime.planId.startsWith("P_"));
        assertEquals("F_1", runtime.frameId);
        assertEquals(-1, runtime.currentStepIndex);
        assertEquals(2, runtime.steps.size());
        assertEquals(PlanRuntime.PlanStatus.PENDING, runtime.status);
        for (PlanStep step : runtime.steps) {
            assertEquals(PlanStepStatus.PENDING, step.status);
            assertTrue(step.stepId.startsWith("ST_"));
        }
        // 挂到会话上下文：投影方法的操作对象与创建对象一致
        assertSame(runtime, contextMemoryService.get("conv-p3").planRuntime);
    }

    @Test
    void ensurePlanRebuildsWhenMissingButKeepsExisting() {
        String convId = "conv-p3-ensure";
        // runtime 缺失（如后端重启后快照重建路径）→ 按快照重建
        projection.ensurePlan(convId, "F_1", samplePlan());
        PlanRuntime rebuilt = contextMemoryService.get(convId).planRuntime;
        assertNotNull(rebuilt);
        assertEquals(2, rebuilt.steps.size());

        // 已有 runtime 且已投影 RUNNING → ensurePlan 不覆盖状态
        projection.markStepRunning(convId, 1);
        projection.ensurePlan(convId, "F_1", samplePlan());
        assertSame(rebuilt, contextMemoryService.get(convId).planRuntime);
        assertEquals(PlanStepStatus.RUNNING, rebuilt.stepByOrder(1).status);

        // 无快照（null/空）→ 不创建也不抛异常
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get("conv-p3-empty");
        ctx.planRuntime = null;
        projection.ensurePlan("conv-p3-empty", "F_1", null);
        assertNull(contextMemoryService.get("conv-p3-empty").planRuntime);
    }

    @Test
    void markStepRunningProjectsRunningAndCurrentIndex() {
        String convId = "conv-p3-run";
        projection.createPlan(convId, "F_1", samplePlan());
        PlanRuntime runtime = contextMemoryService.get(convId).planRuntime;

        projection.markStepRunning(convId, 1);

        assertEquals(PlanStepStatus.RUNNING, runtime.stepByOrder(1).status);
        assertEquals(PlanStepStatus.PENDING, runtime.stepByOrder(2).status);
        assertEquals(0, runtime.currentStepIndex);
        assertEquals(PlanRuntime.PlanStatus.RUNNING, runtime.status);
    }

    @Test
    void waitingStatesProjectStepAndPlanStatus() {
        String convId = "conv-p3-wait";
        projection.createPlan(convId, "F_1", samplePlan());
        PlanRuntime runtime = contextMemoryService.get(convId).planRuntime;

        projection.markStepWaitingInput(convId, 1);
        assertEquals(PlanStepStatus.WAITING_INPUT, runtime.stepByOrder(1).status);
        assertEquals(PlanRuntime.PlanStatus.WAITING_INPUT, runtime.status);

        projection.markStepWaitingExternal(convId, 2);
        assertEquals(PlanStepStatus.WAITING_EXTERNAL, runtime.stepByOrder(2).status);
        assertEquals(PlanRuntime.PlanStatus.WAITING_EXTERNAL, runtime.status);
    }

    @Test
    void markStepDoneProjectsDoneWithoutTouchingPlanStatus() {
        String convId = "conv-p3-done";
        projection.createPlan(convId, "F_1", samplePlan());
        PlanRuntime runtime = contextMemoryService.get(convId).planRuntime;
        projection.markStepRunning(convId, 1);
        assertEquals(PlanRuntime.PlanStatus.RUNNING, runtime.status);

        projection.markStepDone(convId, 1);

        assertEquals(PlanStepStatus.DONE, runtime.stepByOrder(1).status);
        // 计划整体状态由 markPlanCompleted 负责，markStepDone 不越权修改
        assertEquals(PlanRuntime.PlanStatus.RUNNING, runtime.status);
    }

    @Test
    void completedAndAbandonedSetPlanLevelStatus() {
        String convId = "conv-p3-end";
        projection.createPlan(convId, "F_1", samplePlan());
        PlanRuntime runtime = contextMemoryService.get(convId).planRuntime;

        projection.markPlanCompleted(convId);
        assertEquals(PlanRuntime.PlanStatus.COMPLETED, runtime.status);
        // 步骤状态保持最后投影值（不重置）
        assertEquals(PlanStepStatus.PENDING, runtime.stepByOrder(1).status);

        projection.markPlanAbandoned(convId);
        assertEquals(PlanRuntime.PlanStatus.ABANDONED, runtime.status);
    }

    @Test
    void allProjectionsSilentlySkipWithoutRuntime() {
        // 单技能独立执行场景：无 PlanRuntime，全部投影方法静默跳过（不抛异常）
        String convId = "conv-p3-solo";
        projection.ensurePlan(convId, "F_1", null);
        projection.markStepRunning(convId, 1);
        projection.markStepWaitingInput(convId, 1);
        projection.markStepWaitingExternal(convId, 1);
        projection.markStepDone(convId, 1);
        projection.markPlanCompleted(convId);
        projection.markPlanAbandoned(convId);
        assertNull(contextMemoryService.get(convId).planRuntime);
    }

    @Test
    void unknownOrderProjectionIsNoOp() {
        String convId = "conv-p3-unknown";
        projection.createPlan(convId, "F_1", samplePlan());
        PlanRuntime runtime = contextMemoryService.get(convId).planRuntime;

        projection.markStepRunning(convId, 99);
        projection.markStepDone(convId, 0);

        assertEquals(PlanStepStatus.PENDING, runtime.stepByOrder(1).status);
        assertEquals(PlanRuntime.PlanStatus.PENDING, runtime.status);
        assertEquals(-1, runtime.currentStepIndex);
    }
}
