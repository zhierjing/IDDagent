package com.IDDagent.controller;

import com.IDDagent.model.*;
import com.IDDagent.service.*;
import com.IDDagent.skill.IntentMatcher;
import com.IDDagent.skill.IntentTreeMatcher;
import com.IDDagent.skill.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String INTENT_SELECT_PREFIX = "【意图选择】";
    // 模板选择消息协议前缀：前端模板卡片点击后发送「【模板选择】<template_id>」文本消息，
    // 后端在 generate_report 重入时解析注入 template_id（见 handleSkill）
    private static final String TEMPLATE_SELECT_PREFIX = "【模板选择】";
    // 意图穿插：用户对"是否继续旧管道"询问的回答前缀（前端 InterruptAskCard 按钮发送）
    private static final String PIPELINE_RESUME_PREFIX = "【管道恢复】";
    private static final String PIPELINE_RESUME_YES = "继续";
    private static final String PIPELINE_RESUME_NO = "放弃";

    /** 管道暂停期间用户消息归类（意图穿插判定结果） */
    private enum InputClass { PIPELINE_SUPPLEMENT, NEW_INTENT }

    private final ConversationService conversationService;
    private final ContextMemoryService contextMemoryService;
    private final CoordinatorService coordinatorService;
    private final FollowUpService followUpService;
    private final AgentService agentService;
    private final SkillRegistry skillRegistry;
    private final TaskPlanner taskPlanner;
    private final IntentTreeMatcher intentTreeMatcher;
    private final PlanProjectionService planProjectionService;
    private final IntentInterruptClassifier intentInterruptClassifier;
    private final ReportTaskStore reportTaskStore;

    public ChatController(ConversationService conversationService,
                          ContextMemoryService contextMemoryService,
                          CoordinatorService coordinatorService,
                          FollowUpService followUpService,
                          AgentService agentService,
                          SkillRegistry skillRegistry,
                          TaskPlanner taskPlanner,
                          IntentTreeMatcher intentTreeMatcher,
                          PlanProjectionService planProjectionService,
                          IntentInterruptClassifier intentInterruptClassifier,
                          ReportTaskStore reportTaskStore) {
        this.conversationService = conversationService;
        this.contextMemoryService = contextMemoryService;
        this.coordinatorService = coordinatorService;
        this.followUpService = followUpService;
        this.agentService = agentService;
        this.skillRegistry = skillRegistry;
        this.taskPlanner = taskPlanner;
        this.intentTreeMatcher = intentTreeMatcher;
        this.planProjectionService = planProjectionService;
        this.intentInterruptClassifier = intentInterruptClassifier;
        this.reportTaskStore = reportTaskStore;
    }

    /**
     * 强制终止当前对话的流式生成
     * 前端点击"停止"按钮时调用，配合前端断开 SSE 连接（AbortController）双保险生效
     */
    @PostMapping("/chat/stop")
    public Mono<Map<String, Object>> stopChat(@RequestBody Map<String, String> body,
                                              @RequestAttribute("currentUser") UserInfo currentUser) {
        String conversationId = body.get("conversationId");
        Map<String, Object> resp = new LinkedHashMap<>();
        if (conversationId == null || conversationId.isBlank()) {
            resp.put("ok", false);
            resp.put("message", "conversationId 不能为空");
            return Mono.just(resp);
        }
        // 仅允许终止当前用户自己的会话
        Map<String, Conversation> userConvs = conversationService.getUserConvs(currentUser.getId());
        if (!userConvs.containsKey(conversationId)) {
            resp.put("ok", false);
            resp.put("message", "会话不存在");
            return Mono.just(resp);
        }
        contextMemoryService.cancel(conversationId);
        log.info("Chat stop requested for conversation: {}", conversationId);
        resp.put("ok", true);
        return Mono.just(resp);
    }

    /**
     * 报告生成完成通知：前端轮询到报告状态 completed 后调用，推进被挂起的多意图管道。
     * 幂等：无等待报告任务（waitingReportTask）时直接返回，不重复推进。
     * - 最后任务完成：持久化最终完成卡（kind=complete）+ 清理计划快照，返回 allDone=true
     * - 中间任务完成：把 pendingPipeline 首项升级为 pendingSkill（用户下一条消息 resume 续跑），
     *   返回 allDone=false 与剩余任务数
     * - 报告穿插期间完成（waitingReportTask 已被挂起栈快照压栈）→ 置 pendingReportDone
     *   标记，返回 deferred=true（前端跳过本地卡片处理，恢复该挂起层时由后端就地推进）
     */
    @PostMapping("/chat/report-completed")
    public Mono<Map<String, Object>> reportCompleted(@RequestBody Map<String, String> body,
                                                     @RequestAttribute("currentUser") UserInfo currentUser) {
        String conversationId = body.get("conversationId");
        Map<String, Object> resp = new LinkedHashMap<>();
        if (conversationId == null || conversationId.isBlank()) {
            resp.put("ok", false);
            resp.put("message", "conversationId 不能为空");
            return Mono.just(resp);
        }
        Map<String, Conversation> userConvs = conversationService.getUserConvs(currentUser.getId());
        if (!userConvs.containsKey(conversationId)) {
            resp.put("ok", false);
            resp.put("message", "会话不存在");
            return Mono.just(resp);
        }
        Conversation conv = userConvs.get(conversationId);
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(conversationId);
        // Phase 1（frameId）：透传请求体可选 frameId（前端轮询时记录的任务标识），
        // 用于日志定位与响应回显；归属定位逻辑第一阶段保持现状（按 waitingReportTask/挂起栈）
        String bodyFrameId = body.get("frameId");
        String reportId = body.get("reportId");
        log.info("REPORT_COMPLETED_CALL conv={} bodyFrameId={} hasWaitingTask={}", conversationId, bodyFrameId,
                ctx.waitingReportTask != null);
        if (ctx.waitingReportTask == null) {
            // 报告穿插期间完成：waitingReportTask 已被挂起栈快照压栈（当前为空），
            // 置 pendingReportDone 标记推迟到用户恢复该挂起层时消费推进；返回
            // deferred=true 让前端跳过本地卡片处理（恢复时由后端统一推进）
            // Phase 7（DeferredEvent）：优先按 externalTaskId（reportId）精确归属——登记在册的
            // 任务完成时向所属挂起帧入 REPORT_COMPLETED 事件（文档第 38 节：回调按
            // externalTaskId/frameId 定位，不依赖当前活动状态）；未登记/无法定位时
            // 回退"挂起栈中第一个含 waitingReport 快照的帧"兜底
            // 无等待任务时：报告穿插期间完成 → defer 到恢复时推进；否则幂等返回 skipped
            String deferredFrameId = contextMemoryService.deferReportCompleted(conversationId, reportId);
            if (deferredFrameId.isEmpty()
                    && contextMemoryService.suspendedStackHasWaitingReport(conversationId)) {
                deferredFrameId = contextMemoryService.findWaitingReportFrameId(conversationId);
            }
            if (!deferredFrameId.isEmpty()) {
                // 兼容布尔保留（既有恢复消费路径依赖；与帧内事件并存，双轨兜底）
                ctx.pendingReportDone = true;
                log.info("EXTERNAL_EVENT_DEFERRED frameId={} event=REPORT_COMPLETED externalTaskId={} conv={}",
                        deferredFrameId, reportId == null ? "" : reportId, conversationId);
                markReportConsumed(reportId);
                resp.put("ok", true);
                resp.put("deferred", true);
                resp.put("frameId", deferredFrameId);
                return Mono.just(resp);
            }
            // 幂等：无挂起的报告任务（已推进过或非管道任务），直接返回
            markReportConsumed(reportId);
            resp.put("ok", true);
            resp.put("skipped", true);
            resp.put("frameId", bodyFrameId == null ? "" : bodyFrameId);
            return Mono.just(resp);
        }
        // 正常推进路径：waitingReportTask 为当前活动状态（报告等待期间未穿插，
        // 或穿插恢复后仍在等待报告完成）
        boolean allDone = advanceWaitingReportCore(conversationId, conv);
        markReportConsumed(reportId);
        resp.put("ok", true);
        resp.put("completed", true);
        resp.put("allDone", allDone);
        resp.put("frameId", ctx.currentFrameId);
        if (!allDone) {
            ContextMemoryService.ConversationContext cur = contextMemoryService.get(conversationId);
            resp.put("remaining", cur.pendingPipeline != null ? cur.pendingPipeline.size() : 0);
        } else if (ctx.hasSuspendedPipeline()) {
            // 本管道全部完成但挂起栈仍有旧管道（穿插场景）：显式发出询问卡提示是否继续。
            // report-completed 为普通 HTTP 接口（非 SSE 流），无法推送 interrupt_ask 事件，
            // 故在响应中携带询问数据，由前端本地注入卡片；置 interruptAskPending 阻止后续
            // SSE 流尾 interruptAskCheck 重复询问
            ctx.interruptAskPending = true;
            Map<String, Object> askData = buildInterruptAskData(conversationId);
            persistPipelineCard(conv, askData);
            resp.put("hasSuspended", true);
            resp.put("interruptAsk", askData);
        }
        return Mono.just(resp);
    }

    /** 报告任务消费标记：report-completed 有效处理（推进/幂等跳过/推迟）后调用，
     *  使该报告不再出现在 /active、/pending 轮询结果中，避免 3s 轮询反复调用刷屏 */
    private void markReportConsumed(String reportId) {
        if (reportId == null || reportId.isEmpty()) return;
        ReportTaskStore.ReportTask task = reportTaskStore.getTask(reportId);
        if (task != null) task.markConsumed();
    }

    /** 构造"是否继续旧管道"询问数据（挂起栈栈顶剩余任务摘要），供 SSE 流尾
     *  emitInterruptAsk 与 report-completed 推进后显式询问两处复用 */
    private Map<String, Object> buildInterruptAskData(String convId) {
        // 剩余未完成任务摘要（暂停中的任务 + 剩余任务队列）：完整计划快照（peekSuspendedPlan）
        // 含已完成任务，直接取 size 会把已完成任务计入"未完成"（如任务 1 完成后穿插，
        // 询问仍显示 N 项任务未完成）——改用未完成任务统计
        List<Map<String, Object>> remaining = contextMemoryService.peekSuspendedRemainingTasks(convId);
        if (remaining.isEmpty()) {
            // 防御：异常空帧（理论不可达）回退完整计划快照，避免"0 项任务未完成"文案
            remaining = contextMemoryService.peekSuspendedPlan(convId);
        }
        // 兑底：旧快照反查不到 label（如内存快照丢失）时用技能注册表补全
        for (Map<String, Object> task : remaining) {
            if (!task.containsKey("label")) {
                task.put("label", skillRegistry.getSkillLabel((String) task.get("skill")));
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", "interrupt_ask");           // 前端按 extra.action 路由渲染 InterruptAskCard
        data.put("frameId", contextMemoryService.peekSuspendedFrameId(convId)); // Phase 1：被询问层任务标识
        // Phase 6（交互 ID 化）：恢复卡携带 interactionId（Phase 5 起按钮已发结构化协议，
        // 回传一并带 interactionId 便于日志定位；恢复动作校验仍以 frameId 为准）
        data.put("interactionId", newInteractionId());
        data.put("message", "您之前还有 " + remaining.size() + " 项任务未完成，是否继续执行？");
        data.put("plan_summary", remaining);            // 剩余任务摘要 [{skill,label,order},...]
        data.put("total", remaining.size());
        return data;
    }

    /**
     * 推进被挂起的等待报告任务（report-completed 接口与恢复含 waitingReport 挂起层共用）。
     * 前置条件：ctx.waitingReportTask 非空。返回 true 表示报告是管道最后一个任务（全部完成）；
     * false 表示中间任务（pendingPipeline 首项已升级为 pendingSkill，由 handleMultiResume 续跑）。
     * - 最后任务完成：持久化最终完成卡（kind=complete）+ 清理计划快照
     * - 中间任务完成：把 pendingPipeline 首项升级为 pendingSkill
     */
    private boolean advanceWaitingReportCore(String conversationId, Conversation conv) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(conversationId);
        if (ctx.waitingReportTask == null) return false;
        int reportOrder = (int) ctx.waitingReportTask.getOrDefault("order", 0);
        // 完整计划快照（含已完成任务）：优先内存快照，丢失时从对话历史恢复
        List<Map<String, Object>> fullPlan = (ctx.pipelinePlan != null && !ctx.pipelinePlan.isEmpty())
                ? new ArrayList<>(ctx.pipelinePlan) : findPipelinePlanFromHistory(conv);
        int total = fullPlan != null ? fullPlan.size() : reportOrder;
        // 更新已持久化的初始执行计划卡（首卡）进度
        updatePipelinePlanCardOrder(conv, reportOrder);
        // Phase 7（PendingExternalTask）：就地消费路径（活动帧直接推进）→ 外部任务完成登记
        if (ctx.externalTasks != null) {
            Object rid = ctx.waitingReportTask.get("report_id");
            if (rid != null) {
                PendingExternalTask t = ctx.externalTasks.get(String.valueOf(rid));
                if (t != null) {
                    ctx.externalTasks.put(String.valueOf(rid),
                            t.withStatus(PendingExternalTask.ExternalTaskStatus.COMPLETED));
                }
            }
        }
        ctx.waitingReportTask = null;
        if (reportOrder >= total) {
            // 报告任务为管道最后一个任务：全部完成 → 持久化最终完成卡 + 清理计划快照
            if (fullPlan != null && !fullPlan.isEmpty()) {
                Map<String, Object> completeCard = new LinkedHashMap<>();
                completeCard.put("action", "pipeline");
                completeCard.put("kind", "complete");
                completeCard.put("plan", fullPlan);
                completeCard.put("total", fullPlan.size());
                completeCard.put("currentOrder", fullPlan.size());
                completeCard.put("paused", false);
                completeCard.put("completed", true);
                persistPipelineCard(conv, completeCard);
                // 全部完成：同步标记 plan/switch 卡完成态（与 executePipeline 完成分支一致）
                markPipelineCardsCompleted(conv);
                // Phase 3（Plan 投影）：报告为最后任务 → 计划整体 COMPLETED
                planProjectionService.markPlanCompleted(conversationId);
            }
            if (ctx.pipelinePlan != null) ctx.pipelinePlan.clear();
            log.info("Pipeline completed after report generation: conv={}, order={}/{}",
                    conversationId, reportOrder, total);
            return true;
        }
        // 报告任务为中间任务：把 pendingPipeline 首项升级为 pendingSkill，
        // 用户 resume 时续跑剩余任务
        if (ctx.pendingPipeline != null && !ctx.pendingPipeline.isEmpty()) {
            Map<String, Object> next = ctx.pendingPipeline.remove(0);
            Map<String, Object> params = next.get("params") instanceof Map
                    ? (Map<String, Object>) next.get("params") : new LinkedHashMap<>();
            contextMemoryService.setPendingSkill(conversationId, (String) next.get("skill"), params);
            log.info("Pipeline advanced after report generation: conv={}, task {}/{} done, next pendingSkill={}",
                    conversationId, reportOrder, total, next.get("skill"));
        }
        return false;
    }

    /**
     * 恢复含 waitingReport 挂起层后的推进判定（报告生成期间穿插的意图恢复时调用）：
     * - pendingReportDone（穿插期间报告已完成）→ 消费标记，就地推进管道（advanceWaitingReportCore）：
     *   报告是最后任务（allDone）→ task_done 收尾（前端 done 事件追加绿色完成卡）；
     *   中间任务 → pendingPipeline 首项已升级 pendingSkill，handleMultiResume 续跑剩余任务
     * - 报告仍在生成（pendingReportDone=false）→ pipeline_paused 等待提示；此时 waitingReportTask
     *   已恢复为活动状态，前端轮询 report-completed 检测到报告完成会走正常推进路径
     */
    private Flux<String> advanceOrWaitReport(String convId, String userId, Conversation conv) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        // 恢复报告等待层：穿插前段落中的旧跳转卡（stage=redirect）已由恢复路径补发的
        // progress/redirect 卡取代，持久化 hidden 标记使其跨"切走再切回"保持隐藏
        hideSupersededRedirectCard(conv);
        // Phase 7（DeferredEvent）：恢复时消费帧内挂起期间到达的异步事件——REPORT_COMPLETED
        // 事件驱动就地推进（文档第 40 节：consumeDeferredEvents 后决定继续 Pipeline 或等待）；
        // 兼容布尔 pendingReportDone 保留兜底（事件缺失时，如未登记的外部任务）
        boolean reportDone = ctx.pendingReportDone || hasReportCompletedEvent(ctx.activeDeferredEvents);
        if (!reportDone) {
            // v4：区分"报告任务未创建"与"报告仍在生成"——穿插前用户跳转 H5 编辑页
            // 但未上传附件生成即关闭时报告任务从未创建（waitingReportTask 无 report_id），
            // 若仍按"生成中"等待，前端轮询 pending 永远为空、report-completed 永不触发，
            // 管道永久挂起。此时重新发出 redirect 卡片引导用户再次跳转编辑页面上传附件
            Map<String, Object> waitingTask = ctx.waitingReportTask;
            boolean reportTaskCreated = waitingTask != null
                    && waitingTask.get("report_id") != null
                    && !String.valueOf(waitingTask.get("report_id")).isEmpty();
            if (!reportTaskCreated) {
                return reemitRedirectCard(convId, waitingTask);
            }
            // 任务已创建（报告生成中）：补发 report_generate_result（stage=progress）
            // 进度卡 + pipeline_paused 等待提示，使穿插恢复点之后的段落出现进度卡
            return Flux.concat(
                    reportProgressFlux(convId, waitingTask),
                    Flux.just(sseEvent("pipeline_paused",
                            Map.of("hint", "报告仍在生成中，请稍候，生成完成后将自动继续",
                                    "frameId", ctx.currentFrameId), null, convId)));
        }
        // 穿插期间报告已完成 → 消费标记与帧内事件，就地推进
        ctx.pendingReportDone = false;
        ctx.activeDeferredEvents.clear();
        Map<String, Object> reportTask = ctx.waitingReportTask;
        boolean allDone = advanceWaitingReportCore(convId, conv);
        log.info("Deferred report advanced on resume, conv: {}, allDone: {}", convId, allDone);
        // 报告已完成：先补发 progress 卡（生成完成态），再就地推进管道
        Flux<String> progressEvent = reportProgressFlux(convId, reportTask);
        if (!allDone) {
            // 报告是中间任务：续跑剩余任务（防御：无 pendingSkill 的异常状态直接输出文本）
            if (contextMemoryService.get(convId).hasPendingSkill()) {
                return Flux.concat(progressEvent, handleMultiResume(convId, userId, conv, ""));
            }
            return Flux.concat(progressEvent,
                    Flux.just(sseEvent("text_delta", Map.of("content", "报告已完成"), null, convId),
                            sseEvent("text_done", Map.of("content", "报告已完成"), null, convId)));
        }
        // 报告是最后任务：全部完成 → task_done 收尾，前端 done 事件据此追加绿色完成卡
        int order = reportTask != null ? (int) reportTask.getOrDefault("order", 0) : 0;
        String skill = reportTask != null ? (String) reportTask.get("skill") : "";
        Map<String, Object> doneData = new LinkedHashMap<>();
        doneData.put("order", order);
        doneData.put("skill", skill);
        doneData.put("label", skillRegistry.getSkillLabel(skill));
        doneData.put("frameId", ctx.currentFrameId); // Phase 1：任务标识
        return Flux.concat(progressEvent, Flux.just(sseEvent("task_done", doneData, null, convId)));
    }

    /** Phase 7（DeferredEvent）：恢复缓存中是否含 REPORT_COMPLETED 事件（事件驱动消费判定） */
    private boolean hasReportCompletedEvent(List<DeferredEvent> events) {
        if (events == null) return false;
        return events.stream().anyMatch(e -> e.type() == DeferredEvent.EventType.REPORT_COMPLETED);
    }

    /**
     * v4：穿插恢复时报告任务未创建（用户跳转 H5 编辑页后未上传附件生成即关闭）：
     * 重新发出 report_generate_result（stage=redirect）卡片，引导用户再次跳转
     * 编辑页面上传附件生成报告——否则管道等待一个永远不会完成的任务而永久挂起。
     * 模板信息来自 waitingReportTask 快照（redirect 阶段已随任务一并保存）。
     */
    private Flux<String> reemitRedirectCard(String convId, Map<String, Object> waitingTask) {
        if (waitingTask == null || waitingTask.isEmpty()) {
            String msg = "报告尚未开始生成，请重新发起报告生成";
            return Flux.just(
                    sseEvent("text_delta", Map.of("content", msg), null, convId),
                    sseEvent("text_done", Map.of("content", msg), null, convId));
        }
        Map<String, Object> redirectData = new LinkedHashMap<>();
        redirectData.put("action", "result");
        redirectData.put("_skill_name", "generate_report");
        redirectData.put("stage", "redirect");
        // 标记 supersede：前端据此移除穿插前段落中的旧跳转卡（避免旧卡残留对话流）
        redirectData.put("supersede_redirect", true);
        for (String key : List.of("template_id", "template_name", "template_icon",
                "template_description", "accepted_types", "required_fields",
                "organization", "message")) {
            Object v = waitingTask.get(key);
            if (v != null) redirectData.put(key, v);
        }
        redirectData.putIfAbsent("message", "请在报告编辑页面中上传附件并生成报告");
        // 透传管道序号（前端进度卡定位暂停任务用；单技能场景无清单卡，忽略即可）
        if (waitingTask.get("order") instanceof Number) {
            redirectData.put("_multi_index", ((Number) waitingTask.get("order")).intValue() - 1);
        }
        log.info("Re-emit redirect card on resume, conv: {}, report task not created", convId);
        return Flux.just(sseEvent("report_generate_result", redirectData, null, convId));
    }

    /**
     * 恢复报告等待层时，将穿插前段落中最后一张"报告生成跳转卡"（stage=redirect）
     * 持久化标记 hidden=true：该卡已被恢复路径补发的新 progress/redirect 卡取代，
     * 需在对话时间线上隐藏；标记随消息 JSON 落盘，切换会话/刷新后仍保持隐藏。
     */
    @SuppressWarnings("unchecked")
    private void hideSupersededRedirectCard(Conversation conv) {
        List<Message> messages = conv.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (!"assistant".equals(msg.getRole())) continue;
            try {
                Map<String, Object> content = mapper.readValue(msg.getContent(),
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
                if (content != null && "result".equals(content.get("action"))
                        && "generate_report".equals(content.get("_skill_name"))
                        && "redirect".equals(content.get("stage"))
                        && !Boolean.TRUE.equals(content.get("hidden"))) {
                    content.put("hidden", true);
                    msg.setContent(mapper.writeValueAsString(content));
                    conversationService.persist();
                    log.info("Superseded redirect card hidden, conv: {}", conv.getId());
                    return;
                }
            } catch (Exception e) {
                // 非 JSON 或解析失败的消息（如普通文本回复）直接跳过
            }
        }
    }

    /**
     * 恢复报告等待层且报告任务已创建时，补发 report_generate_result（stage=progress）
     * 进度卡事件：前端据此在穿插恢复点之后的段落插入进度卡（并 supersede 取代旧跳转卡）。
     * waitingTask 无 report_id（任务未创建）时返回空流，由 reemitRedirectCard 兜底。
     */
    private Flux<String> reportProgressFlux(String convId, Map<String, Object> waitingTask) {
        if (waitingTask == null) return Flux.empty();
        Object reportId = waitingTask.get("report_id");
        if (reportId == null || String.valueOf(reportId).isEmpty()) return Flux.empty();
        Map<String, Object> progressData = new LinkedHashMap<>();
        progressData.put("action", "result");
        progressData.put("_skill_name", "generate_report");
        progressData.put("stage", "progress");
        progressData.put("report_id", String.valueOf(reportId));
        // 标记 supersede：前端据此移除穿插前段落中的旧跳转卡（supersede 语义）
        progressData.put("supersede_redirect", true);
        // 透传模板信息供进度卡标题展示（无则忽略）
        for (String key : List.of("template_name", "template_icon", "organization")) {
            Object v = waitingTask.get(key);
            if (v != null) progressData.put(key, v);
        }
        log.info("Re-emit progress card on resume, conv: {}, report: {}", convId, reportId);
        return Flux.just(sseEvent("report_generate_result", progressData, null, convId));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @RequestBody ChatRequest body,
            @RequestAttribute("currentUser") UserInfo currentUser) {

        System.out.println("===== 请求到达 ChatController！消息: " + body.getMessage());
        System.out.println("===== 当前用户: " + currentUser.getId());

        String userId = currentUser.getId();
        Map<String, Conversation> userConvs = conversationService.getUserConvs(userId);

        // 获取或创建会话（同步快速）
        String conversationId = body.getConversationId();
        Conversation conv;
        if (conversationId == null || !userConvs.containsKey(conversationId)) {
            conv = conversationService.createConversation(userId, "新对话");
            conversationId = conv.getId();
        } else {
            conv = userConvs.get(conversationId);
        }

        // 存储用户消息
        String userMsgId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        // 附件信息：汇总到消息内容中传递给 LLM
        List<Map<String, Object>> attachments = body.getAttachments();
        String enhancedMessage = body.getMessage();
        if (attachments != null && !attachments.isEmpty()) {
            StringBuilder sb = new StringBuilder(body.getMessage());
            sb.append("\n\n[用户上传了以下附件：");
            for (int i = 0; i < attachments.size(); i++) {
                Map<String, Object> att = attachments.get(i);
                String name = (String) att.getOrDefault("name", "未知文件");
                sb.append(i > 0 ? "、" : "").append(name);
                // 保留 url 等信息供前端展示
                att.put("id", "att-" + userMsgId + "-" + i);
            }
            sb.append("]");
            enhancedMessage = sb.toString();
        }

        // 保存附件 URL 到会话上下文，供技能通过 _attachment_url 参数使用（如营业执照信息核实）
        if (attachments != null && !attachments.isEmpty()) {
            Object firstUrl = attachments.get(0).get("url");
            if (firstUrl instanceof String s && !s.isEmpty()) {
                contextMemoryService.updateAttachment(conversationId, s);
                log.info("Attachment URL saved to context: {}", s);
            }
        }

        Message userMsg = new Message(userMsgId, "user", body.getMessage(), now);
        userMsg.setAttachments(attachments);
        conv.getMessages().add(userMsg);
        conv.setUpdatedAt(now);
        // 消息追加后立即落盘：对话记录实时写入 data/conversations.json，
        // 否则消息只存内存、后端重启后全部丢失（persist 原本仅在创建/删除会话时触发）
        conversationService.persist();

        // 首次消息设置标题
        if (conv.getMessages().size() == 1) {
            conv.setCreatedAt(now);
            String title = body.getMessage();
            conv.setTitle(title.length() > 30 ? title.substring(0, 30) + "..." : title);
        }

        final String convId = conversationId;
        final Conversation finalConv = conv;
        final String finalMessage = enhancedMessage;

        // 每次新消息开始时重置该会话的终止标记（强制终止后允许继续对话）
        contextMemoryService.clearCancelled(convId);

        // 检查是否有待处理技能（上次技能正在等待用户补充信息）
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        boolean hasPendingSkill = ctx.hasPendingSkill();
        boolean hasPendingPipeline = ctx.hasPendingPipeline();

        // 初始事件
        String thinkingText = (hasPendingSkill || hasPendingPipeline) ? "正在查询，请稍候..." : "正在分析您的问题...";
        Flux<String> initEvent = Flux.just(sseEvent("thinking",
                Map.of("content", thinkingText), null, convId));

        // 主流程：结构化恢复协议 → 候选否决兑底 → isWaitingReport（报告生成中，v3 支持穿插）
        // →【管道恢复】前缀（穿插询问回答）→ interruptAskPending 自然语言兑底
        // → (pendingPipeline|pendingSkill) 穿插判定 → 前缀 → routeIntent
        Flux<String> mainFlow;
        // Phase 5（Structured Resume）：结构化恢复协议（resume_frame/abandon_frame + frameId）
        // 优先于一切普通判定（文档第 14 节：结构化协议识别成功后禁止继续进入普通意图穿插分类）
        StructuredResumeAction resumeAction = StructuredResumeAction.parse(finalMessage);
        if (resumeAction != null) {
            mainFlow = handleStructuredResume(convId, userId, finalConv, resumeAction);
        } else if (StructuredInteractionAction.parse(finalMessage) != null) {
            // Phase 6（交互 ID 化）：结构化交互协议（select_candidate/select_intent/select_template
            // + interactionId + frameId）。优先于一切普通判定——交互卡点击必须先在主流程入口
            // 完成帧归属校验（文档第 44 节：点击挂起帧旧卡 → INTERACTION_SUSPENDED，禁止
            // 把挂起帧的交互值传给当前活动任务），校验通过后再转发等价文本走现有判定路径
            mainFlow = handleStructuredInteraction(convId, userId, finalConv,
                    StructuredInteractionAction.parse(finalMessage));
        } else if ("以上都不是".equals(finalMessage)) {
            // 候选否决兜底：用户在模糊匹配候选卡片点击"以上都不是"，表明所有候选均非目标企业。
            // candidates/ambiguous 卡片（CompanyNameSelector）弹出时已设置 pendingSkill，可经
            // pendingSkill 分支重入技能后在 handleSkill 拦截；但 not_found 带候选选项的卡片
            // （RiskCheckCard/InformationCheckCard/CompanyQueryCard 的 not_found 分支）弹出时
            // pendingSkill 已被清理（not_found 视为任务完成态），此消息若落到三层路由会被 LLM
            // 识别成 chat 闲聊，故在主流程统一拦截：返回友好提示并保留既有 pending 状态，
            // 用户下一条消息提供准确企业名后经原路径（pendingSkill 重入 / routeIntent）恢复查询。
            String rejectPrompt = "以上候选企业均不是您要找的目标，请提供准确的企业名称或统一信用代码，我将为您重新查询。";
            // 保留待处理技能（若有），但清空已否决的企业参数，避免下一轮上下文补全
            // 复用旧企业名再次触发模糊匹配重弹候选卡片（与 handleSkill 内拦截逻辑一致）
            if (ctx.hasPendingSkill()) {
                ctx.pendingSkillParams.remove("company_name");
                ctx.pendingSkillParams.remove("credit_code");
                // 标记该挂起技能已被用户否决：残留的 pendingSkill 仅供直接输入企业名时
                // 重入，不再视为"执行中任务"——用户随后发起的新意图不触发穿插压栈
                //（意图穿插仅服务于多意图管道场景，单意图对话不得出现中断提醒）
                ctx.pendingSkillRejected = true;
            }
            log.info("User rejected all candidates (以上都不是), kept pending state for re-query");
            mainFlow = Flux.just(
                    sseEvent("text_delta", Map.of("content", rejectPrompt), null, convId),
                    sseEvent("text_done", Map.of("content", rejectPrompt), null, convId)
            );
        } else if (ctx.isWaitingReport()) {
            // 异步报告仍在生成中（generate_report 跳转 H5 后挂起管道）。
            // v3：不再忽略消息——其余消息视为穿插新意图（报告生成中无参数可补充），
            // 挂起（waitingReportTask 随快照压栈）后执行；报告完成后由 report-completed
            // 推进（穿插期间完成则 pendingReportDone 标记，恢复该层时消费推进）。
            // 例外：带前缀的历史询问卡点击（报告等待期间栈顶可能仍为更早挂起层）——
            // 当前活动状态是报告等待，恢复会与等待中的报告冲突，故提示等待而非恢复
            if (finalMessage.startsWith(PIPELINE_RESUME_PREFIX)) {
                log.info("Resume prefix while report generating, waiting hint: {}", finalMessage);
                mainFlow = Flux.just(sseEvent("pipeline_paused",
                        Map.of("hint", "当前有报告正在生成，请等待生成完成后自动继续"), null, convId));
            } else {
                log.info("Interrupt during report generation, new intent: {}", finalMessage);
                mainFlow = handleInterrupt(convId, userId, finalConv, finalMessage);
            }
        } else if (finalMessage.startsWith(PIPELINE_RESUME_PREFIX)) {
            // 意图穿插：用户对"是否继续旧管道"询问的显式回答（【管道恢复】继续/放弃）
            log.info("Pipeline resume prefix detected: {}", finalMessage);
            mainFlow = handleInterruptAnswer(convId, userId, finalConv, finalMessage);
        } else if (ctx.interruptAskPending && !hasPendingSkill && !hasPendingPipeline) {
            // 意图穿插：询问已发出、用户未用前缀而直接输入 → 自然语言兜底
            // （回复"继续/不需要"或直接发起新问题三种情况，见 handleInterruptAnswerNatural）
            log.info("Interrupt ask pending, natural language answer: {}", finalMessage);
            mainFlow = handleInterruptAnswerNatural(convId, userId, finalConv, finalMessage);
        } else if (hasPendingPipeline || hasPendingSkill) {
            // 管道暂停期间先判定"补充信息 vs 新意图"（意图穿插核心）。
            // v2 统一判定（不再区分是否已穿插）：嵌套穿插时当前活动的仍是最近一次
            // 穿插的新意图管道，classifyPipelineInput 基于其 pendingSkill 判定；
            // 判为新意图 → 继续压栈挂起（挂起栈 LIFO，恢复时从栈顶逐层弹出）
            // Phase 6：结构化交互协议（select_candidate 等）校验通过后亦经此路径转发等效文本
            mainFlow = routePendingInput(convId, ctx, userId, finalConv, finalMessage);
        } else if (body.getMessage() != null && body.getMessage().startsWith(INTENT_SELECT_PREFIX)) {
            // 意图选择前缀：跳过 LLM，直接路由
            String skillName = body.getMessage().substring(INTENT_SELECT_PREFIX.length()).trim();
            if (skillRegistry.get(skillName) != null) {
                log.info("Intent select prefix detected, routing directly to skill: {}", skillName);
                Map<String, Object> decision = new LinkedHashMap<>();
                decision.put("action", "skill");
                decision.put("skill", skillName);
                decision.put("params", new LinkedHashMap<>());
                decision.put("reason", "用户意图选择");
                // 旧文本协议无帧信息（Phase 6 前旧卡/无任务帧会话的回退路径）：从最近一张澄清卡
                // 恢复原始输入并提取参数，与结构化 select_intent 同语义——避免企业名等参数丢失
                // 被上下文自动填充成上一轮企业（如"核实星河信息"被误填成小米科技）
                String originalInput = findClarifyCardInput(finalConv, null);
                if (originalInput != null && !originalInput.isEmpty()) {
                    log.info("INTENT_SELECT restoring original input: {}", originalInput);
                    mainFlow = coordinatorService.extractParamsForSkill(skillName, originalInput,
                                    finalConv.getMessages(), convId)
                            .flatMapMany(extracted -> {
                                if (!extracted.isEmpty()) {
                                    ((Map<String, Object>) decision.get("params")).putAll(extracted);
                                    log.info("INTENT_SELECT filled params from original input: {}", extracted);
                                }
                                return handleSkill(decision, convId, userId, finalConv, -1);
                            });
                } else {
                    mainFlow = handleSkill(decision, convId, userId, finalConv, -1);
                }
            } else {
                log.warn("Intent select prefix with unknown skill: {}, falling back to Coordinator", skillName);
                mainFlow = coordinatorService.routeIntent(finalMessage, finalConv.getMessages(), convId)
                        .flatMapMany(decision -> dispatchDecision(decision, convId, userId, finalConv, finalMessage));
            }
        } else if (body.getMessage() == null || body.getMessage().trim().isEmpty()) {
            log.info("Empty message with attachments, routing directly to chat for purpose inquiry");
            mainFlow = handleChat(convId, finalConv, finalMessage);
        } else {
            // 无待处理技能 → 走 Coordinator 三层路由
            mainFlow = coordinatorService.routeIntent(finalMessage, finalConv.getMessages(), convId)
                    .flatMapMany(decision -> dispatchDecision(decision, convId, userId, finalConv, finalMessage));
        }

        return initEvent.concatWith(mainFlow)
                // 意图穿插：新意图执行完毕、旧管道仍挂起时，流尾统一检查是否询问"是否继续旧管道"
                .concatWith(Flux.defer(() -> interruptAskCheck(convId, finalConv)))
                // 强制终止检查：一旦该会话被标记为取消，立即截断剩余事件流
                .takeWhile(e -> !contextMemoryService.isCancelled(convId))
                // 所有事件流结束后发送 done 事件
                .concatWith(Flux.just(sseEvent("done", Map.of("conversation_id", convId), null, convId)))
                .doOnSubscribe(s -> System.out.println("🔵 SSE Flux 被订阅!"))
                //.doOnNext(event -> System.out.println("📤 发送 SSE: " + event.substring(0, Math.min(120, event.length()))))
                .doOnComplete(() -> {
                    System.out.println("✅ SSE Flux 完成");
                    contextMemoryService.clearCancelled(convId);
                })
                .doOnCancel(() -> {
                    System.out.println("⏹️ SSE Flux 被取消（前端断开连接）");
                    contextMemoryService.clearCancelled(convId);
                })
                .doOnError(e -> log.error("Stream error", e))
                .onErrorResume(e -> Flux.just(sseEvent("error",
                        Map.of("content", "处理请求失败: " + e.getMessage()), null, null)));
    }

    /**
     * 统一分发决策：skill / chat / clarify / multi
     */
    private Flux<String> dispatchDecision(Map<String, Object> decision, String convId,
                                          String userId, Conversation conv, String userMessage) {
        String action = (String) decision.getOrDefault("action", "chat");
        return switch (action) {
            case "skill" -> handleSkill(decision, convId, userId, conv, -1);
            case "clarify" -> handleClarify(decision, convId, conv, userMessage);
            case "multi" -> handleMulti(decision, convId, userId, conv);
            default -> {
                // matchable 预留业务等固定文案（preset_reply）直接展示，不调用 LLM 闲聊
                String presetReply = (String) decision.get("preset_reply");
                if (presetReply != null && !presetReply.isEmpty()) {
                    yield handlePresetReply(convId, conv, presetReply);
                }
                yield handleChat(convId, conv, userMessage);
            }
        };
    }

    // ============================================================
    // 意图穿插：挂起-询问-恢复（中断旧管道，执行新意图，断点再续）
    // ============================================================

    /** Phase 1（frameId）：生成新任务的 frameId（一个独立意图 = 一个 frameId，
     *  贯穿 Runtime/挂起快照/SSE 事件/前端任务卡） */
    private String newFrameId() {
        return "F_" + UUID.randomUUID();
    }

    /** Phase 6（交互 ID 化）：生成交互卡 interactionId（一次交互卡事件一个 ID，
     *  随事件下发、点击回传，定位卡片属于哪个任务/哪次交互——文档第 43 节） */
    private String newInteractionId() {
        return "I_" + UUID.randomUUID();
    }

    /**
     * 技能等待用户补充信息时的重试流程（原主流程 hasPendingSkill 分支逻辑）。
     * 将用户最新消息作为 _user_input 重入暂停技能；连续重试 >=3 次仍未完成时
     * 降级走三层路由兜底，避免死循环。
     */
    private Flux<String> pendingSkillRetryFlow(String convId, String userId, Conversation conv, String userMessage) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        String pendingSkill = ctx.pendingSkillName;
        Map<String, Object> pendingParams = new LinkedHashMap<>(ctx.pendingSkillParams);

        if (ctx.pendingSkillRetry >= 3) {
            log.warn("Pending skill {} exceeded max retries, falling back to routeIntent", pendingSkill);
            contextMemoryService.clearPendingSkill(convId);
            return coordinatorService.routeIntent(userMessage, conv.getMessages(), convId)
                    .flatMapMany(decision -> dispatchDecision(decision, convId, userId, conv, userMessage));
        }

        // 重试计数 +1，携带用户补充信息重入技能
        ctx.pendingSkillRetry += 1;
        pendingParams.put("_user_input", userMessage);
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", "skill");
        decision.put("skill", pendingSkill);
        decision.put("params", pendingParams);
        decision.put("reason", "技能参数补充重试");
        contextMemoryService.clearPendingSkill(convId);
        log.info("Pending skill retry #{}, skill: {}", ctx.pendingSkillRetry, pendingSkill);
        return handleSkill(decision, convId, userId, conv, -1);
    }

    /**
     * 意图穿插核心：判定用户消息为新意图时，全量挂起旧管道（压入挂起栈）并执行新意图。
     * 嵌套穿插：若当前活动管道本身是之前穿插的新意图，suspendPipeline 将再次压栈。
     * 恢复旧管道的时机不由本方法负责，统一由流尾 interruptAskCheck 判定
     * （新意图到达稳定点后主动询问栈顶"是否继续旧管道"）。
     */
    private Flux<String> handleInterrupt(String convId, String userId, Conversation conv, String userMessage) {
        // 1. 全量挂起旧管道（含 pendingSkill 与 pipelinePlan 快照、frameId）→ 压入挂起栈
        boolean suspended = suspendCurrentForNewIntent(convId);

        // 2. 执行新意图（完整复用三层路由；新意图若是多意图管道，
        //    其 handleMulti/handleMultiResume 正常工作，互不干扰）
        Flux<String> flow = coordinatorService.routeIntent(userMessage, conv.getMessages(), convId)
                .flatMapMany(decision -> dispatchDecision(decision, convId, userId, conv, userMessage));
        // 3. 挂起栈达上限拒绝穿插：先提示旧任务已放弃（持久化文本），再执行新意图——
        //    拒绝语义 = 放弃当前活动管道，新意图必须正常执行（用户消息不丢失）
        return suspended ? flow : Flux.concat(handlePresetReply(convId, conv, stackFullHint()), flow);
    }

    /**
     * 挂起当前活动管道并分配新帧（新意图执行前）。供 handleInterrupt（新意图穿插）与
     * Phase 6 结构化交互协议 select_intent（有活动任务时选择新意图）共用，保证两条路径
     * 的挂起语义完全一致：旧帧随快照压栈，新帧继承父帧链。
     *
     * @return true=挂起成功；false=挂起栈已达深度上限（MAX_SUSPENDED_STACK_DEPTH）拒绝穿插，
     *         当前活动管道已被服务层放弃（suspendPipeline 拒绝语义），栈中已有层不受影响、
     *         仍可逐层恢复；调用方需提示用户并照常执行新意图
     */
    private boolean suspendCurrentForNewIntent(String convId) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        String oldFrameId = ctx.currentFrameId;
        // 1. 全量挂起旧管道（含 pendingSkill 与 pipelinePlan 快照、frameId）→ 压入挂起栈
        if (!contextMemoryService.suspendPipeline(convId)) {
            // 防御：嵌套穿插已达上限 → 拒绝本次穿插（旧活动管道已在服务层放弃）。
            // 新意图照常执行，但其父帧置空——被放弃的任务不再参与父帧链追踪
            log.warn("FRAME_STACK_FULL conv={} stackDepth={} abandonedFrameId={}",
                    convId, ctx.suspendedStack.size(), oldFrameId);
            ctx.currentFrameId = newFrameId();
            ctx.parentFrameId = "";
            return false;
        }
        log.info("FRAME_SUSPENDED frameId={} reason=NEW_INTENT conv={}", oldFrameId, convId);
        // Phase 1（frameId）：为新意图任务分配新 frameId（父 frame = 被挂起的旧任务；
        // 旧任务的 frameId 已随快照保存，恢复时回填）
        ctx.currentFrameId = newFrameId();
        ctx.parentFrameId = oldFrameId;
        log.info("FRAME_CREATED frameId={} parentFrameId={} intent=interrupt", ctx.currentFrameId, oldFrameId);
        return true;
    }

    /**
     * 意图穿插断点询问：新意图执行完毕、挂起栈仍有旧管道时，流尾统一检查是否询问
     * "是否继续旧管道"。一次接入覆盖所有新意图稳定点（单技能/多意图/聊天完成），
     * 无需在各处理路径逐点埋询问逻辑。
     * 幂等：interruptAskPending=true 期间不再触发（"放弃后继续询问下一层"场景由
     * handleInterruptAnswer 直接 emitInterruptAsk，避免流尾再次询问产生重复卡片）。
     */
    private Flux<String> interruptAskCheck(String convId, Conversation conv) {
        return Flux.defer(() -> {
            ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
            // 有挂起的旧管道（挂起栈非空）、未在等待回答，且当前意图完全结束
            // （无任何活动中的管道/技能/报告等待）→ 询问栈顶那一层
            if (ctx.hasSuspendedPipeline()
                    && !ctx.interruptAskPending
                    && !ctx.hasPendingSkill() && !ctx.hasPendingPipeline() && !ctx.isWaitingReport()) {
                return emitInterruptAsk(convId, conv);
            }
            return Flux.empty();
        });
    }

    /**
     * 发出"是否继续旧管道"询问（interrupt_ask 事件 + 持久化）。
     * 询问对象为挂起栈栈顶快照（LIFO：后挂起者先询问）。
     * 供流尾 interruptAskCheck 与"放弃后仍有下一层"两个场景复用。
     */
    private Flux<String> emitInterruptAsk(String convId, Conversation conv) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        ctx.interruptAskPending = true;
        Map<String, Object> data = buildInterruptAskData(convId);
        // 持久化为 assistant 消息（复用管道卡落盘模式），刷新/切换会话后
        // 前端历史恢复逻辑自动解析 extra 重新渲染，询问卡仍可点击
        persistPipelineCard(conv, data);
        return Flux.just(sseEvent("interrupt_ask", data, null, convId));
    }

    /**
     * Phase 5（Structured Resume）：处理结构化恢复协议（resume_frame/abandon_frame）。
     * frameId 校验（文档第 31 节）：仅当当前正询问栈顶层（interruptAskPending）且 frameId
     * 与栈顶帧一致时执行——Case 13：允许恢复 B 时点击旧 A 卡 → STALE_ACTION，禁止跳过
     * 栈顶直接恢复深层 Frame；活动任务执行中（无待回答询问）点击恢复卡同样拒绝，
     * 防止 pop 覆盖活动任务状态。校验通过后与旧文本协议共用 resumeTopFrame/abandonTopFrame。
     */
    private Flux<String> handleStructuredResume(String convId, String userId, Conversation conv,
                                                StructuredResumeAction action) {
        if (!contextMemoryService.isResumeActionValid(convId, action.frameId())) {
            log.info("STALE_ACTION action={} frameId={} conv={}", action.action(), action.frameId(), convId);
            return Flux.just(sseEvent("stale_action",
                    Map.of("frameId", action.frameId(), "message", "该任务已失效，请查看最新任务提醒"),
                    null, convId));
        }
        // 校验通过：标记询问卡已答复并落盘（与旧文本协议一致，刷新后保持置灰）
        markInterruptAskAnswered(conv);
        return action.isAbandon()
                ? abandonTopFrame(convId, userId, conv)
                : resumeTopFrame(convId, userId, conv, null);
    }

    /**
     * Phase 6（交互 ID 化）：处理结构化交互协议（select_candidate/select_intent/select_template）。
     * 帧归属校验（文档第 44 节/Case 14）：交互卡事件生成时携带生成帧的 frameId，点击回传后
     * 仅当 frameId 与当前活动帧一致才接受——A 挂起、B 活动时点击 A 的候选卡 →
     * INTERACTION_SUSPENDED，禁止把挂起帧的交互值传入当前活动任务 B（前端禁用只是 UX，
     * 后端必须执行 frameId 校验，文档第 45 节）。校验通过后转发等价文本走现有判定路径，
     * 语义与旧文本协议（【意图选择】/【模板选择】/候选确认消息）完全一致。
     */
    private Flux<String> handleStructuredInteraction(String convId, String userId, Conversation conv,
                                                     StructuredInteractionAction action) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        if (!contextMemoryService.isInteractionActive(convId, action.frameId())) {
            log.info("INTERACTION_SUSPENDED action={} frameId={} interactionId={} currentFrameId={} conv={}",
                    action.action(), action.frameId(), action.interactionId(), ctx.currentFrameId, convId);
            return Flux.just(sseEvent("interaction_suspended",
                    Map.of("frameId", action.frameId(), "interactionId", action.interactionId(),
                            "message", "该选择已失效（对应任务已挂起或完成），请查看最新任务提醒"),
                    null, convId));
        }
        log.info("INTERACTION_ACCEPTED action={} frameId={} interactionId={} conv={}",
                action.action(), action.frameId(), action.interactionId(), convId);
        switch (action.action()) {
            case StructuredInteractionAction.SELECT_INTENT -> {
                // 意图澄清选择：与文本协议【意图选择】同语义——有活动任务时挂起旧任务执行
                // 新意图（文本协议经分类器①判 NEW_INTENT → handleInterrupt 挂起；结构化协议
                // 直接精确路由，不经 LLM）；无活动任务时直接执行。未注册技能返回"暂未开通"提示
                String skillName = action.skill();
                if (skillRegistry.get(skillName) == null) {
                    // 未注册技能（如核实类澄清列出的 verify_natural_person）：直接返回"暂未开通"提示，
                    // 不得回退 routeIntent——协议无 input 字段，回退会把 "null" 当消息路由成闲聊
                    log.warn("Select intent with unregistered skill '{}', returning not-opened hint", skillName);
                    return handlePresetReply(convId, conv, notOpenedHint(skillName));
                }
                boolean suspended = true;
                if (ctx.hasPendingSkill() || ctx.hasPendingPipeline()) {
                    suspended = suspendCurrentForNewIntent(convId);
                }
                Map<String, Object> decision = new LinkedHashMap<>();
                decision.put("action", "skill");
                decision.put("skill", skillName);
                decision.put("params", new LinkedHashMap<>());
                decision.put("reason", "用户意图选择（结构化协议）");
                // 澄清卡点击：从卡消息恢复原始用户输入（frameId 归属校验），用 LLM 提取该技能参数，
                // 避免企业名等参数丢失——此前空 params 由上下文自动填充成上一轮企业（如"小米科技"），
                // 而用户在原始输入中已指定新企业（如"核实星河信息"）却被忽略
                String originalInput = findClarifyCardInput(conv, action.frameId());
                Flux<String> flow;
                if (originalInput == null || originalInput.isEmpty()) {
                    // 旧卡无 user_input（Phase 6 前）→ 维持空 params 行为，由上下文自动填充
                    flow = handleSkill(decision, convId, userId, conv, -1);
                } else {
                    log.info("SELECT_INTENT restoring original input: {}", originalInput);
                    flow = coordinatorService.extractParamsForSkill(skillName, originalInput, conv.getMessages(), convId)
                            .flatMapMany(extracted -> {
                                if (!extracted.isEmpty()) {
                                    ((Map<String, Object>) decision.get("params")).putAll(extracted);
                                    log.info("SELECT_INTENT filled params from original input: {}", extracted);
                                }
                                return handleSkill(decision, convId, userId, conv, -1);
                            });
                }
                // 挂起栈达上限拒绝穿插：先提示旧任务已放弃，再执行新意图（与 handleInterrupt 语义一致）
                return suspended ? flow : Flux.concat(handlePresetReply(convId, conv, stackFullHint()), flow);
            }
            case StructuredInteractionAction.SELECT_TEMPLATE -> {
                // 模板选择：转发等价文本【模板选择】<template_id> 走补充路径（分类器①判
                // SUPPLEMENT → pendingSkillRetryFlow 重入技能注入 template_id，与旧文本协议一致）
                String input = action.input();
                if (ctx.hasPendingSkill() || ctx.hasPendingPipeline()) {
                    return routePendingInput(convId, ctx, userId, conv,
                            input != null ? input : TEMPLATE_SELECT_PREFIX + action.frameId());
                }
                // 防御：无活动任务（理论不可达，模板卡生成时必设 pendingSkill）→ 直接以模板 ID 执行
                Map<String, Object> decision = new LinkedHashMap<>();
                decision.put("action", "skill");
                decision.put("skill", "generate_report");
                Map<String, Object> params = new LinkedHashMap<>();
                if (input != null && input.startsWith(TEMPLATE_SELECT_PREFIX)) {
                    params.put("template_id", input.substring(TEMPLATE_SELECT_PREFIX.length()).trim());
                }
                decision.put("params", params);
                decision.put("reason", "模板选择（结构化协议）");
                return handleSkill(decision, convId, userId, conv, -1);
            }
            default -> {
                // select_candidate（含 select_intent 未知 skill 兜底不落此处）：转发等效文本
                // （"公司：xxx\n统一信用代码：xxx" / 查询句）走 hasPending 分支完整判定路径，
                // 与旧文本协议语义完全一致（含 markCompanyCardConfirmed 标记与穿插判定）
                String input = action.input();
                if (input == null || input.isEmpty()) {
                    // 防御：协议缺 input 无法转发（前端正常实现必带）
                    log.warn("Select candidate without input, rejected: frameId={}", action.frameId());
                    return Flux.just(sseEvent("interaction_suspended",
                            Map.of("frameId", action.frameId(), "interactionId", action.interactionId(),
                                    "message", "该选择缺少必要参数，请重新操作"),
                            null, convId));
                }
                if (ctx.hasPendingSkill() || ctx.hasPendingPipeline()) {
                    return routePendingInput(convId, ctx, userId, conv, input);
                }
                // 防御：无活动任务（理论不可达，候选卡只在技能执行中生成）→ 三层路由兑底
                return coordinatorService.routeIntent(input, conv.getMessages(), convId)
                        .flatMapMany(decision -> dispatchDecision(decision, convId, userId, conv, input));
            }
        }
    }
    
    /**
     * 挂起栈达上限拒绝穿插时的提示文案（见 ContextMemoryService.MAX_SUSPENDED_STACK_DEPTH）：
     * 告知用户最早任务已放弃，新任务正常执行。
     */
    private String stackFullHint() {
        return "您同时穿插的任务数已达上限（" + ContextMemoryService.MAX_SUSPENDED_STACK_DEPTH
                + " 个），最早的任务已自动放弃；新任务已开始执行。";
    }

    /**
     * 未注册技能（预留叶子）点击后的固定提示文案（方案 B）：
     * verify_natural_person 为核实类澄清允许列出的未开通候选，追加引导文案；
     * 其余未注册技能（理论上不会出现在候选卡片）返回通用文案。
     */
    private String notOpenedHint(String skillName) {
        String label = switch (skillName) {
            case "verify_natural_person" -> "身份证核实";
            case "verify_contact_info" -> "通讯信息核实";
            case "query_risk_history" -> "历史风险记录查询";
            case "query_risk_score" -> "风险评价查询";
            default -> skillName;
        };
        return "「" + label + "」功能暂未开通"
                + ("verify_natural_person".equals(skillName) ? "，当前可为您提供营业执照核实服务。" : "，敬请期待。");
    }

    /**
     * 继续栈顶层（文本协议与结构化协议共用）：弹出栈顶快照恢复为当前活动状态 →
     * 复用 handleMultiResume 续跑（其内部已处理：先完成暂停任务 pendingSkill，再执行
     * 剩余任务 pendingPipeline；planning resume=true + task_start 事件重建前端任务清单）。
     *
     * @param fallbackMessage 防御路由用原始消息（旧文本协议传前缀消息；结构化/自然语言
     *                        场景传 null——恢复动作校验已保证栈非空，无兜底路由）
     */
    private Flux<String> resumeTopFrame(String convId, String userId, Conversation conv, String fallbackMessage) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        boolean restored = contextMemoryService.popAndRestorePipeline(convId);
        log.info("FRAME_RESUMED frameId={} conv={}", ctx.currentFrameId, convId);
        if (restored && ctx.isWaitingReport()) {
            // 恢复出的是报告等待层（报告生成期间穿插新意图时压栈）：消费
            // pendingReportDone 就地推进（报告已完成）或提示等待（报告仍在生成）
            return advanceOrWaitReport(convId, userId, conv);
        }
        if (restored && (ctx.hasPendingSkill() || ctx.hasPendingPipeline())) {
            return handleMultiResume(convId, userId, conv, "");
        }
        if (!restored && fallbackMessage != null && !fallbackMessage.isEmpty()) {
            // 防御：挂起栈已空（如后端重启丢失挂起态，但前端历史询问卡仍可点击），
            // 前缀消息落到正常路由兑底，避免用户点击"继续"后无任何响应
            log.info("Pipeline resume with empty stack, routing message: {}", fallbackMessage);
            return coordinatorService.routeIntent(fallbackMessage, conv.getMessages(), convId)
                    .flatMapMany(decision -> dispatchDecision(decision, convId, userId, conv, fallbackMessage));
        }
        return Flux.empty();
    }
    
    /**
     * 放弃栈顶层（文本协议与结构化协议共用）：弹出并丢弃栈顶快照；若栈中仍有下一层
     * 挂起 → 立即询问下一层，否则输出确认文本（用户可逐层放弃/继续，直至栈空或恢复某一层）。
     */
    private Flux<String> abandonTopFrame(String convId, String userId, Conversation conv) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        String abandonedFrameId = ctx.currentFrameId;
        // Phase 3（Plan 投影）：放弃栈顶层 → 该帧计划整体 ABANDONED（pop 会清空 planRuntime，须先投影）
        planProjectionService.markPlanAbandoned(convId);
        boolean hasMore = contextMemoryService.popSuspendedSnapshot(convId);
        log.info("FRAME_ABANDONED frameId={} moreSuspended={} conv={}", abandonedFrameId, hasMore, convId);
        if (hasMore) {
            return emitInterruptAsk(convId, conv);
        }
        return Flux.just(sseEvent("text_delta", Map.of("content", "好的，已取消之前的任务"), null, convId),
                sseEvent("text_done", Map.of("content", "好的，已取消之前的任务"), null, convId));
    }
    
    /**
     * 处理"是否继续旧管道"询问的显式回答（【管道恢复】继续/放弃，旧文本协议）。
     * 前端 InterruptAskCard 按钮发送带前缀消息；Phase 5 起按钮改为结构化协议
     * （resume_frame/abandon_frame），本方法保留兼容旧卡/旧历史消息。
     * v2 栈语义：回答只作用于栈顶那一层——继续则弹出恢复该层；
     * 放弃则丢弃栈顶，若栈中仍有下一层挂起则立即询问下一层（逐层决定）。
     */
    private Flux<String> handleInterruptAnswer(String convId, String userId, Conversation conv, String message) {
        String answer = message.substring(PIPELINE_RESUME_PREFIX.length()).trim();
        // 无论继续还是放弃，该询问卡都已完成使命：标记已答复并落盘，
        // 刷新后前端历史恢复渲染 InterruptAskCard 仍保持置灰（避免重复回答）
        markInterruptAskAnswered(conv);
    
        if (PIPELINE_RESUME_YES.equals(answer)) {
            // ① 继续：弹出栈顶快照恢复为当前活动状态 → resumeTopFrame
            //（含 !restored 防御：挂起栈已空时路由原始消息兜底）
            return resumeTopFrame(convId, userId, conv, message);
        }
        // ② 放弃：弹出并丢弃栈顶快照；若栈中仍有下一层挂起 → 立即询问下一层
        return abandonTopFrame(convId, userId, conv);
    }

    /**
     * 意图穿插自然语言兜底：interruptAskPending 期间用户未用前缀而直接输入。
     * 确认短语（"继续/需要/好"等）→ 弹出栈顶恢复该层；放弃短语（"不需要/算了"等）→
     * 丢弃栈顶（若仍有下一层则继续询问下一层）；其他内容 → 隐式放弃全部挂起
     * 旧管道（清空整个栈），按正常三层路由执行新消息。
     */
    private Flux<String> handleInterruptAnswerNatural(String convId, String userId, Conversation conv, String userMessage) {
        if (isResumePhrase(userMessage)) {
            // 标记询问卡已答复（刷新后保持置灰），复用统一恢复逻辑
            markInterruptAskAnswered(conv);
            log.info("Resume via natural language: {}", userMessage);
            return resumeTopFrame(convId, userId, conv, null);
        }
        if (isAbandonPhrase(userMessage)) {
            // 放弃当前询问的栈顶层；若栈中仍有下一层挂起 → 立即询问下一层
            markInterruptAskAnswered(conv);
            log.info("Abandon via natural language: {}", userMessage);
            return abandonTopFrame(convId, userId, conv);
        }
        // 用户直接发起新问题：保留挂起栈并清除询问标记，按正常路由执行新消息。
        // 文档第 33 节（RESUME_CONFIRMING 期间再次穿插）：必须允许创建新任务，
        // 旧任务继续保留在 Stack——原"隐式放弃全部挂起"会丢失栈中任务，改为保留；
        // 新意图结束后流尾 interruptAskCheck 会再次询问栈顶（A 不丢）
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        ctx.interruptAskPending = false;
        log.info("New intent during resume confirming, suspended stack kept, routing: {}", userMessage);
        return coordinatorService.routeIntent(userMessage, conv.getMessages(), convId)
                .flatMapMany(decision -> dispatchDecision(decision, convId, userId, conv, userMessage));
    }

    /**
     * 管道暂停期间用户消息归类（意图穿插判定）：补充信息 vs 新意图。
     * 六层判定（实施文档第 13 节，顺序不允许调整）：① Structured Protocol
     * ② Explicit Interrupt ③ Expected Input ④ IntentMatcher ⑤ Context Shift ⑥ LLM Fallback。
     * 完整实现与 INPUT_CLASSIFIED 结构化日志见 IntentInterruptClassifier。
     */
    private Mono<InputClass> classifyPipelineInput(String convId,
                                                   ContextMemoryService.ConversationContext ctx,
                                                   String message) {
        return intentInterruptClassifier.classify(convId, ctx, message)
                .map(cls -> cls == IntentInterruptClassifier.Classification.NEW_INTENT
                        ? InputClass.NEW_INTENT : InputClass.PIPELINE_SUPPLEMENT);
    }

    /**
     * 管道暂停期间用户消息处理（主流程 hasPendingPipeline|hasPendingSkill 分支）。
     * 六层判定后分派：NEW_INTENT → 挂起旧管道穿插执行；PIPELINE_SUPPLEMENT → 补充恢复
     * （含候选确认标记）。Phase 6：结构化交互协议（select_candidate/select_template）
     * 校验通过后转发等效文本经此路径执行，与旧文本协议语义完全一致。
     */
    private Flux<String> routePendingInput(String convId, ContextMemoryService.ConversationContext ctx,
                                           String userId, Conversation conv, String message) {
        return classifyPipelineInput(convId, ctx, message)
                .flatMapMany(cls -> {
                    if (cls == InputClass.NEW_INTENT) {
                        // 单意图场景（无多意图管道剩余任务）：若当前挂起技能已被
                        // "以上都不是"否决，用户发起的是独立新意图而非打断——不触发
                        // 穿插压栈（意图穿插仅体现在多意图对话中），清除残留挂起状态
                        // 后按普通三层路由执行，避免单意图对话出现"任务中断提醒"
                        if (ctx.pendingSkillRejected && !ctx.hasPendingPipeline()) {
                            log.info("Rejected pending skill cleared, routing standalone intent: {}", message);
                            contextMemoryService.clearPendingSkill(convId);
                            return coordinatorService.routeIntent(message, conv.getMessages(), convId)
                                    .flatMapMany(decision -> dispatchDecision(decision, convId, userId, conv, message));
                        }
                        // 判定为新意图 → 挂起旧管道（压栈）+ 执行新意图（核心新增路径）
                        return handleInterrupt(convId, userId, conv, message);
                    }
                    // 判定为管道补充信息：用户提供了合法补充参数（企业名/信用代码/日期等），
                    // 旧挂起技能恢复"执行中"状态，清除"以上都不是"否决标记
                    ctx.pendingSkillRejected = false;
                    // 候选确认消息（"公司：xxx\n统一信用代码：xxx"）：标记最新候选选择卡
                    // confirmed=true 并落盘——前端 CompanyNameSelector 据此在刷新/切换会话
                    // （组件重建、本地点击计数丢失）后仍识别"已确认过候选"，再次点击企业
                    // 选项直接发起对应功能查询（"帮我查一下{公司}{查询功能}"）而非重复候选确认
                    if (intentInterruptClassifier.looksLikeCompanySelection(message)) {
                        markCompanyCardConfirmed(conv);
                    }
                    // 判定为管道补充信息 → 走原有恢复逻辑（handleMultiResume / pendingSkill 重试）
                    return ctx.hasPendingPipeline()
                            ? handleMultiResume(convId, userId, conv, message)
                            : pendingSkillRetryFlow(convId, userId, conv, message);
                });
    }

    /** 确认继续旧管道的自然语言短语（精确短词） */
    private boolean isResumePhrase(String message) {
        if (message == null) return false;
        String text = message.trim();
        return Set.of("继续", "需要", "好", "好的", "可以", "是的", "对", "嗯", "行", "要",
                "继续吧", "继续执行", "好，继续", "好的，继续").contains(text);
    }

    /** 放弃旧管道的自然语言短语（精确短词 + "不用/不需要"开头的短语） */
    private boolean isAbandonPhrase(String message) {
        if (message == null) return false;
        String text = message.trim();
        return Set.of("不需要", "不用", "不用了", "算了", "取消", "放弃", "不了", "不要了", "算了算了").contains(text)
                || text.matches("不(用|需要|要).*");
    }

    /**
     * 处理技能分支（非阻塞）
     * @param multiIndex 多意图管道中的任务序号（-1 表示单技能）
     */
    private Flux<String> handleSkill(Map<String, Object> decision, String convId,
                                     String userId, Conversation conv, int multiIndex) {
        String skillName = (String) decision.getOrDefault("skill", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> skillParams = new LinkedHashMap<>(
                (Map<String, Object>) decision.getOrDefault("params", Map.of()));

        // 模板选择消息协议：【模板选择】<template_id>（前端模板卡片点击后发送）。
        // generate_report 展示模板列表时已设置 pendingSkill，用户点击模板后此消息
        // 重入本技能；在此解析并注入 template_id，使技能跳过模板列表直接返回跳转信息。
        // 解析放在 handleSkill 而非各分支：主流程 pendingSkill 分支与 handleMultiResume
        // 都经此方法重入技能，单点覆盖所有路径（含单技能与多意图管道场景）
        String pendingInput = (String) skillParams.get("_user_input");
        if ("generate_report".equals(skillName) && pendingInput != null
                && pendingInput.startsWith(TEMPLATE_SELECT_PREFIX)) {
            String tid = pendingInput.substring(TEMPLATE_SELECT_PREFIX.length()).trim();
            if (!tid.isEmpty()) {
                skillParams.put("template_id", tid);
                log.info("Template selection resolved for generate_report: {}", tid);
            }
        }

        // 上下文记忆补全
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);

        // LLM 提取结果守卫（必须在 ctx 检查之外）：company_name 与 credit_code 独立校验、互不连坐——
        // - company_name 若是上下文指代词（"这家公司"，LLM 误提取）、泛企业指称（"企业/个企业"）、
        //   疑问句残留或明显非企业名，仅移除 company_name，交由上下文补全/缺参询问
        // - credit_code 显式携带但非 18 位合法格式（如 LLM 误把公司名塞入）时仅移除 credit_code
        //   避免技能拿脏值做模糊匹配永远 not_found，堵死缺企业名的询问提示
        String paramCompany = (String) skillParams.get("company_name");
        if (paramCompany != null && !CompanyNameExtractor.isValidCompanyName(paramCompany)) {
            skillParams.remove("company_name");
            log.info("Removed invalid company_name '{}', will ask user for a real company name", paramCompany);
        }
        String paramCode = (String) skillParams.get("credit_code");
        if (paramCode != null && !CompanyNameExtractor.isValidCreditCode(paramCode)) {
            skillParams.remove("credit_code");
            log.info("Removed invalid credit_code '{}'", paramCode);
        }

        // 上下文记忆补全（宽松策略）：credit_code / company_name 各自独立补全，
        // 只要技能参数中缺失且上下文有值即补——此前要求两个参数同时缺失才补全，
        // 导致多意图管道后续任务（如企业风险预查）LLM 只提取了 company_name
        // 而缺 credit_code 时跳过补全，技能拿不到精确信用代码再次模糊匹配弹企业选择卡。
        // 例外：本次消息已明确给出新的企业名（与上下文企业不同）时视为切换查询对象，
        // 不补全旧企业的 credit_code——技能内部 credit_code 优先于 company_name，
        // 强行补全会查到旧企业而忽略用户新指定的企业（如先查小米，再问"云禾科技的法人信息"
        // 却返回小米数据）。用 skillParams 当前值判断（无效名已被守卫移除后视为指代上下文企业）
        if (!ctx.isEmpty()) {
            String effectiveCompany = (String) skillParams.get("company_name");
            // 同一企业判定统一走 CompanyNameExtractor（剥离企业后缀后比较核心名），
            // 解决简称/全称互不包含问题（"小米科技有限责任公司" vs "小米科技公司"）
            boolean sameCompany = CompanyNameExtractor.isSameCompany(ctx.companyName, effectiveCompany);
            if (!skillParams.containsKey("credit_code")
                    && ctx.creditCode != null && !ctx.creditCode.isEmpty()
                    && sameCompany) {
                skillParams.put("credit_code", ctx.creditCode);
                log.info("Auto-filled credit_code: {}", ctx.creditCode);
            }
            // company_name 同步精确化：本次消息给出的是上下文企业的简称/非规范名
            // （sameCompany 已确认同一企业）且上下文已有精确名与信用代码时，用精确名
            // 覆盖简称，避免技能按简称模糊匹配再次弹候选卡（候选确认后任务重复澄清）
            if (sameCompany
                    && ctx.companyName != null && !ctx.companyName.isEmpty()
                    && ctx.creditCode != null && !ctx.creditCode.isEmpty()
                    && effectiveCompany != null && !effectiveCompany.isEmpty()
                    && !effectiveCompany.equals(ctx.companyName)) {
                skillParams.put("company_name", ctx.companyName);
                log.info("Refreshed company_name to context precision: {}", ctx.companyName);
            }
            if (!skillParams.containsKey("company_name")
                    && ctx.companyName != null && !ctx.companyName.isEmpty()) {
                skillParams.put("company_name", ctx.companyName);
                log.info("Auto-filled company_name: {}", ctx.companyName);
            }
        }

        // 注入最新上传的附件 URL（如有），供技能解析营业执照等附件
        if (ctx.attachmentUrl != null && !ctx.attachmentUrl.isEmpty()) {
            skillParams.put("_attachment_url", ctx.attachmentUrl);
            log.info("Injected attachment URL into skill params: {}", ctx.attachmentUrl);
        }

        log.info("Coordinator routed to skill: {}, params: {}", skillName, skillParams);
        skillParams.put("_conversation_id", convId);

        String assistantMsgId = UUID.randomUUID().toString();

        // "以上都不是"第二道防线：用户在模糊匹配候选列表中点击"以上都不是"，表明所有候选均非
        // 目标企业。主流程已优先拦截该消息（chatStream 候选否决兜底），此处仅在主流程拦截被
        // 绕过时兜底：在技能调用前拦截，返回友好提示并保留 pendingSkill 等待用户重新提供准确
        // 企业信息——否则 CompanyNameExtractor 会把该文本当企业名再次模糊匹配，重弹候选卡片甚至死循环
        if (pendingInput != null && pendingInput.contains("以上都不是")) {
            // 清空可能残留的企业参数，避免下一轮上下文补全复用旧企业名再次触发模糊匹配
            skillParams.remove("company_name");
            skillParams.remove("credit_code");
            String rejectPrompt = "以上候选企业均不是您要找的目标，请提供准确的企业名称或统一信用代码，我将为您重新查询。";
            contextMemoryService.setPendingInputHint(convId, rejectPrompt);
            // 标记该挂起技能已被用户否决：残留的 pendingSkill 仅供直接输入企业名时
            // 重入，不再视为"执行中任务"——用户随后发起的新意图不触发穿插压栈
            ctx.pendingSkillRejected = true;
            contextMemoryService.setPendingSkill(convId, skillName, skillParams);
            log.info("User rejected all candidates (以上都不是), pending skill {} kept for re-query", skillName);
            return Flux.just(
                    sseEvent("text_delta", Map.of("content", rejectPrompt), assistantMsgId, null),
                    sseEvent("text_done", Map.of("content", rejectPrompt), assistantMsgId, null)
            );
        }

        // skillRegistry.invoke 是同步阻塞，隔离到弹性线程池
        return Mono.fromCallable(() -> skillRegistry.invoke(skillName, userId, skillParams))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(result -> {
                    // 构建事件流
                    Flux<String> eventFlux;
                    // 模板选择阶段标志：generate_report 展示模板列表（stage=templates）时
                    // 技能尚未完成任务，不发送 task_done、不清理 pendingSkill、不触发后续
                    // 建议，而是暂停管道等待用户点击模板后重入（与 candidates/info_needed 一致）
                    boolean templateStage = "generate_report".equals(skillName)
                            && "templates".equals(result.get("stage"));
                    // 跳转阶段（stage=redirect）：generate_report 已返回跳转 H5 编辑页信息，
                    // 报告由用户在 H5 页面异步生成，本任务尚未真正完成：不发 task_done、
                    // 挂起管道（waitingReportTask）等待报告完成后由 report-completed 接口推进
                    boolean redirectStage = "generate_report".equals(skillName)
                            && "redirect".equals(result.get("stage"));

                    if (result.containsKey("error")) {
                        String errorMsg = (String) result.get("error");
                        eventFlux = Flux.just(
                                sseEvent("text_delta", Map.of("content", errorMsg), assistantMsgId, null),
                                sseEvent("text_done", Map.of("content", errorMsg), assistantMsgId, null)
                        );
                    } else {
                        String action = (String) result.getOrDefault("action", "");
                        // 带候选的 not_found：技能未找到完全匹配企业但返回了相似企业候选（options 非空），
                        // 用户需从候选中选择或点"以上都不是"——与 candidates/ambiguous 同属
                        // "待用户决策"暂停态，不能按 result 视为任务终态（否则多意图管道不暂停
                        // 直接续跑剩余任务，用户无法选择候选企业）
                        boolean candidateOptions = "not_found".equals(action)
                                && result.get("options") instanceof List && !((List<?>) result.get("options")).isEmpty();
                        if ("summary".equals(action)) {
                            eventFlux = Flux.just(sseEvent("potential_customer_summary", result, assistantMsgId, null));
                        } else if ("detail".equals(action)) {
                            eventFlux = Flux.just(sseEvent("potential_customer_detail", result, assistantMsgId, null));
                        } else if ("candidates".equals(action) || "ambiguous".equals(action)) {
                            // ambiguous（企业名多候选）与 candidates 同处理：发企业选择卡片 + 保存待处理技能，
                            // 否则用户点击卡片选项后无 pendingSkill 会重新走 Coordinator 提取 → 再次多候选 → 死循环弹卡片
                            result.put("_skill_name", skillName);
                            // Phase 6（交互 ID 化）：候选卡携带所属任务帧 frameId 与本次交互
                            // interactionId，点击回传后后端校验帧归属（文档第 43/44 节）
                            result.put("frameId", ctx.currentFrameId);
                            result.put("interactionId", newInteractionId());
                            // v4：为候选企业选择卡片附带查询功能标签（query_label）——CompanyQuerySkill
                            // 已在技能内透传（如"基本信息"），其他技能（风险检查/历史尽调等）缺失时
                            // 取技能中文标签兜底；供前端 CompanyNameSelector 第二次点击候选企业时
                            // 拼接"帮我查一下{公司}{标签}"直接触发对应功能（手滑选错后的纠错路径）
                            if (!result.containsKey("query_label")) {
                                result.put("query_label", skillRegistry.getSkillLabel(skillName));
                            }
                            // v4：为候选企业选择卡片附带正确的任务标识（task_label）——多意图管道内任务
                            // （multiIndex >= 0）取计划快照中该任务的 label；意图穿插的新技能（multiIndex < 0）
                            // 取当前技能中文标签。此前前端从消息列表"最后一张任务清单卡片"推断，穿插场景会
                            // 错误关联到被挂起的旧管道任务（如"营业执照信息核实"）；随 result 持久化后
                            // 刷新/切换会话也能恢复一致的正确标识
                            String taskLabel = null;
                            if (multiIndex >= 0 && ctx.pipelinePlan != null && multiIndex < ctx.pipelinePlan.size()) {
                                taskLabel = (String) ctx.pipelinePlan.get(multiIndex).get("label");
                            } else if (multiIndex < 0) {
                                taskLabel = skillRegistry.getSkillLabel(skillName);
                            }
                            if (taskLabel != null && !taskLabel.isEmpty()) {
                                result.put("task_label", taskLabel);
                            }
                            eventFlux = Flux.just(sseEvent("company_name_candidates", result, assistantMsgId, null));
                            // 将技能解析出的 keyword 合并回 skillParams（让下一轮持有企业名上下文）
                            if (result.containsKey("keyword") && !skillParams.containsKey("company_name")) {
                                skillParams.put("company_name", result.get("keyword"));
                            }
                            // 同步上下文企业名：暂停待选时若不更新 ctx.companyName，穿插判定
                            // containsNewCompany 失去比较基准，管道暂停期间的"切换企业查询"
                            // 会被误判为管道补充，意图穿插无法识别
                            String pendingCompany = (String) skillParams.getOrDefault("company_name", "");
                            if (!pendingCompany.isEmpty()) {
                                contextMemoryService.update(convId, pendingCompany, null);
                            }
                            // 保存待处理技能上下文，下一条用户消息将直接回到此技能
                            contextMemoryService.setPendingSkill(convId, skillName, skillParams);
                            log.info("Pending skill set: {} ({})", skillName, action);
                            // Phase 3（Plan 投影）：候选选择等待用户决策 → 步骤 WAITING_INPUT
                            planProjectionService.markStepWaitingInput(convId,
                                    resolvePipelineOrder(skillName, multiIndex, ctx));
                            // 多意图管道内任务等待企业选择同样属于"暂停"：追加 pipeline_paused 事件，
                            // 前端据此把任务清单卡标记 paused，避免本条 SSE 流结束时 done 事件
                            // 误判"currentOrder >= total"而弹出"N 项任务已完成"完成卡
                            if (multiIndex >= 0
                                    || (ctx.pipelinePlan != null && !ctx.pipelinePlan.isEmpty())) {
                                Map<String, Object> pausedData = new LinkedHashMap<>();
                                pausedData.put("hint", result.getOrDefault("message", "请选择企业"));
                                eventFlux = eventFlux.concatWith(
                                        Flux.just(sseEvent("pipeline_paused", pausedData, null, convId)));
                            }
                        } else if ("info_needed".equals(action)) {
                            String prompt = (String) result.getOrDefault("message", "");
                            eventFlux = Flux.just(
                                    sseEvent("text_delta", Map.of("content", prompt), assistantMsgId, null),
                                    sseEvent("text_done", Map.of("content", prompt), assistantMsgId, null)
                            );
                            // 记录暂停提示（如"请上传营业执照图片"），多意图管道暂停时透传给前端任务清单卡片，
                            // 明确提醒用户需要上传附件还是补充文本信息
                            contextMemoryService.setPendingInputHint(convId, prompt);
                            // 将技能已解析的参数字段合并回 skillParams（如 company_name, credit_code）
                            // 避免下一轮参数丢失导致技能重新从阶段一/二开始
                            if (result.containsKey("company_name")) {
                                skillParams.put("company_name", result.get("company_name"));
                            }
                            if (result.containsKey("credit_code")) {
                                skillParams.put("credit_code", result.get("credit_code"));
                            }
                            // 同步上下文企业名（info_needed 同为"待补充"暂停态，保证穿插判定基准一致）
                            String pendingCompany = (String) skillParams.getOrDefault("company_name", "");
                            if (!pendingCompany.isEmpty()) {
                                contextMemoryService.update(convId, pendingCompany, null);
                            }
                            // 保存待处理技能上下文，下一条用户消息将直接回到此技能
                            contextMemoryService.setPendingSkill(convId, skillName, skillParams);
                            log.info("Pending skill set: {} (info_needed), params: {}", skillName, skillParams);
                            // Phase 3（Plan 投影）：等待用户补充输入 → 步骤 WAITING_INPUT
                            planProjectionService.markStepWaitingInput(convId,
                                    resolvePipelineOrder(skillName, multiIndex, ctx));
                        } else if ("result".equals(action) || "not_found".equals(action)) {
                            String eventType = switch (skillName) {
                                case "query_due_diligence_reports" -> "historical_dd_query_result";
                                case "verify_business_license" -> "information_check_result";
                                case "generate_report" -> "report_generate_result";
                                case "query_company_basic_info", "query_shareholder_info", "query_beneficiary_info",
                                     "query_company_genealogy", "query_customs_auth", "query_customs_blacklist",
                                     "query_account_freeze_tag", "query_credit_granting",
                                     "query_pboc_account_control" -> "company_query_result";
                                default -> "risk_check_result";
                            };
                            // 将 skill_name 注入到结果中，方便前端根据技能类型路由卡片
                            result.put("_skill_name", skillName);
                            if (multiIndex >= 0) result.put("_multi_index", multiIndex);
                            // Phase 6（交互 ID 化）：结果类事件统一携带所属任务帧 frameId 与本次
                            // 交互 interactionId——not_found 带候选（相似企业选择卡）与模板选择卡
                            // （stage=templates）均为可点击交互，回传后校验帧归属；成功结果卡
                            // 多携带字段无害（前端不读取）
                            result.put("frameId", ctx.currentFrameId);
                            result.put("interactionId", newInteractionId());
                            // 带候选的 not_found（"是否查询以下相似企业？"）：任务等待用户在候选
                            // 中选择或点"以上都不是"，注入 task_label 供前端信息卡显示所属任务标识
                            // （与 candidates 分支同一逻辑）；回填 keyword 使下一轮重入技能时
                            // 持有企业名上下文（点"以上都不是"后重新输入企业名直接命中候选）
                            if (candidateOptions) {
                                String taskLabel = null;
                                if (multiIndex >= 0 && ctx.pipelinePlan != null && multiIndex < ctx.pipelinePlan.size()) {
                                    taskLabel = (String) ctx.pipelinePlan.get(multiIndex).get("label");
                                } else if (multiIndex < 0) {
                                    taskLabel = skillRegistry.getSkillLabel(skillName);
                                }
                                if (taskLabel != null && !taskLabel.isEmpty()) {
                                    result.put("task_label", taskLabel);
                                }
                                if (result.containsKey("keyword") && !skillParams.containsKey("company_name")) {
                                    skillParams.put("company_name", result.get("keyword"));
                                }
                            }
                            eventFlux = Flux.just(sseEvent(eventType, result, assistantMsgId, null));
                            // 任务完成事件：多意图管道内的任务执行完毕时通知前端将该任务
                            // 标记为已完成（解除等待补充信息的暂停状态），让"第 X 项任务已完成"
                            // 的进度反馈及时出现，而非等整条流结束时由 done 一次性收尾。
                            // 例外：模板选择阶段（stage=templates）与跳转阶段（stage=redirect）
                            // 任务均未真正完成（报告尚未生成），不发 task_done
                            if (!templateStage && !redirectStage && !candidateOptions && (multiIndex >= 0
                                    || (ctx.pipelinePlan != null && !ctx.pipelinePlan.isEmpty()))) {
                                Map<String, Object> taskDoneData = new LinkedHashMap<>();
                                // order 计算：管道内直接取 multiIndex + 1；但模板选择恢复路径
                                // handleSkill 以 multiIndex=-1 重入且 pipelinePlan 非空（暂停时
                                // 完整快照仍在），按 multiIndex + 1 会发出 order=0 的错误事件，
                                // 需按 skill 反查全局序号
                                int doneOrder = multiIndex + 1;
                                if (multiIndex < 0 && ctx.pipelinePlan != null && !ctx.pipelinePlan.isEmpty()) {
                                    for (Map<String, Object> item : ctx.pipelinePlan) {
                                        if (skillName.equals(item.get("skill"))) {
                                            doneOrder = (int) item.getOrDefault("order", multiIndex + 1);
                                            break;
                                        }
                                    }
                                }
                                taskDoneData.put("order", doneOrder);
                                taskDoneData.put("skill", skillName);
                                taskDoneData.put("label", skillRegistry.getSkillLabel(skillName));
                                taskDoneData.put("frameId", ctx.currentFrameId); // Phase 1：任务标识
                                // Phase 3（Plan 投影）：任务完成 → 步骤 DONE
                                planProjectionService.markStepDone(convId, doneOrder);
                                eventFlux = eventFlux.concatWith(
                                        Flux.just(sseEvent("task_done", taskDoneData, null, convId)));
                            }
                            // 模板选择阶段：保存待处理技能，使 executePipeline 检测到
                            // hasPendingSkill 后记录剩余任务并停止续跑；下一条
                            // 【模板选择】<template_id> 消息将重入本技能注入模板 ID
                            if (templateStage) {
                                contextMemoryService.setPendingSkill(convId, skillName, skillParams);
                                log.info("Pending skill set: {} (template selection)", skillName);
                                // Phase 3（Plan 投影）：模板选择等待用户决策 → 步骤 WAITING_INPUT
                                planProjectionService.markStepWaitingInput(convId,
                                        resolvePipelineOrder(skillName, multiIndex, ctx));
                                // 多意图管道内等待模板选择同样属于"暂停"：追加 pipeline_paused 事件，
                                // 前端据此把任务清单卡标记 paused，避免本条 SSE 流结束时 done 事件
                                // 误判"currentOrder >= total"而弹出"N 项任务已完成"完成卡
                                if (multiIndex >= 0
                                        || (ctx.pipelinePlan != null && !ctx.pipelinePlan.isEmpty())) {
                                    Map<String, Object> pausedData = new LinkedHashMap<>();
                                    pausedData.put("hint", result.getOrDefault("message", "请选择报告模板"));
                                    pausedData.put("frameId", ctx.currentFrameId); // Phase 1：任务标识
                                    eventFlux = eventFlux.concatWith(
                                            Flux.just(sseEvent("pipeline_paused", pausedData, null, convId)));
                                }
                            }
                            // 带候选的 not_found：任务等待用户在相似企业候选中选择或点"以上都不是"，
                            // 与 candidates/ambiguous 同属"待用户决策"暂停态。保存待处理技能，使
                            // executePipeline / handleMultiResume 的 defer 检测到 hasPendingSkill 后
                            // 记录剩余任务并停止续跑（不发 task_done、不清理 pendingSkill）；下一条
                            // 【公司：X 统一信用代码：Y】或【以上都不是】消息将重入本技能。
                            // pipeline_paused 由管道 defer 统一发出（未设置 pendingInputHint，hint 为空，
                            // 前端仅将任务清单卡标记 paused，候选说明由信息卡自身展示，避免文本重复）
                            if (candidateOptions) {
                                // 同步上下文企业名（not_found 带候选与 ambiguous 同属"待用户决策"暂停态）
                                String pendingCompany = (String) skillParams.getOrDefault("company_name", "");
                                if (!pendingCompany.isEmpty()) {
                                    contextMemoryService.update(convId, pendingCompany, null);
                                }
                                contextMemoryService.setPendingSkill(convId, skillName, skillParams);
                                log.info("Pending skill set: {} (not_found with candidates)", skillName);
                                // Phase 3（Plan 投影）：候选选择等待用户决策 → 步骤 WAITING_INPUT
                                planProjectionService.markStepWaitingInput(convId,
                                        resolvePipelineOrder(skillName, multiIndex, ctx));
                            }
                            // 跳转阶段（stage=redirect）：任务挂起等待 H5 异步报告生成。
                            // 设置 waitingReportTask 使 executePipeline 的 defer 检测到后保存
                            // 剩余任务并暂停续跑；报告生成完成后前端轮询到 completed 调用
                            // report-completed 接口推进管道（而非用户消息 resume）
                            if (redirectStage) {
                                // 管道判断兜底：multiIndex>=0 直接成立；pendingSkill 重入路径
                                // （multiIndex=-1）时内存快照 pipelinePlan 可能为空，用 snapshotPlan
                                // 从对话历史恢复完整计划，避免 waitingReportTask 漏设导致
                                // report-completed 走 skipped 幂等分支、管道永不推进
                                List<Map<String, Object>> planSnapshot = snapshotPlan(convId, conv);
                                if (multiIndex >= 0
                                        || (planSnapshot != null && !planSnapshot.isEmpty())) {
                                    Map<String, Object> waitingTask = new LinkedHashMap<>();
                                    waitingTask.put("skill", skillName);
                                    waitingTask.put("label", skillRegistry.getSkillLabel(skillName));
                                    waitingTask.put("frameId", ctx.currentFrameId); // Phase 1：报告回调定位归属任务
                                    // 序号同样按 skill 反查全局 order（multiIndex=-1 恢复路径）
                                    int reportOrder = multiIndex + 1;
                                    if (multiIndex < 0 && planSnapshot != null && !planSnapshot.isEmpty()) {
                                        for (Map<String, Object> item : planSnapshot) {
                                            if (skillName.equals(item.get("skill"))) {
                                                reportOrder = (int) item.getOrDefault("order", reportOrder);
                                                break;
                                            }
                                        }
                                    }
                                    waitingTask.put("order", reportOrder);
                                    // v4：快照跳转卡片所需模板信息——穿插恢复时若报告任务未创建
                                    // （用户跳转 H5 后未上传附件生成即关闭），可据此重新发出
                                    // redirect 卡片引导用户继续编辑，避免管道永久挂起
                                    waitingTask.put("template_id", result.get("template_id"));
                                    waitingTask.put("template_name", result.get("template_name"));
                                    waitingTask.put("template_icon", result.get("template_icon"));
                                    waitingTask.put("template_description", result.get("template_description"));
                                    waitingTask.put("accepted_types", result.get("accepted_types"));
                                    waitingTask.put("required_fields", result.get("required_fields"));
                                    waitingTask.put("organization", result.get("organization"));
                                    waitingTask.put("message", result.getOrDefault("message",
                                            "请在报告编辑页面中上传附件并生成报告"));
                                    contextMemoryService.setWaitingReportTask(convId, waitingTask);
                                    log.info("Waiting report task set: {} (order {}), pipeline suspended",
                                            skillName, reportOrder);
                                    // Phase 3（Plan 投影）：等待异步报告生成 → 步骤 WAITING_EXTERNAL
                                    planProjectionService.markStepWaitingExternal(convId, reportOrder);
                                    // 追加 pipeline_paused 事件，避免本条 SSE 流结束时 done 事件
                                    // 误判"currentOrder >= total"而弹出"N 项任务已完成"完成卡
                                    Map<String, Object> pausedData = new LinkedHashMap<>();
                                    pausedData.put("hint", "报告将在编辑页面生成，生成完成后将自动继续");
                                    pausedData.put("frameId", ctx.currentFrameId); // Phase 1：任务标识
                                    eventFlux = eventFlux.concatWith(
                                            Flux.just(sseEvent("pipeline_paused", pausedData, null, convId)));
                                }
                            }
                        } else {
                            eventFlux = Flux.empty();
                        }

                        // 更新上下文记忆（若返回了企业信息）
                        if ("result".equals(action) && result.get("credit_code") != null) {
                            contextMemoryService.update(convId,
                                    (String) result.getOrDefault("company_name", ""),
                                    (String) result.get("credit_code"));
                            log.info("Context updated: {} ({})", result.get("company_name"), result.get("credit_code"));
                        }

                        // 清理待处理技能（技能已完成或未找到结果），并清除已使用的附件。
                        // 例外：模板选择阶段（stage=templates）任务尚未完成，保留 pendingSkill
                        // 等待用户点击模板后重入，此处不能清理
                        if (("result".equals(action) || "not_found".equals(action)) && !templateStage && !candidateOptions) {
                            contextMemoryService.clearPendingSkill(convId);
                            contextMemoryService.clearAttachment(convId);
                        }
                        // reset或result/not_found时重置重试计数（新技能调用从0开始）
                        if (!"candidates".equals(action) && !"ambiguous".equals(action) && !"info_needed".equals(action)
                                && !candidateOptions) {
                            ctx.pendingSkillRetry = 0;
                        }

                        // 存储助手消息（同步，顺序执行）
                        try {
                            String summaryText = mapper.writeValueAsString(result);
                            Message asstMsg = new Message(assistantMsgId, "assistant", summaryText, Instant.now().toString());
                            conv.getMessages().add(asstMsg);
                            conv.setUpdatedAt(asstMsg.getCreatedAt());
                            // 消息追加后立即落盘（与用户消息存储处一致）
                            conversationService.persist();
                        } catch (Exception e) {
                            log.error("Failed to serialize result: {}", e.getMessage());
                        }

                        // 跟踪技能调用 + 后续建议（模板选择阶段不算完成，跳过）
                        if ("result".equals(action) && !templateStage) {
                            String credit = (String) result.getOrDefault("credit_code", "");
                            if (credit != null && !credit.isEmpty()) {
                                conversationService.recordSkillCall(convId, credit, skillName);
                            }

                            List<String> allSkills = conversationService.getAllSkills(convId);
                            List<String> companySkills = credit != null && !credit.isEmpty()
                                    ? conversationService.getCompanySkills(convId, credit) : List.of();

                            String followUpText = followUpService.predictFollowUp(
                                    skillName, action,
                                    (String) result.getOrDefault("company_name", ""),
                                    credit,
                                    allSkills, companySkills);

                            if (followUpText != null) {
                                // 追加 follow_up_suggestion 事件
                                eventFlux = eventFlux.concatWith(
                                        Flux.just(sseEvent("follow_up_suggestion",
                                                Map.of("content", followUpText), assistantMsgId, null))
                                );
                            }
                        }
                    }

                    // 在最前面发送 meta 事件
                    return Flux.just(sseEvent("meta", Map.of("conversation_id", convId), assistantMsgId, null))
                            .concatWith(eventFlux);
                })
                .doOnComplete(() -> {
                    // 更新标题（如有必要）
                    if ("新对话".equals(conv.getTitle()) && conv.getMessages().size() >= 2) {
                        for (Message m : conv.getMessages()) {
                            if ("user".equals(m.getRole())) {
                                String content = m.getContent();
                                conv.setTitle(content.length() > 30 ? content.substring(0, 30) + "..." : content);
                                break;
                            }
                        }
                    }
                });
    }

    /**
     * 固定文案回复（如 matchable 预留业务"该业务暂未开通"提示），不调用 LLM。
     * 事件序列与 handleChat 同构（meta/text_start/text_delta/text_done），并持久化助手消息。
     */
    private Flux<String> handlePresetReply(String convId, Conversation conv, String reply) {
        String assistantMsgId = UUID.randomUUID().toString();
        try {
            Message asstMsg = new Message(assistantMsgId, "assistant", reply, Instant.now().toString());
            conv.getMessages().add(asstMsg);
            conv.setUpdatedAt(asstMsg.getCreatedAt());
            conversationService.persist();
        } catch (Exception e) {
            log.error("Failed to persist preset reply: {}", e.getMessage());
        }
        return Flux.just(
                sseEvent("meta", Map.of("conversation_id", convId), null, convId),
                sseEvent("text_start", null, assistantMsgId, null),
                sseEvent("text_delta", Map.of("content", reply), assistantMsgId, null),
                sseEvent("text_done", Map.of("content", reply), assistantMsgId, null));
    }

    /**
     * 处理普通聊天分支（流式）---兜底
     */
    private Flux<String> handleChat(String convId, Conversation conv, String userMessage) {
        String assistantMsgId = UUID.randomUUID().toString();
        StringBuilder fullContent = new StringBuilder();

        // 先发送 meta（告知前端 conversation_id），然后 text_start，流式输出，最后 text_done
        // 传入历史消息（不含当前这条刚加入的用户消息）
        List<Message> history = conv.getMessages().size() > 1
                ? conv.getMessages().subList(0, conv.getMessages().size() - 1)
                : List.of();
        
        return Flux.just(sseEvent("meta", Map.of("conversation_id", convId), null, convId))
                .concatWith(Flux.just(sseEvent("text_start", null, assistantMsgId, null)))
                .concatWith(agentService.streamChat(userMessage, history)
                        .doOnNext(delta -> fullContent.append(delta))
                        .map(delta -> sseEvent("text_delta", Map.of("content", delta), assistantMsgId, null))
                )
                .concatWith(Flux.just(sseEvent("text_done",
                        Map.of("content", fullContent.toString()), assistantMsgId, null)))
                .doFinally(signal -> {
                    // 存储助手消息：正常完成存完整内容；被强制终止（doOnCancel）时存已生成的部分内容
                    // 这样强制停止后切换会话，中途已生成的内容仍保留在对话记录中
                    if (fullContent.length() > 0) {
                        Message asstMsg = new Message(assistantMsgId, "assistant",
                                fullContent.toString(), Instant.now().toString());
                        conv.getMessages().add(asstMsg);
                        conv.setUpdatedAt(asstMsg.getCreatedAt());
                        // 流式生成结束/被强制终止时保存已生成内容，随后立即落盘
                        conversationService.persist();
                    }
                    // 更新标题
                    if ("新对话".equals(conv.getTitle()) && conv.getMessages().size() >= 2) {
                        for (Message m : conv.getMessages()) {
                            if ("user".equals(m.getRole())) {
                                String content = m.getContent();
                                conv.setTitle(content.length() > 30 ? content.substring(0, 30) + "..." : content);
                                break;
                            }
                        }
                    }
                });
    }

    /**
     * 从对话历史定位澄清卡助手消息（恢复卡生成时的原始用户输入）。
     * 澄清卡消息持久化为 JSON（含 frameId/interactionId/candidates/user_input）；
     * frameId 非空时校验帧归属（结构化协议：挂起帧的旧卡不采用，防止污染当前任务）；
     * frameId 为 null 时（旧文本协议无帧信息）取最近一张澄清卡。
     */
    private String findClarifyCardInput(Conversation conv, String frameId) {
        List<Message> messages = conv.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (!"assistant".equals(m.getRole())) continue;
            String content = m.getContent();
            if (content == null || !content.trim().startsWith("{")) continue;
            try {
                Map<String, Object> data = mapper.readValue(content,
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
                if (!data.containsKey("interactionId") || !data.containsKey("candidates")) continue; // 非澄清卡
                if (frameId != null && !frameId.equals(data.get("frameId"))) continue; // 挂起帧旧卡不采用
                Object input = data.get("user_input");
                if (input instanceof String s && !s.isEmpty()) return s;
            } catch (Exception e) {
                // 非 JSON 或解析失败的消息跳过
            }
        }
        return null;
    }

    /**
     * 处理意图澄清决策：发送 intent_candidates 事件
     */
    @SuppressWarnings("unchecked")
    private Flux<String> handleClarify(Map<String, Object> decision, String convId, Conversation conv,
                                       String userMessage) {
        String assistantMsgId = UUID.randomUUID().toString();
        String message = (String) decision.getOrDefault("message", "您的问题可能有多种理解，请选择您想要的操作：");
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) decision.getOrDefault("candidates", List.of());

        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("message", message);
        eventData.put("candidates", candidates);
        // 原始用户输入随卡持久化：SELECT_INTENT 点击后据此恢复技能参数（如企业名），
        // 避免企业名等参数丢失被上下文自动填充成上一轮企业
        eventData.put("user_input", userMessage);
        // Phase 6（交互 ID 化）：意图澄清卡携带所属任务帧 frameId 与 interactionId，
        // 点击回传后后端校验帧归属（Case 14：挂起帧的旧澄清卡不得污染当前任务）。
        // 无活动任务时（currentFrameId 为空，如新会话首句即澄清）为澄清卡分配新帧，
        // 保证前端 frameId 非空走结构化 select_intent 协议（携带原始输入恢复参数）
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        if (ctx.currentFrameId == null || ctx.currentFrameId.isEmpty()) {
            ctx.currentFrameId = newFrameId();
            log.info("FRAME_CREATED frameId={} intent=clarify conv={}", ctx.currentFrameId, convId);
        }
        eventData.put("frameId", ctx.currentFrameId);
        eventData.put("interactionId", newInteractionId());

        // 存储助手消息
        try {
            String summaryText = mapper.writeValueAsString(eventData);
            Message asstMsg = new Message(assistantMsgId, "assistant", summaryText, Instant.now().toString());
            conv.getMessages().add(asstMsg);
            conv.setUpdatedAt(asstMsg.getCreatedAt());
            // 消息追加后立即落盘（与用户消息存储处一致）
            conversationService.persist();
        } catch (Exception e) {
            log.error("Failed to serialize clarify result: {}", e.getMessage());
        }

        return Flux.just(
                sseEvent("meta", Map.of("conversation_id", convId), assistantMsgId, null),
                sseEvent("intent_candidates", eventData, assistantMsgId, null)
        );
    }

    /**
     * 处理多意图决策：生成执行计划并顺序执行
     */
    @SuppressWarnings("unchecked")
    private Flux<String> handleMulti(Map<String, Object> decision, String convId,
                                     String userId, Conversation conv) {
        List<Map<String, Object>> skills = (List<Map<String, Object>>) decision.getOrDefault("skills", List.of());
        List<TaskPlanner.PlanTask> plan = taskPlanner.plan(skills);

        if (plan.isEmpty()) {
            return handleChat(convId, conv, "");
        }

        String planText = taskPlanner.buildPlanText(plan);
        log.info("Multi-intent plan: {} tasks, text: {}", plan.size(), planText);

        // 保存计划快照（含 label/order），供暂停恢复时重建 planning 事件（前端任务清单）
        List<Map<String, Object>> planSnapshot = new ArrayList<>();
        for (TaskPlanner.PlanTask t : plan) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skill", t.skill());
            item.put("label", skillRegistry.getSkillLabel(t.skill()));
            item.put("order", t.order());
            planSnapshot.add(item);
        }
        contextMemoryService.get(convId).pipelinePlan = planSnapshot;

        // Phase 1（frameId）：多意图管道是独立任务 → 分配 frameId。
        // 穿插新意图已由 handleInterrupt 预分配（currentFrameId 非空）时复用，保证
        // "一个独立意图 = 一个 frameId"贯穿挂起/恢复/SSE/前端卡片
        ContextMemoryService.ConversationContext multiCtx = contextMemoryService.get(convId);
        if (multiCtx.currentFrameId == null || multiCtx.currentFrameId.isEmpty()) {
            multiCtx.currentFrameId = newFrameId();
        }
        String frameId = multiCtx.currentFrameId;
        log.info("FRAME_CREATED frameId={} parentFrameId={} intent=multi tasks={}",
                frameId, multiCtx.parentFrameId, plan.size());
        // Phase 3（Plan 投影）：创建计划状态视图（步骤全 PENDING，随执行事件单向投影）
        planProjectionService.createPlan(convId, frameId, planSnapshot);

        // 发送规划文本 + 顺序执行每个任务
        String assistantMsgId = UUID.randomUUID().toString();
        Map<String, Object> planningData = new LinkedHashMap<>();
        planningData.put("plan", planSnapshot);
        planningData.put("text", planText);
        // resume=false 表示首次规划（前端据此新建任务清单卡片；true 为暂停恢复时更新已有卡片）
        planningData.put("resume", false);
        planningData.put("frameId", frameId); // Phase 1：任务标识贯穿前端任务卡

        // 将任务清单作为可见消息持久化（与其他结果卡片一致），
        // 这样切换会话/刷新后任务清单仍保留在对话流中，不会因管道结束而消失
        try {
            Map<String, Object> planMsgData = new LinkedHashMap<>();
            planMsgData.put("action", "pipeline");
            planMsgData.put("kind", "plan");
            planMsgData.put("frameId", frameId); // Phase 1：历史恢复后仍可凭 frameId 定位任务卡
            planMsgData.put("plan", planSnapshot);
            planMsgData.put("total", planSnapshot.size());
            planMsgData.put("currentOrder", 0);
            planMsgData.put("paused", false);
            planMsgData.put("text", planText);
            String planSummary = mapper.writeValueAsString(planMsgData);
            Message planMsg = new Message(UUID.randomUUID().toString(), "assistant",
                    planSummary, Instant.now().toString());
            conv.getMessages().add(planMsg);
            conv.setUpdatedAt(planMsg.getCreatedAt());
            // 任务清单消息追加后立即落盘
            conversationService.persist();
        } catch (Exception e) {
            log.error("Failed to serialize pipeline plan: {}", e.getMessage());
        }

        Flux<String> planEvent = Flux.just(
                sseEvent("meta", Map.of("conversation_id", convId), assistantMsgId, null),
                sseEvent("planning", planningData, assistantMsgId, null),
                sseEvent("text_delta", Map.of("content", planText), assistantMsgId, null),
                sseEvent("text_done", Map.of("content", planText), assistantMsgId, null)
        );

        return planEvent.concatWith(executePipeline(plan, 0, convId, userId, conv));
    }

    /**
     * 恢复多意图管道（从 pendingPipeline 中继续）
     */
    private Flux<String> handleMultiResume(String convId, String userId, Conversation conv, String userMessage) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);

        // 先完成当前暂停的任务
        String pendingSkill = ctx.pendingSkillName;
        Map<String, Object> pendingParams = new LinkedHashMap<>(ctx.pendingSkillParams);
        pendingParams.put("_user_input", userMessage);

        Map<String, Object> currentDecision = new LinkedHashMap<>();
        currentDecision.put("action", "skill");
        currentDecision.put("skill", pendingSkill);
        currentDecision.put("params", pendingParams);
        currentDecision.put("reason", "恢复管道当前任务: " + pendingSkill);
        contextMemoryService.clearPendingSkill(convId);

        // 恢复计划清单：优先用暂停时保存的完整快照（含已完成任务、label），
        // 其次从对话历史中最近一条规划消息恢复完整 plan（后端重启等内存快照丢失场景），
        // 最后兑底从 pendingPipeline 重建（只含剩余任务，此时前端 task_start 会以
        // 已有清单兑底，避免"第 1/1 项「第 1 项任务」"标签错乱与总数缩水）
        List<Map<String, Object>> planSnapshot = ctx.pipelinePlan;
        if (planSnapshot == null || planSnapshot.isEmpty()) {
            planSnapshot = findPipelinePlanFromHistory(conv);
        }
        if (planSnapshot == null || planSnapshot.isEmpty()) {
            planSnapshot = new ArrayList<>();
            for (Map<String, Object> task : ctx.pendingPipeline) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("skill", task.get("skill"));
                item.put("label", skillRegistry.getSkillLabel((String) task.get("skill")));
                item.put("order", task.getOrDefault("order", 0));
                planSnapshot.add(item);
            }
        }

        // 本次恢复执行的是 pendingSkill（暂停的任务）：其序号需从完整计划快照中按 skill 反查，
        // 而不是取 pendingPipeline 首项（那是剩余任务，序号靠后），否则前端会把尚未完成的
        // 暂停任务错标为已完成、任务进度卡提前跳到后续任务
        int currentIndex = 0;
        int currentOrder = currentIndex + 1;
        for (int i = 0; i < planSnapshot.size(); i++) {
            if (pendingSkill.equals(planSnapshot.get(i).get("skill"))) {
                currentIndex = i;
                currentOrder = (int) planSnapshot.get(i).getOrDefault("order", i + 1);
                break;
            }
        }

        String planText = buildPlanTextFromSnapshot(planSnapshot);
        Map<String, Object> planningData = new LinkedHashMap<>();
        planningData.put("plan", planSnapshot);
        planningData.put("text", planText);
        // 恢复路径：前端应更新已有任务清单卡片（而非新建），故标记 resume=true
        planningData.put("resume", true);
        planningData.put("frameId", ctx.currentFrameId); // Phase 1：前端按 frameId 精确定位历史任务卡

        Map<String, Object> taskStartData = new LinkedHashMap<>();
        taskStartData.put("index", currentOrder);
        taskStartData.put("total", planSnapshot.size());
        taskStartData.put("skill", pendingSkill);
        taskStartData.put("label", skillRegistry.getSkillLabel(pendingSkill));
        taskStartData.put("order", currentOrder);
        taskStartData.put("frameId", ctx.currentFrameId); // Phase 1：任务标识
        // Phase 3（Plan 投影）：恢复路径兜底重建计划（内存快照丢失场景）后投影任务开始
        planProjectionService.ensurePlan(convId, ctx.currentFrameId, planSnapshot);
        planProjectionService.markStepRunning(convId, currentOrder);

        Flux<String> resumeEvents = Flux.just(
                sseEvent("planning", planningData, null, convId),
                sseEvent("task_start", taskStartData, null, convId)
        );

        Flux<String> currentTask = handleSkill(currentDecision, convId, userId, conv, currentIndex);

        // 完成后检查并执行剩余任务
        return resumeEvents.concatWith(currentTask).concatWith(Flux.defer(() -> {
            ContextMemoryService.ConversationContext ctx2 = contextMemoryService.get(convId);
            // 恢复的任务再次返回信息缺失（如用户只选择了企业但尚未上传附件）：
            // 必须停止续跑、保留剩余任务队列（pendingPipeline 不清空），发送暂停事件等待
            // 用户补齐信息后下一条消息再次 resume。若先执行剩余任务，pendingSkill 会被
            // 后续任务覆盖，导致"请上传营业执照"等补充机会被跳过、直接跳到下一个任务
            if (ctx2.hasPendingSkill() || ctx2.isWaitingReport()) {
                Map<String, Object> pausedData = new LinkedHashMap<>();
                pausedData.put("hint", ctx2.pendingInputHint);
                return Flux.just(sseEvent("pipeline_paused", pausedData, null, convId));
            }
            if (ctx2.hasPendingPipeline()) {
                List<Map<String, Object>> remaining = new ArrayList<>(ctx2.pendingPipeline);
                // 续跑前用最新上下文企业刷新剩余任务参数：穿插或候选确认可能已补全/更换企业名，
                // 旧快照参数（如简称"小米"）续跑会再次模糊匹配弹候选卡，导致管道无法自动推进
                if (ctx2.companyName != null && !ctx2.companyName.isEmpty()) {
                    for (Map<String, Object> task : remaining) {
                        Map<String, Object> params = task.get("params") instanceof Map
                                ? (Map<String, Object>) task.get("params") : new LinkedHashMap<>();
                        String taskCompany = String.valueOf(params.getOrDefault("company_name", ""));
                        // 同一企业判定走 CompanyNameExtractor（剥离企业后缀比较核心名），
                        // 解决简称/全称互不包含（"小米科技有限责任公司" vs "小米科技公司"）
                        // 导致刷新被跳过、续跑再次弹候选卡的问题；表述不一致才刷新
                        boolean sameCompany = CompanyNameExtractor.isSameCompany(ctx2.companyName, taskCompany);
                        if (sameCompany && !taskCompany.equals(ctx2.companyName)) {
                            params.put("company_name", ctx2.companyName);
                            if (ctx2.creditCode != null && !ctx2.creditCode.isEmpty()) {
                                params.put("credit_code", ctx2.creditCode);
                            } else {
                                params.remove("credit_code");
                            }
                            task.put("params", params);
                            log.info("Remaining task params refreshed with context company '{}' (skill: {})",
                                    ctx2.companyName, task.get("skill"));
                        }
                    }
                }
                ctx2.pendingPipeline.clear();
                List<TaskPlanner.PlanTask> remainingPlan = new ArrayList<>();
                for (Map<String, Object> task : remaining) {
                    remainingPlan.add(new TaskPlanner.PlanTask(
                            (String) task.get("skill"),
                            task.get("params") instanceof Map ? (Map<String, Object>) task.get("params") : new LinkedHashMap<>(),
                            (int) task.getOrDefault("order", 0),
                            null, List.of()));
                }
                return executePipeline(remainingPlan, 0, convId, userId, conv);
            }
            return Flux.empty();
        }));
    }

    /**
     * Phase 3（Plan 投影）：解析任务在完整计划中的全局 order（管道内直接取 multiIndex + 1；
     * multiIndex=-1 恢复路径按 skill 反查；查不到返回 0，投影方按 order 找不到步骤时跳过）
     */
    private int resolvePipelineOrder(String convId, String skillName, int multiIndex) {
        if (multiIndex >= 0) return multiIndex + 1;
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        if (ctx.pipelinePlan != null) {
            for (Map<String, Object> item : ctx.pipelinePlan) {
                if (skillName.equals(item.get("skill"))) {
                    return (int) item.getOrDefault("order", 0);
                }
            }
        }
        return 0;
    }

    /**
     * Phase 3（Plan 投影）：解析任务在完整计划中的全局 order（管道内直接取 multiIndex + 1；
     * multiIndex=-1 恢复路径按 skill 反查；查不到返回 0，投影方按 order 找不到步骤时跳过）
     */
    private Flux<String> executePipeline(List<TaskPlanner.PlanTask> plan, int startIndex,
                                         String convId, String userId, Conversation conv) {
        if (startIndex >= plan.size()) {
            // 管道全部任务执行完毕，清理计划快照（任务暂停时会先返回 Flux.empty()，不会走到这里）
            ContextMemoryService.ConversationContext ctxDone = contextMemoryService.get(convId);
            // 先取出完整计划快照用于持久化完成卡，再清理内存快照
            List<Map<String, Object>> fullPlan = null;
            if (ctxDone.pipelinePlan != null && !ctxDone.pipelinePlan.isEmpty()) {
                fullPlan = new ArrayList<>(ctxDone.pipelinePlan);
            }
            if (!ctxDone.hasPendingSkill()) {
                ctxDone.pipelinePlan.clear();
            }
            if (fullPlan == null || fullPlan.isEmpty()) {
                fullPlan = findPipelinePlanFromHistory(conv);
            }
            // 全部完成：持久化最终完成卡（kind=complete），使"N 项任务已完成"闭环
            // 在切换会话/刷新后仍保留在对话流中（与前端 done 事件新建完成卡一致）
            if (fullPlan != null && !fullPlan.isEmpty()) {
                Map<String, Object> completeCard = new LinkedHashMap<>();
                completeCard.put("action", "pipeline");
                completeCard.put("kind", "complete");
                completeCard.put("plan", fullPlan);
                completeCard.put("total", fullPlan.size());
                completeCard.put("currentOrder", fullPlan.size());
                completeCard.put("paused", false);
                completeCard.put("completed", true);
                persistPipelineCard(conv, completeCard);
                // 全部完成：将历史中所有 plan/switch 卡标记为完成态，避免切换会话/
                // 刷新后这些卡片仍按 currentOrder 显示"进行中"（与末尾完成卡矛盾）
                markPipelineCardsCompleted(conv);
                // Phase 3（Plan 投影）：管道全部完成 → 计划整体 COMPLETED
                planProjectionService.markPlanCompleted(convId);
            }
            return Flux.empty();
        }

        TaskPlanner.PlanTask task = plan.get(startIndex);
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", "skill");
        decision.put("skill", task.skill());
        decision.put("params", new LinkedHashMap<>(task.params()));
        decision.put("reason", "管道任务 " + task.order());

        // 任务开始事件：前端据此更新"当前任务 x/y"进度指示。
        // total 必须是完整计划的任务总数（而非传入 plan 的大小）：暂停恢复时
        // handleMultiResume 传入的 remainingPlan 只含剩余任务，若按 plan.size()
        // 计算会让 task_start.total 缩水（如 2 → 1），前端任务切换卡/完成卡会按
        // 错误总数渲染（正在执行的任务被隐藏、"N 项任务已完成"数量错误）。
        // plan 中任务的 order 是完整计划的全局连续编号（从 1 起），剩余计划必含
        // 原计划尾部任务，order 最大值即总任务数，不依赖可能丢失/缩水的内存
        // 快照 pipelinePlan（后端重启或快照被清理后无法还原完整计划）
        int totalTasks = plan.size();
        for (TaskPlanner.PlanTask t : plan) {
            totalTasks = Math.max(totalTasks, t.order());
        }
        ContextMemoryService.ConversationContext ctxTotal = contextMemoryService.get(convId);
        if (ctxTotal.pipelinePlan != null && ctxTotal.pipelinePlan.size() > totalTasks) {
            totalTasks = ctxTotal.pipelinePlan.size();
        }
        Map<String, Object> taskStartData = new LinkedHashMap<>();
        taskStartData.put("index", task.order());
        taskStartData.put("total", totalTasks);
        taskStartData.put("skill", task.skill());
        taskStartData.put("label", skillRegistry.getSkillLabel(task.skill()));
        taskStartData.put("order", task.order());
        taskStartData.put("frameId", ctxTotal.currentFrameId); // Phase 1：任务标识
        // Phase 3（Plan 投影）：任务开始 → 步骤 RUNNING（单技能无计划时静默跳过）
        planProjectionService.markStepRunning(convId, task.order());
        Flux<String> taskStartEvent = Flux.just(sseEvent("task_start", taskStartData, null, convId));

        // 管道进度持久化：更新初始执行计划卡（首卡）的 currentOrder，使切换会话/刷新后
        // 首卡仍反映最新执行进度；进入新任务（order>1）时追加任务切换卡，使其保留在对话流中
        updatePipelinePlanCardOrder(conv, task.order());
        List<Map<String, Object>> planSnapshot = snapshotPlan(convId, conv);
        if (task.order() > 1 && planSnapshot != null && !planSnapshot.isEmpty()) {
            Map<String, Object> switchCard = new LinkedHashMap<>();
            switchCard.put("action", "pipeline");
            switchCard.put("kind", "switch");
            switchCard.put("plan", planSnapshot);
            switchCard.put("total", totalTasks);
            switchCard.put("currentOrder", task.order());
            switchCard.put("paused", false);
            persistPipelineCard(conv, switchCard);
        }

        // multiIndex 必须是任务在完整计划中的 0-based 下标（全局 order - 1）：
        // handleSkill 依据它生成 task_done 事件的 order（multiIndex + 1）与结果卡
        // _multi_index；若传 remainingPlan 的局部下标（暂停恢复后从 0 起），
        // risk 等后续任务会被错标为第 1 个任务，前端进度卡无法正确推进
        Flux<String> taskFlux = taskStartEvent.concatWith(handleSkill(decision, convId, userId, conv, task.order() - 1));

        // 检查是否暂停（pendingSkill 被设置），如果暂停则记录剩余管道
        return taskFlux.concatWith(Flux.defer(() -> {
            ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
            if (ctx.hasPendingSkill() || ctx.isWaitingReport()) {
                // 记录剩余任务到 pendingPipeline
                List<Map<String, Object>> remaining = new ArrayList<>();
                for (int i = startIndex + 1; i < plan.size(); i++) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("skill", plan.get(i).skill());
                    t.put("params", plan.get(i).params());
                    t.put("order", plan.get(i).order());
                    t.put("_index", i);
                    remaining.add(t);
                }
                ctx.pendingPipeline.addAll(remaining);
                log.info("Pipeline paused at task {}, remaining {} tasks saved to pendingPipeline",
                        startIndex, remaining.size());
                // 暂停事件：仅标记前端卡片暂停状态（hint 保留供未来扩展），
                // 具体补充提示（如"请上传该企业的营业执照图片"）由 handleSkill 的
                // text_delta/text_done 以文本气泡返回，提示只出现在对话流一处
                Map<String, Object> pausedData = new LinkedHashMap<>();
                pausedData.put("hint", ctx.pendingInputHint);
                return Flux.just(sseEvent("pipeline_paused", pausedData, null, convId));
            }
            // 继续下一个任务
            return executePipeline(plan, startIndex + 1, convId, userId, conv);
        }));
    }

    // ---------- SSE 辅助方法 ----------

    /**
     * 从对话历史中恢复最近一次完整任务计划快照（含 label/order）。
     * 多意图管道首次规划时 handleMulti 会把完整 plan 持久化为 assistant 消息
     * （content 为 {"action":"pipeline","plan":[{skill,label,order},...]}）。
     * 暂停恢复时若内存快照 pipelinePlan 已丢失（如后端重启），从历史中恢复完整计划，
     * 避免兑底重建（只含剩余任务、label 缺失）导致前端任务总数缩水、
     * 任务标签显示"第 X 项任务"占位、已完成任务数错乱。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findPipelinePlanFromHistory(Conversation conv) {
        List<Message> messages = conv.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (!"assistant".equals(msg.getRole())) continue;
            try {
                Map<String, Object> content = mapper.readValue(msg.getContent(),
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
                if (content != null && "pipeline".equals(content.get("action"))) {
                    Object planObj = content.get("plan");
                    if (planObj instanceof List && !((List<?>) planObj).isEmpty()) {
                        return (List<Map<String, Object>>) planObj;
                    }
                }
            } catch (Exception e) {
                // 非 JSON 或解析失败的消息（如普通文本回复）直接跳过
            }
        }
        return null;
    }

    /**
     * 从计划快照（含 label/order 的 Map 列表）生成规划文本，格式与 TaskPlanner.buildPlanText 一致
     */
    private String buildPlanTextFromSnapshot(List<Map<String, Object>> planSnapshot) {
        if (planSnapshot == null || planSnapshot.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("我将依次为您执行：");
        String[] numbers = {"①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩"};
        for (int i = 0; i < planSnapshot.size(); i++) {
            Object label = planSnapshot.get(i).get("label");
            sb.append(numbers[i < numbers.length ? i : 0]).append(" ").append(label);
            if (i < planSnapshot.size() - 1) sb.append(" ");
        }
        return sb.toString();
    }

    /**
     * 获取当前管道的完整任务快照（含 label/order）：优先用内存快照 pipelinePlan，
     * 丢失时（如后端重启）从会话历史中恢复初始规划卡上的完整计划。
     */
    private List<Map<String, Object>> snapshotPlan(String convId, Conversation conv) {
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(convId);
        if (ctx.pipelinePlan != null && !ctx.pipelinePlan.isEmpty()) {
            return new ArrayList<>(ctx.pipelinePlan);
        }
        return findPipelinePlanFromHistory(conv);
    }

    /**
     * Phase 3：解析当前任务在多意图管道中的全局序号（用于 Plan 投影挂接）。
     * 管道内直接取 multiIndex + 1；模板选择/报告跳转等 pendingSkill 重入路径
     * （multiIndex=-1）按 skill 反查计划快照中的全局 order。
     */
    private int resolvePipelineOrder(String skillName, int multiIndex,
                                     ContextMemoryService.ConversationContext ctx) {
        int order = multiIndex + 1;
        if (multiIndex < 0 && ctx.pipelinePlan != null && !ctx.pipelinePlan.isEmpty()) {
            for (Map<String, Object> item : ctx.pipelinePlan) {
                if (skillName.equals(item.get("skill"))) {
                    return (int) item.getOrDefault("order", order);
                }
            }
        }
        return order;
    }

    /**
     * 标记最新一条 interrupt_ask 询问卡为已答复（answered=true）并落盘。
     * 意图穿插询问的"继续/放弃"一旦被回答，按钮即完成使命；前端 InterruptAskCard
     * 实时点击后本地置灰，刷新/切换会话后依据持久化的 answered 标记恢复置灰，
     * 避免历史询问卡再次可点导致重复回答。从后往前找第一条未答复的询问卡，
     * 兼容"放弃后继续询问下一层"时旧卡已答复、新卡待答复的并存场景。
     */
    private void markInterruptAskAnswered(Conversation conv) {
        List<Message> msgs = conv.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (!"assistant".equals(m.getRole())) continue;
            try {
                Map<String, Object> content = mapper.readValue(m.getContent(),
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
                if (content != null && "interrupt_ask".equals(content.get("action"))
                        && !Boolean.TRUE.equals(content.get("answered"))) {
                    content.put("answered", true);
                    m.setContent(mapper.writeValueAsString(content));
                    conversationService.persist();
                    log.info("Interrupt ask marked answered, conv: {}", conv.getId());
                    return;
                }
            } catch (Exception e) {
                // 非 JSON 或解析失败的消息跳过
            }
        }
    }

    /**
     * 标记最新一条候选企业选择卡为已确认（confirmed=true）并落盘。
     * 前端 CompanyNameSelector 第 1 次点击发送候选确认消息（"公司：xxx\n统一信用代码：xxx"）后，
     * 本方法从后往前找第一条候选卡（action 为 candidates/ambiguous，含 options）且尚未 confirmed
     * 的 assistant 消息置 confirmed=true；配合前端乐观更新，刷新/切换会话后（组件重建、本地
     * 点击计数丢失）仍识别"已确认过候选"，再次点击企业选项直接发起对应功能查询
     * （"帮我查一下{公司}{查询功能}"）而非重复候选确认。
     */
    private void markCompanyCardConfirmed(Conversation conv) {
        List<Message> msgs = conv.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (!"assistant".equals(m.getRole())) continue;
            try {
                Map<String, Object> content = mapper.readValue(m.getContent(),
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
                boolean isCandidateCard = ("candidates".equals(content.get("action"))
                        || "ambiguous".equals(content.get("action")))
                        && content.containsKey("options");
                if (content != null && isCandidateCard
                        && !Boolean.TRUE.equals(content.get("confirmed"))) {
                    content.put("confirmed", true);
                    m.setContent(mapper.writeValueAsString(content));
                    conversationService.persist();
                    log.info("Company card marked confirmed, conv: {}", conv.getId());
                    return;
                }
            } catch (Exception e) {
                // 非 JSON 或解析失败的消息跳过
            }
        }
    }

    /**
     * 将管道进度卡片（任务切换卡/完成卡）持久化为 assistant 消息并落盘，
     * 使这些卡片在切换会话或刷新后仍保留在对话流中。
     */
    private void persistPipelineCard(Conversation conv, Map<String, Object> cardData) {
        try {
            String json = mapper.writeValueAsString(cardData);
            Message msg = new Message(UUID.randomUUID().toString(), "assistant", json, Instant.now().toString());
            conv.getMessages().add(msg);
            conv.setUpdatedAt(msg.getCreatedAt());
            conversationService.persist();
        } catch (Exception e) {
            log.error("Failed to persist pipeline card: {}", e.getMessage());
        }
    }

    /**
     * 更新已持久化的初始执行计划卡（首卡，kind 非 switch/complete）的 currentOrder，
     * 使切换会话/刷新后首卡仍反映最新执行进度（与前端 task_start 同步首卡逻辑一致）。
     */
    @SuppressWarnings("unchecked")
    private void updatePipelinePlanCardOrder(Conversation conv, int currentOrder) {
        List<Message> messages = conv.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (!"assistant".equals(msg.getRole())) continue;
            try {
                Map<String, Object> content = mapper.readValue(msg.getContent(),
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
                if (content != null && "pipeline".equals(content.get("action"))
                        && !"switch".equals(content.get("kind")) && !"complete".equals(content.get("kind"))) {
                    content.put("currentOrder", currentOrder);
                    msg.setContent(mapper.writeValueAsString(content));
                    conversationService.persist();
                    return;
                }
            } catch (Exception e) {
                // 非 JSON 或解析失败的消息（如普通文本回复）直接跳过
            }
        }
    }

    /**
     * 管道全部完成后，将历史中所有任务进度卡（plan/switch，即 kind != complete）
     * 标记为已完成（completed=true），使切换会话/刷新后这些卡片显示"已完成"态，
     * 而非按 currentOrder 显示"进行中"，与末尾绿色完成卡语义一致。
     */
    @SuppressWarnings("unchecked")
    private void markPipelineCardsCompleted(Conversation conv) {
        boolean changed = false;
        for (Message msg : conv.getMessages()) {
            if (!"assistant".equals(msg.getRole())) continue;
            try {
                Map<String, Object> content = mapper.readValue(msg.getContent(),
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
                if (content != null && "pipeline".equals(content.get("action"))
                        && !"complete".equals(content.get("kind"))) {
                    content.put("completed", true);
                    msg.setContent(mapper.writeValueAsString(content));
                    changed = true;
                }
            } catch (Exception e) {
                // 非 JSON 或解析失败的消息（如普通文本回复）直接跳过
            }
        }
        if (changed) {
            conversationService.persist();
        }
    }

    private String sseEvent(String type, Map<String, Object> data, String messageId, String conversationId) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", type);
            if (messageId != null) event.put("message_id", messageId);
            if (conversationId != null) event.put("conversation_id", conversationId);
            if (data != null) event.putAll(data);
            return  mapper.writeValueAsString(event) + "\n\n";
        } catch (Exception e) {
            return " {\"type\":\"error\",\"content\":\"serialization error\"}\n\n";
        }
    }
}