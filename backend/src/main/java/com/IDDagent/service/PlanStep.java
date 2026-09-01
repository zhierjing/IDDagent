package com.IDDagent.service;

/**
 * Phase 3（Plan 状态投影）：计划中的单个步骤（吸收旧 IDDagent 的 PlanStep 状态机思想）。
 * 由 PlanProjectionService 根据 Pipeline 执行事实单向投影更新，本身不参与调度。
 *
 * <p>stepId 与 PipelineTask 的 ID 映射（PlanStep.stepId ↔ PipelineTask.stepId）在
 * 交互 ID 化阶段（Phase 6）建立，当前按 order 定位。
 */
public class PlanStep {

    /** 步骤唯一标识（Phase 6 与 PipelineTask.stepId 建立双向映射） */
    public String stepId = "";

    /** 执行任务标识（Phase 6 后回填 PipelineTask.taskId，当前为空） */
    public String executionTaskId = "";

    /** 步骤对应的技能标识 */
    public String skillName = "";

    /** 步骤展示标题（技能中文标签） */
    public String title = "";

    /** 步骤在计划内的全局序号（从 1 起，与 PipelineTask.order 一致） */
    public int order = 0;

    /** 步骤状态（Pipeline → Plan 单向投影） */
    public PlanStepStatus status = PlanStepStatus.PENDING;

    /** 状态附加说明（如暂停提示文案，可选） */
    public String statusMessage = "";
}
