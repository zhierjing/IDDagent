import React, { useState } from 'react'
import type { CompanyNameCandidate } from '../types'

// ============================================================
// Props
// ============================================================
interface CompanyCandidatePanelProps {
  /** 功能名称（如"风险预查""基本信息"），显示在卡片头部 */
  title: string
  /** ambiguous=候选确认（琥珀色）；not_found=未找到企业（灰色空态） */
  variant: 'ambiguous' | 'not_found'
  options?: CompanyNameCandidate[]
  /** 搜索关键词，头部显示"搜索到 N 家名称包含「XX」的企业" */
  keyword?: string
  /** 候选区动作文案（ambiguous 头部后缀），默认"请选择要查询的企业" */
  confirmLabel?: string
  /** 已确认过候选（CompanyNameSelector 用）：显示已确认提示与候选"查询"标签 */
  confirmed?: boolean
  /** 候选区顶部额外提示（如超时提醒） */
  notice?: React.ReactNode
  /** 未找到企业提示文案 */
  notFoundMessage?: string
  /** 是否显示"以上都不是"（not_found 形态强制不显示） */
  showNoneOfAbove?: boolean
  onSelect?: (opt: CompanyNameCandidate) => void
  onNoneOfAbove?: () => void
}

/**
 * 企业候选选择 / 未找到企业统一面板（统一模糊匹配卡片样式规范）：
 * - ambiguous：候选确认卡（琥珀色），头部"🔍 {功能名} · 请选择要查询的企业"，
 *   展示候选列表 + 可选"以上都不是"
 * - not_found：未找到企业空态卡（灰色），头部"ℹ️ {功能名} · 未找到企业"，
 *   展示提示文案 + 可选的部分匹配候选；不显示"以上都不是"
 */
const CompanyCandidatePanel: React.FC<CompanyCandidatePanelProps> = ({
  title,
  variant,
  options,
  keyword,
  confirmLabel = '请选择要查询的企业',
  confirmed = false,
  notice,
  notFoundMessage = '未找到企业，请补充完整企业名称或统一社会信用代码后重新描述',
  showNoneOfAbove = true,
  onSelect,
  onNoneOfAbove,
}) => {
  // 本地点击标记：未确认前点击过任一候选后弱化其余候选（提示已作出选择）
  const [clicked, setClicked] = useState(false)
  const isNotFound = variant === 'not_found'
  const candidates = options && options.length > 0 ? options : undefined

  const handleSelect = (opt: CompanyNameCandidate) => {
    setClicked(true)
    onSelect?.(opt)
  }

  return (
    <div
      className={`rounded-xl border overflow-hidden bg-gradient-to-br ${
        isNotFound
          ? 'from-gray-50 to-slate-50 border-gray-200'
          : 'from-amber-50 to-yellow-50 border-amber-200'
      }`}
    >
      {/* 头部：功能名称 */}
      <div className={`px-4 py-3 border-b bg-white/60 ${isNotFound ? 'border-gray-100' : 'border-amber-100'}`}>
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">{isNotFound ? 'ℹ️' : '🔍'}</span>
          {title}
          {isNotFound ? ' · 未找到企业' : ` · ${confirmLabel}`}
        </h3>
        {!isNotFound && keyword && (
          <p className="text-xs text-gray-500 mt-1">
            搜索到 {candidates?.length || 0} 家名称包含「{keyword}」的企业
          </p>
        )}
        {!isNotFound && confirmed && (
          <p className="text-xs text-blue-600 mt-1">已确认企业，再次点击可直接发起查询</p>
        )}
      </div>

      <div className="p-3 space-y-2">
        {/* 未找到：空态提示 */}
        {isNotFound && (
          <div className="flex flex-col items-center gap-2 py-4 px-4">
            <span className="text-2xl">🤷</span>
            <p className="text-sm text-gray-500 text-center leading-relaxed">{notFoundMessage}</p>
          </div>
        )}

        {/* 候选列表（未找到时若仍有部分匹配候选也展示，点击直接查询） */}
        {candidates && (
          <>
            {notice}
            {candidates.map((opt) => (
              <button
                key={opt.credit_code}
                onClick={() => handleSelect(opt)}
                className={`w-full text-left px-4 py-3 rounded-lg border bg-white transition-all
                           flex items-center justify-between group cursor-pointer ${
                             isNotFound
                               ? 'border-gray-200 hover:bg-gray-50 hover:border-gray-300'
                               : `border-amber-200 hover:bg-amber-50 hover:border-amber-300 ${
                                   !confirmed && clicked ? 'opacity-40' : ''
                                 }`
                           }`}
              >
                <div>
                  <div
                    className={`text-sm font-medium text-gray-800 ${
                      isNotFound ? 'group-hover:text-gray-900' : 'group-hover:text-amber-700'
                    }`}
                  >
                    {opt.company_name}
                  </div>
                  <div className="text-xs text-gray-400 font-mono mt-0.5">{opt.credit_code}</div>
                </div>
                <div className="flex items-center gap-1.5">
                  {confirmed && !isNotFound && (
                    <span className="text-[10px] text-blue-500 bg-blue-100/80 rounded-full px-2 py-0.5">
                      查询
                    </span>
                  )}
                  <svg
                    className={`w-4 h-4 ${
                      isNotFound
                        ? 'text-gray-400 group-hover:text-gray-600'
                        : 'text-amber-400 group-hover:text-amber-600'
                    }`}
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                  </svg>
                </div>
              </button>
            ))}
            {/* 以上都不是：仅候选确认形态显示（未找到企业时不提供该选项） */}
            {!isNotFound && showNoneOfAbove && (
              <button
                onClick={onNoneOfAbove}
                className="w-full px-4 py-3 rounded-lg border border-dashed border-gray-300 bg-white/60
                           hover:bg-gray-100 hover:border-gray-400 transition-all
                           flex items-center justify-center gap-1.5 group cursor-pointer"
              >
                <span className="text-sm text-gray-500 group-hover:text-gray-700">以上都不是</span>
              </button>
            )}
          </>
        )}
      </div>
    </div>
  )
}

export default CompanyCandidatePanel
