package com.IDDagent.skill;

import com.IDDagent.skill.IntentMatcher.SkillCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 分层意图树匹配器：DFS 遍历 {@link IntentTree}，收集全部命中叶子（三层路由第一层）。
 * 对应 docs/分层意图树设计方案.md 3.3 匹配算法。
 *
 * 算法特征：
 * - 条件不满足即剪枝（mustNot 否决 → mustAll 全中 → mustAny 任一命中）；
 * - defaultLeaf 在同父兄弟普通节点均未命中时激活（C1 最新风险 / C2 基本信息兜底）；
 * - 跨子域并存：不同父节点下的叶子可同时命中（如 C2 default 与 C3 海关），交 LLM 仲裁；
 * - 返回结构复用 {@link IntentMatcher.SkillCandidate}；matchable 预留节点命中时
 *   skillName 为 null（label 取节点名），由上层识别为"业务未开通"候选。
 */
@Component
public class IntentTreeMatcher {

    private static final Logger log = LoggerFactory.getLogger(IntentTreeMatcher.class);

    private final IntentNode root;
    private final SkillRegistry skillRegistry;

    public IntentTreeMatcher(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        this.root = IntentTree.build();
    }

    /**
     * 匹配输入文本，返回全部命中叶子（按 score 降序；score 仅用于排序展示，路由不依赖）。
     */
    public List<SkillCandidate> match(String userMessage) {
        List<SkillCandidate> leaves = new ArrayList<>();
        if (userMessage == null || userMessage.isBlank()) {
            return leaves;
        }
        dfs(root, userMessage, leaves);
        leaves.sort((a, b) -> Double.compare(b.score(), a.score()));
        // 同技能多叶子命中（如 C2 branch_5n 名词对象 + branch_5 行为词兜底）只保留 score 最高一条，
        // 避免同技能双候选进入仲裁；matchable 预留候选（skillName=null）不去重——不同预留业务互不相同
        List<SkillCandidate> deduped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SkillCandidate c : leaves) {
            if (c.skillName() != null && !seen.add(c.skillName())) {
                continue;
            }
            deduped.add(c);
        }
        return deduped;
    }

    /**
     * 深度优先：条件不满足剪枝；收集全部命中叶子；defaultLeaf 在兄弟普通节点全未命中时激活。
     */
    private void dfs(IntentNode node, String text, List<SkillCandidate> leaves) {
        if (node.getCondition() != null && !node.getCondition().matches(text)) return;
        if (node.isLeaf()) {
            leaves.add(toCandidate(node, text));
            return;
        }
        List<IntentNode> normal = node.getChildren().stream().filter(n -> !n.isDefaultLeaf()).toList();
        List<IntentNode> defaults = node.getChildren().stream().filter(IntentNode::isDefaultLeaf).toList();
        int before = leaves.size();
        for (IntentNode child : normal) {
            dfs(child, text, leaves);
        }
        // 兄弟普通节点一个都没命中时，才激活 default 子节点
        if (leaves.size() == before) {
            for (IntentNode d : defaults) {
                dfs(d, text, leaves);
            }
        }
    }

    /** 叶子 → SkillCandidate：matchable 预留节点 skillName 为 null，label 取节点名 */
    private SkillCandidate toCandidate(IntentNode node, String text) {
        List<String> matched = new ArrayList<>();
        IntentNode.IntentCondition cond = node.getCondition();
        if (cond != null) {
            collectMatched(cond.mustAny(), text, matched);
            collectMatched(cond.mustAll(), text, matched);
            if (cond.extraMustAnyGroups() != null) {
                for (List<String> group : cond.extraMustAnyGroups()) {
                    collectMatched(group, text, matched);
                }
            }
        }
        String skillName = node.getSkillName();
        String label = (skillName != null) ? skillRegistry.getSkillLabel(skillName) : node.getName();
        int maxLen = matched.stream().mapToInt(String::length).max().orElse(0);
        double score = matched.size() * 10.0 + maxLen;
        return new SkillCandidate(skillName, label, "", 0, matched, score);
    }

    private void collectMatched(List<String> keywords, String text, List<String> matched) {
        if (keywords == null) return;
        for (String kw : keywords) {
            if (text.contains(kw)) {
                matched.add(kw);
            }
        }
    }
}
