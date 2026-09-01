package com.IDDagent.skill;

import com.IDDagent.skill.IntentMatcher.SkillCandidate;
import com.IDDagent.skill.IntentTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分层意图树匹配器单元测试。
 * 覆盖 docs/分层意图树设计方案.md 3.3 匹配算法特征表与第七章验收用例：
 * 单叶子、defaultLeaf 兜底、matchable 预留、跨子域并存、互斥（mustNot）、分隔写法、无叶子。
 */
class IntentTreeMatcherTest {

    private IntentTreeMatcher matcher;

    @BeforeEach
    void setUp() {
        // 空注册表即可：label 回退为技能名/节点名，匹配逻辑不依赖技能注册
        matcher = new IntentTreeMatcher(new SkillRegistry());
    }

    private List<String> skillNames(String message) {
        return matcher.match(message).stream().map(SkillCandidate::skillName).toList();
    }

    // ---------- 单叶子 ----------

    @Test
    void riskLatestViaDefaultLeaf() {
        assertEquals(List.of("check_company_risk"), skillNames("云禾科技的风险"));
    }

    @Test
    void basicInfoViaDefaultLeaf() {
        assertEquals(List.of("query_company_basic_info"), skillNames("查询云禾科技的信息"));
    }

    @Test
    void verifyBusinessLicense() {
        assertEquals(List.of("verify_business_license"), skillNames("核实云禾科技的营业执照"));
    }

    @Test
    void shareholder() {
        assertEquals(List.of("query_shareholder_info"), skillNames("云禾科技的股东有哪些"));
    }

    @Test
    void beneficiary() {
        assertEquals(List.of("query_beneficiary_info"), skillNames("查询小米科技的受益人信息"));
    }

    @Test
    void genealogy() {
        assertEquals(List.of("query_company_genealogy"), skillNames("查一下云禾的企业族谱"));
    }

    @Test
    void customsAuth() {
        assertEquals(List.of("query_customs_auth"), skillNames("海关认证信息"));
    }

    @Test
    void customsBlacklist() {
        assertEquals(List.of("query_customs_blacklist"), skillNames("海关失信名单"));
    }

    @Test
    void accountFreeze() {
        assertEquals(List.of("query_account_freeze_tag"), skillNames("查云禾的账户冻结标签"));
    }

    @Test
    void creditGranting() {
        assertEquals(List.of("query_credit_granting"), skillNames("授信额度是多少"));
    }

    @Test
    void pbocSeparatedWriting() {
        // 分隔写法："人行账户管控" → 人行（组1）∧ 账户管控（组2），修复复合词漏匹配；
        // branch_5 被"人行/账户"黑名单否决 → pboc 唯一
        assertEquals(List.of("query_pboc_account_control"), skillNames("查一下人行账户管控情况"));
    }

    @Test
    void generateReport() {
        assertEquals(List.of("generate_report"), skillNames("生成小米科技的尽调报告"));
    }

    @Test
    void generateReportWithProductDd() {
        // branch_8 新增"产品尽调"触发词
        assertEquals(List.of("generate_report"), skillNames("帮我出一份产品尽调"));
    }

    @Test
    void historicalReport() {
        // branch_new 命中；branch_5 被"历史/报告"黑名单否决 → 历史查询唯一
        assertEquals(List.of("query_due_diligence_reports"), skillNames("查询历史尽调报告"));
    }

    @Test
    void historicalReportWithPreviousWord() {
        // "以前的尽调报告"：branch_8 被"以前"否决，branch_new 新增"以前"触发词 → 历史查询唯一
        assertEquals(List.of("query_due_diligence_reports"), skillNames("小米科技以前的尽调报告"));
    }

    @Test
    void historicalReportWithHasQuery() {
        // "有没有尽调报告"：branch_8 被"看看/有没有"否决，branch_new 新增"有没有"触发词 → 历史查询唯一；
        // "有没有AEO认证"含"认证/AEO" → branch_new 否决；C3 需"海关"字样 → 空候选 → LLM 兜底
        assertEquals(List.of("query_due_diligence_reports"), skillNames("帮我看看有没有尽调报告"));
        assertTrue(matcher.match("有没有AEO认证").isEmpty());
    }

    @Test
    void generateReportExcludedByHistoricalWords() {
        // "查一下2024年...尽调报告"：branch_8 被 mustNot[查一下] 否决，branch_new 无"历史/以前"字眼；
        // branch_5 被"报告/尽调"黑名单否决 → 空候选 → LLM 兜底
        assertTrue(matcher.match("查一下2024年小米科技的尽调报告").isEmpty());
    }

    @Test
    void generateReportNotExcludedWithoutExcludeWords() {
        // "怎么看尽调报告"：mustNot 不触发（"查看"≠"怎么看"）→ 唯一 generate_report
        assertEquals(List.of("generate_report"), skillNames("怎么看尽调报告"));
    }

    // ---------- defaultLeaf 兜底 ----------

    @Test
    void riskHistoryNotFallbackToLatest() {
        // 兄弟（历史）命中 → default（最新风险）不激活；branch_5 被"风险/历史"黑名单否决 → 唯一
        assertEquals(List.of("query_risk_history"), skillNames("查一下云禾科技的风险历史记录"));
    }

    @Test
    void riskScoreNotFallbackToLatest() {
        assertEquals(List.of("query_risk_score"), skillNames("风险评价得分"));
    }

    @Test
    void crossSubdomainCoexist() {
        // branch_5 被"风险"黑名单否决 → C1 最新风险唯一（跨子域并存改为黑名单互斥）
        assertEquals(List.of("check_company_risk"), skillNames("查一下小米科技的风险情况"));
    }

    @Test
    void companyNameOnlyNoQueryVerb() {
        // 无查询行为词 → C2 default 不激活 → 无叶子
        assertTrue(matcher.match("小米科技").isEmpty());
    }

    @Test
    void siblingHitSuppressesDefault() {
        // C1 内"历史+得分"同时命中，default（最新）不激活；branch_5 被"风险"黑名单否决
        List<String> names = skillNames("查一下风险历史得分");
        assertTrue(names.contains("query_risk_history"));
        assertTrue(names.contains("query_risk_score"));
        assertFalse(names.contains("check_company_risk"));
    }

    // ---------- branch_5 黑名单：查询行为词不再吞掉具体意图 ----------

    @Test
    void queryCustomsInfoSuppressesBasicDefault() {
        // "查询海关信息"：C3 缺认证/失信词不命中，branch_5 被"海关"否决 → 空候选 → LLM 兜底（可澄清）
        assertTrue(matcher.match("查询海关信息").isEmpty());
    }

    @Test
    void queryAccountInfoSuppressesBasicDefault() {
        // "查询账户情况"：freeze 缺"冻结"、pboc 缺"人行"，branch_5 被"账户"否决 → 空候选
        assertTrue(matcher.match("查询小米科技的账户情况").isEmpty());
    }

    @Test
    void pbocAloneHits() {
        // branch_19 放宽：账管词单独出现即命中 pboc（对齐测试集 PBO-003 预期）；branch_5 被"账户"黑名单否决
        assertEquals(List.of("query_pboc_account_control"), skillNames("查一下账户管控"));
    }

    @Test
    void queryFreezeStatusSuppressesBasicDefault() {
        // "冻结状态"缺"账户/账号"维度 → 空候选 → LLM 兜底
        assertTrue(matcher.match("帮我查一下北京星河科技的冻结状态").isEmpty());
    }

    @Test
    void queryReportSuppressesBasicDefault() {
        // "帮我查一下报告"：branch_8 被"查一下"否决，branch_5 被"报告"否决 → 空候选
        assertTrue(matcher.match("帮我查一下报告").isEmpty());
    }

    @Test
    void verifyByHecha() {
        // branch_2 核实词补"核查" → 确定性命中（不再被 basic 兜底抢走）
        assertEquals(List.of("verify_business_license"), skillNames("核查一下深圳前海创新金融集团的营业执照"));
    }

    @Test
    void beneficiaryByShikongren() {
        // branch_7 触发词补"实控人" → 确定性命中（不再被 basic 兜底抢走）
        assertEquals(List.of("query_beneficiary_info"), skillNames("帮我查一下深圳前海创新金融集团的实控人"));
    }

    @Test
    void riskScoreReserved() {
        // branch_18 触发词补"评分" → 预留叶子（未注册）→ 未开通 chat；不再被 C1 default 兜底为最新风险
        assertEquals(List.of("query_risk_score"), skillNames("给小米科技的风险评分"));
    }

    // ---------- matchable 预留节点（branch_9~12） ----------

    @Test
    void matchableEPay() {
        List<SkillCandidate> cs = matcher.match("e缴费尽调怎么做");
        assertEquals(1, cs.size());
        assertNull(cs.get(0).skillName(), "matchable 候选 skillName 应为 null");
        assertTrue(cs.get(0).label().contains("e缴费"));
    }

    @Test
    void matchableEnterpriseBank() {
        List<SkillCandidate> cs = matcher.match("银企互联尽调");
        assertEquals(1, cs.size());
        assertNull(cs.get(0).skillName());
    }

    @Test
    void matchableSmartCard() {
        List<SkillCandidate> cs = matcher.match("财智卡尽调");
        assertEquals(1, cs.size());
        assertNull(cs.get(0).skillName());
    }

    // ---------- B 域无对象核实兜底叶子（verify_ambiguous） ----------

    @Test
    void ambiguousVerifyViaDefaultLeaf() {
        // 仅核实行为词无对象 → 兜底叶子激活（虚拟技能 verify_ambiguous，协调层转澄清卡片）
        assertEquals(List.of(IntentTree.VERIFY_AMBIGUOUS), skillNames("核实一下"));
    }

    @Test
    void ambiguousVerifyWithCompanyName() {
        // 信息核查 + 企业名但无核实对象 → 兜底叶子激活
        assertEquals(List.of(IntentTree.VERIFY_AMBIGUOUS), skillNames("信息核查一下北京星河科技"));
    }

    @Test
    void ambiguousVerifyWithAttachment() {
        assertEquals(List.of(IntentTree.VERIFY_AMBIGUOUS), skillNames("上传的附件帮我核实一下"));
    }

    @Test
    void ambiguousVerifySuppressedByObjectWord() {
        // 对象词（联系电话）明确 → branch_20 承接，兜底不激活
        assertEquals(List.of("verify_contact_info"), skillNames("核实云禾科技的联系电话"));
    }

    @Test
    void ambiguousVerifySuppressedByLicense() {
        // 对象词（营业执照）明确 → branch_2 承接，兜底不激活
        assertEquals(List.of("verify_business_license"), skillNames("帮我核实一下小米科技的营业执照"));
    }

    @Test
    void ambiguousVerifyCoexistWithRisk() {
        // 跨子域并存：核实兜底 + C1 风险（协调层特判 → 先澄清核实对象）
        List<String> names = skillNames("核实小米科技的信息并做风险识别");
        assertTrue(names.contains(IntentTree.VERIFY_AMBIGUOUS));
        assertTrue(names.contains("check_company_risk"));
    }

    // ---------- C2 基本信息名词对象叶子（branch_5n） ----------

    @Test
    void basicInfoByNounObject() {
        // 基本信息类名词直接命中，无需查询行为词
        assertEquals(List.of("query_company_basic_info"), skillNames("查询云禾科技的注册资本"));
    }

    @Test
    void basicInfoByLicenseInfoNoun() {
        assertEquals(List.of("query_company_basic_info"), skillNames("查询云禾科技的营业执照信息"));
    }

    @Test
    void basicInfoNounDedupedWithVerbDefault() {
        // 名词对象 + 行为词兜底同技能双命中 → 去重后唯一候选
        assertEquals(List.of("query_company_basic_info"), skillNames("查一下小米科技的基本信息"));
    }

    @Test
    void basicInfoNounSuppressedByCustomsWord() {
        // 5n mustNot 海关子域词：认证信息归 C3，不抢 basic
        assertEquals(List.of("query_customs_auth"), skillNames("查询海关认证信息"));
    }

    // ---------- 预留叶子（skillName 已填但技能未注册） ----------

    @Test
    void reservedNaturalPerson() {
        assertEquals(List.of("verify_natural_person"), skillNames("核实一下法定代表人的身份信息"));
    }

    @Test
    void reservedContactInfo() {
        assertEquals(List.of("verify_contact_info"), skillNames("核实云禾的联系电话"));
    }

    // ---------- 无叶子 ----------

    @Test
    void noLeafForChat() {
        assertTrue(matcher.match("你好").isEmpty());
    }

    @Test
    void noLeafForPureCompanyName() {
        assertTrue(matcher.match("北京星河科技怎么样").isEmpty());
    }

    @Test
    void noLeafForFreezeAlone() {
        // branch_16 要求 冻结∧(账户|账号)，仅"冻结"不命中
        assertTrue(matcher.match("冻结").isEmpty());
    }

    // ---------- 互斥与多候选 ----------

    @Test
    void customsAuthVsBlacklistMutualExclusion() {
        // 认证/失信分支互斥：各自独立命中，可并存（"海关认证和失信情况"→ 双候选 → LLM 仲裁）
        assertEquals(List.of("query_customs_auth", "query_customs_blacklist"),
                skillNames("海关认证和失信情况"));
        // 无认证/失信词的"海关信息"：C3 两分支都不命中；无查询行为词时 C2 default 也不激活 → 无叶子
        assertTrue(matcher.match("海关信息").isEmpty());
    }

    @Test
    void matchedKeywordsRecorded() {
        SkillCandidate c = matcher.match("查询小米科技的股东信息").get(0);
        assertNotNull(c.matchedKeywords());
        assertTrue(c.matchedKeywords().contains("股东"));
    }
}
