package com.IDDagent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 2（ExecutionFrame）：执行帧——一个可被完整保存、挂起、恢复、放弃的任务运行现场，
 * 意图穿插挂起栈（suspendedFrames）的元素单位。
 *
 * <p>第一版为"快照兼容层"：字段与旧挂起快照 Map 键一一对应（pipeline/plan/pendingSkill/
 * waitingReport/companyName/creditCode + frameId/parentFrameId），保留全部现有字段与逻辑；
 * 后续 Phase 逐步拆分为 PlanRuntime / ExecutionRuntime / BusinessContext 等子对象。
 * 按实施文档要求：不允许为了追求类结构漂亮而一次性重写成熟 Pipeline 代码。
 */
public class ExecutionFrame {

    /** 任务标识（一个独立意图 = 一个 frameId；空表示旧快照升级前无标识） */
    public String frameId = "";

    /** 父帧标识（嵌套穿插时记录被挂起任务的 frameId，日志追踪嵌套层级用） */
    public String parentFrameId = "";

    /** 帧状态（Phase 2 仅记录挂起时刻状态，不参与调度；后续 Phase 逐步收敛
     *  interruptAskPending/isWaitingXXX/reportXXXPending 等组合 boolean） */
    public FrameStatus status = FrameStatus.SUSPENDED;

    /** 挂起时刻（日志/统计用） */
    public Instant suspendedAt = null;

    // ---------------- 兼容旧快照字段（与 suspendPipeline 快照键一一对应） ----------------

    /** 多意图管道剩余任务队列（List<Map<String,Object>>，每项含 skill/params/order/_index） */
    public List<Map<String, Object>> pipeline = new ArrayList<>();

    /** 多意图管道完整计划快照（List<Map<String,Object>>，每项含 skill/label/order，
     *  供恢复时重建 planning 事件） */
    public List<Map<String, Object>> plan = new ArrayList<>();

    /** 挂起时刻的待处理技能（{skill, params, hint, retry}；无待处理技能时为 null） */
    public Map<String, Object> pendingSkill = null;

    /** 挂起时刻的等待报告任务副本（waitingReportTask；报告生成期间穿插时非 null） */
    public Map<String, Object> waitingReport = null;

    /** 挂起时刻的企业上下文（v4：恢复时还原，避免穿插执行期间更新过的企业名漂移） */
    public String companyName = "";

    /** 挂起时刻的统一信用代码上下文 */
    public String creditCode = "";

    /** 挂起时刻的最新附件 URL（P4：随帧快照/还原，穿插期间上传的附件不漂移到被挂起的旧管道） */
    public String attachmentUrl = "";

    /** Phase 3（Plan 投影）：挂起时刻的计划状态视图（活动 PlanRuntime 快照；
     *  恢复时还原，穿插期间新意图的计划不会漂移到恢复后的旧管道） */
    public PlanRuntime planRuntime = null;

    /** Phase 7（DeferredEvent）：挂起期间到达的异步完成事件（实施文档第 40 节——
     *  目标帧 SUSPENDED 时事件入帧，禁止推进被挂起帧；恢复该帧时由恢复路径
     *  consumeDeferredEvents 消费后再决定继续 Pipeline 或等待其他事件）。
     *  帧被放弃（popSuspendedSnapshot）时随帧作废。 */
    public List<DeferredEvent> deferredEvents = new ArrayList<>();

    /** 是否含等待异步报告生成的任务（报告穿插层判定） */
    public boolean hasWaitingReport() {
        return waitingReport != null;
    }

    /** 帧状态（Phase 2 仅使用 SUSPENDED/ABANDONED；RUNNING/WAITING_* 等由现有
     *  ConversationContext 活动状态推导，逐步收敛迁移） */
    public enum FrameStatus {
        RUNNING,
        WAITING_INPUT,
        WAITING_EXTERNAL,
        SUSPENDED,
        RESUME_CONFIRMING,
        COMPLETED,
        FAILED,
        ABANDONED
    }
}
