import React, { useEffect, useRef, useState } from 'react'
import type { CompanyNameCandidate } from '../types'
import CompanyCandidatePanel from './CompanyCandidatePanel'

interface CompanyNameSelectorProps {
  options: CompanyNameCandidate[]
  keyword?: string
  /** 所属任务标识（多意图管道中如"历史尽调报告查询"），候选选择与任务关联，用户清楚是谁在询问 */
  taskLabel?: string
  /** 查询功能标签（如"基本信息"）：已确认候选后点击企业选项拼"帮我查一下{公司名}{标签}"直接触发对应功能 */
  queryLabel?: string
  /**
   * 该候选卡是否已确认过候选（消息 extra.confirmed，后端持久化）。
   * 第 1 次点击发送候选确认格式（"公司：xxx\n统一信用代码：xxx"）后置 true，
   * 刷新/切换会话（组件重建、本地状态丢失）后仍能识别"已确认过"。
   */
  confirmed?: boolean
  /** Phase 6（交互 ID 化）：卡片所属任务帧标识（后端事件注入，点击回传后校验归属） */
  frameId?: string
  /** Phase 6（交互 ID 化）：本次交互标识（后端事件注入，随协议回传定位） */
  interactionId?: string
  /** silent=true 时静默发送（不展示企业名/信用代码于 user 气泡），直接进入结果/下一步 */
  onSendMessage?: (content: string, silent?: boolean) => void
}

/**
 * 候选企业选择卡片（company_name_candidates 事件渲染）。
 * 首次点击企业选项为候选确认（Phase 6：发送结构化交互协议 select_candidate JSON，
 * 携带 frameId/interactionId 供后端校验帧归属；旧卡无 frameId 时回退文本格式
 * "公司：xxx\n统一信用代码：xxx"，后端继续兼容）；已确认后再次点击相当于点击
 * 公司名直接发起查询（发送"帮我查一下{公司名}{查询功能}"），支持多意图中手滑
 * 选错企业后纠错（后端按新意图穿插挂起当前管道，先执行新查询再继续旧管道）。
 * 确认状态以消息 extra.confirmed 为准（后端落盘 + 前端乐观更新），组件重建不丢失。
 * 样式统一走 CompanyCandidatePanel（头部含功能名称）；options 为空时渲染"未找到企业"
 * 空态（无"以上都不是"选项）。
 */
const CompanyNameSelector: React.FC<CompanyNameSelectorProps> = ({
  options,
  keyword,
  taskLabel,
  queryLabel,
  confirmed = false,
  frameId,
  interactionId,
  onSendMessage,
}) => {
  // 本地确认标记：实时点击第 1 次后立即生效（无需等后端落盘响应），
  // 与 prop confirmed 取并集——组件不重建时本地标记即可，重建后靠持久化 prop 恢复
  const localConfirmedRef = useRef(false)
  const [timedOut, setTimedOut] = useState(false)
  const isConfirmed = confirmed || localConfirmedRef.current
  const title = taskLabel || '企业查询'

  // 未选择超时提示：候选确认是对话式挂起点（后端 setPendingSkill 等待确认），
  // 长时间不选择时给出明确提醒并告知后续操作，避免界面静默停在"等待候选确认"
  // 造成"对话不停止"的观感（原查询仍挂起，选择候选或"以上都不是"即可继续）
  useEffect(() => {
    if (isConfirmed) return
    const t = setTimeout(() => setTimedOut(true), 3 * 60 * 1000)
    return () => clearTimeout(t)
  }, [isConfirmed])

  const handleSelect = (opt: CompanyNameCandidate) => {
    if (!isConfirmed) {
      // 第 1 次：发送候选确认（静默）。Phase 6：优先发送结构化交互协议
      // （select_candidate + frameId/interactionId，后端校验帧归属——Case 14：
      // 挂起帧旧卡点击被拒 INTERACTION_SUSPENDED）；旧卡无 frameId 时回退文本格式，
      // 后端解析"公司：名称\n统一信用代码：代码"识别企业身份（跳过二次选项卡）
      localConfirmedRef.current = true
      const confirmText = `公司：${opt.company_name}\n统一信用代码：${opt.credit_code}`
      onSendMessage?.(
        frameId && interactionId
          ? JSON.stringify({ action: 'select_candidate', frameId, interactionId, input: confirmText })
          : confirmText,
        true
      )
      return
    }
    // 已确认过候选：相当于点击公司名直接查询对应功能（静默，与 CompanyQueryCard 交互一致）
    onSendMessage?.(`帮我查一下${opt.company_name}${queryLabel || '企业信息'}`, true)
  }

  // 未匹配到任何企业（options 为空）：渲染"未找到企业"空态面板，无"以上都不是"选项
  if (!options || options.length === 0) {
    return <CompanyCandidatePanel variant="not_found" title={title} />
  }

  return (
    <CompanyCandidatePanel
      variant="ambiguous"
      title={title}
      keyword={keyword}
      options={options}
      confirmed={isConfirmed}
      notice={
        timedOut && !isConfirmed ? (
          <div className="px-3 py-2.5 rounded-lg bg-amber-50 border border-amber-200 text-xs text-amber-700 leading-relaxed">
            候选选择已超时：原查询仍挂起等待确认。请选择上方候选企业继续查询，或点击「以上都不是」重新描述。
          </div>
        ) : undefined
      }
      onSelect={handleSelect}
      onNoneOfAbove={() => onSendMessage?.('以上都不是')}
    />
  )
}

export default CompanyNameSelector
