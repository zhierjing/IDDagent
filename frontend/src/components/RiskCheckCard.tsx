import React from 'react'
import type { RiskAmbiguousOption } from '../types'
import CompanyCandidatePanel from './CompanyCandidatePanel'

// ============================================================
// Props
// ============================================================
interface RiskCheckCardProps {
  data: Record<string, unknown>
  onSendMessage?: (content: string, silent?: boolean) => void
}

// ============================================================
// 组件
// ============================================================
const RiskCheckCard: React.FC<RiskCheckCardProps> = ({ data, onSendMessage }) => {
  const action = data.action as string | undefined
  const options = data.options as RiskAmbiguousOption[] | undefined
  const keyword = data.keyword as string || ''

  // 候选点击：直接查询该企业风险（静默发送，不展示企业名/信用代码于 user 气泡）。
  // Phase 6：优先发送结构化交互协议（select_candidate + frameId/interactionId，
  // 后端校验帧归属——挂起帧旧卡点击被拒）；旧卡无 frameId 时回退文本查询句
  const handleSelect = (opt: RiskAmbiguousOption) => {
    const queryText = `查询统一信用代码为${opt.credit_code}的客户的风险`
    const frameId = data.frameId as string | undefined
    const interactionId = data.interactionId as string | undefined
    onSendMessage?.(
      frameId && interactionId
        ? JSON.stringify({ action: 'select_candidate', frameId, interactionId, input: queryText })
        : queryText,
      true
    )
  }

  // ========== 未找到：空态卡片（无"以上都不是"选项） ==========
  if (action === 'not_found') {
    return (
      <CompanyCandidatePanel
        variant="not_found"
        title="风险预查"
        options={options}
        notFoundMessage={(data.message as string) || '未找到相关企业信息'}
        onSelect={handleSelect}
      />
    )
  }

  // ========== 名称歧义：候选确认卡片 ==========
  if (action === 'ambiguous') {
    return (
      <CompanyCandidatePanel
        variant="ambiguous"
        title="风险预查"
        keyword={keyword}
        options={options}
        onSelect={handleSelect}
        onNoneOfAbove={() => onSendMessage?.('以上都不是')}
      />
    )
  }

  // ========== 风险结果 ==========
  const companyName = data.company_name as string || ''
  const creditCode = data.credit_code as string || ''
  const riskSummary = data.risk_summary as string || ''
  const h5Url = data.h5_url as string || ''

  return (
    <div className="risk-check-card bg-gradient-to-br from-blue-50 to-sky-50 rounded-xl border border-blue-200 overflow-hidden">
      {/* 头部 */}
      <div className="px-4 py-3 border-b border-white/40 bg-white/30">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">🛡️</span>
          风险预查结果
        </h3>
      </div>

      {/* 内容 */}
      <div className="p-4 space-y-3">
        {/* 企业信息 */}
        <div>
          <div className="text-xs text-gray-500">查询企业</div>
          <div className="text-sm font-semibold text-gray-800">{companyName}</div>
          <div className="text-xs text-gray-400 font-mono">信用代码：{creditCode}</div>
        </div>

        {/* 风险结论（大模型摘要） */}
        <div className="bg-white/70 rounded-lg p-3 border-l-2 border-blue-400">
          <p className="text-sm text-gray-700 leading-relaxed">
            💡 {riskSummary}
          </p>
        </div>

        {/* H5 链接按钮 */}
        <a
          href={h5Url}
          target="_blank"
          rel="noopener noreferrer"
          className="block w-full text-center px-4 py-2.5 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-lg transition-colors"
        >
          📄 查看完整风险报告（H5）
        </a>
      </div>
    </div>
  )
}

export default RiskCheckCard
