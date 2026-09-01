import React, { useState, useEffect, useCallback, useRef } from 'react';
import Sidebar from './components/Sidebar';
import ChatContainer from './components/ChatContainer';
import LoginPage from './components/LoginPage';
import { useChat } from './hooks/useChat';
import {
  getConversations,
  createConversation,
  getConversation,
  deleteConversation as deleteConversationApi,
  checkHealth,
  notifyReportCompleted,
} from './api/agent';
import type { ConversationListItem, ChatMessage, ChatAttachment, PipelineExtra } from './types';

interface UserData {
  id: string;
  username: string;
  bankInstitution?: string;
}

const App: React.FC = () => {
  // ---- 认证状态 ----
  const [user, setUser] = useState<UserData | null>(() => {
    const stored = localStorage.getItem('user_info');
    return stored ? JSON.parse(stored) : null;
  });
  const [token, setToken] = useState<string | null>(() =>
    localStorage.getItem('auth_token')
  );

  // ---- 应用状态 ----
  // 从 localStorage 恢复上次会话 ID：此前初始化为 null 导致刷新页面后
  // 对话级 pending 轮询（conversationId 为空直接 return）失效，
  // H5 新标签页生成的报告进度/完成卡永远无法注入（"无卡片"根因之一）
  const [conversationId, setConversationId] = useState<string | null>(() =>
    localStorage.getItem('currentConversationId')
  );
  const [conversations, setConversations] = useState<ConversationListItem[]>([]);
  const [conversationsLoading, setConversationsLoading] = useState(false);
  const [backendOnline, setBackendOnline] = useState<boolean | null>(null);

  // 已调用过 report-completed 推进的报告 ID 集合（按 reportId 去重）：
  // /active 与 /pending 两个 3s 轮询都会对 completed 报告调推进接口，不按 reportId
  // 去重会重复调用（后端幂等返回 skipped，但日志刷屏）；与后端 consumed 标记双保险
  const processedReportIdsRef = useRef<Set<string>>(new Set());

  const isAuthenticated = !!token && !!user;

  // ---- 登录回调 ----
  const handleLoginSuccess = useCallback(
    (newToken: string, newUser: UserData) => {
      setToken(newToken);
      setUser(newUser);
    },
    []
  );

  // ---- 退出登录 ----
  const handleLogout = useCallback(() => {
    localStorage.removeItem('auth_token');
    localStorage.removeItem('user_info');
    setToken(null);
    setUser(null);
    setConversationId(null);
    clearMessages();
  }, []);

  const handleConversationIdChange = useCallback((id: string) => {
    setConversationId(id);
    loadConversations();
  }, []);

  const { messages, isSending, sendMessage, clearMessages, setMessages, stopStreaming } =
    useChat(conversationId, handleConversationIdChange, () => {
      loadConversations();
    });

  // ================================================================
  // 报告进度消息注入（将进度卡片以智能体消息形式插入聊天流）
  // ================================================================

  /** 向聊天流注入一条进度卡片消息（穿插场景按对话时间线定位在穿插恢复点之后） */
  const injectProgressMessage = useCallback((reportId: string, createdAt?: string) => {
    setMessages((prev) => {
      const cardId = `report-progress-${reportId}`;
      const card = {
        id: cardId,
        role: 'assistant' as const,
        content: '',
        extra: {
          action: 'result',
          _skill_name: 'generate_report',
          stage: 'progress',
          report_id: reportId,
        },
        created_at: createdAt || new Date().toISOString(),
      };
      const oldIdx = prev.findIndex((m) => m.id === cardId);
      // 1) 穿插恢复点定位：最后一条 interrupt_ask 卡（穿插询问）即穿插段落的分界，
      //    进度卡必须出现在其之后（穿插后段落），否则会插回穿插前段落（根因）；
      //    旧卡已位于恢复点之后则保持原位——轮询每 3s 重注入不抖动，避免把 done
      //    事件在流末尾追加的绿色完成卡挤到进度卡之前
      let anchorIdx = -1;
      for (let i = prev.length - 1; i >= 0; i--) {
        const extra = (prev[i] as { extra?: Record<string, unknown> }).extra;
        if (extra && extra.action === 'interrupt_ask') {
          anchorIdx = i;
          break;
        }
      }
      if (anchorIdx !== -1) {
        if (oldIdx !== -1 && oldIdx > anchorIdx) return prev;
        // 旧卡（若存在）位于锚点之前被移除后锚点位置左移一位，插入位置相应前移
        const insertPos = oldIdx !== -1 && oldIdx < anchorIdx ? anchorIdx : anchorIdx + 1;
        const next = prev.filter((m) => m.id !== cardId);
        next.splice(insertPos, 0, card);
        return next;
      }
      // 2) 无穿插恢复点：插入到会话中最后一条未隐藏的"报告生成流程"消息
      //    （模板选择卡/跳转卡）之后，固定在其初始返回位置而非追加到末尾
      const next = prev.filter((m) => m.id !== cardId);
      for (let i = next.length - 1; i >= 0; i--) {
        const extra = (next[i] as { extra?: Record<string, unknown> }).extra;
        if (
          extra &&
          extra._skill_name === 'generate_report' &&
          extra.stage !== 'progress' &&
          extra.hidden !== true
        ) {
          next.splice(i + 1, 0, card);
          return next;
        }
      }
      // 3) 兜底：按任务创建时间排序插入（基于去重后的数组计算，位置稳定不抖动）
      if (createdAt) {
        const cardTime = new Date(createdAt).getTime();
        const insertIdx = next.findIndex((m) => {
          const t = m.created_at ? new Date(m.created_at).getTime() : 0;
          return t > cardTime;
        });
        if (insertIdx === -1) {
          next.push(card);
        } else {
          next.splice(insertIdx, 0, card);
        }
        return next;
      }
      return [...next, card];
    });
  }, [setMessages]);

  /**
   * 报告生成完成后通知后端推进管道（POST /api/chat/report-completed）。
   * 后端无法感知 H5 编辑页报告的完成时机，故仅在前端轮询/状态检测到报告
   * status === 'completed' 时调用；后端清除 waitingReportTask 并推进管道，
   * 本地同步任务卡片：
   * - allDone：最后任务完成 → 原地将最后一张管道卡转为完成卡（kind='complete'）
   * - 中间任务完成 → 仅解除最后一张管道卡 paused，用户下条消息 resume 续跑剩余任务
   * - skipped（无挂起报告任务）→ 直接返回，接口幂等
   */
  const advancePipelineAfterReport = useCallback(async (convId: string, reportId?: string) => {
    // 按 reportId 去重：同一报告只调一次推进接口（/active 与 /pending 两个轮询都会触发，
    // 避免对已完成报告重复调用推进接口导致后端日志刷屏；与后端 consumed 标记双保险）
    if (reportId && processedReportIdsRef.current.has(reportId)) return;
    try {
      // 必须携带 Authorization：/api/chat/report-completed 不在 JwtAuthFilter
      // 白名单内，裸 fetch 会 401，导致管道永久停留在 waitingReportTask 挂起
      // 状态、完成卡永不出现（本次 bug 根因）
      // Phase 7：附带 reportId（外部任务标识），后端精确归属穿插挂起帧（DeferredEvent）
      const data = await notifyReportCompleted(convId, reportId);
      // 接口有效响应（无论推进/跳过/推迟）即视为已消费，后续轮询不再重复调用
      if (reportId) processedReportIdsRef.current.add(reportId);
      // skipped：无挂起报告任务（幂等返回）；deferred：报告穿插期间已完成，推进动作
      // 推迟到用户恢复挂起层时由后端统一执行（此时本地卡片由恢复流驱动，不能在此处理）
      if (data.skipped || data.deferred || !data.ok) return;
      setMessages((prev) => {
        const next = [...prev];
        // 定位最后一张管道卡：allDone 时以其 plan/total 为准追加最终完成卡
        let lastIdx = -1;
        for (let i = next.length - 1; i >= 0; i--) {
          const ex = (next[i] as { extra?: Record<string, unknown> }).extra;
          if (ex && ex.action === 'pipeline') {
            lastIdx = i;
            break;
          }
        }
        // 穿插询问卡数据（本管道全部完成但挂起栈仍有旧管道时由后端返回）
        const askData = data.hasSuspended && data.interruptAsk ? data.interruptAsk : null;
        const askCard = askData
          ? {
              id: `interrupt-ask-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
              role: 'assistant' as const,
              content: '',
              extra: {
                action: 'interrupt_ask',
                frameId: askData.frameId,
                interactionId: askData.interactionId,
                message: askData.message,
                plan_summary: askData.plan_summary,
                total: askData.total,
              } as Record<string, unknown>,
              created_at: new Date().toISOString(),
            }
          : null;
        if (lastIdx === -1) {
          // 防御：消息流中无管道卡（如历史消息未落盘），仍应展示询问卡供用户继续旧管道
          return askCard ? [...next, askCard] : next;
        }
        const lastEx = next[lastIdx].extra as unknown as PipelineExtra;
        if (data.allDone) {
          const total = Math.max(lastEx.total ?? 0, lastEx.plan.length);
          // 1) 同步所有管道卡进度为完成态（执行计划卡 + 任务切换卡）：标记
          //    completed=true（蓝色完成态，保留 plan/switch 形态），避免中段卡片
          //    停留在"进行中"与末尾最终完成卡矛盾
          for (let i = 0; i < next.length; i++) {
            const ex = (next[i] as { extra?: Record<string, unknown> }).extra;
            if (ex && ex.action === 'pipeline' && (ex as unknown as PipelineExtra).kind !== 'complete') {
              const p = ex as unknown as PipelineExtra;
              next[i] = {
                ...next[i],
                extra: {
                  ...p,
                  currentOrder: total,
                  paused: false,
                  completed: true,
                } as PipelineExtra,
              };
            }
          }
          // 2) 对话末尾追加最终完成卡（绿色闭环，与 SSE done 事件行为一致）；
          //    幂等：最后一张已是 complete（重复轮询/会话重载后）则不重复追加
          if (lastEx.kind !== 'complete') {
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
                  total,
                  currentOrder: total,
                  paused: false,
                  completed: true,
                } as PipelineExtra,
                created_at: new Date().toISOString(),
              },
              // 3) 本管道全部完成但挂起栈仍有旧管道（穿插场景）：追加询问卡提示是否继续
              ...(askCard ? [askCard] : []),
            ];
          }
        } else {
          // 中间任务完成：仅解除最后一张卡 paused（剩余任务由下条消息 resume 续跑）
          next[lastIdx] = { ...next[lastIdx], extra: { ...lastEx, paused: false } };
        }
        return next;
      });
    } catch { /* ignore */ }
  }, [setMessages]);

  /** 拉取指定会话的待处理报告并注入进度卡片（切换会话时立即调用，无需等待轮询） */
  const injectConversationReports = useCallback(async (convId: string) => {
    try {
      const res = await fetch(`/api/generate-report/conversation/${convId}/pending`);
      if (!res.ok) return;
      const data = await res.json();
      if (data.reports && data.reports.length > 0) {
        for (const r of data.reports) {
          injectProgressMessage(r.reportId, r.createdAt as string | undefined);
          // 报告已完成且管道挂起等待推进：通知后端推进管道
          if (r.status === 'completed') advancePipelineAfterReport(convId, r.reportId);
        }
      }
    } catch { /* ignore */ }
  }, [injectProgressMessage, advancePipelineAfterReport]);

  // 定时轮询当前用户的活跃报告（捕获 H5 标签页关闭后发起的生成）
  useEffect(() => {
    if (!isAuthenticated || !user?.id) return;

    const checkActive = async () => {
      try {
        const res = await fetch(`/api/generate-report/user/${user.id}/active`);
        if (!res.ok) return;
        const data = await res.json();
        if (data.reports && data.reports.length > 0) {
          for (const r of data.reports) {
            injectProgressMessage(r.reportId, r.createdAt as string | undefined);
            // 刚完成的报告（同步生成 2ms 即完成，generating 态 3s 轮询必错过）：
            // 后端 active 兜底返回最近窗口内 completed 任务，前端按 reportId 去重注入，
            // 并携带 conversationId 推进对应会话挂起的管道（与对话级 pending 行为一致）
            const convId = (r as { conversationId?: string }).conversationId;
            if (r.status === 'completed' && convId) {
              advancePipelineAfterReport(convId, r.reportId);
            }
          }
        }
      } catch { /* ignore */ }
    };

    checkActive();
    const interval = setInterval(checkActive, 3000);
    return () => clearInterval(interval);
  }, [isAuthenticated, user?.id, injectProgressMessage, advancePipelineAfterReport]);

  // 按对话 ID 轮询待处理报告（H5 新标签页生成报告后，原聊天页自动获取进度卡片）
  useEffect(() => {
    if (!isAuthenticated || !conversationId) return;

    const checkConversationPending = async () => {
      try {
        const res = await fetch(`/api/generate-report/conversation/${conversationId}/pending`);
        if (!res.ok) return;
        const data = await res.json();
        if (data.reports && data.reports.length > 0) {
          for (const r of data.reports) {
            injectProgressMessage(r.reportId, r.createdAt as string | undefined);
            // 报告已完成且管道挂起等待推进：通知后端推进管道（含最后任务完成转 complete 卡）
            if (r.status === 'completed') advancePipelineAfterReport(conversationId, r.reportId);
          }
        }
      } catch { /* ignore */ }
    };

    checkConversationPending();
    const interval = setInterval(checkConversationPending, 3000);
    return () => clearInterval(interval);
  }, [isAuthenticated, conversationId, injectProgressMessage, advancePipelineAfterReport]);

  // 检查后端服务状态
  useEffect(() => {
    if (!isAuthenticated) return;
    const check = async () => {
      const healthy = await checkHealth();
      setBackendOnline(healthy);
      if (healthy) {
        loadConversations();
      }
    };
    check();
    const interval = setInterval(check, 10000);
    return () => clearInterval(interval);
  }, [isAuthenticated]);

  // 加载会话列表
  const loadConversations = async () => {
    try {
      setConversationsLoading(true);
      const list = await getConversations();
      setConversations(list);
    } catch (err) {
      console.error('加载会话列表失败:', err);
    } finally {
      setConversationsLoading(false);
    }
  };

  // 新建会话
  const handleNewConversation = async () => {
    try {
      const conv = await createConversation();
      setConversationId(conv.id);
      clearMessages();
      await loadConversations();
    } catch (err) {
      console.error('创建会话失败:', err);
    }
  };

  // 选择会话
  const handleSelectConversation = async (id: string) => {
    try {
      setConversationId(id);
      const conv = await getConversation(id);
      const msgs: ChatMessage[] = conv.messages
        .filter((m) => {
          // 静默发送的协议消息（【管道恢复】继续/放弃、结构化恢复协议 JSON）：
          // 按钮点击时不展示用户气泡，历史恢复时同样过滤，避免刷新/切换会话后
          // 重新冒出"【管道恢复】继续"或"{"action":"resume_frame"...}"等内部协议文本
          if (m.role === 'user' && typeof m.content === 'string') {
            if (m.content.startsWith('【管道恢复】')) {
              return false;
            }
            // Phase 5：结构化恢复协议消息（InterruptAskCard 按钮发送，JSON 文本）
            if (m.content.includes('"action":"resume_frame"') || m.content.includes('"action":"abandon_frame"')) {
              return false;
            }
            // Phase 6：结构化交互协议消息（select_candidate/select_intent/select_template，
            // 企业候选/意图澄清/模板卡片点击发送的 JSON）——静默发送，历史恢复同样过滤
            if (m.content.includes('"action":"select_candidate"')
                || m.content.includes('"action":"select_intent"')
                || m.content.includes('"action":"select_template"')) {
              return false;
            }
          }
          // 恢复路径 supersede 旧跳转卡后，后端已将被取代的穿插前跳转卡持久化标记
          // hidden=true：过滤掉隐藏消息，跨"切走再切回"保持隐藏
          if (m.role === 'assistant' && m.content && m.content.trim().startsWith('{')) {
            try {
              const parsed = JSON.parse(m.content);
              if (parsed && typeof parsed === 'object' && parsed.hidden === true) return false;
            } catch { /* 非 JSON 按普通消息处理 */ }
          }
          return true;
        })
        .map((m) => {
        const base = {
          id: m.id,
          role: m.role as 'user' | 'assistant',
          content: m.content,
          // 后端序列化为 createdAt（驼峰），兼容读取；缺失时兜底当前时间
          created_at:
            m.created_at ??
            (m as { createdAt?: string }).createdAt ??
            new Date().toISOString(),
          // 还原消息附件（用户上传的文件）
          ...(m.attachments && m.attachments.length > 0 ? { attachments: m.attachments } : {}),
        };
        // 如果是助手消息且 content 是 JSON（技能返回结果），解析为 extra 以渲染卡片
        if (m.role === 'assistant' && m.content && m.content.trim().startsWith('{')) {
          try {
            const parsed = JSON.parse(m.content);
            if (parsed && typeof parsed.action === 'string') {
              // 归一化候选选项卡 action：实时 SSE 路径前端设为 company_name_candidates，
              // 而消息持久化的是技能原始返回值 action=candidates / ambiguous（企业名多候选），
              // 若不归一化，切换对话重载后选项卡（CompanyNameSelector）将无法恢复渲染：
              // ambiguous 会命中技能路由分支被渲染成技能结果卡片（如风险识别卡）
              if (parsed.action === 'candidates' || parsed.action === 'ambiguous') {
                parsed.action = 'company_name_candidates';
              }
              // info_needed（如"请问您要查询哪家企业"）：实时 SSE 路径是 text_delta/text_done
              // 普通文本消息，而持久化的是 {"action":"info_needed","message":"..."} JSON；
              // 若解析为 extra 则无对应渲染分支（消息消失），故归一化为普通文本，与实时路径一致
              if (parsed.action === 'info_needed') {
                const msg = typeof parsed.message === 'string' ? parsed.message : '';
                return { ...base, content: msg || base.content };
              }
              return { ...base, content: '', extra: parsed };
            }
          } catch {
            // 非合法 JSON，按普通文本处理
          }
        }
        return base;
      });
      setMessages(msgs);
      // 立即注入该会话的待处理报告进度卡片（切换后无需刷新即可显示，轮询兜底）
      injectConversationReports(id);
    } catch (err) {
      console.error('加载会话失败:', err);
    }
  };

  // 挂载后从 localStorage 恢复上次会话：验证会话仍存在后复用"选择会话"路径
  // 加载消息并注入该会话的待处理报告卡（此前 conversationId 初始化 null 且无恢复逻辑，
  // 刷新页面后消息列表空白、对话级 pending 轮询失效——报告进度卡永远不出现）
  const restoredInitialConvRef = useRef(false);
  useEffect(() => {
    if (!isAuthenticated || restoredInitialConvRef.current) return;
    restoredInitialConvRef.current = true;
    const savedId = localStorage.getItem('currentConversationId');
    if (!savedId) return;
    getConversation(savedId)
      .then((conv) => {
        if (conv && conv.id) {
          handleSelectConversation(savedId);
        } else {
          localStorage.removeItem('currentConversationId');
          setConversationId(null);
        }
      })
      .catch(() => {
        // 会话不存在/已删除（404）：清除恢复值，避免对话级轮询查询无效会话
        localStorage.removeItem('currentConversationId');
        setConversationId(null);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAuthenticated]);

  // 删除会话
  const handleDeleteConversation = async (id: string) => {
    try {
      await deleteConversationApi(id);
      if (conversationId === id) {
        // 删除的是当前会话：同步清除 localStorage 恢复值，避免下次刷新恢复已删除会话
        localStorage.removeItem('currentConversationId');
        setConversationId(null);
        clearMessages();
      }
      await loadConversations();
    } catch (err) {
      console.error('删除会话失败:', err);
    }
  };

  // 用 ref 追踪最新状态，避免闭包问题
  const conversationIdRef = useRef(conversationId);
  conversationIdRef.current = conversationId;
  const backendOnlineRef = useRef(backendOnline);
  backendOnlineRef.current = backendOnline;

  // 将 conversationId 同步到 localStorage，供 H5 新标签页读取
  useEffect(() => {
    if (conversationId) {
      localStorage.setItem('currentConversationId', conversationId);
    }
  }, [conversationId]);

  // 检测 URL 参数中的 reportId 和 convId（H5 页面确认生成后跳转回来）
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const rid = params.get('reportId');
    const convId = params.get('convId');
    if (rid || convId) {
      window.history.replaceState({}, '', window.location.pathname);

      const selectAndInject = async () => {
        // 等待后端健康检查完成（确保 loadConversations 已启动）
        let retries = 0;
        while (backendOnlineRef.current === null && retries < 20) {
          await new Promise(r => setTimeout(r, 300));
          retries++;
        }
        // 额外等待对话列表加载完成
        await new Promise(r => setTimeout(r, 500));

        if (convId) {
          await handleSelectConversation(convId);
        }
        if (rid) {
          // 通过 /status 接口获取任务创建时间，确保跳转回来时卡片也插入其初始生成位置
          try {
            const res = await fetch(`/api/generate-report/${rid}/status`);
            if (res.ok) {
              const data = await res.json();
              injectProgressMessage(rid, data.createdAt as string | undefined);
              // 报告已完成：通过 status 返回的 conversationId 反查会话并推进挂起的管道
              if (data.status === 'completed' && data.conversationId) {
                advancePipelineAfterReport(data.conversationId);
              }
            } else {
              injectProgressMessage(rid);
            }
          } catch {
            injectProgressMessage(rid);
          }
        }
      };
      selectAndInject();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 发送消息
  // silent=true：卡片候选选择等交互指令静默发送（不展示为用户气泡），由卡片组件透传
  const handleSend = useCallback(async (content: string, attachments?: ChatAttachment[], silent?: boolean) => {
    let currentConvId = conversationIdRef.current;
    if (!currentConvId) {
      try {
        const conv = await createConversation();
        currentConvId = conv.id;
        setConversationId(conv.id);
        await loadConversations();
      } catch (err) {
        console.error('创建会话失败:', err);
        return;
      }
    }
    console.log('📤 App 发送消息, conversationId:', currentConvId);
    // forceSend=true：卡片交互消息（企业候选确认/功能查询等）不受 isSending 阻挡，
    // 支持多意图管道执行中穿插；输入框/快捷按钮自带 disabled 保护不会误触发
    sendMessage(content, currentConvId, attachments, true, silent);
  }, [sendMessage]);

  // ---- 未登录：显示登录页 ----
  if (!isAuthenticated) {
    return <LoginPage onLoginSuccess={handleLoginSuccess} />;
  }

  // ---- 已登录：显示主界面 ----
  return (
    <div className="flex h-screen bg-gray-100">
      <Sidebar
        conversations={conversations}
        activeId={conversationId}
        onSelect={handleSelectConversation}
        onNew={handleNewConversation}
        onDelete={handleDeleteConversation}
        loading={conversationsLoading}
      />

      <div className="flex-1 flex flex-col min-w-0">
        {/* 顶部状态栏 */}
        <div className="h-12 bg-white border-b border-gray-200 flex items-center px-6 flex-shrink-0">
          <div className="flex items-center gap-2">
            <div
              className={`w-2 h-2 rounded-full ${
                backendOnline === null
                  ? 'bg-yellow-400 animate-pulse'
                  : backendOnline
                  ? 'bg-green-500'
                  : 'bg-red-500'
              }`}
            />
            <span className="text-sm text-gray-600">
              {backendOnline === null
                ? '正在连接服务...'
                : backendOnline
                ? '服务已连接'
                : '服务未连接'}
            </span>
          </div>
          <div className="ml-auto flex items-center gap-4">
            <span className="text-sm text-gray-500">
              {user?.username}
            </span>
            <button
              onClick={handleLogout}
              className="text-xs text-gray-400 hover:text-red-500 transition-colors"
            >
              退出
            </button>
          </div>
        </div>

        <ChatContainer
          messages={messages}
          isSending={isSending}
          onSend={handleSend}
          onStop={stopStreaming}
        />
      </div>
    </div>
  );
};

export default App;
