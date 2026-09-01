package com.IDDagent.skill;

import java.util.List;

/**
 * 分层意图树静态定义（A 尽调业务域 / B 核实域 / C 法人查询域，20 branch + branch_new）。
 * 与 docs/分层意图树设计方案.md 3.2 树定义 1:1 映射。
 * 设计要点：
 * - 域 = 组织分组，不设匹配门槛；行为词（核实/查询）作为条件维度下放到每个叶子；
 * - 排他性由树结构承担（分支互斥 + defaultLeaf 兜底 + mustNot），不再依赖 conflictGroup/排除词链；
 * - branch_9~12 为 matchable 预留节点（skillName=null，命中返回"该业务暂未开通"提示）；
 * - branch_1/4/18/20 为预留叶子（skillName 已填但技能未注册，命中同样返回未开通提示）；
 * - branch_21 为"无对象核实"兜底叶子（绑定虚拟技能 {@link #VERIFY_AMBIGUOUS}，非真实技能，
 *   命中后由 CoordinatorService 构造核实澄清卡片：营业执照核实 / 身份证核实）。
 */
public final class IntentTree {

    /** B 域"无对象核实"兜底叶子绑定的虚拟技能名：非真实技能，命中后由 CoordinatorService 构造核实澄清卡片 */
    public static final String VERIFY_AMBIGUOUS = "verify_ambiguous";

    /** branch_9~12 尽调业务类型（businessType 扩展参数） */
    public static final String BIZ_TYPE_E_PAY = "E_PAY";
    public static final String BIZ_TYPE_ENTERPRISE_BANK = "ENTERPRISE_BANK";
    public static final String BIZ_TYPE_RONG_E_JU = "RONG_E_JU";
    public static final String BIZ_TYPE_SMART_CARD = "SMART_CARD";

    private IntentTree() {}

    public static IntentNode build() {
        // ============ A 尽调业务域（不设域级门槛，仅作组织分组） ============
        // branch_8 产品尽调生成 → generate_report（mustNot 承担与历史查询的互斥）
        IntentNode branch8 = IntentNode.branch("A-产品尽调生成",
                new IntentNode.IntentCondition(
                        List.of("生成报告", "尽调报告", "财务分析报告", "授信评估", "报告模板",
                                "生成尽调", "智能尽调", "上传资料生成报告", "产品尽调", "生成"),
                        List.of(),
                        List.of("历史", "查询", "查一下", "查看", "查找", "以前", "有没有", "看看")),
                "generate_report");
        // branch_new 历史尽调报告查询 → query_due_diligence_reports（"风险"归 C1，"海关/认证"归 C3）
        IntentNode branchNew = IntentNode.branch("A-历史尽调报告查询",
                new IntentNode.IntentCondition(
                        List.of("历史尽调", "查询历史", "尽调记录", "历史报告", "历史", "以前", "过往", "有没有"),
                        List.of(), List.of("风险", "海关", "认证", "AEO", "失信")),
                "query_due_diligence_reports");
        // branch_9~12 matchable 预留：mustAll[业务词] + mustAny[尽调,尽职调查] → businessType 区分
        IntentNode branch9 = IntentNode.matchableBranch("A-e缴费尽调",
                new IntentNode.IntentCondition(
                        List.of("尽调", "尽职调查"), List.of("e缴费"), List.of()),
                BIZ_TYPE_E_PAY);
        IntentNode branch10 = IntentNode.matchableBranch("A-银企互联尽调",
                new IntentNode.IntentCondition(
                        List.of("尽调", "尽职调查"), List.of("银企互联"), List.of()),
                BIZ_TYPE_ENTERPRISE_BANK);
        IntentNode branch11 = IntentNode.matchableBranch("A-工银融e聚尽调",
                new IntentNode.IntentCondition(
                        List.of("尽调", "尽职调查"), List.of("融e聚"), List.of()),
                BIZ_TYPE_RONG_E_JU);
        IntentNode branch12 = IntentNode.matchableBranch("A-财智账户卡尽调",
                new IntentNode.IntentCondition(
                        List.of("财智账户卡", "财智卡"), List.of(),
                        List.of(), List.of(List.of("尽调", "尽职调查"))),
                BIZ_TYPE_SMART_CARD);
        IntentNode domainA = IntentNode.group("A-尽调业务域",
                branch8, branchNew, branch9, branch10, branch11, branch12);

        // ============ B 核实域（行为词作为 mustAny 维度下放至各叶子，域本身不裁剪） ============
        // branch_1 身份核实 → verify_natural_person（预留；角色维度待业务方确认）
        IntentNode branch1 = IntentNode.branch("B-身份核实",
                new IntentNode.IntentCondition(
                        List.of("法定代表人", "授权代理人", "财务主管"), List.of(), List.of(),
                        List.of(List.of("身份", "个人信息"), List.of("核实", "核对", "核验"))),
                "verify_natural_person");
        // branch_2 营业执照核实 → verify_business_license
        IntentNode branch2 = IntentNode.branch("B-营业执照核实",
                new IntentNode.IntentCondition(
                        List.of("营业执照"), List.of(), List.of(),
                        List.of(List.of("核实", "核对", "核验", "核查"))),
                "verify_business_license");
        // branch_20 通讯核实 → verify_contact_info（预留）
        IntentNode branch20 = IntentNode.branch("B-通讯核实",
                new IntentNode.IntentCondition(
                        List.of("通讯", "电话", "手机", "号码"), List.of(), List.of(),
                        List.of(List.of("核实", "核对", "核验"))),
                "verify_contact_info");
        // branch_21 无对象核实（defaultLeaf 兜底）：仅"核实/核验/核查/核对"类行为词且未指明任何
        // 核实对象时激活（如"核实一下"、"信息核查一下北京星河科技"、"上传的附件帮我核实一下"）。
        // 命中后不直接路由具体技能：绑定虚拟技能 verify_ambiguous，由 CoordinatorService 构造
        // clarify 决策（营业执照核实/身份证核实候选卡片），与 LLM 兜底规则 e 同语义。
        // mustNot 覆盖 B 域全部对象词：对象明确时由 branch_1/2/20 优先承接，兜底不得抢走
        IntentNode branch21 = IntentNode.defaultBranch("B-无对象核实",
                new IntentNode.IntentCondition(
                        List.of("核实", "核验", "核查", "核对"), List.of(),
                        List.of("营业执照", "执照", "身份", "个人信息", "通讯", "电话", "手机", "号码",
                                "法定代表人", "授权代理人", "财务主管")),
                VERIFY_AMBIGUOUS);
        IntentNode domainB = IntentNode.group("B-核实域", branch1, branch2, branch20, branch21);

        // ============ C 法人查询域（不设域级门槛，仅作组织分组） ============
        // C1 风险子域：branch_4 历史 / branch_18 得分 / branch_3 最新（defaultLeaf 兜底）
        IntentNode branch4 = IntentNode.branch("C1-历史风险",
                new IntentNode.IntentCondition(
                        List.of("风险"), List.of(), List.of(),
                        List.of(List.of("历史", "记录", "存量"))),
                "query_risk_history");
        IntentNode branch18 = IntentNode.branch("C1-风险评价得分",
                new IntentNode.IntentCondition(
                        List.of("风险"), List.of(), List.of(),
                        List.of(List.of("评价", "得分", "打分", "评分"))),
                "query_risk_score");
        IntentNode branch3 = IntentNode.defaultBranch("C1-最新风险",
                new IntentNode.IntentCondition(List.of("风险"), List.of(), List.of()),
                "check_company_risk");
        IntentNode c1 = IntentNode.group("C1-风险子域", branch4, branch18, branch3);

        // C2 工商子域（平级挂，不再要求"不含风险词"）：branch_5 基本信息为 defaultLeaf，强制必含查询行为词；
        // mustNot 为全局意图词黑名单：其他域/叶子核心词出现时放弃兜底，避免"查询/查一下"无条件吞掉具体意图
        IntentNode branch5 = IntentNode.defaultBranch("C2-基本信息",
                new IntentNode.IntentCondition(
                        List.of("查询", "查一下", "查查", "提供", "获取", "看一下", "看看", "了解一下"),
                        List.of(),
                        List.of("风险", "海关", "认证", "失信", "黑名单", "AEO",
                                "账户", "账号", "冻结", "人行", "人民银行", "中国人民银行", "央行",
                                "授信", "尽调", "报告", "历史", "以前", "有没有",
                                "营业执照", "核实", "核对", "核验", "核查", "身份", "通讯", "电话", "手机",
                                "实控人", "法定代表人", "授权代理人", "财务主管")),
                "query_company_basic_info");
        IntentNode branch6 = IntentNode.branch("C2-股东信息",
                new IntentNode.IntentCondition(List.of("股东", "股权结构", "股权分布"), List.of(), List.of()),
                "query_shareholder_info");
        IntentNode branch7 = IntentNode.branch("C2-受益人信息",
                new IntentNode.IntentCondition(List.of("受益人", "实际控制人", "受益所有人", "实控人"), List.of(), List.of()),
                "query_beneficiary_info");
        IntentNode branch13 = IntentNode.branch("C2-企业族谱",
                new IntentNode.IntentCondition(List.of("企业族谱", "家族图谱", "关联企业图谱"), List.of(), List.of()),
                "query_company_genealogy");
        // branch_5n 基本信息名词对象（普通叶子）：明确的基本信息类名词直接命中 basic，无需查询行为词
        // （如"查询云禾科技的营业执照信息"）。mustNot 排除海关子域核心词："查询海关信息"类上义词
        // 输入保持空候选 → LLM 兜底澄清（认证 or 失信）；"营业执照信息"等核实对象词与 B 域并存时
        // 交仲裁（如"核实营业执照信息"→ verify + basic 双候选）。
        IntentNode branch5n = IntentNode.branch("C2-基本信息对象",
                new IntentNode.IntentCondition(
                        List.of("基本信息", "基本资料", "基本情况", "工商信息", "工商资料",
                                "注册信息", "注册资本", "营业执照信息", "法定代表人信息"), List.of(),
                        List.of("海关", "认证", "失信", "黑名单", "AEO")),
                "query_company_basic_info");
        IntentNode c2 = IntentNode.group("C2-工商子域", branch6, branch7, branch13, branch5n, branch5);

        // C3 海关子域：认证 / 失信 互斥由分支结构承担（各含两个 mustAny 维度）
        IntentNode branch14 = IntentNode.branch("C3-海关认证",
                new IntentNode.IntentCondition(
                        List.of("海关"), List.of(), List.of(),
                        List.of(List.of("认证", "高级认证", "AEO"))),
                "query_customs_auth");
        IntentNode branch15 = IntentNode.branch("C3-海关失信",
                new IntentNode.IntentCondition(
                        List.of("海关"), List.of(), List.of(),
                        List.of(List.of("失信", "黑名单"))),
                "query_customs_blacklist");
        IntentNode c3 = IntentNode.group("C3-海关子域", branch14, branch15);

        // 暂挂 C 下的独立叶子（暂不并入子域）
        IntentNode branch16 = IntentNode.branch("C-账户冻结标签",
                new IntentNode.IntentCondition(
                        List.of("账户", "账号"), List.of("冻结"), List.of()),
                "query_account_freeze_tag");
        IntentNode branch19 = IntentNode.branch("C-人行账户管控",
                new IntentNode.IntentCondition(
                        List.of("人行", "人民银行", "中国人民银行", "央行", "账管", "账户管理", "账户管控"),
                        List.of(), List.of()),
                "query_pboc_account_control");
        IntentNode branch17 = IntentNode.branch("C-授信信息",
                new IntentNode.IntentCondition(List.of("授信", "授信额度", "综合授信", "授信余额"), List.of(), List.of()),
                "query_credit_granting");
        IntentNode domainC = IntentNode.group("C-法人查询域", c1, c2, c3, branch16, branch19, branch17);

        // ROOT（无匹配 → LLM 完整规则兜底 → chat，branch_0）
        return IntentNode.group("ROOT", domainA, domainB, domainC);
    }
}
