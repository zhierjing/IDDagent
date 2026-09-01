package com.IDDagent.service;

import com.IDDagent.skill.IntentMatcher;
import com.IDDagent.skill.IntentTreeMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Phase 4（IntentClassifier）：管道暂停期间用户消息归类（意图穿插判定）——补充信息 vs 新意图。
 *
 * <p>六层判定（实施文档第 13 节，顺序不允许随意调整）：
 * ① Structured Protocol（机器可确定的协议交互，命中后禁止继续进入普通穿插分类）
 * ② Explicit Interrupt（显式穿插信号 + 独立任务请求组合判断）
 * ③ Expected Input（当前等待参数能否被满足——必须先于 IntentMatcher / Context Shift，
 *    否则等待企业名时输入"阿里巴巴"会被新企业检测误判为新意图）
 * ④ IntentMatcher（明确命中另一个独立技能）
 * ⑤ Context Shift（Skill 相同但业务对象变化 / 企业切换 + 查询行为词）
 * ⑥ LLM Fallback（CoordinatorService.classifyIntentInterrupt 二分类兜底）
 *
 * <p>每次判定输出结构化日志 INPUT_CLASSIFIED（frameId/result/source），便于排查状态污染。
 */
@Component
public class IntentInterruptClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentInterruptClassifier.class);

    /** 分类结果（与 ChatController.InputClass 一一对应） */
    public enum Classification { NEW_INTENT, SUPPLEMENT }

    /** 判定来源（INPUT_CLASSIFIED 日志 source 字段） */
    private enum Source { PROTOCOL, EXPLICIT_INTERRUPT, EXPECTED_INPUT, INTENT_MATCHER, CONTEXT_SHIFT, LLM }

    // 模板选择消息协议前缀：前端模板卡片点击后发送「【模板选择】<template_id>」文本消息
    private static final String TEMPLATE_SELECT_PREFIX = "【模板选择】";
    // 意图选择消息协议前缀：前端意图候选卡片点击后发送「【意图选择】<skill>」文本消息
    private static final String INTENT_SELECT_PREFIX = "【意图选择】";

    /** 显式穿插信号词（第二层）：命中后还需组合"独立任务请求"才判 NEW_INTENT，
     *  不允许简单地只要命中单个词就判（如"帮我选择第二个"不得判穿插） */
    private static final String[] INTERRUPT_SIGNALS = {
            "顺便", "另外", "再帮我", "同时", "换个问题", "先查一下",
            "先别管这个", "这个先放一下", "对了", "还有一个事"
    };

    private final IntentTreeMatcher intentTreeMatcher;
    private final CoordinatorService coordinatorService;

    public IntentInterruptClassifier(IntentTreeMatcher intentTreeMatcher,
                                     CoordinatorService coordinatorService) {
        this.intentTreeMatcher = intentTreeMatcher;
        this.coordinatorService = coordinatorService;
    }

    /**
     * 六层判定入口：管道暂停期间用户消息归类（补充信息 vs 新意图）。
     *
     * @param conversationId 会话标识（INPUT_CLASSIFIED 日志用）
     * @param ctx            会话上下文（pendingSkillName/companyName/pendingInputHint 等判定基准）
     * @param message        用户最新消息
     */
    public Mono<Classification> classify(String conversationId,
                                         ContextMemoryService.ConversationContext ctx,
                                         String message) {
        return classifyInternal(ctx, message)
                .map(r -> {
                    log.info("INPUT_CLASSIFIED frameId={} result={} source={} conv={}",
                            ctx.currentFrameId, r.cls, r.source, conversationId);
                    return r.cls;
                });
    }

    private Mono<Result> classifyInternal(ContextMemoryService.ConversationContext ctx, String message) {
        // ① Structured Protocol：机器可确定的协议交互优先处理（禁止继续进入普通穿插分类）
        if (message.startsWith(TEMPLATE_SELECT_PREFIX)) return result(Classification.SUPPLEMENT, Source.PROTOCOL);
        if (message.startsWith(INTENT_SELECT_PREFIX)) return result(Classification.NEW_INTENT, Source.PROTOCOL);
        if ("以上都不是".equals(message)) return result(Classification.SUPPLEMENT, Source.PROTOCOL);

        // ② Explicit Interrupt：显式穿插信号 + 独立任务请求（不允许单命中信号词就判）
        if (isExplicitInterrupt(message, ctx)) return result(Classification.NEW_INTENT, Source.EXPLICIT_INTERRUPT);

        // ③ Expected Input：当前等待的参数能否被当前消息满足（必须先于 IntentMatcher / Context Shift）
        if (matchesExpectedInput(message, ctx)) return result(Classification.SUPPLEMENT, Source.EXPECTED_INPUT);

        // ④ IntentMatcher：明确命中另一个独立技能
        List<IntentMatcher.SkillCandidate> candidates = intentTreeMatcher.match(message);
        if (!candidates.isEmpty()) {
            String topSkill = candidates.get(0).skillName();
            // matchable 预留候选（skillName=null，如"e缴费尽调"）与任何挂起技能都不同 → 视为新意图
            if (topSkill == null || !topSkill.equals(ctx.pendingSkillName)) {
                return result(Classification.NEW_INTENT, Source.INTENT_MATCHER);
            }
            // 命中同技能且无新企业：补充信息（如 query_due_diligence_reports 缺日期时用户回复日期区间）；
            // 命中同技能但有新企业 → 落入 ⑤ Context Shift 判定（如"阿里的股东呢？"）
            if (!containsNewCompany(message, ctx)) {
                return result(Classification.SUPPLEMENT, Source.INTENT_MATCHER);
            }
        }

        // ⑤ Context Shift：Skill 相同但业务对象发生变化（同技能 + 新企业），
        // 或企业切换 + 查询行为词（"帮我查一下 云禾科技"）
        // 到此处 candidates 若非空必为同技能（不同技能已在 ④ 返回）
        boolean sameSkillHit = !candidates.isEmpty();
        if (containsNewCompany(message, ctx) && (sameSkillHit || containsQueryVerb(message))) {
            return result(Classification.NEW_INTENT, Source.CONTEXT_SHIFT);
        }

        // ⑥ LLM 仲裁兜底（CoordinatorService 二分类）
        return coordinatorService.classifyIntentInterrupt(message, ctx.pendingSkillName,
                        ctx.pendingSkillParams, ctx.pendingInputHint)
                .map(m -> {
                    String cls = String.valueOf(m.getOrDefault("class", "supplement"));
                    return "new_intent".equals(cls)
                            ? new Result(Classification.NEW_INTENT, Source.LLM)
                            : new Result(Classification.SUPPLEMENT, Source.LLM);
                });
    }

    // ---------- 第二层：Explicit Interrupt ----------

    /** 显式穿插信号 + 独立任务请求组合判定（旧项目穿插语言规则） */
    private boolean isExplicitInterrupt(String message, ContextMemoryService.ConversationContext ctx) {
        if (message == null || message.isBlank()) return false;
        boolean signalHit = false;
        for (String signal : INTERRUPT_SIGNALS) {
            if (message.contains(signal)) {
                signalHit = true;
                break;
            }
        }
        if (!signalHit) return false;
        // 独立任务请求：
        // - 命中与暂停技能不同的技能（"顺便生成风险报告" → generate_report）
        // - 或查询行为词 + 消息带企业名（"先别管这个，顺便查一下阿里"）
        List<IntentMatcher.SkillCandidate> candidates = intentTreeMatcher.match(message);
        if (!candidates.isEmpty()) {
            String topSkill = candidates.get(0).skillName();
            if (topSkill != null && !topSkill.equals(ctx.pendingSkillName)) return true;
        }
        return containsQueryVerb(message) && extractCompanyFrom(message) != null;
    }

    // ---------- 第三层：Expected Input ----------

    /** 当前等待的参数能否被当前消息满足（按输入形态 + 实际缺失参数判定，防止
     *  "生成星河报告和风险识别"等含技能动作词的新意图句子被企业名校验误放行而误拦） */
    private boolean matchesExpectedInput(String message, ContextMemoryService.ConversationContext ctx) {
        // 企业候选确认消息（"公司：xxx\n统一信用代码：xxx"）——固定协议格式，形态即补充
        if (looksLikeCompanySelection(message)) return true;
        // 等待附件（如营业执照图片）：任何文本都无法满足附件参数 → 不算补充，
        // 放行后续 ④⑤⑥ 判定（否则"生成星河报告和风险识别"会被企业名分支误拦）
        if (isWaitingAttachment(ctx)) return false;
        // 等待企业名：仅当参数确实缺 company_name 且消息整体是纯企业名时视为参数补充——
        // 否则"查询云禾科技的股东"这类查询句会被企业名校验误拦，应进入 ④⑤ 判定；
        // 已收集 company_name 的挂起技能（执照核实等附件、候选待选等）即使消息像企业名
        // 也不再按"补充企业名"处理，避免新意图句子被误判为补充
        if (!containsQueryVerb(message) && CompanyNameExtractor.isValidCompanyName(message)
                && !hasCollectedParam(ctx, "company_name")) return true;
        // 等待信用代码（18 位裸代码，与查询句形态不冲突）
        if (CompanyNameExtractor.isValidCreditCode(message)) return true;
        // 等待日期范围（"近一年" / "2024-01-01 到 2024-03-31"）——形态唯一，不可能是新意图
        if (looksLikeDateRange(message)) return true;
        // 等待候选选择（"第二个" / "帮我选择第二个" / "选2"）——形态唯一，不可能是新意图
        if (looksLikeCandidateOrdinal(message)) return true;
        return false;
    }

    /** 当前挂起技能是否在等待附件：系统附件参数（_attachment_url）已声明但未提供，
     *  或提示文案含"上传/附件/图片"语义（如"请上传营业执照图片"）。等待附件时
     *  纯文本无法满足附件参数，任何文本消息都不得判为补充信息。 */
    private boolean isWaitingAttachment(ContextMemoryService.ConversationContext ctx) {
        if (ctx == null) return false;
        Object url = ctx.pendingSkillParams.get("_attachment_url");
        if (url != null) return String.valueOf(url).trim().isEmpty();
        String hint = ctx.pendingInputHint;
        return hint != null && !hint.isEmpty()
                && (hint.contains("上传") || hint.contains("附件") || hint.contains("图片"));
    }

    /** 挂起技能是否已收集到指定参数（存在且非空） */
    private boolean hasCollectedParam(ContextMemoryService.ConversationContext ctx, String key) {
        if (ctx == null || ctx.pendingSkillParams == null) return false;
        Object v = ctx.pendingSkillParams.get(key);
        return v != null && !String.valueOf(v).trim().isEmpty();
    }

    /** 候选序号消息："第二个"/"选 2"/"第1个"/"3号"（文档 Case 1：等待候选时"第二个" → SUPPLEMENT） */
    private boolean looksLikeCandidateOrdinal(String message) {
        if (message == null) return false;
        String text = message.trim().replaceAll("\\s+", "");
        if (text.matches("(帮我?|请帮我?)?(选择|选|点)?第[一二三四五六七八九十0-9]+个?")) return true;
        if (text.matches("(帮我?|请帮我?)?(选择|选)[一二三四五六七八九十0-9]+")) return true;
        return text.matches("[一二三四五六七八九十0-9]+号");
    }

    // ---------- 第四/五层辅助 ----------

    /** 消息中是否含与上下文企业不同的新企业名（企业切换检测）。
     *  上下文企业为空（无旧查询对象可比，用户更像在补充企业名）或提取失败视为无新企业。 */
    private boolean containsNewCompany(String message, ContextMemoryService.ConversationContext ctx) {
        if (message == null || message.isBlank()) return false;
        // 上下文企业为空时无"切换"对象可言：用户正在补充企业名（管道补充），不算新意图
        if (ctx.companyName == null || ctx.companyName.isEmpty()) return false;
        String extracted = extractCompanyFrom(message);
        if (extracted == null || extracted.isEmpty()) return false;
        return !ctx.companyName.contains(extracted) && !extracted.contains(ctx.companyName);
    }

    /** 从消息中提取企业名（复用 CompanyNameExtractor 统一清洗链） */
    private String extractCompanyFrom(String message) {
        String verbs = "查询|查一下|查查|帮我查|查|提供|获取|看一下|看看|了解一下|搜索|查找|尽调|历史";
        String suffixes = "科技|有限公司|股份|集团|公司|厂|银行|药业|能源|网络|信息|实业|建材|地产|贸易|服务";
        return CompanyNameExtractor.extractCompanyName(message, verbs, suffixes, null);
    }

    /** 查询行为词：消息中是否带"查询/查一下/提供/获取"等动作 */
    private boolean containsQueryVerb(String message) {
        if (message == null) return false;
        String[] verbs = {"查询", "查一下", "查查", "帮我查", "查", "提供", "获取", "看一下", "看看", "了解一下", "搜索", "查找"};
        for (String v : verbs) {
            if (message.contains(v)) return true;
        }
        return false;
    }

    /** 企业候选选择消息："公司：xxx\n统一信用代码：xxx"（前端企业候选卡片点击格式）。
     *  公开：ChatController 主流程候选确认标记（markCompanyCardConfirmed）复用。 */
    public boolean looksLikeCompanySelection(String message) {
        if (message == null) return false;
        String normalized = message.replace("：", ":").replace("\n", " ").trim();
        return normalized.contains("公司:")
                && (normalized.contains("统一信用代码:") || normalized.contains("信用代码:"));
    }

    /** 日期范围补充消息："近一年"、"就查去年一整年"、"2024-01-01 到 2024-03-31" 等 */
    private boolean looksLikeDateRange(String message) {
        if (message == null) return false;
        String text = message.trim();
        // 相对时间短语（近一年/近半年/近三个月等）
        if (text.matches("近(一年|半年|三个月|一个月|两周|一个星期|一个季度)")) return true;
        // 无分隔符整段时间短语（"就查去年一整年"/"今年全年"）：整句为时间补充，
        // 即使含"查"等查询行为词也视为参数补充——时间即查询对象，非企业切换；
        // 整句匹配保证"去年成立的云禾科技"这类混合句不落入，仍走 ④⑤ 判定
        if (text.matches("(就|只|就只|给我|请)?\\s*(查一下|查查|帮我查|看一下|看看|查|提供|获取)?\\s*"
                + "(去年|今年|前年|上一年)([一二三四五六七八九十0-9]+)?\\s*(一整年|整年|全年|一年|两年|三年)?")) {
            return true;
        }
        // 日期区间："2020 年到 2023 年" / "今年1月到3月" / "1月1日到3月31日"
        if (text.contains("到") || text.contains("至") || text.contains("~") || text.contains("～")) {
            boolean containsTime = text.matches(".*(\\d{4}年|\\d{1,2}月|\\d{1,2}日|\\d{4}-\\d{1,2}).*")
                    || text.contains("今年") || text.contains("去年") || text.contains("前年");
            // 排除含查询行为词的长句（如"帮我查一下 云禾科技 2020 年到 2023 年的报告"）：
            // 此类消息即使命中日期区间也应进入 ④⑤⑥ 级判定而非直接归为补充
            return containsTime && !containsQueryVerb(text);
        }
        return false;
    }

    /** 判定结果载体 */
    private record Result(Classification cls, Source source) {}

    private Mono<Result> result(Classification cls, Source source) {
        return Mono.just(new Result(cls, source));
    }
}
