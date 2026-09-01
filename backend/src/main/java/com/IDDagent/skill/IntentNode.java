package com.IDDagent.skill;

import java.util.Arrays;
import java.util.List;

/**
 * 意图树节点：条件满足即进入该节点；叶子节点绑定技能名。
 * 对应 docs/分层意图树设计方案.md 3.1 节点模型。
 */
public class IntentNode {
    private final String name;                 // 节点名，如 "法人查询-风险-历史"
    private final IntentCondition condition;   // 进入条件（null = 无条件）
    private final boolean defaultLeaf;         // 兜底叶子：同父兄弟均未命中时激活
    private final boolean matchable;           // 可命中：预留节点不绑定技能（skillName=null），
                                               // 命中后由上层返回"该业务暂未开通"提示
    private final String skillName;            // 叶子绑定的技能名（matchable 预留节点为 null）
    private final String businessType;         // 扩展参数（尽调业务类型等，可为 null）
    private final List<IntentNode> children;   // 子节点（有序，匹配时全部检查）

    private IntentNode(String name, IntentCondition condition, boolean defaultLeaf,
                       boolean matchable, String skillName, String businessType,
                       List<IntentNode> children) {
        this.name = name;
        this.condition = condition;
        this.defaultLeaf = defaultLeaf;
        this.matchable = matchable;
        this.skillName = skillName;
        this.businessType = businessType;
        this.children = children;
    }

    /** 普通叶子：绑定技能名 */
    public static IntentNode branch(String name, IntentCondition condition, String skillName) {
        return new IntentNode(name, condition, false, false, skillName, null, List.of());
    }

    /** 兜底叶子：同父兄弟均未命中时激活（如 C1 最新风险、C2 基本信息） */
    public static IntentNode defaultBranch(String name, IntentCondition condition, String skillName) {
        return new IntentNode(name, condition, true, false, skillName, null, List.of());
    }

    /** matchable 预留叶子：可正常命中但不绑定技能（skillName=null），命中后返回"该业务暂未开通" */
    public static IntentNode matchableBranch(String name, IntentCondition condition, String businessType) {
        return new IntentNode(name, condition, false, true, null, businessType, List.of());
    }

    /** 组织分组节点：不带条件，仅作组织分组（A/B/C 域不设匹配门槛） */
    public static IntentNode group(String name, IntentNode... children) {
        return new IntentNode(name, null, false, false, null, null, Arrays.asList(children));
    }

    /** 条件节点：带进入条件的分组（如 C1 风险子域） */
    public static IntentNode withCondition(String name, IntentCondition condition, IntentNode... children) {
        return new IntentNode(name, condition, false, false, null, null, Arrays.asList(children));
    }

    public boolean isLeaf() { return skillName != null || matchable; }

    public String getName() { return name; }
    public IntentCondition getCondition() { return condition; }
    public boolean isDefaultLeaf() { return defaultLeaf; }
    public boolean isMatchable() { return matchable; }
    public String getSkillName() { return skillName; }
    public String getBusinessType() { return businessType; }
    public List<IntentNode> getChildren() { return children; }

    @Override
    public String toString() {
        return "IntentNode(" + name + (isLeaf() ? ", skill=" + skillName : ", children=" + children.size()) + ")";
    }

    /**
     * 条件组语义：
     * - mustNot  命中任一 → 否决该节点（剪枝）
     * - mustAll  必须全部命中
     * - mustAny  命中任一即可；空列表 = 本维度宽松（不设限制，如查询行为词）
     * - extraMustAnyGroups 附加 mustAny 维度：与 mustAny 为 AND 叠加、组内 OR。
     *   文档 3.2 中 "+" 连接多个 mustAny（如 branch_1 角色∧身份∧行为、branch_14 海关∧认证），
     *   单 record 无法用一份 mustAny 列表表达，故拆为多组，matches 时逐组判定。
     */
    public record IntentCondition(
            List<String> mustAny,
            List<String> mustAll,
            List<String> mustNot,
            List<List<String>> extraMustAnyGroups) {

        public IntentCondition(List<String> mustAny, List<String> mustAll, List<String> mustNot) {
            this(mustAny, mustAll, mustNot, List.of());
        }

        public boolean matches(String text) {
            if (mustNot != null && mustNot.stream().anyMatch(text::contains)) return false;
            if (mustAll != null && !mustAll.stream().allMatch(text::contains)) return false;
            if (mustAny != null && !mustAny.isEmpty() && mustAny.stream().noneMatch(text::contains)) return false;
            if (extraMustAnyGroups != null) {
                for (List<String> group : extraMustAnyGroups) {
                    if (!group.isEmpty() && group.stream().noneMatch(text::contains)) return false;
                }
            }
            return true;
        }
    }
}
