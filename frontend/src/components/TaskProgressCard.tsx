import React from 'react';
import type { PipelineExtra, PipelineTask, PipelineTaskStatus } from '../types';

interface TaskProgressCardProps {
  /** 任务清单卡片数据（消息 extra，action='pipeline'） */
  data: PipelineExtra;
}

/** 状态徽章配色映射（右侧徽章：文案 + 样式） */
const STATUS_BADGE: Record<PipelineTaskStatus, { text: string; cls: string }> = {
  PENDING: { text: '待执行', cls: 'bg-gray-100 text-gray-500' },
  WAITING_INPUT: { text: '等待补充信息', cls: 'bg-blue-100 text-blue-600' },
  RUNNING: { text: '执行中', cls: 'bg-blue-600 text-white animate-pulse' },
  DONE: { text: '已完成', cls: 'bg-green-100 text-green-600' },
  FAILED: { text: '失败', cls: 'bg-red-100 text-red-600' },
  WAITING_EXTERNAL: { text: '等待外部完成', cls: 'bg-purple-100 text-purple-600' },
};

/** 高亮判断：状态为 RUNNING/WAITING_INPUT/WAITING_EXTERNAL，或索引等于当前进度索引且为 PENDING */
function isCurrentStep(task: PipelineTask, status: PipelineTaskStatus, currentOrder: number): boolean {
  return (
    status === 'RUNNING' ||
    status === 'WAITING_INPUT' ||
    status === 'WAITING_EXTERNAL' ||
    (task.order === currentOrder && status === 'PENDING')
  );
}

/**
 * 多意图任务清单卡片（extra.action='pipeline'），统一按"任务规划"卡片结构渲染：
 * - 外层 indigo 渐变容器 + 头部（📋 任务规划 + 右侧状态文案）+ 步骤列表
 * - 每个步骤一行：序号徽章 + 步骤名（可选摘要）+ 右侧状态徽章
 * - 任务状态优先取显式 status（未来后端下发 FAILED/WAITING_EXTERNAL 时生效），
 *   缺省按 currentOrder/paused/completed 推导，历史持久化卡片亦能正确展示
 * - 头部状态文案按优先级：已挂起 > 全部完成 > 等待确认 > 汇总文案 > 默认执行中
 */
const TaskProgressCard: React.FC<TaskProgressCardProps> = ({ data }) => {
  const { plan, total, currentOrder, paused, completed, suspended, waitingConfirm, summary } = data;
  if (!plan || plan.length === 0) return null;

  // total 以完整清单长度兜底：历史会话持久化的卡片或后端 resume 场景下
  // total 可能小于清单长度，统一取最大值避免任务行被错误隐藏、进度序号错乱
  const safeTotal = Math.max(total, plan.length);
  // 暂停/已完成时当前任务序号展示不回退（0 = 尚未开始，兜底显示第 1 项）
  const displayOrder = Math.min(Math.max(currentOrder, 1), safeTotal);

  // 步骤状态推导：显式 status 优先，否则按进度位置推导
  const statuses = plan.map((task) =>
    task.status ??
    (completed || task.order < currentOrder
      ? 'DONE'
      : task.order === currentOrder
        ? paused
          ? 'WAITING_INPUT'
          : 'RUNNING'
        : 'PENDING')
  );
  const allDone = completed === true || statuses.every((s) => s === 'DONE');

  const currentTask = plan.find((t) => t.order === displayOrder) ?? plan[displayOrder - 1];
  const currentLabel = currentTask?.label || `第 ${displayOrder} 项任务`;

  // ========== 头部状态文案（优先级自上而下） ==========
  let headerText: string;
  let headerCls: string;
  if (suspended) {
    headerText = '已挂起（可穿插其他任务）';
    headerCls = 'text-amber-600';
  } else if (allDone) {
    headerText = '全部任务已完成';
    headerCls = 'text-green-700';
  } else if (waitingConfirm) {
    headerText = `等待确认下一步（第 ${Math.max(displayOrder - 1, 0)}/${safeTotal} 步已完成）`;
    headerCls = 'text-blue-600';
  } else if (summary) {
    headerText = summary;
    headerCls = 'text-green-700';
  } else {
    headerText = `第 ${displayOrder}/${safeTotal} 步：${currentLabel} 执行中`;
    headerCls = 'text-blue-600';
  }

  return (
    <div className="rounded-xl border overflow-hidden bg-gradient-to-br from-indigo-50 to-blue-50 border-indigo-200">
      {/* 头部：标题 + 右侧状态文案 */}
      <div className="px-4 py-3 border-b border-indigo-100 bg-white/60">
        <div className="flex items-center gap-2">
          <span className="text-lg">📋</span>
          <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">任务规划</h3>
          <span className={`ml-auto text-xs font-normal ${headerCls}`}>{headerText}</span>
        </div>
      </div>

      {/* 步骤列表 */}
      <div className="px-4 py-3 space-y-2">
        {plan.map((task, idx) => {
          const status = statuses[idx];
          const badge = STATUS_BADGE[status];
          const active = isCurrentStep(task, status, currentOrder);
          return (
            <div
              key={task.skill}
              className={`flex items-center gap-3 px-3 py-2.5 rounded-lg border transition-colors ${
                active ? 'bg-white border-indigo-300 shadow-sm' : 'bg-white/50 border-indigo-100'
              }`}
            >
              <span
                className={`w-6 h-6 rounded-full flex items-center justify-center text-xs font-semibold flex-shrink-0 ${
                  active ? 'bg-indigo-600 text-white' : 'bg-gray-200 text-gray-500'
                }`}
              >
                {task.order}
              </span>
              <div className="min-w-0 flex-1">
                <div className={`text-sm font-medium truncate ${active ? 'text-indigo-800' : 'text-gray-800'}`}>
                  {task.label}
                </div>
                {task.summary && <div className="text-xs text-gray-500 truncate">{task.summary}</div>}
              </div>
              <span className={`text-xs px-2 py-0.5 rounded-full font-medium flex-shrink-0 ${badge.cls}`}>
                {badge.text}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default TaskProgressCard;
