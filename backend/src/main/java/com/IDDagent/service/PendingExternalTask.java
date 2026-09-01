package com.IDDagent.service;

/**
 * Phase 7（报告事件泛化）：外部异步任务登记（实施文档第 39 节 PendingExternalTask）。
 * 报告提交（H5 编辑页创建任务成功，report_id 写回）时登记到会话上下文 externalTasks 表，
 * 完成回调（report-completed）按 externalTaskId/frameId 精确定位归属帧（第 38 节），
 * 不再依赖当前活动状态（currentFrame/company/pipeline）判断属于谁。
 *
 * <p>当前业务仅使用 REPORT_GENERATION；ASYNC_QUERY/EXPORT/APPROVAL/CALLBACK 类型
 * 为后续泛化预留（事件类型见 {@link DeferredEvent.EventType}）。
 */
public record PendingExternalTask(String externalTaskId, String frameId, String stepId,
                                  ExternalTaskType type, ExternalTaskStatus status, Object metadata) {

    public PendingExternalTask withStatus(ExternalTaskStatus newStatus) {
        return new PendingExternalTask(externalTaskId, frameId, stepId, type, newStatus, metadata);
    }

    /** 外部任务类型（第 39 节 ExternalTaskType） */
    public enum ExternalTaskType {
        REPORT_GENERATION,
        ASYNC_QUERY,
        EXPORT,
        APPROVAL,
        CALLBACK
    }

    /** 外部任务状态（登记 RUNNING，完成回调置 COMPLETED，失败置 FAILED） */
    public enum ExternalTaskStatus {
        RUNNING,
        COMPLETED,
        FAILED
    }
}
