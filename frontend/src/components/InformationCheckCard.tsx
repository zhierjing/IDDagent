import React from 'react'
import type { RiskAmbiguousOption } from '../types'
import CompanyCandidatePanel from './CompanyCandidatePanel'

// ============================================================
// Props
// ============================================================
interface InformationCheckCardProps {
  data: Record<string, unknown>
  onSendMessage?: (content: string, silent?: boolean) => void
}

// ============================================================
// 组件
// ============================================================
const InformationCheckCard: React.FC<InformationCheckCardProps> = ({ data, onSendMessage }) => {
  const action = data.action as string | undefined
  const options = data.options as RiskAmbiguousOption[] | undefined
  const keyword = data.keyword as string || ''

  // 候选点击：携带信用代码（与 CompanyNameSelector 一致）静默发送，
  // 技能可直接定位企业避免二次歧义选项卡，且不展示企业名/信用代码于 user 气泡。
  // Phase 6：优先发送结构化交互协议（select_candidate + frameId/interactionId，
  // 后端校验帧归属——挂起帧旧卡点击被拒）；旧卡无 frameId 时回退文本格式
  const handleSelect = (opt: RiskAmbiguousOption) => {
    const confirmText = `公司：${opt.company_name}\n统一信用代码：${opt.credit_code}`
    const frameId = data.frameId as string | undefined
    const interactionId = data.interactionId as string | undefined
    onSendMessage?.(
      frameId && interactionId
        ? JSON.stringify({ action: 'select_candidate', frameId, interactionId, input: confirmText })
        : confirmText,
      true
    )
  }

  // ========== 未找到：空态卡片（无"以上都不是"选项） ==========
  if (action === 'not_found') {
    return (
      <CompanyCandidatePanel
        variant="not_found"
        title="信息核实"
        options={options}
        notFoundMessage={(data.message as string) || '未找到相关信息核实数据'}
        onSelect={handleSelect}
      />
    )
  }

  // ========== 名称歧义：候选确认卡片 ==========
  if (action === 'ambiguous') {
    return (
      <CompanyCandidatePanel
        variant="ambiguous"
        title="信息核实"
        confirmLabel="请选择要核实的企业"
        keyword={keyword}
        options={options}
        onSelect={handleSelect}
        onNoneOfAbove={() => onSendMessage?.('以上都不是')}
      />
    )
  }

  // ========== 核实结果 ==========
  const companyName = data.company_name as string || ''
  const creditCode = data.credit_code as string || ''
  const passCount = data.pass_count as number || 0
  const failCount = data.fail_count as number || 0
  const noneCount = data.none_count as number || 0
  const totalCount = data.total_count as number || 0
  const h5Url = data.h5_url as string || ''

  // 根据是否有不通过项来决定卡片风格
  const hasFail = failCount > 0
  const config = hasFail
    ? {
        bg: 'from-amber-50 to-yellow-50',
        border: 'border-amber-200',
        headerBg: 'bg-white/60',
        headerBorder: 'border-amber-100',
        passColor: 'text-emerald-600',
        failColor: 'text-red-600',
      }
    : {
        bg: 'from-emerald-50 to-green-50',
        border: 'border-emerald-200',
        headerBg: 'bg-white/60',
        headerBorder: 'border-emerald-100',
        passColor: 'text-emerald-600',
        failColor: 'text-red-600',
      }

  return (
    <div className={`info-check-card bg-gradient-to-br ${config.bg} rounded-xl border ${config.border} overflow-hidden`}>
      {/* 头部 */}
      <div className={`px-4 py-3 border-b ${config.headerBorder} ${config.headerBg}`}>
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">📋</span>
          信息核实结果
        </h3>
      </div>

      {/* 内容 */}
      <div className="p-4 space-y-3">
        {/* 企业信息 */}
        <div>
          <div className="text-xs text-gray-500">核实企业</div>
          <div className="text-sm font-semibold text-gray-800">{companyName}</div>
          <div className="text-xs text-gray-400 font-mono">信用代码：{creditCode}</div>
        </div>

        {/* 提取参数概览 */}
        <div className="bg-white/70 rounded-lg p-3">
          <div className="text-xs text-gray-500 mb-2">
            营业执照参数提取完成，共 <span className="font-semibold text-gray-700">{totalCount}</span> 项信息
          </div>
          <div className="flex flex-col items-start gap-2">
            <div className="flex items-center gap-1.5">
              <span className="inline-block w-2.5 h-2.5 rounded-full bg-emerald-500"></span>
              <span className="text-sm text-gray-600">
                <span className={`font-bold ${config.passColor}`}>{passCount}</span> 项核实通过
              </span>
            </div>
            <div className="flex items-center gap-1.5">
              <span className="inline-block w-2.5 h-2.5 rounded-full bg-red-500"></span>
              <span className="text-sm text-gray-600">
                <span className={`font-bold ${config.failColor}`}>{failCount}</span> 项核实不通过
              </span>
            </div>
            {noneCount > 0 && (
              <div className="flex items-center gap-1.5">
                <span className="inline-block w-2.5 h-2.5 rounded-full bg-gray-400"></span>
                <span className="text-sm text-gray-600">
                  <span className="font-bold text-gray-500">{noneCount}</span> 项无需核实
                </span>
              </div>
            )}
          </div>
        </div>

        {/* H5 链接按钮 */}
        <a
          href={h5Url}
          target="_blank"
          rel="noopener noreferrer"
          className="block w-full text-center px-4 py-2.5 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-lg transition-colors"
        >
          📄 查看核实结果
        </a>
      </div>
    </div>
  )
}

export default InformationCheckCard
