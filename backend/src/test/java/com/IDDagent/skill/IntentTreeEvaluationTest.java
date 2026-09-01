package com.IDDagent.skill;

import com.IDDagent.skill.IntentMatcher.SkillCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 意图识别评估：按 intent_testset.json（204 条）对分层意图树匹配器（三层路由第一层）做确定性直测。
 *
 * 评估方式说明：
 * - 树层可定案场景（唯一候选 / 未开通）直接与 expected 对比，得出确定性层准确率；
 * - 多候选（LLM 仲裁）与空候选（LLM 完整规则兜底）标注为 LLM 依赖场景，统计"树支持率"（候选集覆盖 expected）；
 * - 测试集 metadata.skills_under_test 视为已注册技能集，树中其余叶子（query_risk_history / query_risk_score /
 *   verify_natural_person / verify_contact_info 等）命中时输出"业务暂未开通"chat。
 *
 * 输出：scripts/eval-output/intent-tree-eval.json（机器明细）与 intent-tree-eval.md（人类报告）。
 */
class IntentTreeEvaluationTest {

    private static final String RESOURCE = "/intent_testset.json";
    private static final Path OUT_DIR = Paths.get("..", "scripts", "eval-output");

    private final IntentTreeMatcher matcher = new IntentTreeMatcher(new SkillRegistry());

    /** 结果判定：DIRECT_HIT / DIRECT_MISMATCH / NOT_OPENED_CHAT_HIT / NOT_OPENED_MISMATCH / ARBITRATION_HIT / ARBITRATION_MISS / LLM_FALLBACK / DIRECT_MAY_CLARIFY / ARBITRATION_CAN_CLARIFY */
    private static String judge(String expected, List<SkillCandidate> cands, String verdict, Set<String> registered) {
        List<String> expSkills = parseExpected(expected);
        switch (verdict) {
            case "direct_skill": {
                String skill = cands.get(0).skillName();
                // expected=chat/multi 时，树唯一路由单技能无法输出该决策
                if (expected.equals("chat")) return "DIRECT_MISMATCH";
                // expected=clarify 时：唯一候选 → 参数提取低置信度（<0.6）可转澄清，端到端可达（依赖 LLM 置信度）
                if (expected.equals("clarify")) return "DIRECT_MAY_CLARIFY";
                if (expected.startsWith("multi")) return "DIRECT_MISMATCH";
                return expSkills.contains(skill) ? "DIRECT_HIT" : "DIRECT_MISMATCH";
            }
            case "not_opened":
            case "reserved_not_opened":
                // 未开通业务 → chat 固定文案；仅 expected=chat 时一致；expected=clarify（ADV-003）为已知取舍
                return expected.equals("chat") ? "NOT_OPENED_CHAT_HIT" : "NOT_OPENED_MISMATCH";
            case "arbitration": {
                // expected=clarify：仲裁规则 3 明确支持"无法确定用户意图 → clarify"，端到端可达（依赖 LLM）
                if (expected.equals("clarify")) return "ARBITRATION_CAN_CLARIFY";
                // expected=chat 且候选含 matchable：仲裁 prompt 明确引导"matchable 业务 → chat 未开通提示"，端到端可达（依赖 LLM）
                if (expected.equals("chat")) {
                    boolean hasMatchable = cands.stream().anyMatch(c -> c.skillName() == null);
                    return hasMatchable ? "ARBITRATION_CAN_CHAT" : "ARBITRATION_MISS";
                }
                Set<String> candSkills = cands.stream().map(SkillCandidate::skillName).collect(Collectors.toSet());
                return candSkills.containsAll(expSkills) ? "ARBITRATION_HIT" : "ARBITRATION_MISS";
            }
            default: // llm_fallback：空候选，LLM 完整规则兜底（规则 1 已支持 clarify）理论上可输出任意 expected
                return "LLM_FALLBACK";
        }
    }

    /** expected 解析：multi[a,b,...] → [a,b,...]；单技能 → [技能]；chat/clarify → 空列表 */
    private static List<String> parseExpected(String expected) {
        if (expected.startsWith("multi[")) {
            String inner = expected.substring("multi[".length(), expected.length() - 1);
            return Arrays.stream(inner.split(",")).map(String::trim).toList();
        }
        if (expected.equals("chat") || expected.equals("clarify")) return List.of();
        return List.of(expected);
    }

    /** 树层判定：direct_skill / not_opened(matchable) / reserved_not_opened(预留叶子) / arbitration / llm_fallback */
    private static String treeVerdict(List<SkillCandidate> cands, Set<String> registered) {
        if (cands.isEmpty()) return "llm_fallback";
        if (cands.size() == 1) {
            String skill = cands.get(0).skillName();
            if (skill == null) return "not_opened";
            if (!registered.contains(skill)) return "reserved_not_opened";
            return "direct_skill";
        }
        return "arbitration";
    }

    @Test
    void evaluateIntentTestset() throws Exception {
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        JsonNode root = om.readTree(Objects.requireNonNull(getClass().getResourceAsStream(RESOURCE)));
        Set<String> registered = new HashSet<>();
        root.get("metadata").get("skills_under_test").forEach(n -> registered.add(n.asText()));
        assertEquals(217, root.get("cases").size(), "测试集应含 217 条用例");

        // ---------- 逐条匹配与判定 ----------
        List<Map<String, Object>> rows = new ArrayList<>();
        // 树层匹配耗时观测：单条同步匹配为微秒级（确定性层零延迟的硬数据来源）
        long matchTotalNs = 0;
        long matchMaxNs = 0;
        for (JsonNode c : root.get("cases")) {
            String id = c.get("id").asText();
            String input = c.get("input").asText();
            String expected = c.get("expected").asText();
            String level = c.get("level").asText();
            String category = c.get("category").asText();
            String note = c.has("note") ? c.get("note").asText() : "";

            long t0 = System.nanoTime();
            List<SkillCandidate> cands = matcher.match(input);
            long costNs = System.nanoTime() - t0;
            matchTotalNs += costNs;
            matchMaxNs = Math.max(matchMaxNs, costNs);
            String verdict = treeVerdict(cands, registered);
            String result = judge(expected, cands, verdict, registered);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("input", input);
            row.put("expected", expected);
            row.put("level", level);
            row.put("category", category);
            row.put("tree_verdict", verdict);
            row.put("candidates", cands.stream().map(cd -> cd.skillName() != null ? cd.skillName() : "(matchable)" + cd.label()).toList());
            row.put("matched_keywords", cands.stream().map(cd -> String.join("|", cd.matchedKeywords())).toList());
            row.put("result", result);
            row.put("note", note);
            rows.add(row);
        }

        // ---------- 统计 ----------
        Map<String, Integer> byResult = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> byLevel = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> byCategory = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> byExpected = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String result = (String) r.get("result");
            byResult.merge(result, 1, Integer::sum);
            byLevel.computeIfAbsent((String) r.get("level"), k -> new LinkedHashMap<>()).merge(result, 1, Integer::sum);
            byCategory.computeIfAbsent((String) r.get("category"), k -> new LinkedHashMap<>()).merge(result, 1, Integer::sum);
            byExpected.computeIfAbsent((String) r.get("expected"), k -> new LinkedHashMap<>()).merge(result, 1, Integer::sum);
        }

        int total = rows.size();
        int directHit = count(byResult, "DIRECT_HIT") + count(byResult, "NOT_OPENED_CHAT_HIT");
        int directMiss = count(byResult, "DIRECT_MISMATCH") + count(byResult, "NOT_OPENED_MISMATCH");
        int arbHit = count(byResult, "ARBITRATION_HIT") + count(byResult, "ARBITRATION_CAN_CLARIFY") + count(byResult, "ARBITRATION_CAN_CHAT");
        int arbMiss = count(byResult, "ARBITRATION_MISS");
        int llmFallback = count(byResult, "LLM_FALLBACK");
        int mayClarify = count(byResult, "DIRECT_MAY_CLARIFY");
        // 理论可达（LLM 仲裁/兜底/澄清完美工作）：确定性命中 + 仲裁含 expected + 唯一候选可澄清 + 空候选兜底
        int reachable = directHit + arbHit + llmFallback + mayClarify;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("direct_hit", directHit);
        stats.put("direct_mismatch", directMiss);
        stats.put("arbitration_hit", arbHit);
        stats.put("arbitration_miss", arbMiss);
        stats.put("llm_fallback", llmFallback);
        stats.put("direct_may_clarify", mayClarify);
        stats.put("direct_hit_rate", round(directHit * 100.0 / total));
        stats.put("direct_mismatch_rate", round(directMiss * 100.0 / total));
        stats.put("tree_support_rate", round((directHit + arbHit + llmFallback + mayClarify) * 100.0 / total));
        stats.put("reachable_rate_with_ideal_llm", round(reachable * 100.0 / total));
        stats.put("llm_dependent_cases", arbHit + arbMiss + llmFallback + mayClarify);
        // 树层匹配耗时（微秒）：确定性层零延迟的量化指标
        stats.put("tree_match_total_us", matchTotalNs / 1000);
        stats.put("tree_match_avg_us", matchTotalNs / 1000 / total);
        stats.put("tree_match_max_us", matchMaxNs / 1000);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meta", Map.of("testset", "backend/src/test/resources/intent_testset.json", "total", total,
                "eval_target", "IntentTreeMatcher（确定性层）+ LLM 依赖标注"));
        out.put("stats", stats);
        out.put("by_result", byResult);
        out.put("by_level", byLevel);
        out.put("by_category", byCategory);
        out.put("by_expected", byExpected);
        out.put("cases", rows);

        Files.createDirectories(OUT_DIR);
        Files.writeString(OUT_DIR.resolve("intent-tree-eval.json"), om.writeValueAsString(out));
        Files.writeString(OUT_DIR.resolve("intent-tree-eval.md"), buildMdReport(total, stats, byResult, byLevel, byCategory, rows));

        // 评估为纯输出，无失败断言；仅校验用例数
        assertEquals(total, 217);
    }

    private static int count(Map<String, Integer> m, String k) {
        return m.getOrDefault(k, 0);
    }

    private static String round(double v) {
        return String.format("%.1f%%", v);
    }

    private static String buildMdReport(int total, Map<String, Object> stats, Map<String, Integer> byResult,
                                        Map<String, Map<String, Integer>> byLevel,
                                        Map<String, Map<String, Integer>> byCategory,
                                        List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 意图识别评估报告（分层意图树确定性层）\n\n");
        sb.append("- 测试集：backend/src/test/resources/intent_testset.json（217 条，easy/medium/hard）\n");
        sb.append("- 评估对象：IntentTreeMatcher（三层路由第一层，确定性树匹配）\n");
        sb.append("- 评估方式：树层直测；多候选（LLM 仲裁）、空候选（LLM 完整规则兜底）标注为 LLM 依赖场景\n");
        sb.append("- 说明：后端 .env 未配置 DEEPSEEK_API_KEY，端到端 HTTP 评测（evaluate-intent.mjs）不可行，本次为确定性层直测\n\n");

        sb.append("## 总体指标\n\n");
        sb.append("| 指标 | 数值 |\n|---|---|\n");
        sb.append(String.format("| 总用例 | %d |\n", total));
        sb.append(String.format("| 树层确定性直接命中（唯一候选==expected） | %d（%s） |\n", stats.get("direct_hit"), stats.get("direct_hit_rate")));
        sb.append(String.format("| 树层确定性错误定案（唯一候选≠expected） | %d（%s） |\n", stats.get("direct_mismatch"), stats.get("direct_mismatch_rate")));
        sb.append(String.format("| 多候选仲裁且候选集覆盖 expected | %d |\n", stats.get("arbitration_hit")));
        sb.append(String.format("| 多候选仲裁但候选集不覆盖 expected | %d |\n", stats.get("arbitration_miss")));
        sb.append(String.format("| 空候选 → LLM 完整规则兜底 | %d |\n", stats.get("llm_fallback")));
        sb.append(String.format("| 唯一候选可澄清（expected=clarify，依赖 LLM 低置信度） | %d |\n", stats.get("direct_may_clarify")));
        sb.append(String.format("| 树支持率（确定性命中 + 仲裁覆盖 + LLM 兜底机会） | %s |\n", stats.get("tree_support_rate")));
        sb.append(String.format("| 理论可达命中率（LLM 仲裁/兜底 100%% 正确） | %s |\n", stats.get("reachable_rate_with_ideal_llm")));
        sb.append(String.format("| LLM 依赖用例数 | %d |\n\n", stats.get("llm_dependent_cases")));

        sb.append("## 确定性层耗时\n\n");
        sb.append(String.format("| 指标 | 数值 |\n|---|---|\n"));
        sb.append(String.format("| 单条匹配平均耗时 | %d us（微秒） |\n", stats.get("tree_match_avg_us")));
        sb.append(String.format("| 单条匹配最大耗时 | %d us（微秒） |\n", stats.get("tree_match_max_us")));
        sb.append(String.format("| 217 条总耗时 | %d us（微秒） |\n\n", stats.get("tree_match_total_us")));

        sb.append("## 按结果类别\n\n| 结果 | 含义 | 数量 |\n|---|---|---|\n");
        sb.append("| DIRECT_HIT | 唯一候选技能 == expected，确定性直接命中 | ").append(count(byResult, "DIRECT_HIT")).append(" |\n");
        sb.append("| NOT_OPENED_CHAT_HIT | 命中未开通业务 → chat 固定文案，expected=chat 一致 | ").append(count(byResult, "NOT_OPENED_CHAT_HIT")).append(" |\n");
        sb.append("| DIRECT_MISMATCH | 唯一候选技能 ≠ expected（确定性错误路由） | ").append(count(byResult, "DIRECT_MISMATCH")).append(" |\n");
        sb.append("| NOT_OPENED_MISMATCH | 命中未开通业务，但 expected 为技能/澄清 | ").append(count(byResult, "NOT_OPENED_MISMATCH")).append(" |\n");
        sb.append("| ARBITRATION_HIT | 多候选且 expected 全部在候选集（LLM 仲裁有机会） | ").append(count(byResult, "ARBITRATION_HIT")).append(" |\n");
        sb.append("| ARBITRATION_CAN_CLARIFY | 多候选且 expected=clarify（仲裁规则 3 可输出澄清，依赖 LLM） | ").append(count(byResult, "ARBITRATION_CAN_CLARIFY")).append(" |\n");
        sb.append("| ARBITRATION_CAN_CHAT | 多候选含 matchable 且 expected=chat（仲裁可输出未开通提示，依赖 LLM） | ").append(count(byResult, "ARBITRATION_CAN_CHAT")).append(" |\n");
        sb.append("| ARBITRATION_MISS | 多候选但 expected 不在候选集 | ").append(count(byResult, "ARBITRATION_MISS")).append(" |\n");
        sb.append("| DIRECT_MAY_CLARIFY | 唯一候选且 expected=clarify（参数提取低置信度转澄清，依赖 LLM） | ").append(count(byResult, "DIRECT_MAY_CLARIFY")).append(" |\n");
        sb.append("| LLM_FALLBACK | 树层无候选 → LLM 完整规则兜底（规则 1 已支持 clarify） | ").append(count(byResult, "LLM_FALLBACK")).append(" |\n\n");

        sb.append("## 按 level 分组\n\n| level | DIRECT_HIT | NOT_OPENED_CHAT_HIT | DIRECT_MISMATCH | NOT_OPENED_MISMATCH | ARBITRATION_HIT | ARBITRATION_MISS | LLM_FALLBACK | 小计 |\n|---|---|---|---|---|---|---|---|---|\n");
        appendGroupTable(sb, byLevel);
        sb.append("\n## 按 category 分组\n\n| category | DIRECT_HIT | NOT_OPENED_CHAT_HIT | DIRECT_MISMATCH | NOT_OPENED_MISMATCH | ARBITRATION_HIT | ARBITRATION_MISS | LLM_FALLBACK | 小计 |\n|---|---|---|---|---|---|---|---|---|\n");
        appendGroupTable(sb, byCategory);

        sb.append("\n## 逐条明细\n\n");
        sb.append("| id | input | expected | 树候选 | 树判定 | 结果 | 备注 |\n|---|---|---|---|---|---|---|\n");
        for (Map<String, Object> r : rows) {
            sb.append(String.format("| %s | %s | %s | %s | %s | **%s** | %s |\n",
                    r.get("id"), r.get("input"), r.get("expected"), r.get("candidates"),
                    r.get("tree_verdict"), r.get("result"), r.get("note")));
        }
        return sb.toString();
    }

    private static void appendGroupTable(StringBuilder sb, Map<String, Map<String, Integer>> group) {
        for (Map.Entry<String, Map<String, Integer>> e : group.entrySet()) {
            Map<String, Integer> m = e.getValue();
            sb.append(String.format("| %s | %d | %d | %d | %d | %d | %d | %d | %d |\n",
                    e.getKey(), count(m, "DIRECT_HIT"), count(m, "NOT_OPENED_CHAT_HIT"),
                    count(m, "DIRECT_MISMATCH"), count(m, "NOT_OPENED_MISMATCH"),
                    count(m, "ARBITRATION_HIT"), count(m, "ARBITRATION_MISS"),
                    count(m, "LLM_FALLBACK"), m.values().stream().mapToInt(Integer::intValue).sum()));
        }
    }
}
