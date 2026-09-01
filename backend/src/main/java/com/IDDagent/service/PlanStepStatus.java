package com.IDDagent.service;

/**
 * Phase 3（Plan 状态投影）：计划步骤状态（吸收旧 IDDagent 的 PlanStep 状态机思想）。
 * Plan 是 Pipeline 的状态投影，不是新的执行引擎——本枚举仅描述 Pipeline 执行事实的
 * 状态视图，不参与调度决策。
 */
public enum PlanStepStatus {
    /** 尚未开始执行 */
    PENDING,
    /** 正在执行（PipelineTask 开始） */
    RUNNING,
    /** 等待用户补充输入（技能返回 info_needed/candidates/模板选择/候选确认等暂停态） */
    WAITING_INPUT,
    /** 等待异步外部结果（报告已提交 H5 生成，等待 report-completed 回调） */
    WAITING_EXTERNAL,
    /** 执行成功（task_done） */
    DONE,
    /** 执行失败 */
    FAILED,
    /** 已取消（管道被放弃/清空） */
    CANCELLED
}
