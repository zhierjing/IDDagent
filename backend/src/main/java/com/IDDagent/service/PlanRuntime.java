package com.IDDagent.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3（Plan 状态投影）：计划运行时——Pipeline 执行事实的状态视图（单向投影）。
 *
 * <p>按实施文档要求：Plan 只负责表示"当前任务包含哪些步骤、哪一步正在执行/等待输入/
 * 等待异步结果/完成/失败"，是 Pipeline 的状态投影而非新的执行引擎；本类不参与调度，
 * 由 PlanProjectionService 根据执行事件驱动更新，并随 ExecutionFrame 挂起/恢复保存。
 */
public class PlanRuntime {

    /** 计划唯一标识（Phase 3 仅后端持有，Phase 6 交互 ID 化后再贯穿前端） */
    public String planId = "";

    /** 所属执行帧的任务标识（一个独立意图 = 一个 frameId = 至多一个活动计划） */
    public String frameId = "";

    /** 计划步骤列表（顺序即执行顺序，与 PipelineTask.order 对齐） */
    public List<PlanStep> steps = new ArrayList<>();

    /** 当前执行步骤在 steps 中的下标（0-based；-1 表示尚未开始） */
    public int currentStepIndex = -1;

    /** 计划整体状态（步骤级状态见 PlanStep.status） */
    public PlanStatus status = PlanStatus.PENDING;

    /** 按全局序号查找步骤（order 从 1 起；未找到返回 null） */
    public PlanStep stepByOrder(int order) {
        for (PlanStep step : steps) {
            if (step.order == order) return step;
        }
        return null;
    }

    /** 计划整体状态（Phase 3 由 PlanProjectionService 维护） */
    public enum PlanStatus {
        /** 计划已创建，尚未开始执行 */
        PENDING,
        /** 正在执行中 */
        RUNNING,
        /** 整体等待用户输入（至少一个步骤处于 WAITING_INPUT） */
        WAITING_INPUT,
        /** 整体等待异步外部结果（至少一个步骤处于 WAITING_EXTERNAL） */
        WAITING_EXTERNAL,
        /** 全部步骤完成 */
        COMPLETED,
        /** 执行失败 */
        FAILED,
        /** 已放弃（挂起层被用户放弃/清空） */
        ABANDONED
    }
}
