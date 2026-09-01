import type { IntentCandidate } from '../types'

interface IntentSelectorProps {
  candidates: IntentCandidate[]
  message?: string
  /** Phase 6（交互 ID 化）：卡片所属任务帧标识（后端事件注入，点击回传后校验归属） */
  frameId?: string
  /** Phase 6（交互 ID 化）：本次交互标识（后端事件注入，随协议回传定位） */
  interactionId?: string
  /** silent=true 时静默发送（不插入用户气泡，选择消息不暴露于对话流） */
  onSendMessage?: (content: string, silent?: boolean) => void
}

/**
 * 意图澄清选择卡（intent_candidates 事件渲染）。
 * Phase 6：点击发送结构化交互协议（select_intent + frameId/interactionId，后端校验
 * 帧归属后精确路由技能，不经 LLM）；旧卡无 frameId 时回退文本协议【意图选择】<skill>。
 */
const IntentSelector: React.FC<IntentSelectorProps> = ({ candidates, message, frameId, interactionId, onSendMessage }) => {
  return (
    <div className="bg-gradient-to-br from-purple-50 to-indigo-50 rounded-xl border border-purple-200 overflow-hidden">
      <div className="px-4 py-3 border-b border-purple-100 bg-white/60">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">🎯</span>
          请选择您想要的操作
        </h3>
        {message && (
          <p className="text-xs text-gray-500 mt-1">{message}</p>
        )}
      </div>
      <div className="p-3 space-y-2">
        {candidates.map((c) => (
          <button
            key={c.skill}
            onClick={() =>
              onSendMessage?.(
                frameId && interactionId
                  ? JSON.stringify({ action: 'select_intent', frameId, interactionId, skill: c.skill })
                  : `【意图选择】${c.skill}`,
                true
              )
            }
            className="w-full text-left px-4 py-3 rounded-lg border border-purple-200 bg-white
                       hover:bg-purple-50 hover:border-purple-300 transition-all
                       flex items-center justify-between group cursor-pointer"
          >
            <div>
              <div className="text-sm font-medium text-gray-800 group-hover:text-purple-700">
                {c.label}
              </div>
              {c.description && (
                <div className="text-xs text-gray-400 mt-0.5">{c.description}</div>
              )}
            </div>
            <svg className="w-4 h-4 text-purple-400 group-hover:text-purple-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
            </svg>
          </button>
        ))}
      </div>
    </div>
  )
}

export default IntentSelector
