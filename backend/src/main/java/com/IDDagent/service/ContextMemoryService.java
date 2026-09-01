package com.IDDagent.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ContextMemoryService {

    /**
     * 判断文本是否包含上下文指代词（用户用"这家公司"等指代前文企业）。
     * 统一委托 {@link CompanyNameExtractor}，词表集中维护，避免多处定义漂移。
     */
    public static boolean isContextReference(String text) {
        return CompanyNameExtractor.isContextReference(text);
    }

    /**
     * 判断文本是否为泛企业指称（非真实企业名）。
     * 统一委托 {@link CompanyNameExtractor}，词表集中维护，避免多处定义漂移。
     */
    public static boolean isGenericCompanyReference(String text) {
        return CompanyNameExtractor.isGenericCompanyReference(text);
    }

    private final Map<String, ConversationContext> store = new ConcurrentHashMap<>();

    /**
     * 获取会话上下文；若不存在则创建并注册到 store 后返回。
     * 注意：不能用 getOrDefault 返回临时实例——调用方（如 handleMulti 设置
     * pipelinePlan）可能直接修改返回对象的字段，临时实例不会写入 store，
     * 会导致管道计划快照等状态丢失（表现为后续 resume 时 pipelinePlan 为空、
     * report-completed 走 skipped 分支，管道永不推进）。
     */
    public ConversationContext get(String conversationId) {
        return store.computeIfAbsent(conversationId, k -> new ConversationContext());
    }

    public void update(String conversationId, String companyName, String creditCode) {
        ConversationContext ctx = store.computeIfAbsent(conversationId, k -> new ConversationContext());
        if (companyName != null && !companyName.isEmpty()) ctx.companyName = companyName;
        if (creditCode != null && !creditCode.isEmpty()) ctx.creditCode = creditCode;
    }

    /**
     * 设置待处理技能（技能返回 info_needed / candidates 时调用）
     * 后续用户消息将直接路由到该技能，跳过 Coordinator/LLM
     */
    public void setPendingSkill(String conversationId, String skillName, Map<String, Object> params) {
        ConversationContext ctx = store.computeIfAbsent(conversationId, k -> new ConversationContext());
        ctx.pendingSkillName = skillName;
        ctx.pendingSkillParams.clear();
        if (params != null) {
            ctx.pendingSkillParams.putAll(params);
        }
    }

    /**
     * 记录暂停等待用户补充时的提示文案（如"请上传该企业的营业执照图片以进行信息核实。"），
     * 供多意图管道暂停时透传给前端任务清单卡片，明确提醒用户需要上传附件或补充信息
     */
    public void setPendingInputHint(String conversationId, String hint) {
        if (hint == null || hint.isEmpty()) return;
        ConversationContext ctx = store.computeIfAbsent(conversationId, k -> new ConversationContext());
        ctx.pendingInputHint = hint;
    }

    /**
     * 清除待处理技能
     */
    public void clearPendingSkill(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx != null) {
            ctx.pendingSkillName = "";
            ctx.pendingSkillParams.clear();
            ctx.pendingInputHint = "";
            ctx.pendingSkillRejected = false;
        }
    }

    /**
     * 设置等待报告生成完成的任务（generate_report 返回 redirect 跳转 H5 编辑页时设置）。
     * 管道任务进入异步报告生成阶段后挂起，直到报告完成（前端轮询到报告 completed 后
     * 调用 report-completed 接口）才清除并推进管道。
     */
    public void setWaitingReportTask(String conversationId, Map<String, Object> task) {
        ConversationContext ctx = store.computeIfAbsent(conversationId, k -> new ConversationContext());
        ctx.waitingReportTask = task;
    }

    /** 清除等待报告标记（报告生成完成、管道推进后调用） */
    public void clearWaitingReport(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx != null) ctx.waitingReportTask = null;
    }

    /** v4：H5 编辑页创建报告任务成功后写回 report_id 到 waitingReportTask。
     *  供穿插恢复时区分"报告任务未创建"（用户跳转 H5 后未上传附件生成即关闭）
     *  与"报告仍在生成"两种状态，避免管道等待一个永不完成的任务而永久挂起。
     *  Phase 7：穿插挂起期间（waitingReportTask 随帧保存、活动态为空）创建报告任务时
     *  同步写回挂起帧内副本；并登记 PendingExternalTask（externalTaskId=report_id），
     *  完成回调（report-completed）按此精确定位归属帧（文档第 38/39 节） */
    public void setWaitingReportId(String conversationId, String reportId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || reportId == null || reportId.isEmpty()) return;
        String frameId = "";
        if (ctx.waitingReportTask != null) {
            ctx.waitingReportTask.put("report_id", reportId);
            Object taskFrameId = ctx.waitingReportTask.get("frameId");
            frameId = taskFrameId instanceof String s ? s : ctx.currentFrameId;
        } else {
            // 报告等待任务已被穿插挂起（随帧保存）：定位含该等待报告的帧并同步 report_id，
            // 避免恢复时误判"报告任务未创建"而重复重发 redirect 卡
            for (ExecutionFrame frame : ctx.suspendedStack) {
                if (frame.hasWaitingReport()) {
                    frame.waitingReport.put("report_id", reportId);
                    frameId = frame.frameId;
                    break;
                }
            }
        }
        if (!frameId.isEmpty()) {
            // 登记外部任务（文档第 39 节 PendingExternalTask）：完成回调按 externalTaskId/frameId
            // 定位归属帧，不依赖当前活动状态
            ctx.externalTasks.put(reportId, new PendingExternalTask(reportId, frameId, "",
                    PendingExternalTask.ExternalTaskType.REPORT_GENERATION,
                    PendingExternalTask.ExternalTaskStatus.RUNNING, null));
        }
    }
    public void updateAttachment(String conversationId, String attachmentUrl) {
        if (attachmentUrl == null || attachmentUrl.isEmpty()) return;
        ConversationContext ctx = store.computeIfAbsent(conversationId, k -> new ConversationContext());
        ctx.attachmentUrl = attachmentUrl;
    }

    public void clearAttachment(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx != null) ctx.attachmentUrl = "";
    }

    public void clear(String conversationId) {
        store.remove(conversationId);
        cancelledFlags.remove(conversationId);
    }

    // ============================================================
    // 意图穿插：挂起栈（v2：支持嵌套穿插，LIFO 逐层挂起/恢复）
    // ============================================================

    /** 挂起栈最大深度（防御性限制）：嵌套穿插达到该上限后拒绝继续压栈
     *  （suspendPipeline 返回 false，调用方放弃当前活动管道并提示用户），
     *  防止极端场景下嵌套穿插与执行帧快照内存无限增长。 */
    public static final int MAX_SUSPENDED_STACK_DEPTH = 5;

    /**
     * 意图穿插：全量挂起当前管道状态并压入挂起栈。
     * 在新意图执行前调用，将旧管道完整状态（含 pendingSkill、pipelinePlan 与
     * waitingReportTask 快照）打包为 ExecutionFrame 执行帧 push 到栈顶后清空前者，避免新意图的
     * handleMulti 覆盖 pipelinePlan 导致旧管道计划丢失。挂起栈支持嵌套穿插：穿插执行中
     * 再次穿插时新帧继续压栈，恢复时从栈顶逐层弹出（LIFO），保证"后挂起者先恢复"。
     *
     * @return true=挂起成功；false=挂起栈已达深度上限（MAX_SUSPENDED_STACK_DEPTH）拒绝本次穿插，
     *         当前活动管道已随之放弃（拒绝语义=放弃，见方法体），栈中已有层不受影响、仍可逐层恢复
     */
    public boolean suspendPipeline(String conversationId) {
        ConversationContext ctx = store.computeIfAbsent(conversationId, k -> new ConversationContext());
        // 防御：嵌套穿插已达栈深度上限 → 拒绝本次穿插并放弃当前活动管道。
        // 取舍：选择"拒绝+提示"而非"覆盖最底层"——覆盖会静默丢弃最老任务（用户无感知，
        // 且需额外处理底层帧的报告作废/事件作废，易遗漏）；拒绝语义透明可预期，
        // 新意图照常执行（用户消息不丢失），栈中已有层仍可逐层恢复
        if (ctx.suspendedStack.size() >= MAX_SUSPENDED_STACK_DEPTH) {
            clearActivePipeline(ctx);
            return false;
        }
        // Phase 2（ExecutionFrame）：挂起现场打包为执行帧（快照兼容层，字段与旧快照键
        // 一一对应），压入挂起栈；保留 v2 嵌套穿插（LIFO）、v3 报告穿插、v4 企业上下文
        // 还原、Phase 1 frameId 全部语义
        ExecutionFrame frame = new ExecutionFrame();
        frame.frameId = ctx.currentFrameId;
        frame.parentFrameId = ctx.parentFrameId;
        frame.suspendedAt = Instant.now();
        frame.pipeline = new ArrayList<>(ctx.pendingPipeline);
        frame.plan = new ArrayList<>(ctx.pipelinePlan);
        // v4：帧携带挂起时刻的企业上下文，恢复时还原，避免穿插执行期间更新过的
        // 企业名漂移到恢复后的旧管道（续跑参数刷新以恢复后的 ctx.companyName 为依据）
        frame.companyName = ctx.companyName;
        frame.creditCode = ctx.creditCode;
        // P4：帧携带挂起时刻的附件 URL，穿插期间上传的新附件不漂移到被挂起的旧管道
        // （穿插层的附件由穿插层自己的任务消费；恢复本层时由帧还原）
        frame.attachmentUrl = ctx.attachmentUrl;
        if (ctx.hasPendingSkill()) {
            Map<String, Object> skill = new LinkedHashMap<>();
            skill.put("skill", ctx.pendingSkillName);
            skill.put("params", new LinkedHashMap<>(ctx.pendingSkillParams));
            skill.put("hint", ctx.pendingInputHint);
            skill.put("retry", ctx.pendingSkillRetry);
            frame.pendingSkill = skill;
        }
        // v3：等待异步报告生成期间穿插 → 等待任务也随帧保存（报告完成后由
        // report-completed 推进；穿插期间报告先完成时 pendingReportDone 标记推迟到恢复消费）
        if (ctx.isWaitingReport()) {
            frame.waitingReport = new LinkedHashMap<>(ctx.waitingReportTask);
        }
        // Phase 3（Plan 投影）：活动计划状态视图随帧保存，穿插期间新意图的计划
        // 不覆盖旧计划（恢复时还原）
        frame.planRuntime = ctx.planRuntime;
        ctx.suspendedStack.push(frame);
        // 现场已随帧保存 → 清空活动状态，避免新意图的 handleMulti/handleSkill 覆盖旧管道状态
        clearActivePipeline(ctx);
        return true;
    }

    /**
     * 清空当前活动管道状态：挂起现场已随帧保存后调用；挂起栈满拒绝穿插时同样调用
     * （拒绝语义即放弃——不压栈则新意图的 handleMulti/handleSkill 必然覆盖旧管道状态，
     * 残留反而导致状态不一致）。
     */
    private void clearActivePipeline(ConversationContext ctx) {
        ctx.pendingPipeline.clear();
        ctx.pipelinePlan.clear();
        ctx.pendingSkillName = "";
        ctx.pendingSkillParams.clear();
        ctx.pendingInputHint = "";
        ctx.pendingSkillRetry = 0;
        ctx.pendingSkillRejected = false;
        ctx.interruptAskPending = false;
        ctx.attachmentUrl = "";
        ctx.waitingReportTask = null;
        ctx.planRuntime = null;
    }

    /**
     * 意图穿插：弹出栈顶执行帧并恢复为当前活动状态（LIFO 恢复）。
     * 复用 handleMultiResume 即可继续执行断点任务；栈中其余层不受影响，
     * 待本层意图结束后流尾 interruptAskCheck 会继续询问下一层。
     *
     * @return 是否成功弹出并恢复（栈为空时返回 false）
     */
    @SuppressWarnings("unchecked")
    public boolean popAndRestorePipeline(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || ctx.suspendedStack.isEmpty()) return false;
        ExecutionFrame frame = ctx.suspendedStack.pop();
        // Phase 2（ExecutionFrame）：字段与旧快照键一一对应，无条件还原（挂起时刻值）；
        // 旧快照 Map 的"无键保持现有"兼容分支随内存态 store 升级自然消除
        ctx.pendingPipeline = new ArrayList<>(frame.pipeline);
        ctx.pipelinePlan = new ArrayList<>(frame.plan);
        if (frame.pendingSkill != null) {
            Map<String, Object> s = frame.pendingSkill;
            ctx.pendingSkillName = String.valueOf(s.getOrDefault("skill", ""));
            Object params = s.get("params");
            ctx.pendingSkillParams.clear();
            if (params instanceof Map) {
                ctx.pendingSkillParams.putAll((Map<String, Object>) params);
            }
            ctx.pendingInputHint = String.valueOf(s.getOrDefault("hint", ""));
            ctx.pendingSkillRetry = ((Number) s.getOrDefault("retry", 0)).intValue();
        } else {
            // 帧无暂停技能：确保活动技能字段为空，避免残留上一层的技能状态
            ctx.pendingSkillName = "";
            ctx.pendingSkillParams.clear();
            ctx.pendingInputHint = "";
            ctx.pendingSkillRetry = 0;
        }
        // v3：恢复帧中的等待报告任务；帧无 waitingReport（普通挂起层）时必须清空，
        // 避免残留上层报告的等待状态干扰当前层任务执行
        if (frame.hasWaitingReport()) {
            ctx.waitingReportTask = new LinkedHashMap<>(frame.waitingReport);
        } else {
            ctx.waitingReportTask = null;
        }
        // v4：还原帧携带的企业上下文
        ctx.companyName = frame.companyName;
        ctx.creditCode = frame.creditCode;
        // P4：还原帧携带的附件 URL（穿插期间的上传附件随帧隔离，恢复本层时归位）
        ctx.attachmentUrl = frame.attachmentUrl;
        // Phase 1（frameId）：还原帧携带的任务标识
        ctx.currentFrameId = frame.frameId;
        ctx.parentFrameId = frame.parentFrameId;
        // Phase 3（Plan 投影）：还原帧携带的计划状态视图（帧无计划时为 null）
        ctx.planRuntime = frame.planRuntime;
        // Phase 7（DeferredEvent）：帧内挂起期间到达的异步事件随恢复转移到活动缓存，
        // 由恢复路径消费后清空（文档第 40 节 consumeDeferredEvents(frame)）
        ctx.activeDeferredEvents = frame.deferredEvents != null
                ? new ArrayList<>(frame.deferredEvents) : new ArrayList<>();
        ctx.interruptAskPending = false;
        return true;
    }

    /**
     * 意图穿插：弹出并丢弃栈顶执行帧（放弃该层旧管道）。
     *
     * @return true 表示栈中仍有下一层挂起（调用方应继续询问下一层）；
     *         false 表示栈已空（无更多挂起旧管道）
     */
    public boolean popSuspendedSnapshot(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || ctx.suspendedStack.isEmpty()) return false;
        ExecutionFrame top = ctx.suspendedStack.peek();
        // Phase 2（ExecutionFrame）：记录放弃态（日志/统计用，不参与调度）
        if (top != null) {
            top.status = ExecutionFrame.FrameStatus.ABANDONED;
        }
        ctx.suspendedStack.pop();
        // v3：放弃的是等待报告层 → 其报告作废，同时清除穿插期间可能置位的
        // pendingReportDone 标记，避免被栈中其余层误消费
        if (top != null && top.hasWaitingReport()) {
            ctx.pendingReportDone = false;
        }
        // Phase 3（Plan 投影）：放弃后活动计划随之作废
        ctx.planRuntime = null;
        // Phase 7（DeferredEvent）：放弃帧 → 其挂起期间到达的事件随帧作废，恢复缓存同步清空
        ctx.activeDeferredEvents.clear();
        if (ctx.suspendedStack.isEmpty()) {
            ctx.interruptAskPending = false;
            return false;
        }
        return true;
    }

    /** 意图穿插：清理整个挂起栈与询问标记（隐式放弃全部旧管道场景） */
    public void clearSuspended(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx != null) {
            ctx.suspendedStack.clear();
            ctx.interruptAskPending = false;
            ctx.pendingReportDone = false;
            // Phase 1（frameId）：放弃/清空挂起后当前无活动任务，清空 frame 标识
            ctx.currentFrameId = "";
            ctx.parentFrameId = "";
            // Phase 3（Plan 投影）：隐式放弃全部挂起 → 活动计划一并作废
            ctx.planRuntime = null;
            // Phase 7（DeferredEvent）：隐式放弃全部挂起 → 事件缓存一并清空
            ctx.activeDeferredEvents.clear();
        }
    }

    /** 意图穿插：挂起栈中是否存在含等待报告任务（waitingReport）的层 */
    public boolean suspendedStackHasWaitingReport(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || ctx.suspendedStack.isEmpty()) return false;
        for (ExecutionFrame frame : ctx.suspendedStack) {
            if (frame.hasWaitingReport()) return true;
        }
        return false;
    }

    /** 意图穿插：栈顶执行帧的完整计划（供中断询问卡片展示剩余任务摘要） */
    public List<Map<String, Object>> peekSuspendedPlan(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || ctx.suspendedStack.isEmpty()) return new ArrayList<>();
        ExecutionFrame top = ctx.suspendedStack.peek();
        return top != null ? top.plan : new ArrayList<>();
    }

    /**
     * 意图穿插：栈顶执行帧的"剩余未完成任务"摘要（[{skill,label,order},...]，供中断询问卡片展示）。
     * 与 peekSuspendedPlan（完整计划快照，含已完成任务）不同：仅统计挂起时刻尚未完成的部分——
     *  - pendingSkill / waitingReport 各算 1 个任务（order 优先用自身携带值，缺省时从完整计划反查）
     *  - pendingPipeline 各项依次接续
     * 避免"已完成任务仍计入未完成"导致询问文案/剩余任务列表失真（如任务 1 完成后穿插，
     * 询问仍显示 N 项任务未完成）
     */
    public List<Map<String, Object>> peekSuspendedRemainingTasks(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || ctx.suspendedStack.isEmpty()) return new ArrayList<>();
        ExecutionFrame top = ctx.suspendedStack.peek();
        if (top == null) return new ArrayList<>();
        List<Map<String, Object>> remaining = new ArrayList<>();
        // 暂停中的任务（等待用户补充输入 / 等待异步报告）：1 个未完成任务
        if (top.pendingSkill != null) {
            String skill = String.valueOf(top.pendingSkill.getOrDefault("skill", ""));
            if (!skill.isEmpty()) {
                remaining.add(buildRemainingSummary(top.plan, skill, -1));
            }
        } else if (top.hasWaitingReport()) {
            String skill = String.valueOf(top.waitingReport.getOrDefault("skill", ""));
            if (!skill.isEmpty()) {
                int order = top.waitingReport.get("order") instanceof Number
                        ? ((Number) top.waitingReport.get("order")).intValue() : -1;
                remaining.add(buildRemainingSummary(top.plan, skill, order));
            }
        }
        // 剩余管道任务队列
        for (Map<String, Object> task : top.pipeline) {
            String skill = String.valueOf(task.getOrDefault("skill", ""));
            if (skill.isEmpty()) continue;
            int order = task.get("order") instanceof Number
                    ? ((Number) task.get("order")).intValue() : -1;
            remaining.add(buildRemainingSummary(top.plan, skill, order));
        }
        return remaining;
    }

    /** 构建单条未完成任务摘要：label/order 优先按 order 精确反查完整计划，其次按 skill 首个匹配 */
    private Map<String, Object> buildRemainingSummary(List<Map<String, Object>> plan, String skill, int order) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("skill", skill);
        summary.put("order", order > 0 ? order : 0);
        if (plan != null) {
            if (order > 0) {
                for (Map<String, Object> item : plan) {
                    Object planOrder = item.get("order");
                    if (planOrder instanceof Number && ((Number) planOrder).intValue() == order
                            && skill.equals(item.get("skill"))) {
                        summary.put("order", order);
                        summary.put("label", String.valueOf(item.getOrDefault("label", "")));
                        return summary;
                    }
                }
            }
            for (Map<String, Object> item : plan) {
                if (skill.equals(item.get("skill"))) {
                    summary.put("order", item.getOrDefault("order", summary.get("order")));
                    summary.put("label", String.valueOf(item.getOrDefault("label", "")));
                    return summary;
                }
            }
        }
        return summary;
    }

    /** Phase 1（frameId）：栈顶执行帧的 frameId（供 interrupt_ask 事件携带被询问层的任务标识） */
    public String peekSuspendedFrameId(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || ctx.suspendedStack.isEmpty()) return "";
        ExecutionFrame top = ctx.suspendedStack.peek();
        return top != null ? top.frameId : "";
    }

    /**
     * Phase 5（Structured Resume）：校验结构化恢复动作是否可执行。
     * 恢复/放弃只作用于"正在询问"的栈顶层（interruptAskPending 期间），且 frameId
     * 必须与栈顶帧一致（文档第 31 节/Case 13：允许恢复 B 时点击旧 A 卡 → 不通过）；
     * 活动任务执行中（interruptAskPending=false）点击恢复卡同样不通过，防止 pop
     * 覆盖活动任务状态。
     */
    public boolean isResumeActionValid(String conversationId, String frameId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || !ctx.interruptAskPending || frameId == null || frameId.isEmpty()) return false;
        ExecutionFrame top = ctx.suspendedStack.peek();
        return top != null && frameId.equals(top.frameId);
    }

    /**
     * Phase 6（交互 ID 化）：校验交互卡所属帧是否为当前活动帧。
     * 文档第 44 节/Case 14：交互卡事件生成时携带生成帧的 frameId，点击回传后校验——
     * 仅当 frameId 与当前活动帧一致才接受交互（该帧对应任务正在等待用户输入）；
     * 不一致（卡片所属帧已挂起/完成，或当前无活动任务）→ 旧卡点击，调用方拒绝并返回
     * INTERACTION_SUSPENDED，禁止把挂起帧的交互值传入当前活动任务。
     */
    public boolean isInteractionActive(String conversationId, String frameId) {
        if (conversationId == null || frameId == null || frameId.isEmpty()) return false;
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null) return false;
        return frameId.equals(ctx.currentFrameId);
    }

    /** Phase 1（frameId）：挂起栈中第一个含等待报告任务的执行帧的 frameId
     *  （报告穿插期间完成事件定位归属任务，EXTERNAL_EVENT_DEFERRED 日志用） */
    public String findWaitingReportFrameId(String conversationId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || ctx.suspendedStack.isEmpty()) return "";
        for (ExecutionFrame frame : ctx.suspendedStack) {
            if (frame.hasWaitingReport()) {
                return frame.frameId;
            }
        }
        return "";
    }

    // ============================================================
    // Phase 7（报告事件泛化）：DeferredEvent 入帧与外部任务归属
    // ============================================================

    /**
     * Phase 7（DeferredEvent）：挂起期间异步完成事件入帧（文档第 40 节——目标帧
     * SUSPENDED → frame.deferredEvents.add(event)，禁止推进被挂起帧）。
     * 按 frameId 精确定位挂起栈帧（文档第 38 节：回调按 externalTaskId/frameId 定位，
     * 不依赖当前活动状态）；找不到目标帧返回 false。
     */
    public boolean deferEvent(String conversationId, String frameId, DeferredEvent event) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || frameId == null || frameId.isEmpty() || event == null) return false;
        for (ExecutionFrame frame : ctx.suspendedStack) {
            if (frameId.equals(frame.frameId)) {
                frame.deferredEvents.add(event);
                return true;
            }
        }
        return false;
    }

    /**
     * Phase 7（DeferredEvent）：按外部任务标识（reportId）完成登记并定位归属帧。
     * 任务登记在册且其 frameId 位于挂起栈 → 置 COMPLETED 并向该帧添加 REPORT_COMPLETED
     * 事件，返回该 frameId（调用方据此回显 deferred 归属并置兼容布尔 pendingReportDone）；
     * 任务未登记或归属帧不在挂起栈（活动帧直接消费路径）→ 返回空串，由调用方走
     * 现有正常推进或"栈中第一个报告层"兜底。
     */
    public String deferReportCompleted(String conversationId, String reportId) {
        ConversationContext ctx = store.get(conversationId);
        if (ctx == null || reportId == null || reportId.isEmpty()) return "";
        PendingExternalTask task = ctx.externalTasks.get(reportId);
        if (task == null) return "";
        ctx.externalTasks.put(reportId, task.withStatus(PendingExternalTask.ExternalTaskStatus.COMPLETED));
        String frameId = task.frameId();
        if (frameId != null && !frameId.isEmpty()
                && deferEvent(conversationId, frameId, DeferredEvent.reportCompleted(reportId, frameId))) {
            return frameId;
        }
        return "";
    }

    // ============================================================
    // 强制终止对话标记
    // ============================================================

    private final Map<String, Boolean> cancelledFlags = new ConcurrentHashMap<>();

    /** 标记该会话的流式生成为终止状态（前端点击"强制终止"时调用） */
    public void cancel(String conversationId) {
        cancelledFlags.put(conversationId, true);
    }

    /** 该会话是否被标记终止 */
    public boolean isCancelled(String conversationId) {
        return Boolean.TRUE.equals(cancelledFlags.get(conversationId));
    }

    /** 清除终止标记（每次新消息开始时重置） */
    public void clearCancelled(String conversationId) {
        cancelledFlags.remove(conversationId);
    }

    public static class ConversationContext {
        public String companyName = "";
        public String creditCode = "";
        /** 待处理技能名称（技能正在等待用户补充信息） */
        public String pendingSkillName = "";
        /** 待处理技能的已有参数 */
        public Map<String, Object> pendingSkillParams = new LinkedHashMap<>();
        /** 待处理技能连续重试次数（防死循环） */
        public int pendingSkillRetry = 0;
        /** 暂停等待用户补充时的提示文案（如"请上传营业执照图片"） */
        public String pendingInputHint = "";
        public String attachmentUrl = "";

        /** 多意图管道剩余任务队列（List<Map<String,Object>>，每项含 skill/params/order/_index） */
        public List<Map<String, Object>> pendingPipeline = new ArrayList<>();

        /** 多意图管道完整计划快照（List<Map<String,Object>>，每项含 skill/label/order），
         *  供暂停恢复时重建 planning 事件（含已完成任务，前端据此恢复任务清单） */
        public List<Map<String, Object>> pipelinePlan = new ArrayList<>();

        /** 等待异步报告生成完成的任务信息（generate_report redirect 阶段设置，
         *  含 skill/label/order；报告完成后由 report-completed 接口清除并推进管道） */
        public Map<String, Object> waitingReportTask = null;

        /** 意图穿插（v2）：挂起栈（LIFO），每层为 ExecutionFrame 执行帧（Phase 2：由旧快照
         *  Map 升级，字段与旧快照键一一对应，保留嵌套穿插、报告穿插、企业上下文还原
         *  全部逻辑；后续 Phase 逐步拆分子对象）。
         *  栈为空表示当前无挂起的旧管道。 */
        public Deque<ExecutionFrame> suspendedStack = new ArrayDeque<>();

        /** 意图穿插：是否已发出询问、正在等待用户确认恢复 */
        public boolean interruptAskPending = false;

        /** Phase 1（frameId）：当前活动任务的 frameId（一个独立意图 = 一个 frameId；
         *  穿插挂起时旧 frameId 随快照保存，恢复时回填；空表示当前无任务执行） */
        public String currentFrameId = "";

        /** Phase 1（frameId）：当前活动任务的父 frameId（穿插新意图时记录被挂起任务
         *  的 frameId，用于日志追踪嵌套层级；恢复挂起层时随快照还原） */
        public String parentFrameId = "";

        /** 意图穿插：当前挂起技能是否已被用户"以上都不是"否决。否决后残留的
         *  pendingSkill 仅供用户直接输入企业名时重入技能，不再视为"执行中任务"——
         *  用户随后发起的新意图不得触发穿插压栈（意图穿插仅服务于多意图管道场景） */
        public boolean pendingSkillRejected = false;

        /** 报告穿插期间完成标记：waitingReportTask 被压栈挂起时 report-completed 无法
         *  直接推进（waitingReportTask 为空），置位表示"等待报告已完成待推进"；
         *  恢复含 waitingReport 的挂起层时消费并就地推进，防止推进动作丢失导致管道死锁。
         *  Phase 7：兼容布尔保留（事件驱动消费的兜底，与 activeDeferredEvents 并存） */
        public boolean pendingReportDone = false;

        /** Phase 7（PendingExternalTask）：会话级外部任务登记表（externalTaskId →
         *  PendingExternalTask；报告任务创建成功时登记，完成回调按此精确定位归属帧。
         *  会话级生命周期，穿插挂起期间不清空——穿插时登记的任务恢复后仍可定位） */
        public Map<String, PendingExternalTask> externalTasks = new LinkedHashMap<>();

        /** Phase 7（DeferredEvent）：恢复帧时携带的挂起期间异步事件缓存
         *  （popAndRestorePipeline 从帧转移，恢复路径消费后清空；正常为空列表） */
        public List<DeferredEvent> activeDeferredEvents = new ArrayList<>();

        /** Phase 3（Plan 投影）：活动计划状态视图（多意图管道执行期间由
         *  PlanProjectionService 单向投影；挂起时随 ExecutionFrame 保存，恢复时还原；
         *  null 表示当前无活动计划——单技能独立执行或管道尚未创建/已结束） */
        public PlanRuntime planRuntime = null;

        public boolean isEmpty() {
            return (companyName == null || companyName.isEmpty())
                    && (creditCode == null || creditCode.isEmpty());
        }

        /** 是否有待处理的技能（技能等待用户回复补充信息） */
        public boolean hasPendingSkill() {
            return pendingSkillName != null && !pendingSkillName.isEmpty();
        }

        /** 是否有多意图管道待恢复 */
        public boolean hasPendingPipeline() {
            return pendingPipeline != null && !pendingPipeline.isEmpty();
        }

        /** 是否在等待异步报告生成完成（generate_report redirect 阶段设置） */
        public boolean isWaitingReport() {
            return waitingReportTask != null;
        }

        /** 意图穿插：是否有被挂起的旧管道（挂起栈非空） */
        public boolean hasSuspendedPipeline() {
            return suspendedStack != null && !suspendedStack.isEmpty();
        }
    }
}
