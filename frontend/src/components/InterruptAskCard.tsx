import { useState } from 'react'
import type { InterruptAskData } from '../types'

interface InterruptAskCardProps {
  data: InterruptAskData
  /** silent=true 时静默发送（不插入用户气泡） */
  onSendMessage?: (content: string, silent?: boolean) => void
}

/**
 * 意图穿插断点询问卡片：新意图执行完毕、旧管道仍挂起时，询问用户是否继续旧管道。
 * 按钮静默发送结构化恢复协议（silent=true，不插入用户气泡）：
 *   {"action":"resume_frame","frameId":"F001"} / {"action":"abandon_frame","frameId":"F001"}
 * 由后端主流程校验 frameId 后恢复/放弃（Phase 5：文档第 29/31 节）。
 * 历史旧询问卡（升级前持久化、无 frameId）回退发送旧文本协议【管道恢复】继续/放弃，
 * 后端继续兼容（文档第 29 节：旧文本协议第一阶段继续兼容）。
 * 已答复（继续/放弃任一点击）后两按钮置灰：实时点击本地置灰，刷新/切换会话后
 * 读取持久化的 answered 标记恢复置灰，避免历史询问卡重复点击产生二次回答。
 */
const InterruptAskCard: React.FC<InterruptAskCardProps> = ({ data, onSendMessage }) => {
  const remaining = data.plan_summary ?? []
  const [answered, setAnswered] = useState(data.answered === true)

  const handleAnswer = (content: string, silent = false) => {
    if (answered) return
    setAnswered(true)
    onSendMessage?.(content, silent)
  }

  // Phase 5：优先发送结构化恢复协议（携带 frameId，后端可校验栈顶）；
  // 历史旧卡无 frameId 时回退旧文本协议（后端兼容路径）。
  // Phase 6：协议消息一并携带 interactionId（后端日志定位用，校验仍以 frameId 为准）
  const sendResume = () => {
    const content = data.frameId
      ? JSON.stringify({ action: 'resume_frame', frameId: data.frameId, interactionId: data.interactionId })
      : '【管道恢复】继续'
    handleAnswer(content, true)
  }
  const sendAbandon = () => {
    const content = data.frameId
      ? JSON.stringify({ action: 'abandon_frame', frameId: data.frameId, interactionId: data.interactionId })
      : '【管道恢复】放弃'
    handleAnswer(content, true)
  }

  return (
    <div className="bg-gradient-to-br from-amber-50 to-orange-50 rounded-xl border border-amber-200 overflow-hidden">
      <div className="px-4 py-3 border-b border-amber-100 bg-white/60">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">⏸</span>
          任务中断提醒
        </h3>
        {data.message && (
          <p className="text-xs text-gray-500 mt-1">{data.message}</p>
        )}
      </div>
      {remaining.length > 0 && (
        <div className="p-3 space-y-2">
          <div className="text-xs text-gray-500 mb-1">剩余任务：</div>
          {remaining.map((t) => (
            <div
              key={`${t.skill}-${t.order}`}
              className="w-full px-4 py-2.5 rounded-lg border border-amber-200 bg-white
                         flex items-center gap-2"
            >
              <span className="flex-shrink-0 w-5 h-5 rounded-full bg-amber-100 text-amber-700
                               flex items-center justify-center text-[10px] font-semibold">
                {t.order}
              </span>
              <span className="text-sm text-gray-700">{t.label}</span>
            </div>
          ))}
          <div className="pt-1 flex gap-2">
            <button
              onClick={sendResume}
              disabled={answered}
              className={`flex-1 px-4 py-2 rounded-lg text-sm font-medium transition-all active:scale-95 ${
                answered
                  ? 'bg-gray-200 text-gray-400 cursor-not-allowed'
                  : 'bg-amber-500 hover:bg-amber-600 text-white cursor-pointer'
              }`}
            >
              继续执行
            </button>
            <button
              onClick={sendAbandon}
              disabled={answered}
              className={`flex-1 px-4 py-2 rounded-lg text-sm transition-all active:scale-95 ${
                answered
                  ? 'bg-gray-100 border border-gray-200 text-gray-400 cursor-not-allowed'
                  : 'bg-white border border-amber-300 hover:bg-amber-50 text-gray-700 cursor-pointer'
              }`}
            >
              不需要
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

export default InterruptAskCard
