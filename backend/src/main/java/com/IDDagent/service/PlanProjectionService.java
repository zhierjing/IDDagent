package com.IDDagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 3（Plan 状态投影）：根据 Pipeline 执行事实单向更新 Plan 状态视图。
 *
 * <p>投影方向（实施文档第 11 节）：Pipeline execution → Plan state projection。
 * 禁止 Plan 反过来决定 Pipeline 执行什么——本服务只记录状态、打结构化日志
 * （PLAN_CREATED / PLAN_PROJECTED / PLAN_COMPLETED），不参与任何调度决策。
 *
 * <p>挂接点：管道计划创建（handleMulti）、任务开始（executePipeline/handleMultiResume）、
 * 暂停等待（handleSkill 的 info_needed/候选/模板选择）、报告等待（redirect）、
 * 任务完成（task_done）、管道完成（完成卡）。单技能独立执行无 PlanRuntime，
 * 投影方法静默跳过。
 */
@Component
public class PlanProjectionService {

    private static final Logger log = LoggerFactory.getLogger(PlanProjectionService.class);

    private final ContextMemoryService contextMemoryService;

    public PlanProjectionService(ContextMemoryService contextMemoryService) {
        this.contextMemoryService = contextMemoryService;
    }

    /**
     * 为多意图管道创建 PlanRuntime（步骤全部初始化为 PENDING）。
     * planId 由本服务生成（"P_" + UUID）；planSnapshot 为 pipelinePlan 快照
     * （每项含 skill/label/order）。仅活动帧持有一个活动计划。
     *
     * @return 新创建的 PlanRuntime（挂到会话上下文 planRuntime）
     */
    public PlanRuntime createPlan(String conversationId, String frameId,
                                  List<Map<String, Object>> planSnapshot) {
        PlanRuntime runtime = new PlanRuntime();
        runtime.planId = "P_" + UUID.randomUUID();
        runtime.frameId = frameId;
        runtime.currentStepIndex = -1;
        List<PlanStep> steps = new ArrayList<>();
        for (Map<String, Object> item : planSnapshot) {
            PlanStep step = new PlanStep();
            step.stepId = "ST_" + UUID.randomUUID();
            step.skillName = String.valueOf(item.getOrDefault("skill", ""));
            step.title = String.valueOf(item.getOrDefault("label", step.skillName));
            step.order = ((Number) item.getOrDefault("order", steps.size() + 1)).intValue();
            step.status = PlanStepStatus.PENDING;
            steps.add(step);
        }
        runtime.steps = steps;
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(conversationId);
        ctx.planRuntime = runtime;
        log.info("PLAN_CREATED planId={} frameId={} steps={} conv={}",
                runtime.planId, frameId, steps.size(), conversationId);
        return runtime;
    }

    /**
     * 确保活动计划存在（恢复路径兜底）：planRuntime 丢失（如后端重启后从对话历史
     * 重建计划快照）时按快照重建，状态从 PENDING 开始；已存在则原样保留（不覆盖
     * 已投影的步骤状态）。
     */
    public void ensurePlan(String conversationId, String frameId, List<Map<String, Object>> planSnapshot) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(conversationId);
        if (ctx.planRuntime == null && planSnapshot != null && !planSnapshot.isEmpty()) {
            createPlan(conversationId, frameId, planSnapshot);
        }
    }

    /** 任务开始执行：步骤 RUNNING + 计划整体 RUNNING */
    public void markStepRunning(String conversationId, int order) {
        PlanRuntime runtime = runtime(conversationId);
        if (runtime == null) return;
        PlanStep step = runtime.stepByOrder(order);
        if (step == null) return;
        step.status = PlanStepStatus.RUNNING;
        runtime.currentStepIndex = indexOf(runtime, step);
        runtime.status = PlanRuntime.PlanStatus.RUNNING;
        logProjected(runtime, step, "RUNNING", conversationId);
    }

    /** 等待用户补充输入（info_needed/候选选择/模板选择等暂停态）：步骤 WAITING_INPUT */
    public void markStepWaitingInput(String conversationId, int order) {
        PlanRuntime runtime = runtime(conversationId);
        if (runtime == null) return;
        PlanStep step = runtime.stepByOrder(order);
        if (step == null) return;
        step.status = PlanStepStatus.WAITING_INPUT;
        runtime.status = PlanRuntime.PlanStatus.WAITING_INPUT;
        logProjected(runtime, step, "WAITING_INPUT", conversationId);
    }

    /** 等待异步外部结果（报告提交 H5 生成）：步骤 WAITING_EXTERNAL */
    public void markStepWaitingExternal(String conversationId, int order) {
        PlanRuntime runtime = runtime(conversationId);
        if (runtime == null) return;
        PlanStep step = runtime.stepByOrder(order);
        if (step == null) return;
        step.status = PlanStepStatus.WAITING_EXTERNAL;
        runtime.status = PlanRuntime.PlanStatus.WAITING_EXTERNAL;
        logProjected(runtime, step, "WAITING_EXTERNAL", conversationId);
    }

    /** 任务成功完成（task_done 事件）：步骤 DONE；全部完成时调用方再触发 markPlanCompleted */
    public void markStepDone(String conversationId, int order) {
        PlanRuntime runtime = runtime(conversationId);
        if (runtime == null) return;
        PlanStep step = runtime.stepByOrder(order);
        if (step == null) return;
        step.status = PlanStepStatus.DONE;
        logProjected(runtime, step, "DONE", conversationId);
    }

    /** 管道全部任务完成：计划整体 COMPLETED */
    public void markPlanCompleted(String conversationId) {
        PlanRuntime runtime = runtime(conversationId);
        if (runtime == null) return;
        runtime.status = PlanRuntime.PlanStatus.COMPLETED;
        log.info("PLAN_COMPLETED planId={} frameId={} conv={}",
                runtime.planId, runtime.frameId, conversationId);
    }

    /** 管道被放弃/清空：计划整体 ABANDONED（各步骤保持最后投影状态） */
    public void markPlanAbandoned(String conversationId) {
        PlanRuntime runtime = runtime(conversationId);
        if (runtime == null) return;
        runtime.status = PlanRuntime.PlanStatus.ABANDONED;
        log.info("PLAN_ABANDONED planId={} frameId={} conv={}",
                runtime.planId, runtime.frameId, conversationId);
    }

    private PlanRuntime runtime(String conversationId) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(conversationId);
        return ctx != null ? ctx.planRuntime : null;
    }

    private int indexOf(PlanRuntime runtime, PlanStep target) {
        for (int i = 0; i < runtime.steps.size(); i++) {
            if (runtime.steps.get(i) == target) return i;
        }
        return -1;
    }

    private void logProjected(PlanRuntime runtime, PlanStep step, String status, String conversationId) {
        log.info("PLAN_PROJECTED planId={} frameId={} step={} order={} status={} conv={}",
                runtime.planId, runtime.frameId, step.stepId, step.order, status, conversationId);
    }
}
