package com.IDDagent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 确定性意图匹配器：基于技能元数据（keywords/excludeKeywords/priority/conflictGroup）
 * 实现三层路由的第一层（确定性前置匹配）与冲突仲裁。
 */
@Component
public class IntentMatcher {

    private static final Logger log = LoggerFactory.getLogger(IntentMatcher.class);

    private final SkillRegistry skillRegistry;

    public IntentMatcher(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /**
     * 匹配输入文本，返回候选技能列表（已按 score 降序）。
     * 算法：
     * 1. 遍历注册表中所有声明了元数据的技能；
     * 2. 若输入包含任一 excludeKeywords → 直接否决该技能；
     * 3. 统计命中 keywords 的数量与最长命中词长度，计算 score = 命中词数 * 10 + 最长命中词长度；
     * 4. 命中至少 1 个触发词 → 产生候选。
     */
    public List<SkillCandidate> match(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return List.of();
        }

        List<SkillCandidate> candidates = new ArrayList<>();
        for (Skill skill : skillRegistry.getSkills()) {
            if (!skill.hasMeta()) {
                continue; // 无元数据，不参与确定性匹配
            }

            // 排除词检查：命中任一排除词则否决
            boolean excluded = false;
            for (String excludeKw : skill.getExcludeKeywords()) {
                if (userMessage.contains(excludeKw)) {
                    excluded = true;
                    log.debug("Skill {} excluded by keyword: {}", skill.getName(), excludeKw);
                    break;
                }
            }
            if (excluded) continue;

            // 触发词匹配
            List<String> matchedKeywords = new ArrayList<>();
            int maxLen = 0;
            for (String kw : skill.getKeywords()) {
                if (userMessage.contains(kw)) {
                    matchedKeywords.add(kw);
                    maxLen = Math.max(maxLen, kw.length());
                }
            }

            if (!matchedKeywords.isEmpty()) {
                double score = matchedKeywords.size() * 10.0 + maxLen;
                candidates.add(new SkillCandidate(
                        skill.getName(), skill.getLabel(), skill.getConflictGroup(),
                        skill.getPriority(), matchedKeywords, score));
                log.debug("Skill {} matched: keywords={}, score={}", skill.getName(), matchedKeywords, score);
            }
        }

        // 按 score 降序排列
        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
        return candidates;
    }

    /**
     * 同冲突组内按 priority 保留最高者（平票则全部保留交给 LLM 仲裁）。
     * 跨组候选不互相否决。
     */
    public List<SkillCandidate> resolveConflict(List<SkillCandidate> candidates) {
        if (candidates.size() <= 1) return candidates;

        // 按 conflictGroup 分组
        Map<String, List<SkillCandidate>> groups = new LinkedHashMap<>();
        for (SkillCandidate c : candidates) {
            String group = c.conflictGroup().isEmpty() ? "__no_group_" + c.skillName() : c.conflictGroup();
            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(c);
        }

        List<SkillCandidate> resolved = new ArrayList<>();
        for (Map.Entry<String, List<SkillCandidate>> entry : groups.entrySet()) {
            List<SkillCandidate> groupCandidates = entry.getValue();
            if (groupCandidates.size() == 1) {
                resolved.add(groupCandidates.get(0));
            } else {
                // 同组内按 priority 裁决
                int maxPriority = groupCandidates.stream().mapToInt(SkillCandidate::priority).max().orElse(0);
                List<SkillCandidate> topCandidates = groupCandidates.stream()
                        .filter(c -> c.priority() == maxPriority)
                        .collect(Collectors.toList());
                // 平票保留多个 → 交给 LLM 仲裁
                resolved.addAll(topCandidates);
                if (topCandidates.size() > 1) {
                    log.info("Conflict group '{}' has tie at priority={}, {} candidates for arbitration",
                            entry.getKey(), maxPriority, topCandidates.size());
                }
            }
        }

        // 按 score 降序返回
        resolved.sort((a, b) -> Double.compare(b.score(), a.score()));
        return resolved;
    }

    /**
     * 多意图补检：找出"触发词命中但因排除词被否决"的技能。
     * 唯一候选路径上调用：若存在此类技能（如"查X风险和信息"中 query_company_basic_info
     * 被排除词"风险"否决），说明输入可能混合了多个意图，需并入候选列表交给 LLM 仲裁
     * （仲裁支持 multi 输出），避免零延迟参数提取路径跳过 LLM 导致多意图无法识别。
     *
     * @return 被否决技能列表，无则空列表
     */
    public List<ExcludedSkill> findTriggeredButExcluded(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return List.of();
        }

        List<ExcludedSkill> excluded = new ArrayList<>();
        for (Skill skill : skillRegistry.getSkills()) {
            if (!skill.hasMeta()) {
                continue; // 无元数据，不参与确定性匹配
            }

            // 触发词命中（否则该技能本就与本消息无关）
            List<String> matched = new ArrayList<>();
            for (String kw : skill.getKeywords()) {
                if (userMessage.contains(kw)) {
                    matched.add(kw);
                }
            }
            if (matched.isEmpty()) continue;

            // 排除词命中（未命中排除词则本就是候选，不属于本方法范围）
            List<String> hitExcludes = new ArrayList<>();
            for (String excl : skill.getExcludeKeywords()) {
                if (userMessage.contains(excl)) {
                    hitExcludes.add(excl);
                }
            }
            if (hitExcludes.isEmpty()) continue;

            excluded.add(new ExcludedSkill(skill.getName(), skill.getLabel(), matched, hitExcludes));
        }
        return excluded;
    }

    /**
     * 被排除词否决但触发词命中的技能（多意图补检用）
     */
    public record ExcludedSkill(
            String skillName,
            String label,
            List<String> matchedKeywords,
            List<String> excludedBy
    ) {}

    /**
     * 技能匹配候选
     */
    public record SkillCandidate(
            String skillName,
            String label,
            String conflictGroup,
            int priority,
            List<String> matchedKeywords,
            double score
    ) {}
}
