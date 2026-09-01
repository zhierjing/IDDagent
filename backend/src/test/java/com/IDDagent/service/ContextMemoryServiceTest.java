package com.IDDagent.service;

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
 * ContextMemoryService 意图穿插挂起栈单元测试。
 * Phase 2（ExecutionFrame）：验证挂起/恢复/放弃/报告层/嵌套穿插在挂起栈元素升级为
 * ExecutionFrame 后行为与旧快照 Map 完全一致（回归保障），并验证 frameId 与
 * 企业上下文随帧保存还原。
 */
class ContextMemoryServiceTest {

    private final ContextMemoryService service = new ContextMemoryService();

    /** 构造一个含多意图管道+暂停技能+等待报告+企业上下文的会话上下文（模拟穿插前活动状态） */
    private ContextMemoryService.ConversationContext seedBusyContext(String convId, String frameId) {
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        ctx.currentFrameId = frameId;
        ctx.parentFrameId = "F_PARENT";
        ctx.companyName = "小米科技";
        ctx.creditCode = "91310000XXXX";
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("skill", "query_due_diligence_reports");
        task.put("params", new LinkedHashMap<String, Object>());
        task.put("order", 1);
        task.put("_index", 0);
        ctx.pendingPipeline = new ArrayList<>(List.of(task));
        Map<String, Object> planItem = new LinkedHashMap<>();
        planItem.put("skill", "query_due_diligence_reports");
        planItem.put("label", "历史尽调报告查询");
        planItem.put("order", 1);
        ctx.pipelinePlan = new ArrayList<>(List.of(planItem));
        Map<String, Object> skillParams = new LinkedHashMap<>();
        skillParams.put("company_name", "小米科技");
        service.setPendingSkill(convId, "verify_business_license", skillParams);
        return ctx;
    }

    @Test
    void suspendClearsActiveStateAndPushFrame() {
        String convId = "conv-suspend";
        seedBusyContext(convId, "F_1");
        ContextMemoryService.ConversationContext ctx = service.get(convId);

        service.suspendPipeline(convId);

        // 挂起后活动状态清空（避免新意图覆盖旧管道状态）
        assertFalse(ctx.hasPendingPipeline());
        assertFalse(ctx.hasPendingSkill());
        assertFalse(ctx.isWaitingReport());
        assertFalse(ctx.hasPendingPipeline());
        assertTrue(ctx.hasSuspendedPipeline());
        // 栈顶帧携带完整现场
        assertEquals("F_1", service.peekSuspendedFrameId(convId));
        assertEquals(1, service.peekSuspendedPlan(convId).size());
        // seed 未设置等待报告 → 栈中无报告层，活动 waitingReportTask 已被清空
        assertFalse(service.suspendedStackHasWaitingReport(convId));
        assertNull(ctx.waitingReportTask);
    }

    @Test
    void popAndRestoreRestoresFullRuntimeWithFrameId() {
        String convId = "conv-restore";
        seedBusyContext(convId, "F_1");
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        service.suspendPipeline(convId);
        // 穿插期新意图更新企业名与 frameId，模拟穿插执行期间上下文漂移
        ctx.companyName = "穿插期企业";
        ctx.currentFrameId = "F_2";

        assertTrue(service.popAndRestorePipeline(convId));

        // 完整还原挂起时刻现场（企业名与 frameId 均为挂起时刻值，不残留穿插期漂移）
        assertEquals("F_1", ctx.currentFrameId);
        assertEquals("F_PARENT", ctx.parentFrameId);
        assertEquals("小米科技", ctx.companyName);
        assertEquals("91310000XXXX", ctx.creditCode);
        assertTrue(ctx.hasPendingPipeline());
        assertEquals(1, ctx.pipelinePlan.size());
        assertTrue(ctx.hasPendingSkill());
        assertEquals("verify_business_license", ctx.pendingSkillName);
        assertEquals("小米科技", ctx.pendingSkillParams.get("company_name"));
        assertFalse(ctx.hasSuspendedPipeline());
        assertFalse(ctx.interruptAskPending);
    }

    @Test
    void nestedInterruptRestoresLifoOrder() {
        String convId = "conv-nested";
        // A 层：含管道与暂停技能
        seedBusyContext(convId, "F_A");
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        service.suspendPipeline(convId);
        // B 层：穿插期新意图的管道（无暂停技能，无企业上下文）
        Map<String, Object> bTask = new LinkedHashMap<>();
        bTask.put("skill", "query_company_basic_info");
        bTask.put("params", new LinkedHashMap<String, Object>());
        bTask.put("order", 1);
        bTask.put("_index", 0);
        ctx.pendingPipeline = new ArrayList<>(List.of(bTask));
        ctx.currentFrameId = "F_B";
        ctx.companyName = "穿插期企业";
        service.suspendPipeline(convId);

        // LIFO：先恢复后挂起的 B 层
        assertEquals("F_B", service.peekSuspendedFrameId(convId));
        assertTrue(service.popAndRestorePipeline(convId));
        assertEquals("F_B", ctx.currentFrameId);
        assertEquals("穿插期企业", ctx.companyName);
        assertFalse(ctx.hasPendingSkill()); // B 层无暂停技能
        // 再恢复 A 层
        assertEquals("F_A", service.peekSuspendedFrameId(convId));
        assertTrue(service.popAndRestorePipeline(convId));
        assertEquals("F_A", ctx.currentFrameId);
        assertEquals("小米科技", ctx.companyName);
        assertTrue(ctx.hasPendingSkill());
        assertFalse(ctx.hasSuspendedPipeline());
    }

    @Test
    void reportInterruptFrameKeepsWaitingReportAndFindById() {
        String convId = "conv-report";
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        ctx.currentFrameId = "F_REPORT";
        Map<String, Object> waiting = new LinkedHashMap<>();
        waiting.put("skill", "generate_report");
        waiting.put("order", 2);
        waiting.put("report_id", "R_1");
        service.setWaitingReportTask(convId, waiting);
        // 无管道任务层：仅报告等待状态（报告生成期间穿插场景）
        ctx.pipelinePlan = new ArrayList<>();

        service.suspendPipeline(convId);

        // 挂起后 waitingReportTask 清空（随帧保存），但帧内仍可定位
        assertNull(ctx.waitingReportTask);
        assertTrue(service.suspendedStackHasWaitingReport(convId));
        assertEquals("F_REPORT", service.findWaitingReportFrameId(convId));
        // 穿插期新意图获得新 frameId，不影响报告层归属定位
        ctx.currentFrameId = "F_NEW";
        assertEquals("F_REPORT", service.findWaitingReportFrameId(convId));

        // 恢复后 waitingReportTask 还原，报告回调可继续推进
        assertTrue(service.popAndRestorePipeline(convId));
        assertEquals("F_REPORT", ctx.currentFrameId);
        assertTrue(ctx.isWaitingReport());
        assertEquals("R_1", ctx.waitingReportTask.get("report_id"));
    }

    @Test
    void abandonReportLayerClearsPendingReportDone() {
        String convId = "conv-abandon";
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        ctx.currentFrameId = "F_REPORT";
        service.setWaitingReportTask(convId, new LinkedHashMap<>(Map.of("skill", "generate_report")));
        service.suspendPipeline(convId);
        // 穿插期间报告先完成 → 置位 pendingReportDone
        ctx.pendingReportDone = true;

        // 放弃该报告挂起层：报告作废，pendingReportDone 清除，栈空
        assertFalse(service.popSuspendedSnapshot(convId));
        assertFalse(ctx.pendingReportDone);
        assertFalse(ctx.hasSuspendedPipeline());
        assertFalse(ctx.interruptAskPending);
    }

    @Test
    void abandonTopLayerStillAsksNextLayer() {
        String convId = "conv-abandon-next";
        seedBusyContext(convId, "F_A");
        service.suspendPipeline(convId);
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        ctx.currentFrameId = "F_B";
        service.suspendPipeline(convId);

        // 放弃栈顶 B 层后仍有 A 层 → 返回 true（调用方继续询问下一层）
        assertTrue(service.popSuspendedSnapshot(convId));
        assertEquals("F_A", service.peekSuspendedFrameId(convId));
        assertEquals("小米科技", ctx.companyName); // A 层企业上下文未受影响
    }

    @Test
    void clearSuspendedResetsFramesAndFrameId() {
        String convId = "conv-clear";
        seedBusyContext(convId, "F_1");
        service.suspendPipeline(convId);
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        ctx.currentFrameId = "F_2";
        ctx.interruptAskPending = true;

        service.clearSuspended(convId);

        assertFalse(ctx.hasSuspendedPipeline());
        assertFalse(ctx.interruptAskPending);
        assertEquals("", ctx.currentFrameId);
        assertEquals("", ctx.parentFrameId);
    }

    @Test
    void suspendPipelineRejectsWhenStackDepthLimitReached() {
        String convId = "conv-stack-limit";
        // 连续穿插压满 MAX_SUSPENDED_STACK_DEPTH 层（每层均含管道+暂停技能+企业上下文快照）
        for (int i = 0; i < ContextMemoryService.MAX_SUSPENDED_STACK_DEPTH; i++) {
            seedBusyContext(convId, "F_" + i);
            assertTrue(service.suspendPipeline(convId));
        }
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        assertEquals(ContextMemoryService.MAX_SUSPENDED_STACK_DEPTH, ctx.suspendedStack.size());

        // 第 MAX+1 次穿插：拒绝压栈并放弃当前活动管道（返回 false），栈中已有层不受影响
        seedBusyContext(convId, "F_EXTRA");
        assertFalse(service.suspendPipeline(convId));
        assertEquals(ContextMemoryService.MAX_SUSPENDED_STACK_DEPTH, ctx.suspendedStack.size());
        assertFalse(ctx.hasPendingPipeline());
        assertFalse(ctx.hasPendingSkill());
        assertNull(ctx.waitingReportTask);
        assertNull(ctx.planRuntime);
        // 栈中已有层仍可正常 LIFO 恢复（后挂起者先恢复）
        assertEquals("F_" + (ContextMemoryService.MAX_SUSPENDED_STACK_DEPTH - 1),
                service.peekSuspendedFrameId(convId));
        assertTrue(service.popAndRestorePipeline(convId));
        assertEquals("F_" + (ContextMemoryService.MAX_SUSPENDED_STACK_DEPTH - 1), ctx.currentFrameId);
    }

    @Test
    void emptyStackOperationsAreNoOps() {
        String convId = "conv-empty";
        assertFalse(service.popAndRestorePipeline(convId));
        assertFalse(service.popSuspendedSnapshot(convId));
        assertFalse(service.suspendedStackHasWaitingReport(convId));
        assertEquals("", service.peekSuspendedFrameId(convId));
        assertEquals("", service.findWaitingReportFrameId(convId));
        assertEquals(0, service.peekSuspendedPlan(convId).size());
        service.clearSuspended(convId); // 不抛异常
    }

    /** 构造一个含两步步骤的 PlanRuntime 并挂到会话上下文（模拟管道执行中的计划投影） */
    private PlanRuntime seedPlanRuntime(String convId) {
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        PlanRuntime runtime = new PlanRuntime();
        runtime.planId = "P_TEST";
        runtime.frameId = ctx.currentFrameId;
        PlanStep s1 = new PlanStep();
        s1.stepId = "ST_1";
        s1.skillName = "query_company_basic_info";
        s1.title = "企业基本信息";
        s1.order = 1;
        s1.status = PlanStepStatus.RUNNING;
        PlanStep s2 = new PlanStep();
        s2.stepId = "ST_2";
        s2.skillName = "generate_report";
        s2.title = "报告生成";
        s2.order = 2;
        s2.status = PlanStepStatus.PENDING;
        runtime.steps = new ArrayList<>(List.of(s1, s2));
        runtime.currentStepIndex = 0;
        runtime.status = PlanRuntime.PlanStatus.RUNNING;
        ctx.planRuntime = runtime;
        return runtime;
    }

    @Test
    void planRuntimeSavedWithFrameAndRestored() {
        String convId = "conv-p3-save";
        seedBusyContext(convId, "F_1");
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        PlanRuntime runtime = seedPlanRuntime(convId);

        service.suspendPipeline(convId);

        // 挂起后活动 planRuntime 清空（穿插期新意图的投影不会污染旧计划）
        assertNull(ctx.planRuntime);
        // 穿插期新意图创建新计划
        ctx.planRuntime = new PlanRuntime();
        ctx.planRuntime.planId = "P_NEW";

        assertTrue(service.popAndRestorePipeline(convId));
        // 恢复后回填挂起时刻的同一计划（引用一致，步骤/整体状态完整还原）
        assertSame(runtime, ctx.planRuntime);
        assertEquals(PlanStepStatus.RUNNING, ctx.planRuntime.stepByOrder(1).status);
        assertEquals(PlanStepStatus.PENDING, ctx.planRuntime.stepByOrder(2).status);
        assertEquals(PlanRuntime.PlanStatus.RUNNING, ctx.planRuntime.status);
    }

    @Test
    void planRuntimeClearedOnAbandonAndClear() {
        String convId = "conv-p3-clear";
        seedBusyContext(convId, "F_1");
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        seedPlanRuntime(convId);
        service.suspendPipeline(convId);
        // 穿插期新计划（放弃栈顶时应一并作废，不残留）
        ctx.planRuntime = new PlanRuntime();
        ctx.planRuntime.planId = "P_NEW";

        service.popSuspendedSnapshot(convId);
        assertNull(ctx.planRuntime);

        // 重新挂起后隐式放弃全部挂起：同样清空活动计划
        seedPlanRuntime(convId);
        service.suspendPipeline(convId);
        ctx.planRuntime = new PlanRuntime();
        service.clearSuspended(convId);
        assertNull(ctx.planRuntime);
    }

    // ---------- Phase 5（Structured Resume）：恢复动作有效性校验 ----------

    @Test
    void resumeActionValidOnlyDuringAskWithTopFrameId() {
        String convId = "conv-p5-valid";
        seedBusyContext(convId, "F_A");
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        service.suspendPipeline(convId);
        // 正常询问状态：正询问栈顶层 A（文档第 31 节：peek().frameId == frameId 才可恢复）
        ctx.interruptAskPending = true;

        assertTrue(service.isResumeActionValid(convId, "F_A"));
    }

    @Test
    void resumeActionInvalidWhenNotAsking() {
        String convId = "conv-p5-notask";
        seedBusyContext(convId, "F_A");
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        service.suspendPipeline(convId);
        // 活动任务执行中（无待回答询问）：点击恢复卡不得 pop 覆盖活动状态
        ctx.interruptAskPending = false;

        assertFalse(service.isResumeActionValid(convId, "F_A"));
    }

    @Test
    void resumeActionInvalidForStaleFrame() {
        String convId = "conv-p5-stale";
        seedBusyContext(convId, "F_A");
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        service.suspendPipeline(convId);
        // 嵌套穿插：B 层压栈后询问的是 B，用户点击旧 A 卡（Case 13）→ 不允许恢复 A
        ctx.currentFrameId = "F_B";
        service.suspendPipeline(convId);
        ctx.interruptAskPending = true;

        assertEquals("F_B", service.peekSuspendedFrameId(convId));
        assertFalse(service.isResumeActionValid(convId, "F_A"));
        assertTrue(service.isResumeActionValid(convId, "F_B"));
    }

    @Test
    void resumeActionInvalidWithEmptyStackOrBlankFrameId() {
        String convId = "conv-p5-empty";
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        ctx.interruptAskPending = true;
        // 栈空（后端重启丢失挂起态，历史询问卡仍可点击）→ 拒绝
        assertFalse(service.isResumeActionValid(convId, "F_A"));
        // frameId 为空 → 拒绝
        seedBusyContext(convId, "F_A");
        service.suspendPipeline(convId);
        ctx.interruptAskPending = true;
        assertFalse(service.isResumeActionValid(convId, ""));
        assertFalse(service.isResumeActionValid(convId, null));
    }

    // ---------- Phase 6（交互 ID 化）：交互卡帧归属校验 ----------

    @Test
    void interactionActiveOnlyForCurrentFrame() {
        String convId = "conv-p6-active";
        seedBusyContext(convId, "F_1");

        // 卡片所属帧 == 当前活动帧（候选卡在技能执行中生成，此时任务正在等待用户输入）→ 接受
        assertTrue(service.isInteractionActive(convId, "F_1"));
        // 其他帧（已挂起/不存在）→ 拒绝
        assertFalse(service.isInteractionActive(convId, "F_2"));
    }

    @Test
    void interactionRejectedWhenFrameSuspendedByInterrupt() {
        String convId = "conv-p6-suspend";
        seedBusyContext(convId, "F_A");
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        service.suspendPipeline(convId);
        // 穿插新意图后 A 帧挂起、B 为活动帧：点击 A 的候选卡（Case 14）→ 拒绝，
        // 禁止把 A 的交互值传给 B（文档第 44 节 INTERACTION_SUSPENDED）
        ctx.currentFrameId = "F_B";

        assertFalse(service.isInteractionActive(convId, "F_A"));
        assertTrue(service.isInteractionActive(convId, "F_B"));

        // 恢复 A 层后 A 重新成为活动帧 → 其卡片恢复可交互
        assertTrue(service.popAndRestorePipeline(convId));
        assertEquals("F_A", ctx.currentFrameId);
        assertTrue(service.isInteractionActive(convId, "F_A"));
    }

    @Test
    void interactionRejectedWhenNoActiveTaskOrBlankFrameId() {
        String convId = "conv-p6-none";
        // 无活动任务（currentFrameId 为空）：任何交互卡均为旧卡 → 拒绝
        assertFalse(service.isInteractionActive(convId, "F_A"));
        assertFalse(service.isInteractionActive(convId, ""));
        assertFalse(service.isInteractionActive(convId, null));
        assertFalse(service.isInteractionActive(null, "F_A"));
    }

    // ---------- Phase 7（报告事件泛化）：DeferredEvent 入帧与外部任务归属 ----------

    @Test
    void deferEventAddsEventToMatchingSuspendedFrame() {
        String convId = "conv-p7-defer";
        seedBusyContext(convId, "F_A");
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        service.suspendPipeline(convId);
        // 嵌套穿插：B 层压栈（栈序 A 在下、B 在上）
        ctx.currentFrameId = "F_B";
        service.suspendPipeline(convId);

        // 事件按 frameId 精确入帧：A 帧入事件，B 帧不受影响（文档第 40 节：
        // 目标帧 SUSPENDED → frame.deferredEvents.add，不依赖栈序）
        assertTrue(service.deferEvent(convId, "F_A", DeferredEvent.reportCompleted("R_1", "F_A")));
        assertFalse(service.deferEvent(convId, "F_A", null));
        // 栈顶 B 帧无事件；A 帧精确收到 1 个事件
        assertEquals(0, ctx.suspendedStack.peek().deferredEvents.size());
        assertTrue(ctx.suspendedStack.stream()
                .anyMatch(f -> "F_A".equals(f.frameId) && f.deferredEvents.size() == 1));
    }

    @Test
    void deferEventRejectsUnknownOrBlankFrame() {
        String convId = "conv-p7-unknown";
        seedBusyContext(convId, "F_A");
        service.suspendPipeline(convId);

        // 归属帧不在挂起栈（已恢复/完成）或 frameId 为空 → 拒绝（不生成事件）
        assertFalse(service.deferEvent(convId, "F_X", DeferredEvent.reportCompleted("R_1", "F_X")));
        assertFalse(service.deferEvent(convId, "", DeferredEvent.reportCompleted("R_1", "")));
        assertFalse(service.deferEvent(convId, null, DeferredEvent.reportCompleted("R_1", "")));
        assertFalse(service.deferEvent("conv-none", "F_A", DeferredEvent.reportCompleted("R_1", "F_A")));
    }

    @Test
    void popRestoreTransfersDeferredEventsToActiveCache() {
        String convId = "conv-p7-restore";
        seedBusyContext(convId, "F_A");
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        service.suspendPipeline(convId);
        // 穿插期间报告完成：事件入 A 帧（第 40 节，禁止推进被挂起帧）
        assertTrue(service.deferEvent(convId, "F_A", DeferredEvent.reportCompleted("R_1", "F_A")));
        ctx.currentFrameId = "F_B";

        // 恢复 A 层：帧内事件转移到活动缓存，由恢复路径消费（consumeDeferredEvents）
        assertTrue(service.popAndRestorePipeline(convId));
        assertEquals("F_A", ctx.currentFrameId);
        assertEquals(1, ctx.activeDeferredEvents.size());
        assertEquals(DeferredEvent.EventType.REPORT_COMPLETED, ctx.activeDeferredEvents.get(0).type());
        assertEquals("R_1", ctx.activeDeferredEvents.get(0).externalTaskId());
    }

    @Test
    void setWaitingReportIdWhileSuspendedSyncsFrameAndRegistersTask() {
        String convId = "conv-p7-sync";
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        ctx.currentFrameId = "F_REPORT";
        Map<String, Object> waiting = new LinkedHashMap<>();
        waiting.put("skill", "generate_report");
        waiting.put("order", 2);
        service.setWaitingReportTask(convId, waiting);
        service.suspendPipeline(convId);

        // 穿插挂起期间 H5 创建报告任务成功：写回挂起帧内副本（活动态为空），
        // 并登记 PendingExternalTask（externalTaskId=report_id，frameId=归属帧）
        service.setWaitingReportId(convId, "R_1");
        assertNull(ctx.waitingReportTask);
        assertEquals("R_1", ctx.suspendedStack.peek().waitingReport.get("report_id"));
        PendingExternalTask task = ctx.externalTasks.get("R_1");
        assertNotNull(task);
        assertEquals("F_REPORT", task.frameId());
        assertSame(PendingExternalTask.ExternalTaskStatus.RUNNING, task.status());

        // 恢复后 waitingReportTask 还原并携带 report_id → 不会误判"报告任务未创建"
        assertTrue(service.popAndRestorePipeline(convId));
        assertEquals("R_1", ctx.waitingReportTask.get("report_id"));
    }

    @Test
    void deferReportCompletedLocatesSuspendedFrameByReportId() {
        String convId = "conv-p7-locate";
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        ctx.currentFrameId = "F_REPORT";
        Map<String, Object> waiting = new LinkedHashMap<>();
        waiting.put("skill", "generate_report");
        waiting.put("order", 2);
        service.setWaitingReportTask(convId, waiting);
        service.suspendPipeline(convId);
        service.setWaitingReportId(convId, "R_1");

        // 完成回调：按 reportId 精确定位归属帧（文档第 38 节），返回该 frameId
        assertEquals("F_REPORT", service.deferReportCompleted(convId, "R_1"));
        // 任务置 COMPLETED；帧内入 REPORT_COMPLETED 事件（第 40 节：挂起帧只入事件不推进）
        assertSame(PendingExternalTask.ExternalTaskStatus.COMPLETED,
                ctx.externalTasks.get("R_1").status());
        assertEquals(1, ctx.suspendedStack.peek().deferredEvents.size());
        assertEquals(DeferredEvent.EventType.REPORT_COMPLETED,
                ctx.suspendedStack.peek().deferredEvents.get(0).type());
        assertEquals("R_1", ctx.suspendedStack.peek().deferredEvents.get(0).externalTaskId());
    }

    @Test
    void deferReportCompletedReturnsEmptyForUnknownReport() {
        String convId = "conv-p7-unknown-report";
        seedBusyContext(convId, "F_A");
        service.suspendPipeline(convId);

        // 未登记的任务（如非管道会话的报告、登记丢失）→ 空串，调用方走兜底定位
        assertEquals("", service.deferReportCompleted(convId, "R_X"));
        assertEquals("", service.deferReportCompleted(convId, ""));
        assertEquals("", service.deferReportCompleted(convId, null));
        assertEquals("", service.deferReportCompleted("conv-none", "R_X"));
    }

    // ============================================================
    // P4：attachmentUrl 随帧快照/还原（穿插期间附件不漂移到旧管道）
    // ============================================================

    /** 挂起时附件 URL 随帧快照且活动态清空，穿插期新附件不会被旧管道消费 */
    @Test
    void suspendSnapshotsAttachmentAndClearsActive() {
        String convId = "conv-att-suspend";
        seedBusyContext(convId, "F_1");
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        ctx.attachmentUrl = "/uploads/old.png";

        service.suspendPipeline(convId);

        // 活动态清空（穿插层自己的附件由穿插层消费），帧携带挂起时刻快照
        assertEquals("", ctx.attachmentUrl);
        assertEquals("/uploads/old.png", ctx.suspendedStack.peek().attachmentUrl);
    }

    /** 恢复时还原挂起时刻附件，穿插期上传的新附件不残留 */
    @Test
    void popRestoreRestoresFrameAttachment() {
        String convId = "conv-att-restore";
        seedBusyContext(convId, "F_1");
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        ctx.attachmentUrl = "/uploads/old.png";
        service.suspendPipeline(convId);
        // 穿插期上传新附件（属于穿插层任务）
        ctx.attachmentUrl = "/uploads/new.png";

        assertTrue(service.popAndRestorePipeline(convId));

        // 还原挂起时刻附件，新附件不漂移到旧管道
        assertEquals("/uploads/old.png", ctx.attachmentUrl);
    }

    // ============================================================
    // 剩余未完成任务摘要（interrupt_ask 计数：已完成任务不计入未完成）
    // ============================================================

    private Map<String, Object> planItem(String skill, String label, int order) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("skill", skill);
        item.put("label", label);
        item.put("order", order);
        return item;
    }

    /** 完整计划含已完成任务时，剩余摘要只含未完成部分（暂停任务 + 剩余队列），
     *  避免询问文案显示"N 项任务未完成"把已完成任务计入（日志场景：任务 1 完成后穿插） */
    @Test
    void remainingTasksExcludeCompletedPlanItems() {
        String convId = "conv-rem-1";
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        ctx.currentFrameId = "F_1";
        ctx.companyName = "小米科技";
        // 完整计划 3 项：任务 1 已完成、任务 2 暂停等待输入、任务 3 剩余队列
        ctx.pipelinePlan = new ArrayList<>(List.of(
                planItem("check_company_risk", "企业风险预查", 1),
                planItem("verify_business_license", "营业执照信息核实", 2),
                planItem("query_due_diligence_reports", "历史尽调报告查询", 3)));
        Map<String, Object> task3 = new LinkedHashMap<>();
        task3.put("skill", "query_due_diligence_reports");
        task3.put("params", new LinkedHashMap<String, Object>());
        task3.put("order", 3);
        task3.put("_index", 1);
        ctx.pendingPipeline = new ArrayList<>(List.of(task3));
        service.setPendingSkill(convId, "verify_business_license", new LinkedHashMap<>());
        service.suspendPipeline(convId);

        List<Map<String, Object>> remaining = service.peekSuspendedRemainingTasks(convId);

        // 仅未完成任务：暂停任务 + 剩余队列（任务 1 已完成不计入），label/order 从完整计划反查
        assertEquals(2, remaining.size());
        assertEquals("verify_business_license", remaining.get(0).get("skill"));
        assertEquals(2, remaining.get(0).get("order"));
        assertEquals("营业执照信息核实", remaining.get(0).get("label"));
        assertEquals("query_due_diligence_reports", remaining.get(1).get("skill"));
        assertEquals(3, remaining.get(1).get("order"));
        assertEquals("历史尽调报告查询", remaining.get(1).get("label"));
    }

    /** 报告等待层挂起：剩余摘要 = 报告任务 1 项（含 label/order 反查） */
    @Test
    void remainingTasksCountsWaitingReport() {
        String convId = "conv-rem-report";
        ContextMemoryService.ConversationContext ctx = service.get(convId);
        ctx.currentFrameId = "F_REPORT";
        ctx.pipelinePlan = new ArrayList<>(List.of(
                planItem("check_company_risk", "企业风险预查", 1),
                planItem("generate_report", "尽调报告生成", 2)));
        Map<String, Object> waiting = new LinkedHashMap<>();
        waiting.put("skill", "generate_report");
        waiting.put("order", 2);
        service.setWaitingReportTask(convId, waiting);
        service.suspendPipeline(convId);

        List<Map<String, Object>> remaining = service.peekSuspendedRemainingTasks(convId);

        assertEquals(1, remaining.size());
        assertEquals("generate_report", remaining.get(0).get("skill"));
        assertEquals(2, remaining.get(0).get("order"));
        assertEquals("尽调报告生成", remaining.get(0).get("label"));
    }

    /** 无挂起帧（或帧不存在）→ 空摘要 */
    @Test
    void remainingTasksEmptyForNoSuspendedFrame() {
        String convId = "conv-rem-empty";
        assertTrue(service.peekSuspendedRemainingTasks(convId).isEmpty());
        seedBusyContext(convId, "F_1");
        assertTrue(service.peekSuspendedRemainingTasks(convId).isEmpty());
    }
}
