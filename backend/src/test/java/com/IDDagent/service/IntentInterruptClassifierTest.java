package com.IDDagent.service;

import com.IDDagent.config.AppConfig;
import com.IDDagent.skill.IntentTreeMatcher;
import com.IDDagent.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Phase 4（IntentClassifier 六层）单元测试：覆盖实施文档第 50 节 Case 1-6 与协议前缀、
 * 显式穿插组合判定、Expected Input 优先于 Context Shift、LLM 兜底降级等场景。
 * 六层顺序：① Protocol ② Explicit Interrupt ③ Expected Input ④ IntentMatcher
 * ⑤ Context Shift ⑥ LLM Fallback（不允许调整）。
 */
class IntentInterruptClassifierTest {

    private IntentInterruptClassifier classifier;
    private ContextMemoryService contextMemoryService;

    @BeforeEach
    void setUp() {
        IntentTreeMatcher matcher = new IntentTreeMatcher(new SkillRegistry());
        contextMemoryService = new ContextMemoryService();
        // AppConfig 无 API key：LLM 兜底层降级为 supplement（无需真实调用）
        CoordinatorService coordinator = new CoordinatorService(
                new SkillRegistry(), matcher, mock(WebClient.class),
                new AppConfig(), contextMemoryService);
        classifier = new IntentInterruptClassifier(matcher, coordinator);
    }

    /** 构造一个含挂起技能的会话上下文（pendingSkill 与上下文企业可空） */
    private ContextMemoryService.ConversationContext ctx(String pendingSkill, String company) {
        ContextMemoryService.ConversationContext ctx =
                contextMemoryService.get("conv-p4-" + pendingSkill + "-" + company);
        ctx.currentFrameId = "F_P4";
        if (pendingSkill != null) {
            ctx.pendingSkillName = pendingSkill;
            ctx.pendingSkillParams = new LinkedHashMap<>();
            if (company != null && !company.isEmpty()) {
                ctx.pendingSkillParams.put("company_name", company);
            }
        }
        ctx.companyName = company == null ? "" : company;
        return ctx;
    }

    private IntentInterruptClassifier.Classification classify(
            ContextMemoryService.ConversationContext ctx, String message) {
        return classifier.classify("conv-p4", ctx, message).block();
    }

    // ---------- ① Structured Protocol ----------

    @Test
    void protocolTemplateSelectIsSupplement() {
        assertEquals(IntentInterruptClassifier.Classification.SUPPLEMENT,
                classify(ctx("generate_report", "小米科技"), "【模板选择】tpl_1"));
    }

    @Test
    void protocolIntentSelectIsNewIntent() {
        assertEquals(IntentInterruptClassifier.Classification.NEW_INTENT,
                classify(ctx("query_shareholder_info", "小米科技"), "【意图选择】generate_report"));
    }

    @Test
    void protocolRejectAllCandidatesIsSupplement() {
        assertEquals(IntentInterruptClassifier.Classification.SUPPLEMENT,
                classify(ctx("query_company_basic_info", "小米科技"), "以上都不是"));
    }

    // ---------- ③ Expected Input（必须先于 ④⑤，文档第 17/21 节） ----------

    @Test
    void expectedCompanyNameBeatsContextShift() {
        // 文档 Case 3：等待企业名时输入企业——即使与上下文企业不同，也必须 SUPPLEMENT
        //（Expected Input 优先于 Context Shift，不得因企业变化误判穿插）
        assertEquals(IntentInterruptClassifier.Classification.SUPPLEMENT,
                classify(ctx("query_company_basic_info", "小米科技"), "北京字节跳动科技有限公司"));
    }

    @Test
    void candidateOrdinalIsSupplement() {
        // 文档 Case 1：等待候选选择时"第二个" → SUPPLEMENT
        assertEquals(IntentInterruptClassifier.Classification.SUPPLEMENT,
                classify(ctx("query_company_basic_info", "小米科技"), "第二个"));
    }

    @Test
    void helpMeSelectSecondIsSupplement() {
        // 文档第 15 节反例："帮我选择第二个"不得因含"帮我"判穿插 → SUPPLEMENT（候选序号）
        assertEquals(IntentInterruptClassifier.Classification.SUPPLEMENT,
                classify(ctx("query_company_basic_info", "小米科技"), "帮我选择第二个"));
    }

    @Test
    void companySelectionMessageIsSupplement() {
        assertEquals(IntentInterruptClassifier.Classification.SUPPLEMENT,
                classify(ctx("query_company_basic_info", "小米科技"),
                        "公司：云禾科技\n统一信用代码：91310000MA1FL4XH3X"));
    }

    @Test
    void creditCodeIsSupplement() {
        assertEquals(IntentInterruptClassifier.Classification.SUPPLEMENT,
                classify(ctx("query_company_basic_info", ""), "91310000MA1FL4XH3X"));
    }

    @Test
    void dateRangeIsSupplement() {
        assertEquals(IntentInterruptClassifier.Classification.SUPPLEMENT,
                classify(ctx("query_due_diligence_reports", "小米科技"), "2024-01-01 到 2024-03-31"));
    }

    @Test
    void attachmentWaitingTextWithSkillWordsIsNewIntent() {
        // 回归修复：执照核实等待附件（company_name/credit_code 已收集）时，
        // "生成星河报告和风险识别"等含技能动作词的新意图句子不得被企业名补充
        // （EXPECTED_INPUT）误拦——应命中 generate_report/check_company_risk 判穿插
        ContextMemoryService.ConversationContext c = ctx("verify_business_license", "北京星河科技有限公司");
        c.pendingSkillParams.put("credit_code", "91110108MA01B3XK2P");
        c.pendingInputHint = "请上传【北京星河科技有限公司】的营业执照图片以进行信息核实。";
        assertEquals(IntentInterruptClassifier.Classification.NEW_INTENT,
                classify(c, "生成星河报告和风险识别"));
    }

    @Test
    void attachmentWaitingQuerySentenceIsNewIntent() {
        // 等待附件时含查询行为词的新意图（"帮我查一下云禾科技的风险"命中风险检查）同样放行
        ContextMemoryService.ConversationContext c = ctx("verify_business_license", "北京星河科技有限公司");
        c.pendingSkillParams.put("credit_code", "91110108MA01B3XK2P");
        c.pendingInputHint = "请上传【北京星河科技有限公司】的营业执照图片以进行信息核实。";
        assertEquals(IntentInterruptClassifier.Classification.NEW_INTENT,
                classify(c, "帮我查一下云禾科技的风险"));
    }

    // ---------- ② Explicit Interrupt（信号词 + 独立任务请求组合） ----------

    @Test
    void explicitInterruptWithCompanyTask() {
        // 文档 Case 2："先别管这个，顺便查一下阿里" → NEW_INTENT（显式穿插信号 + 独立查询任务）
        assertEquals(IntentInterruptClassifier.Classification.NEW_INTENT,
                classify(ctx("query_company_basic_info", "小米科技"), "先别管这个，顺便查一下云禾科技"));
    }

    @Test
    void explicitInterruptWithDifferentSkill() {
        // 文档 Case 5："顺便生成风险报告"（挂起股东查询）→ NEW_INTENT（信号词 + 命中不同技能）
        assertEquals(IntentInterruptClassifier.Classification.NEW_INTENT,
                classify(ctx("query_shareholder_info", "小米科技"), "顺便生成风险报告"));
    }

    @Test
    void signalWordWithoutIndependentTaskIsNotInterrupt() {
        // 仅信号词无独立任务（"对了"后接普通闲聊）→ 不得判穿插，落入 LLM 兜底 → SUPPLEMENT
        assertEquals(IntentInterruptClassifier.Classification.SUPPLEMENT,
                classify(ctx("query_shareholder_info", "小米科技"), "对了，今天天气不错"));
    }

    // ---------- ④ IntentMatcher / ⑤ Context Shift ----------

    @Test
    void differentSkillIsNewIntent() {
        // 无信号词但明确命中另一独立技能（挂起历史尽调查询，用户发起风险检查）
        assertEquals(IntentInterruptClassifier.Classification.NEW_INTENT,
                classify(ctx("query_due_diligence_reports", "小米科技"), "帮我查一下云禾科技的风险情况"));
    }

    @Test
    void sameSkillCompanyShiftIsNewIntent() {
        // 文档 Case 4 变体：同 Skill 切企业（股东查询 腾讯 → 云禾）→ NEW_INTENT（Context Shift）
        assertEquals(IntentInterruptClassifier.Classification.NEW_INTENT,
                classify(ctx("query_shareholder_info", "腾讯科技"), "查询云禾科技的股东"));
    }

    @Test
    void sameSkillWithoutNewCompanyIsSupplement() {
        // 命中同技能且无新企业：补充信息（如历史尽调缺日期时回复日期区间）
        assertEquals(IntentInterruptClassifier.Classification.SUPPLEMENT,
                classify(ctx("query_due_diligence_reports", "小米科技"), "2020 年到 2023 年"));
    }

    @Test
    void newCompanyWithQueryVerbIsNewIntent() {
        // 企业切换 + 查询行为词（含日期区间长句回归：不得被日期补充误拦）
        assertEquals(IntentInterruptClassifier.Classification.NEW_INTENT,
                classify(ctx("query_due_diligence_reports", "小米科技"),
                        "帮我查一下 云禾科技 2020 年到 2023 年的报告"));
    }

    // ---------- ⑥ LLM Fallback ----------

    @Test
    void plainDateSupplementIsExpectedInput() {
        // 文档 Case 6：等待日期时"就查去年一整年"——整句为时间补充（即使含"查"），
        // 由 ③ Expected Input 时间形态识别拦截 → SUPPLEMENT，不得被 ⑤ Context Shift 误判
        assertEquals(IntentInterruptClassifier.Classification.SUPPLEMENT,
                classify(ctx("query_due_diligence_reports", "小米科技"), "就查去年一整年"));
    }

    @Test
    void unrelatedChatFallsToLlmSupplement() {
        assertEquals(IntentInterruptClassifier.Classification.SUPPLEMENT,
                classify(ctx("query_shareholder_info", "小米科技"), "今天天气怎么样"));
    }
}
