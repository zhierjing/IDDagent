// ============================================================
// useChat - 聊天核心逻辑 Hook
// ============================================================

import React, { useState, useCallback, useRef } from 'react';
import type { ChatMessage, SSEEvent, ChatAttachment, PipelineExtra, PlanningData, TaskStartData, TaskDoneData, PipelineTask, InterruptAskData } from '../types';
import { isStreamingMessage } from '../types';
import { sendMessageStream, stopChatStream } from '../api/agent';

interface UseChatReturn {
  messages: ChatMessage[];
  isSending: boolean;
  /** silent=true：静默发送（卡片候选选择等），不插入用户气泡，直接进入助手响应 */
  sendMessage: (content: string, overrideConvId?: string, attachments?: ChatAttachment[], forceSend?: boolean, silent?: boolean) => Promise<void>;
  stopStreaming: () => void;
  clearMessages: () => void;
  setMessages: React.Dispatch<React.SetStateAction<ChatMessage[]>>;
}

export function useChat(
  conversationId: string | null,
  onConversationIdChange?: (id: string) => void,
  onMessageComplete?: () => void
): UseChatReturn {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isSending, setIsSending] = useState(false);
  const isSendingRef = useRef(false);
  // 当前进行中的 SSE 请求控制器，用于强制终止
  const abortControllerRef = useRef<AbortController | null>(null);

  // 用 ref 追踪最新值，避免闭包陈旧引用
  const conversationIdRef = useRef(conversationId);
  conversationIdRef.current = conversationId;

  const onMessageCompleteRef = useRef(onMessageComplete);
  onMessageCompleteRef.current = onMessageComplete;

  const sendMessage = useCallback(
    async (content: string, overrideConvId?: string, attachments?: ChatAttachment[], forceSend?: boolean, silent?: boolean) => {
      const effectiveConvId = overrideConvId ?? conversationIdRef.current;
      const hasAttachments = !!attachments && attachments.length > 0;
      if ((!content.trim() && !hasAttachments) || (isSendingRef.current && !forceSend)) return;

      // 候选确认消息（"公司：xxx\n统一信用代码：xxx"）：乐观标记最新候选选择卡 confirmed=true，
      // 使 CompanyNameSelector 实时点击第 1 次后立即进入"已确认"态（组件不重建时无需等后端
      // 落盘）；与后端 markCompanyCardConfirmed 持久化配合，刷新/切换会话重载后仍保持已确认，
      // 再次点击企业选项直接发起功能查询（"帮我查一下{公司}{查询功能}"）而非重复候选确认。
      // Phase 6：结构化候选协议（select_candidate JSON）同样触发乐观标记
      const looksLikeCandidateConfirm =
        content.includes('公司') && content.includes('统一信用代码')
        || content.includes('"action":"select_candidate"');
      if (looksLikeCandidateConfirm) {
        setMessages((prev) => {
          const next = [...prev];
          for (let i = next.length - 1; i >= 0; i--) {
            const m = next[i];
            const ex = (m as { extra?: Record<string, unknown> }).extra;
            if (
              m.role === 'assistant' &&
              ex &&
              ex.action === 'company_name_candidates' &&
              !(ex.confirmed === true)
            ) {
              next[i] = { ...m, extra: { ...ex, confirmed: true } };
              break;
            }
          }
          return next;
        });
      }

      console.log('🚀 useChat.sendMessage 开始, content:', content, 'conversationId:', effectiveConvId, '附件数:', attachments?.length ?? 0);

      isSendingRef.current = true;
      setIsSending(true);

      // 创建本次请求的终止控制器（点击"停止"按钮时 abort）
      const controller = new AbortController();
      abortControllerRef.current = controller;

      // 添加用户消息
      // silent=true（卡片候选选择）：不插入用户气泡，点击候选后直接进入助手响应，
      // 避免企业名称/统一信用代码等卡片指令暴露在对话流的 user 部分
      const userMsg: ChatMessage = {
        id: `user-${Date.now()}`,
        role: 'user',
        content: content.trim(),
        created_at: new Date().toISOString(),
        ...(hasAttachments ? { attachments } : {}),
      };

      if (!silent) {
        setMessages((prev) => {
          console.log('📋 添加用户消息, 之前消息数:', prev.length);
          return [...prev, userMsg];
        });
      }

      // 添加流式助手消息占位
      const assistantMsgId = `assistant-${Date.now()}`;
      const assistantMsg: ChatMessage = {
        id: assistantMsgId,
        role: 'assistant',
        content: '',
        isStreaming: true,
        created_at: new Date().toISOString(),
      };

      setMessages((prev) => {
        console.log('📋 添加助手占位消息, assistantMsgId:', assistantMsgId, '之前消息数:', prev.length);
        return [...prev, assistantMsg];
      });

      /**
       * 从消息列表最后一张任务清单卡片推断当前正在执行的任务 label。
       * 供候选选择/时间区间输入等交互类卡片关联所属任务（如"历史尽调报告查询 · 请选择企业"），
       * 让用户在多意图管道中清楚"是哪个任务在询问"；无清单卡片（单技能场景）返回 undefined 优雅降级。
       */
      const resolveCurrentTaskLabel = (prev: ChatMessage[]): string | undefined => {
        for (let i = prev.length - 1; i >= 0; i--) {
          const ex = (prev[i] as { extra?: Record<string, unknown> }).extra;
          if (ex && ex.action === 'pipeline') {
            const plan = (ex.plan as PipelineTask[] | undefined) ?? [];
            const order = (ex.currentOrder as number) ?? 0;
            return plan.find((t) => t.order === order)?.label;
          }
        }
        return undefined;
      };

      /**
       * 将结果卡片写入消息列表：
       * 1. 单技能/管道首任务：复用流式占位消息（原逻辑）
       * 2. 多意图管道后续任务：planText 已定稿、无 streaming 占位可匹配，创建唯一 id 的新消息，
       *    避免结果卡片/选择器被静默丢弃（此前用户只能看到规划文本、看不到后续任务结果）
       */
      const upsertCardMessage = (content: string, extra: Record<string, unknown>) => {
        setMessages((prev) => {
          // 交互类卡片（企业/意图候选选择、时间区间输入）自动关联当前任务标识：
          // 函数式更新读取的是最新排队状态（同一批 SSE 事件中 task_start 已先推进进度），
          // task_label 随消息 extra 持久化，切换会话重载后仍可恢复展示
          const interactive =
            extra.action === 'company_name_candidates' ||
            extra.action === 'intent_candidates' ||
            extra.action === 'need_date_range';
          const mergedExtra = interactive
            ? { ...extra, task_label: extra.task_label ?? resolveCurrentTaskLabel(prev) }
            : extra;
          const next = [...prev];
          const idx = next.findIndex((m) => isStreamingMessage(m) && m.id === assistantMsgId);
          if (idx !== -1) {
            next[idx] = {
              id: next[idx].id,
              role: 'assistant' as const,
              content,
              extra: mergedExtra,
              created_at: next[idx].created_at,
            };
            return next;
          }
          return [
            ...next,
            {
              id: `result-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
              role: 'assistant' as const,
              content,
              extra: mergedExtra,
              created_at: new Date().toISOString(),
            },
          ];
        });
      };

      await sendMessageStream(
        content.trim(),
        effectiveConvId,
        (event: SSEEvent) => {
          console.log('📨 useChat SSE 回调收到事件:', event.type, event.content?.substring(0, 40) || '(无内容)', '消息总数:', messages.length);
          switch (event.type) {
            case 'thinking':
              // 后端正在分析意图，更新占位消息为思考状态
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? { ...msg, content: '🤔 正在思考...' }
                    : msg
                )
              );
              break;

            case 'meta':
              // 如果后端返回了新的 conversation_id，更新它
              if (event.conversation_id && onConversationIdChange) {
                onConversationIdChange(event.conversation_id);
              }
              break;

            case 'text_start':
              // 文本开始 - 清空占位内容
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? { ...msg, content: '' }
                    : msg
                )
              );
              break;

            case 'text_delta':
              // 增量更新消息内容
              if (event.content) {
                setMessages((prev) => {
                  const hasStreaming = prev.some(m => isStreamingMessage(m) && m.id === assistantMsgId);
                  if (hasStreaming) {
                    // 正常情况：追加到流式消息
                    return prev.map((msg) =>
                      isStreamingMessage(msg) && msg.id === assistantMsgId
                        ? { ...msg, content: msg.content + event.content }
                        : msg
                    );
                  }
                  // 多意图管道场景：上一段 text_done 已定稿，新的 text_delta 需要创建新消息
                  // 注意：id 必须唯一（不能复用 assistantMsgId，否则与已定稿消息产生 React key 冲突）
                  if (isSendingRef.current) {
                    const newContent = event.content || '';
                    return [...prev, {
                      id: `text-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                      role: 'assistant' as const,
                      content: newContent,
                      isStreaming: true,
                      created_at: new Date().toISOString(),
                    }];
                  }
                  return prev;
                });
              }
              break;

            case 'text_done':
              // 文本完成 - 将流式消息转为普通消息
              setMessages((prev) => {
                const doneContent = event.content || '';
                const next = [...prev];
                // 优先定稿当前请求的流式占位消息
                const idx = next.findIndex((msg) => isStreamingMessage(msg) && msg.id === assistantMsgId);
                if (idx !== -1) {
                  next[idx] = {
                    id: next[idx].id,
                    role: 'assistant' as const,
                    content: doneContent || next[idx].content,
                    created_at: next[idx].created_at,
                  };
                  return next;
                }
                // 多意图管道：后续任务的文本消息 id 唯一、与 assistantMsgId 不匹配，
                // 定稿最近一条 streaming 消息
                for (let i = next.length - 1; i >= 0; i--) {
                  if (isStreamingMessage(next[i])) {
                    next[i] = {
                      id: next[i].id,
                      role: 'assistant' as const,
                      content: doneContent || next[i].content,
                      created_at: next[i].created_at,
                    };
                    break;
                  }
                }
                return next;
              });
              break;

            case 'planning':
              // 多意图任务清单：作为一条可见的 assistant 卡片消息插入对话流
              // （resume=true 表示暂停恢复，更新已有卡片；否则新建卡片）
              {
                const data = event.data as unknown as PlanningData | undefined;
                if (data && Array.isArray(data.plan)) {
                  const newExtra: PipelineExtra = {
                    action: 'pipeline',
                    // 初始规划卡：完整任务列表，仅在首次规划时出现（后续进度由切换卡/完成卡承载）
                    kind: 'plan',
                    plan: data.plan,
                    total: data.plan.length,
                    currentOrder: 0,
                    paused: false,
                    text: data.text,
                    // Phase 1：任务标识（同一意图的管道卡共享同一 frameId）
                    frameId: data.frameId,
                  };
                  setMessages((prev) => {
                    const next = [...prev];
                    if (data.resume) {
                      // 恢复路径（含意图穿插恢复）：优先按 frameId 精确匹配历史任务卡（Phase 1）——
                      // 同一意图的管道卡共享同一 frameId，可精确锁定被恢复的旧管道卡；
                      // 升级前旧对话持久化的卡无 frameId，回退 plan 内容匹配
                      let targetIdx = -1;
                      if (data.frameId) {
                        for (let i = next.length - 1; i >= 0; i--) {
                          const ex = (next[i] as { extra?: Record<string, unknown> }).extra;
                          if (ex && ex.action === 'pipeline' && ex.frameId === data.frameId) {
                            targetIdx = i;
                            break;
                          }
                        }
                      }
                      if (targetIdx === -1) {
                        // 回退：按 plan 内容匹配历史卡——穿插恢复时对话流最后一张 pipeline 卡是
                        // 新意图的管道卡（已完成），若按“最后一张”更新会错误修改新管道卡；恢复的
                        // plan 与旧管道卡的 skill 序列一致，可精确匹配到旧卡（普通暂停恢复时恢复
                        // plan 与最后一张卡相同，两种策略结果一致，不影响现有行为）
                        const planKey = data.plan.map((p) => p.skill).join(',');
                        for (let i = next.length - 1; i >= 0; i--) {
                          const ex = (next[i] as { extra?: Record<string, unknown> }).extra;
                          if (ex && ex.action === 'pipeline') {
                            const prevPlan = (ex as { plan?: PipelineTask[] }).plan ?? [];
                            if (prevPlan.map((p) => p.skill).join(',') === planKey) {
                              targetIdx = i;
                              break;
                            }
                          }
                        }
                      }
                      // 回退：找不到匹配卡（如后端恢复路径 plan 缩水）→ 更新最后一张 pipeline 卡
                      if (targetIdx === -1) {
                        for (let i = next.length - 1; i >= 0; i--) {
                          const ex = (next[i] as { extra?: Record<string, unknown> }).extra;
                          if (ex && ex.action === 'pipeline') {
                            targetIdx = i;
                            break;
                          }
                        }
                      }
                      if (targetIdx !== -1) {
                        const target = next[targetIdx] as {
                          extra?: { plan?: PipelineTask[]; kind?: PipelineExtra['kind']; currentOrder?: number };
                        };
                        const prevPlan = target.extra?.plan ?? [];
                        next[targetIdx] = {
                          ...next[targetIdx],
                          extra: {
                            ...newExtra,
                            // plan 取较长者：防御后端恢复路径 plan 缩水（如内存快照丢失后
                            // 从剩余任务重建），避免任务总数变小、已完成任务行丢失
                            plan: prevPlan.length >= data.plan.length ? prevPlan : data.plan,
                            total: Math.max(data.plan.length, prevPlan.length),
                            kind: target.extra?.kind ?? 'plan',
                            currentOrder: target.extra?.currentOrder ?? 0,
                            // 恢复执行：解除暂停/穿插挂起标记（头部状态文案回到执行中）
                            waitingConfirm: false,
                            suspended: false,
                          },
                        };
                        return next;
                      }
                    }
                    // 首次规划：新建卡片消息
                    return [
                      ...next,
                      {
                        id: `pipeline-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                        role: 'assistant' as const,
                        content: '',
                        extra: newExtra,
                        created_at: new Date().toISOString(),
                      },
                    ];
                  });
                }
                // 清空占位消息的"🤔 正在思考..."文案，避免与规划文本（text_delta）拼接
                setMessages((prev) =>
                  prev.map((msg) =>
                    isStreamingMessage(msg) && msg.id === assistantMsgId && msg.content === '🤔 正在思考...'
                      ? { ...msg, content: '' }
                      : msg
                  )
                );
              }
              break;

            case 'task_start':
              // 某任务开始执行：按对话流时间顺序推进任务卡片形态
              // - order === 1（首个任务）：更新初始规划卡（plan）的进度
              // - order > 1（进入新的一级任务）：新建轻量任务切换卡（switch），
              //   展示"已完成 x/total → 正在执行 order/total"，用户无需上翻看旧卡
              // - 与最后一张卡进度相同（暂停恢复/重试）：原地更新，不重复建卡
              {
                const data = event.data as unknown as TaskStartData | undefined;
                if (data) {
                  setMessages((prev) => {
                    const next = [...prev];
                    let lastIdx = -1;
                    for (let i = next.length - 1; i >= 0; i--) {
                      const ex = (next[i] as { extra?: Record<string, unknown> }).extra;
                      if (ex && ex.action === 'pipeline') {
                        lastIdx = i;
                        break;
                      }
                    }
                    const lastEx = lastIdx !== -1 ? (next[lastIdx].extra as unknown as PipelineExtra) : undefined;
                    if (lastEx) {
                      const order = data.order ?? data.index;
                      // total 不缩小：后端 resume 时 task_start.total 可能只含剩余任务数，
                      // 以完整清单长度（plan）与已有 total 兜底取最大值，避免任务切换卡/
                      // 完成卡按错误总数渲染（正在执行的任务被隐藏、"N 项任务已完成"数量错误）
                      const safeTotal = Math.max(data.total ?? 0, lastEx.total ?? 0, lastEx.plan.length);
                      // 暂停恢复/重试：进度与最后一张卡一致，原地刷新（不产生重复卡）
                      if (lastEx.currentOrder === order) {
                        // 暂停恢复/重试：解除等待确认标记，回到执行中
                        next[lastIdx] = {
                          ...next[lastIdx],
                          extra: {
                            ...lastEx,
                            total: safeTotal,
                            currentOrder: order,
                            paused: false,
                            completed: false,
                            waitingConfirm: false,
                          },
                        };
                        return next;
                      }
                      // 进入新的一级任务：新建轻量任务切换卡（复制完整清单用于渲染，展示时隐藏待办）
                      if (order > 1) {
                        // 同步更新初始执行计划卡（第一张 plan 卡）的 currentOrder，
                        // 让首卡始终反映最新执行位置（与后端 updatePipelinePlanCardOrder 一致），
                        // 新任务进度详情由下方新建的切换卡承载
                        const firstPlanIdx = next.findIndex((m) => {
                          const ex = (m as { extra?: Record<string, unknown> }).extra;
                          return ex && ex.action === 'pipeline' && ex.kind !== 'switch' && ex.kind !== 'complete';
                        });
                        if (firstPlanIdx !== -1) {
                          const firstPlanEx = next[firstPlanIdx].extra as unknown as PipelineExtra;
                          next[firstPlanIdx] = {
                            ...next[firstPlanIdx],
                            extra: { ...firstPlanEx, currentOrder: order },
                          };
                        }
                        return [
                          ...next,
                          {
                            id: `pipeline-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                            role: 'assistant' as const,
                            content: '',
                            extra: {
                              action: 'pipeline',
                              kind: 'switch',
                              plan: lastEx.plan,
                              total: safeTotal,
                              currentOrder: order,
                              paused: false,
                            } as PipelineExtra,
                            created_at: new Date().toISOString(),
                          },
                        ];
                      }
                      // 首个任务：更新初始规划卡（同时解除暂停/穿插挂起标记）
                      next[lastIdx] = {
                        ...next[lastIdx],
                        extra: {
                          ...lastEx,
                          // 兜底：若未收到 planning（异常路径），用当前任务构造最小清单
                          plan: lastEx.plan.length > 0
                            ? lastEx.plan
                            : [{ skill: data.skill, label: data.label, order: data.order }],
                          total: data.total,
                          currentOrder: order,
                          paused: false,
                          completed: false,
                          waitingConfirm: false,
                          suspended: false,
                        },
                      };
                      return next;
                    }
                    // 异常路径：无清单卡片但收到 task_start（如历史会话恢复），新建最小卡片
                    return [
                      ...next,
                      {
                        id: `pipeline-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                        role: 'assistant' as const,
                        content: '',
                        extra: {
                          action: 'pipeline',
                          kind: 'plan',
                          plan: [{ skill: data.skill, label: data.label, order: data.order }],
                          total: data.total,
                          currentOrder: data.order ?? data.index,
                          paused: false,
                        } as PipelineExtra,
                        created_at: new Date().toISOString(),
                      },
                    ];
                  });
                }
              }
              break;

            case 'pipeline_paused':
              // 管道暂停（当前任务等待用户补充信息/报告生成）：将卡片标记为暂停状态，
              // 同时把后端透传的 hint（如"请上传营业执照图片"、"报告仍在生成中…"）
              // 渲染为可见的助手文本气泡——此前 hint 被忽略，穿插恢复等无文本气泡的
              // 暂停场景用户看不到任何状态说明；去重：info_needed 等场景后端已同时
              // 发送相同内容的文本气泡，避免重复展示
              {
                const pausedData = event.data as { hint?: string } | undefined;
                const hint = pausedData?.hint?.trim();
                setMessages((prev) => {
                  const next = [...prev];
                  for (let i = next.length - 1; i >= 0; i--) {
                    const ex = (next[i] as { extra?: Record<string, unknown> }).extra;
                    if (ex && ex.action === 'pipeline') {
                      // waitingConfirm：管道暂停等待（模板选择/候选确认/补充信息等），
                      // 头部文案据此显示"等待确认下一步"；恢复执行（task_start/planning
                      // resume）时清除
                      next[i] = {
                        ...next[i],
                        extra: { ...ex, paused: true, waitingConfirm: true },
                      };
                      break;
                    }
                  }
                  if (hint) {
                    let lastText = '';
                    for (let i = next.length - 1; i >= 0; i--) {
                      const m = next[i];
                      if (m.role === 'assistant' && m.content && m.content.trim()) {
                        lastText = m.content.trim();
                        break;
                      }
                    }
                    if (lastText !== hint) {
                      next.push({
                        id: `hint-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                        role: 'assistant' as const,
                        content: hint,
                        created_at: new Date().toISOString(),
                      });
                    }
                  }
                  return next;
                });
              }
              break;

            case 'task_done': {
              // 管道中某个任务执行完成：
              // - 最后一个任务完成（order >= total）→ 仅将最后一张任务清单卡标记为
              //   完成态（completed=true，保留原 kind=plan/switch），不再原地转为
              //   kind='complete'，避免绿色完成卡顶到该任务结果卡之前（"中间"）且
              //   任务进度卡（switch）被转绿而"消失"；最终绿色完成卡由随后的 done
              //   事件在流末尾追加，保证位置正确
              // - 中间任务完成 → 仅解除暂停（该任务可能因等待补充信息/企业选择而暂停），
              //   剩余任务由随后的 task_start 创建 switch 卡继续推进、done 事件收尾。
              // 注意：不得推进 currentOrder——否则后续 task_start 的
              // "currentOrder === order 原地更新"判定失效，switch 卡不再创建
              const taskData = event.data as unknown as TaskDoneData | undefined;
              const doneOrder = taskData?.order ?? 0;
              setMessages((prev) => {
                const next = [...prev];
                for (let i = next.length - 1; i >= 0; i--) {
                  const ex = (next[i] as { extra?: Record<string, unknown> }).extra;
                  if (ex && ex.action === 'pipeline') {
                    const pipelineEx = ex as unknown as PipelineExtra;
                    const safeTotal = Math.max(pipelineEx.total ?? 0, pipelineEx.plan.length);
                    if (doneOrder > 0 && doneOrder >= safeTotal) {
                      // 最后任务完成：最后一张卡标记完成态（completed=true，保留原 kind），
                      // 其余管道卡（执行计划卡等）同步标记 completed=true 完成态，
                      // 与 report-completed 路径（advancePipelineAfterReport）展示一致；
                      // 绿色完成卡由 done 事件在流末尾追加（与轮询路径一致）
                      next[i] = {
                        ...next[i],
                        extra: {
                          ...pipelineEx,
                          paused: false,
                          completed: true,
                          currentOrder: safeTotal,
                        } as PipelineExtra,
                      };
                      for (let j = 0; j < next.length; j++) {
                        if (j === i) continue;
                        const ex2 = (next[j] as { extra?: Record<string, unknown> }).extra;
                        if (ex2 && ex2.action === 'pipeline' && (ex2 as unknown as PipelineExtra).kind !== 'complete') {
                          const p2 = ex2 as unknown as PipelineExtra;
                          next[j] = {
                            ...next[j],
                            extra: {
                              ...p2,
                              currentOrder: safeTotal,
                              paused: false,
                              completed: true,
                            } as PipelineExtra,
                          };
                        }
                      }
                    } else {
                      next[i] = { ...next[i], extra: { ...pipelineEx, paused: false } };
                    }
                    break;
                  }
                }
                return next;
              });
              break;
            }

            case 'interrupt_ask':
              // 意图穿插断点询问：作为独立可见 assistant 卡片消息插入对话流。
              // 后端负责持久化（assistant 消息 content 为含 action='interrupt_ask' 的 JSON），
              // 刷新/切换会话后由历史恢复逻辑重新渲染，卡片仍可点击
              {
                const data = event.data as unknown as InterruptAskData | undefined;
                if (data && Array.isArray(data.plan_summary)) {
                  setMessages((prev) => {
                    const next = [...prev];
                    // 被穿插挂起的旧管道卡标记 suspended（头部文案"已挂起（可穿插其他任务）"）：
                    // 优先按 frameId 精确匹配被挂起卡（Phase 1）；升级前旧卡无 frameId 时回退
                    // plan_summary 后缀匹配（旧管道卡完整清单 plan 的后缀应与剩余任务序列一致，
                    // 避免误标新意图自己的管道卡）
                    const restSkills = data.plan_summary.map((p) => p.skill);
                    for (let i = next.length - 1; i >= 0; i--) {
                      const ex = (next[i] as { extra?: Record<string, unknown> }).extra;
                      if (ex && ex.action === 'pipeline') {
                        const matched = data.frameId
                          ? ex.frameId === data.frameId
                          : (() => {
                              const planSkills = ((ex as { plan?: PipelineTask[] }).plan ?? []).map((p) => p.skill);
                              return (
                                restSkills.length > 0 &&
                                planSkills.length >= restSkills.length &&
                                planSkills.slice(planSkills.length - restSkills.length).join(',') ===
                                  restSkills.join(',')
                              );
                            })();
                        if (matched) {
                          next[i] = { ...next[i], extra: { ...ex, suspended: true } };
                        }
                      }
                    }
                    return [
                      ...next,
                      {
                        id: `interrupt-ask-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                        role: 'assistant' as const,
                        content: '',
                        extra: {
                          action: 'interrupt_ask',
                          message: data.message,
                          plan_summary: data.plan_summary,
                          total: data.total,
                          // Phase 1：被询问挂起层任务标识（历史恢复后仍可凭 frameId 定位穿插边界）
                          frameId: data.frameId,
                          // Phase 6：本次询问交互标识（按钮协议回传，后端日志定位）
                          interactionId: data.interactionId,
                        },
                        created_at: new Date().toISOString(),
                      },
                    ];
                  });
                }
              }
              break;

            case 'risk_check_result':
              // 风险预查结果
              upsertCardMessage('风险预查', event.data as unknown as Record<string, unknown>);
              break;
            
            case 'report_generate_result': {
              // 报告生成结果。穿插恢复路径补发（supersede_redirect 标记）与轮询注入共用：
              // - stage=progress：进度卡 id 固定为 report-progress-${reportId}（与轮询
              //   injectProgressMessage 一致），移除穿插前旧跳转卡 + 同 report_id 旧进度卡
              //   后，优先复用当前流式占位（"继续"消息之后），避免"正在思考"占位残留
              // - stage=redirect 且 supersede_redirect：重发的跳转卡同样取代穿插前旧卡
              const reportData = (event.data ?? {}) as unknown as Record<string, unknown>;
              const isSupersede = reportData.supersede_redirect === true;
              const isProgress = reportData.stage === 'progress';
              if (isSupersede || isProgress) {
                const reportId = isProgress ? String(reportData.report_id || '') : '';
                const cardId =
                  isProgress && reportId
                    ? `report-progress-${reportId}`
                    : `result-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
                setMessages((prev) => {
                  // 1) supersede 语义：移除穿插前段落中的旧跳转卡（已被新卡取代）
                  const filtered = prev.filter((m) => {
                    const ex = (m as { extra?: Record<string, unknown> }).extra;
                    if (ex && ex._skill_name === 'generate_report' && ex.stage === 'redirect') {
                      return false;
                    }
                    return true;
                  });
                  // 2) 移除同 report_id 的旧进度卡（允许按正确位置重定位）
                  const deduped = filtered.filter((m) => m.id !== cardId);
                  // 3) 优先复用当前流式占位（穿插恢复流无 text_done，占位不消失），否则末尾追加
                  const idx = deduped.findIndex((m) => isStreamingMessage(m) && m.id === assistantMsgId);
                  const card = {
                    role: 'assistant' as const,
                    content: '',
                    extra: reportData,
                    created_at: idx !== -1 ? deduped[idx].created_at : new Date().toISOString(),
                  };
                  if (idx !== -1) {
                    const next = [...deduped];
                    next[idx] = { id: cardId, ...card };
                    return next;
                  }
                  return [...deduped, { id: cardId, ...card }];
                });
                break;
              }
              // 其余阶段（templates/upload/generating/done）保持原逻辑
              upsertCardMessage('智能尽调报告生成', reportData);
              break;
            }
            
            case 'information_check_result':
              // 信息核实结果
              upsertCardMessage('信息核实', event.data as unknown as Record<string, unknown>);
              break;
            
            case 'historical_dd_query_result':
              // 历史尽调查询结果
              upsertCardMessage('历史尽调报告', event.data as unknown as Record<string, unknown>);
              break;
            
            case 'company_query_result':
              // 企业信息查询结果（基本信息/股东/受益人/族谱/海关/冻结/授信/人行账管）
              upsertCardMessage('企业信息查询', event.data as unknown as Record<string, unknown>);
              break;
            
            case 'company_name_candidates':
              // 候选企业选择器：复用流式消息（替代"正在思考"占位）
              upsertCardMessage('', {
                ...(event.data as unknown as Record<string, unknown>),
                action: 'company_name_candidates',
              });
              break;
            
            case 'intent_candidates':
              // 意图澄清选择器：复用流式消息
              upsertCardMessage('', {
                ...(event.data as unknown as Record<string, unknown>),
                action: 'intent_candidates',
              });
              break;
            
            case 'need_date_range':
              // 时间区间输入提示：更新流式消息，展示提示文本
              upsertCardMessage('', {
                action: 'need_date_range',
                text: (event.data as unknown as Record<string, unknown>).message || event.content || '',
              });
              break;

            case 'follow_up_suggestion':
              // 追问建议：创建独立追问消息，追加在消息列表末尾
              if (event.content) {
                const followUpId = `followup-${Date.now()}`;
                setMessages((prev) => [
                  ...prev,
                  {
                    id: followUpId,
                    role: 'assistant' as const,
                    content: '',
                    extra: { action: 'follow_up', text: event.content } as unknown as Record<string, unknown>,
                    created_at: new Date().toISOString(),
                  },
                ]);
              }
              break;

            case 'error':
              // 错误处理
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? {
                        id: msg.id,
                        role: 'assistant' as const,
                        content: `抱歉，发生了错误：${event.content || '请稍后重试'}`,
                        created_at: msg.created_at,
                      }
                    : msg
                )
              );
              break;

            case 'stale_action': {
              // Phase 5（Structured Resume）：结构化恢复协议校验失败（frameId 与栈顶不匹配 /
              // 无待回答询问）——旧恢复卡点击后给出可见反馈，避免无任何响应
              const staleData = event.data as unknown as { frameId?: string; message?: string } | undefined;
              const staleText = staleData?.message || '该任务已失效，请查看最新任务提醒';
              setMessages((prev) => [
                ...prev,
                {
                  id: `stale-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                  role: 'assistant' as const,
                  content: staleText,
                  created_at: new Date().toISOString(),
                },
              ]);
              break;
            }

            case 'interaction_suspended': {
              // Phase 6（交互 ID 化）：结构化交互协议校验失败（frameId 与当前活动帧不匹配——
              // 卡片所属任务已挂起/完成，Case 14：挂起帧旧卡点击不得污染当前任务）→
              // 给出可见反馈，避免旧卡点击后无任何响应
              const suspData = event.data as unknown as { frameId?: string; message?: string } | undefined;
              const suspText = suspData?.message || '该选择已失效（对应任务已挂起或完成），请查看最新任务提醒';
              setMessages((prev) => [
                ...prev,
                {
                  id: `interaction-suspended-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                  role: 'assistant' as const,
                  content: suspText,
                  created_at: new Date().toISOString(),
                },
              ]);
              break;
            }

            case 'done':
              console.log('✅ SSE 流完成');
              // 任务清单卡片收尾（卡片本身保留在对话流中，不消失）：
              // - 所有任务执行完（currentOrder >= total）→ 新建最终完成卡（complete），
              //   在当前对话位置汇总"N 项任务已完成"，与初始规划卡形成闭环
              // - 中途暂停（等待用户补充信息）→ 标记最后一张卡 paused（保留清单让用户看到剩余任务）
              setMessages((prev) => {
                const next = [...prev];
                let lastIdx = -1;
                for (let i = next.length - 1; i >= 0; i--) {
                  const ex = (next[i] as { extra?: Record<string, unknown> }).extra;
                  if (ex && ex.action === 'pipeline') {
                    lastIdx = i;
                    break;
                  }
                }
                if (lastIdx !== -1) {
                  const lastEx = next[lastIdx].extra as unknown as PipelineExtra;
                  // task_done 已将最后一张卡转为完成卡（kind='complete'）时，done 事件
                  // 不得再新建完成卡或标记 paused（否则出现重复完成卡、完成卡被标回暂停），
                  // 直接跳过收尾
                  if (lastEx.kind === 'complete') return next;
                  // 暂停中（等待补充信息/企业选择）不得判为全部完成：currentOrder 已推进
                  //（如 switch 卡 2>=2）但任务并未结束，此时只标记 paused，不做完成收尾
                  if (!lastEx.paused && lastEx.currentOrder >= lastEx.total) {
                    // 全部完成：新建最终完成卡，汇总全部任务
                    // total 以完整清单长度兜底（防御后端 resume 场景 total 缩水），
                    // 确保"N 项任务已完成"的数量与完整计划一致
                    const completeTotal = Math.max(lastEx.total ?? 0, lastEx.plan.length);
                    return [
                      ...next,
                      {
                        id: `pipeline-complete-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                        role: 'assistant' as const,
                        content: '',
                        extra: {
                          action: 'pipeline',
                          kind: 'complete',
                          plan: lastEx.plan,
                          total: completeTotal,
                          currentOrder: completeTotal,
                          completed: true,
                        } as PipelineExtra,
                        created_at: new Date().toISOString(),
                      },
                    ];
                  }
                  // 中途暂停：标记最后一张卡 paused
                  next[lastIdx] = {
                    ...next[lastIdx],
                    extra: { ...lastEx, paused: true },
                  };
                }
                return next;
              });
              // 流结束，通知外部刷新会话列表
              onMessageCompleteRef.current?.();
              break;

            default:
              console.warn('⚠️ 未识别的 SSE 事件类型:', event.type, event);
              break;
          }
        },
        (error: Error) => {
          console.error('发送消息失败:', error);
          isSendingRef.current = false;
          setIsSending(false);
          setMessages((prev) =>
            prev.map((msg) =>
              isStreamingMessage(msg) && msg.id === assistantMsgId
                ? {
                    id: msg.id,
                    role: 'assistant' as const,
                    content: `抱歉，请求失败：${error.message}`,
                    isStreaming: false,
                    created_at: msg.created_at,
                  }
                : msg
            )
          );
        },
        () => {
          // 完成回调
          isSendingRef.current = false;
          setIsSending(false);
          if (abortControllerRef.current === controller) {
            abortControllerRef.current = null;
          }
        },
        attachments,
        controller.signal
      );
    },
    [onConversationIdChange]
  );

  /**
   * 强制终止当前流式对话
   * 1. 前端：abort 断开 SSE 连接（fetch 抛 AbortError，走完成回调而非错误回调）
   * 2. 后端：通知 /api/chat/stop 设置取消标记，截断剩余事件流（双保险）
   * 3. 将进行中的流式占位消息转为普通消息（标记已停止），避免永久处于加载状态
   */
  const stopStreaming = useCallback(() => {
    if (!isSendingRef.current) return;
    console.log('⏹️ 用户点击停止，终止当前对话');
    const controller = abortControllerRef.current;
    const convId = conversationIdRef.current;
    if (controller) {
      controller.abort();
      abortControllerRef.current = null;
    }
    if (convId) {
      stopChatStream(convId);
    }
    isSendingRef.current = false;
    setIsSending(false);
    // 将流式占位消息转为普通消息，保留已生成的内容（如有）
    setMessages((prev) =>
      prev.map((msg) =>
        isStreamingMessage(msg)
          ? {
              id: msg.id,
              role: 'assistant' as const,
              content: msg.content && msg.content !== '🤔 正在思考...'
                ? msg.content + '\n\n> ⏹️ 已停止生成'
                : '⏹️ 已停止生成',
              ...(msg.extra ? { extra: msg.extra } : {}),
              created_at: msg.created_at,
            }
          : msg
      )
    );
  }, [setMessages]);

  const clearMessages = useCallback(() => {
    setMessages([]);
  }, []);

  return {
    messages,
    isSending,
    sendMessage,
    stopStreaming,
    clearMessages,
    setMessages,
  };
}
