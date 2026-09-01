package com.IDDagent.skill;

import com.IDDagent.config.AppConfig;
import com.IDDagent.service.CompanyNameExtractor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

@Component
public class RiskCheckSkill {

    private static final Logger log = LoggerFactory.getLogger(RiskCheckSkill.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String RISK_FILE = "data-template/risk_check.json";
    private static final String NAME_INDEX_FILE = "data-template/company_name_index.json";
    private static final int MIN_AUTO_MATCH_SCORE = 80;
    private static final int MAX_SUGGESTIONS = 3;
    /** _user_input 清洗用技能动词/查询后缀（供 CompanyNameExtractor 统一清洗链） */
    private static final String RISK_VERBS = "风险预查|风险筛查|风险识别|预查|筛查|查询|查一下|查|看看";
    private static final String RISK_SUFFIXES = "的风险情况|的风险信息|的风险|风险情况|风险信息|风险|情况|信息";

    private final SkillRegistry registry;
    private final WebClient webClient;
    private final AppConfig config;

    public RiskCheckSkill(SkillRegistry registry, WebClient webClient, AppConfig config) {
        this.registry = registry;
        this.webClient = webClient;
        this.config = config;
    }

    @PostConstruct
    public void init() {
        registry.register(new Skill(
                "check_company_risk",
                "当用户进行风险预查、企业风险筛查、" +
                        "查询xx企业风险报告、风险预检时调用此技能。" +
                        "根据企业统一信用代码或企业名称查询风险信息，返回风险结论和详细风险报告链接。",
                this::handle,
                Map.of(
                        "credit_code", new Skill.SkillParam("string", "企业统一信用代码，18位数字+字母", false, "91110108MA01B3XK2P"),
                        "company_name", new Skill.SkillParam("string", "企业名称，用于模糊匹配", false, "北京星河科技有限公司")
                )
        ).withMeta("企业风险预查",
                List.of("风险预查", "风险筛查", "风险识别", "企业风险", "风险报告", "风险预检", "风险"),
                List.of("得分", "评分", "评价", "打分"),
                "法人", 50, "risk"));
    }

    private Map<String, Object> handle(String userId, Map<String, Object> params) {
        String creditCode = ((String) params.getOrDefault("credit_code", "")).trim();
        String companyName = ((String) params.getOrDefault("company_name", "")).trim();

        // 多轮交互：当 _user_input 携带信用代码时（前端 CompanyNameSelector 卡片点击），
        // 优先从用户输入中提取信用代码，防止因 pendingSkillParams 中的旧 company_name
        // 再次触发 fuzzyMatch → candidates → 选择卡片 → 无限循环；
        // 无信用代码时以公共清洗链提取的企业名覆盖旧 companyName（旧值作废）
        String userInput = ((String) params.getOrDefault("_user_input", "")).trim();
        if (!userInput.isEmpty()) {
            String extractedCode = CompanyNameExtractor.extractCreditCode(userInput);
            if (!extractedCode.isEmpty()) {
                creditCode = extractedCode;
            } else {
                String cleaned = CompanyNameExtractor.extractCompanyName(userInput, RISK_VERBS, RISK_SUFFIXES, null);
                if (cleaned != null) {
                    companyName = cleaned;
                }
            }
        }

        Map<String, Object> riskData = DataLoader.loadJson(RISK_FILE);

        if (!creditCode.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) riskData.get(creditCode);
            if (result != null) {
                return buildResult(result);
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "not_found");
            resp.put("message", "未查询到统一信用代码为 " + creditCode + " 的企业风险信息，请核实代码是否正确。");
            return resp;
        }

        if (!companyName.isEmpty()) {
            Map<String, String> nameIndex = loadNameIndex();
            Map<String, Object> resolved = resolveCompanyMatch(companyName, nameIndex);

            if (resolved.containsKey("credit_code_without_action")) {
                return handle(userId, Map.of("credit_code", resolved.get("credit_code_without_action")));
            }

            if (resolved.containsKey("credit_code")) {
                // Handled internally by resolveCompanyMatch
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) riskData.get(resolved.get("credit_code"));
                if (result != null) {
                    return buildResult(result);
                }
                // 名称匹配成功但 risk_check.json 中无该企业风险数据（如索引中的混淆/无数据企业）：
                // 区分"未收录"与"名称有误"，避免把存在企业误报为"未找到"
                Map<String, Object> resp = new HashMap<>();
                resp.put("action", "not_found");
                String matchedName = nameIndex.get(resolved.get("credit_code"));
                resp.put("message", matchedName != null
                        ? "已找到「" + matchedName + "」，但当前风险数据库中暂无该企业的风险数据，后续将陆续补充。"
                        : "未找到「" + companyName + "」的风险数据，请确认企业名称是否正确。");
                return resp;
            }

            return resolved;
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("action", "info_needed");
        resp.put("message", "请提供企业名称或统一信用代码进行查询。");
        return resp;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildResult(Map<String, Object> data) {
        String baseUrl = DataLoader.buildBaseUrl();
        String code = (String) data.get("credit_code");
        // 模板数据用 enterprise_name，统一映射为 company_name
        String companyName = (String) data.getOrDefault("company_name", data.get("enterprise_name"));

        Map<String, Object> result = new HashMap<>();
        result.put("action", "result");
        result.put("credit_code", code);
        result.put("company_name", companyName);
        // 风险摘要由大模型根据报告 details 内容摘要生成，替代模板固定文案
        // （不再返回 risk_level/has_risk，结论口径统一由摘要文本表达；调用失败时回退模板原文）
        result.put("risk_summary", summarizeRiskSummary(data));
        result.put("h5_url", baseUrl + "/h5/risk-report.html?code=" + code);
        return result;
    }

    /**
     * 利用大模型对风险报告 details 内容进行摘要总结，生成风险预查结论文案。
     * - 输入：各维度（工商信息/反洗钱/融安E信）的结构化核查项
     * - 输出：2-3 句客观精炼的风险摘要（列出主要风险点及严重程度，不输出风险等级标签）
     * - 失败回退：未配置 API Key 或调用/解析异常时回退模板原始 risk_summary，保证卡片始终有内容
     */
    private String summarizeRiskSummary(Map<String, Object> data) {
        String apiKey = config.getDeepseek().getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("DEEPSEEK_API_KEY not set, risk summary falls back to raw text");
            return (String) data.getOrDefault("risk_summary", "暂未发现风险点");
        }
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", config.getModel().getName());
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content",
                            "你是一位银行风控合规专家。根据客户风险核查的结构化结果，客观、精炼地总结该客户的风险状况：" +
                            "列出主要风险点（命中项）及其严重程度，不要输出“高风险/中风险/低风险”等风险等级标签。" +
                            "直接输出 2-3 句总结文本，不要输出 JSON、标题或任何多余格式。"),
                    Map.of("role", "user", "content", buildSummaryPrompt(data))
            ));
            requestBody.put("temperature", 0.3);
            requestBody.put("max_tokens", 500);
            requestBody.put("thinking", Map.of("type", "disabled"));

            String response = webClient.post()
                    .uri(config.getDeepseek().getBaseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp ->
                            resp.bodyToMono(String.class).flatMap(body -> {
                                log.error("风险摘要 LLM 调用失败: status={}, body={}", resp.statusCode(), body);
                                return Mono.error(new RuntimeException("LLM API error: " + resp.statusCode()));
                            }))
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            String summary = parseSummaryResponse(response);
            if (summary != null && !summary.isBlank()) {
                log.info("风险摘要生成成功: {}", summary);
                return summary;
            }
        } catch (Exception e) {
            log.error("风险摘要生成失败，回退模板原文: {}", e.getMessage());
        }
        return (String) data.getOrDefault("risk_summary", "暂未发现风险点");
    }

    /**
     * 将报告 details 各维度核查项结构化为供 LLM 摘要的文本输入。
     */
    @SuppressWarnings("unchecked")
    private String buildSummaryPrompt(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("企业名称：").append(data.getOrDefault("company_name", data.get("enterprise_name"))).append("\n");
        sb.append("统一信用代码：").append(data.getOrDefault("credit_code", "")).append("\n");
        sb.append("以下为该客户各维度风险核查结果：\n");
        Object detailsObj = data.get("details");
        if (detailsObj instanceof Map) {
            Map<String, Object> details = (Map<String, Object>) detailsObj;
            for (var entry : details.entrySet()) {
                if (!(entry.getValue() instanceof Map)) continue;
                Map<String, Object> module = (Map<String, Object>) entry.getValue();
                sb.append("\n【").append(module.getOrDefault("name", entry.getKey())).append("】\n");
                Object itemsObj = module.get("items");
                if (!(itemsObj instanceof List)) continue;
                for (Object itemObj : (List<?>) itemsObj) {
                    if (!(itemObj instanceof Map)) continue;
                    Map<String, Object> item = (Map<String, Object>) itemObj;
                    Object result = item.getOrDefault("result", item.getOrDefault("riskLevel", "—"));
                    sb.append("- ").append(item.getOrDefault("name", "")).append("：").append(result);
                    Object detail = item.get("detail");
                    if (detail != null && !detail.toString().isBlank()) {
                        sb.append("（").append(detail).append("）");
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 解析 LLM 摘要响应，提取 choices[0].message.content 纯文本。
     */
    @SuppressWarnings("unchecked")
    private String parseSummaryResponse(String response) {
        if (response == null || response.isBlank()) return "";
        try {
            Map<String, Object> respMap = mapper.readValue(response, new TypeReference<>() {});
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respMap.get("choices");
            if (choices == null || choices.isEmpty()) return "";
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) return "";
            Object content = message.get("content");
            return content == null ? "" : content.toString().trim();
        } catch (Exception e) {
            log.warn("风险摘要响应解析失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 根据 details 中的各项指标计算风险等级。
     * - aml 中 has_risk=true 且 result 为 "异常"/"命中"/"严重" 计为高风险项
     * - rongan 中 riskLevel="high" 计为高风险项
     * - aml 中 has_risk=true 且 result 为 "关注" 计为中风险项
     * - rongan 中 riskLevel="medium" 计为中风险项
     * - business_info 中 result="命中" 计为中风险项
     * 高风险项 >=1 → high；中风险项 >=2 → medium；否则 → low
     */
    @SuppressWarnings("unchecked")
    public static String computeRiskLevel(Map<String, Object> data) {
        Map<String, Object> details = (Map<String, Object>) data.get("details");
        if (details == null) return "low";

        int highCount = 0;
        int mediumCount = 0;

        // 反洗钱
        Map<String, Object> aml = (Map<String, Object>) details.get("aml");
        if (aml != null) {
            List<Map<String, Object>> items = (List<Map<String, Object>>) aml.get("items");
            if (items != null) {
                for (Map<String, Object> item : items) {
                    Boolean hasRisk = (Boolean) item.get("has_risk");
                    if (Boolean.TRUE.equals(hasRisk)) {
                        String result = (String) item.get("result");
                        if ("异常".equals(result) || "命中".equals(result) || "严重".equals(result)) {
                            highCount++;
                        } else {
                            mediumCount++;
                        }
                    }
                }
            }
        }

        // 融安E信
        Map<String, Object> rongan = (Map<String, Object>) details.get("rongan");
        if (rongan != null) {
            List<Map<String, Object>> items = (List<Map<String, Object>>) rongan.get("items");
            if (items != null) {
                for (Map<String, Object> item : items) {
                    String riskLevel = (String) item.get("riskLevel");
                    if (riskLevel == null) riskLevel = (String) item.get("risklevel");
                    if ("high".equalsIgnoreCase(riskLevel)) {
                        highCount++;
                    } else if ("medium".equalsIgnoreCase(riskLevel)) {
                        mediumCount++;
                    }
                }
            }
        }

        // 工商信息 — 只有特定指标的"命中"才算风险
        Map<String, Object> businessInfo = (Map<String, Object>) details.get("business_info");
        if (businessInfo != null) {
            List<Map<String, Object>> items = (List<Map<String, Object>>) businessInfo.get("items");
            if (items != null) {
                for (Map<String, Object> item : items) {
                    String result = (String) item.get("result");
                    String name = (String) item.get("name");
                    if ("命中".equals(result) && name != null &&
                            (name.contains("一人多企") || name.contains("异常经营") || name.contains("空壳"))) {
                        mediumCount++;
                    }
                }
            }
        }

        if (highCount >= 1) return "high";
        if (mediumCount >= 2) return "medium";
        return "low";
    }

    private static final Map<String, String> RONGAN_STATUS_MAP = Map.of(
            "PENDING", "待处理",
            "MONITORING", "监控中",
            "RESOLVED", "已解决",
            "CLEAR", "正常"
    );
    private static final Map<String, String> RONGAN_LEVEL_MAP = Map.of(
            "high", "高风险",
            "medium", "中风险",
            "low", "低风险"
    );

    /**
     * 将 data-template/risk_check.json 的原始数据标准化为 H5 页面所需格式。
     * - enterprise_name → company_name
     * - 计算 risk_level / has_risk
     * - rongan items: riskLevel/detectDate/status → result/has_risk/detail
     * - business_info items: 缺失 has_risk 时根据 result 补全
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalizeForH5(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>(raw);

        // 企业名称统一
        if (!result.containsKey("company_name") && result.containsKey("enterprise_name")) {
            result.put("company_name", result.get("enterprise_name"));
        }

        // 计算风险等级
        String riskLevel = computeRiskLevel(raw);
        result.put("risk_level", riskLevel);
        result.put("has_risk", !"low".equals(riskLevel));

        // 标准化 details 中各模块的 items
        Map<String, Object> details = (Map<String, Object>) result.get("details");
        if (details != null) {
            for (var entry : details.entrySet()) {
                if (!(entry.getValue() instanceof Map)) continue;
                Map<String, Object> module = (Map<String, Object>) entry.getValue();
                Object itemsObj = module.get("items");
                if (!(itemsObj instanceof List)) continue;
                List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
                List<Map<String, Object>> normalizedItems = new ArrayList<>();
                for (Map<String, Object> item : items) {
                    Map<String, Object> ni = new LinkedHashMap<>(item);
                    // rongan 模块：将 riskLevel/detectDate/status 转为 result/has_risk/detail
                    if ("rongan".equals(entry.getKey())) {
                        String rl = (String) ni.getOrDefault("riskLevel", ni.get("risklevel"));
                        String status = (String) ni.get("status");
                        String detectDate = (String) ni.get("detectDate");
                        ni.put("result", RONGAN_LEVEL_MAP.getOrDefault(rl != null ? rl.toLowerCase() : "", rl != null ? rl : "未知"));
                        ni.put("has_risk", "high".equalsIgnoreCase(rl) || "medium".equalsIgnoreCase(rl));
                        StringBuilder detail = new StringBuilder();
                        if (detectDate != null) detail.append("检测日期: ").append(detectDate);
                        if (status != null) {
                            if (detail.length() > 0) detail.append("  |  ");
                            detail.append("状态: ").append(RONGAN_STATUS_MAP.getOrDefault(status, status));
                        }
                        ni.put("detail", detail.toString());
                    }
                    // business_info 模块：补全缺失的 has_risk（仅特定指标的"命中"算风险）
                    if ("business_info".equals(entry.getKey())) {
                        if (!ni.containsKey("has_risk")) {
                            String r = (String) ni.get("result");
                            String nm = (String) ni.get("name");
                            ni.put("has_risk", "命中".equals(r) && nm != null &&
                                    (nm.contains("一人多企") || nm.contains("异常经营") || nm.contains("空壳")));
                        }
                    }
                    normalizedItems.add(ni);
                }
                module.put("items", normalizedItems);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadNameIndex() {
        Map<String, Object> data = DataLoader.loadJson(NAME_INDEX_FILE);
        return (Map<String, String>) (Map<?, ?>) data;
    }

    // Package-private for use by other skills
    static Map<String, Object> resolveCompanyMatch(String query, Map<String, String> nameIndex) {
        List<Map<String, Object>> matches = fuzzyMatchCompany(query, nameIndex);

        if (matches.isEmpty()) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "not_found");
            resp.put("message", "未找到与「" + query + "」匹配的企业。请检查企业名称是否正确（可尝试更简短的关键词），" +
                    "或提供统一信用代码查询；若名称无误，该企业可能尚未收录于当前数据库。");
            return resp;
        }

        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < Math.min(matches.size(), MAX_SUGGESTIONS); i++) {
            Map<String, Object> m = matches.get(i);
            Map<String, Object> opt = new HashMap<>();
            opt.put("credit_code", m.get("credit_code"));
            opt.put("company_name", m.get("company_name"));
            options.add(opt);
        }

        if (matches.size() == 1) {
            int score = ((Number) matches.get(0).get("_score")).intValue();
            if (score >= MIN_AUTO_MATCH_SCORE) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("credit_code", matches.get(0).get("credit_code"));
                return resp;
            }
            // 单个相似候选但分数未达自动确认阈值：仍走候选确认（ambiguous）而非 not_found——
            // 只要存在模糊匹配候选就不应显示"未找到企业"，让用户确认或点"以上都不是"
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "ambiguous");
            resp.put("keyword", query);
            resp.put("options", options);
            resp.put("message", "搜索到 " + matches.size() + " 家与「" + query + "」匹配的企业，请确认要查询哪一家：");
            return resp;
        }

        int bestScore = ((Number) matches.get(0).get("_score")).intValue();
        int secondScore = matches.size() > 1 ? ((Number) matches.get(1).get("_score")).intValue() : 0;

        if (bestScore >= 95 && secondScore < MIN_AUTO_MATCH_SCORE) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("credit_code", matches.get(0).get("credit_code"));
            return resp;
        }

        // 存在相似候选但分数未达自动确认阈值：统一走候选确认（ambiguous）。
        // not_found 仅保留"无任何匹配"场景（matches 为空），保证"未找到企业"与候选不会同时出现
        Map<String, Object> resp = new HashMap<>();
        resp.put("action", "ambiguous");
        resp.put("keyword", query);
        resp.put("options", options);
        resp.put("message", "搜索到 " + matches.size() + " 家与「" + query + "」匹配的企业，请确认要查询哪一家：");
        return resp;
    }

    static List<Map<String, Object>> fuzzyMatchCompany(String query, Map<String, String> nameIndex) {
        if (query == null || query.isEmpty()) return List.of();

        // nameIndex mapping: credit_code → company_name
        // Build reverse index: company_name → credit_code (for lookup)
        Map<String, String> reverseIndex = new HashMap<>();
        for (var entry : nameIndex.entrySet()) {
            reverseIndex.put(entry.getValue(), entry.getKey());
        }

        // 1. Exact match (by company name)
        if (reverseIndex.containsKey(query)) {
            Map<String, Object> match = new HashMap<>();
            match.put("credit_code", reverseIndex.get(query));
            match.put("company_name", query);
            match.put("_score", 100);
            return List.of(match);
        }

        Map<String, Map<String, Object>> results = new LinkedHashMap<>();

        // 2. Multi-keyword AND matching
        String normalized = query.replace('　', ' ').replaceAll("  +", " ").trim();
        if (normalized.contains(" ")) {
            String[] keywords = normalized.split(" ");
            for (var entry : reverseIndex.entrySet()) {
                String name = entry.getKey();
                boolean allMatch = true;
                for (String kw : keywords) {
                    if (!name.contains(kw)) { allMatch = false; break; }
                }
                if (allMatch) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("credit_code", entry.getValue());
                    m.put("company_name", name);
                    m.put("_score", 80);
                    results.put(entry.getValue(), m);
                }
            }
            if (!results.isEmpty()) {
                return sortByScore(results);
            }
        }

        // 3. Character-level subsequence matching
        String cleanQuery = query.replace("有限公司", "").replace("有限责任", "")
                .replace("股份", "").replace("集团", "").replace("公司", "");
        for (var entry : reverseIndex.entrySet()) {
            String name = entry.getKey();
            String cleanName = name.replace("有限公司", "").replace("有限责任", "")
                    .replace("股份", "").replace("集团", "").replace("公司", "");
            if (cleanQuery.isEmpty() || isSubsequence(cleanQuery, cleanName)) {
                if (cleanQuery.isEmpty()) continue;
                int score;
                if (cleanQuery.equals(cleanName)) {
                    score = 95;
                } else {
                    double density = (double) query.length() / name.length();
                    score = 60 + (int) (density * 30);
                    // 简称高置信加分：核心名子序列全命中且查询覆盖企业名核心 40% 以上时视为高置信简称
                    // （如"云栖大数据"→"杭州云栖大数据技术有限公司"，否则密度公式对简称偏低无法自动匹配）
                    if (cleanQuery.length() >= cleanName.length() * 0.4) {
                        score = Math.max(score, 85);
                    }
                }
                Map<String, Object> m = new HashMap<>();
                m.put("credit_code", entry.getValue());
                m.put("company_name", name);
                m.put("_score", score);
                results.putIfAbsent(entry.getValue(), m);
            }
        }

        // 4. Simple substring match
        if (results.isEmpty()) {
            for (var entry : reverseIndex.entrySet()) {
                if (entry.getKey().contains(query)) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("credit_code", entry.getValue());
                    m.put("company_name", entry.getKey());
                    m.put("_score", 40);
                    results.put(entry.getValue(), m);
                }
            }
        }

        // 5. 宽松窗口兜底：cleanQuery 的连续子串（最长优先）包含匹配，仅作候选不做自动匹配
        //    （如"云禾科技"误输入为"云禾科支"时，窗口"云禾科"仍可命中候选供用户确认）
        if (results.isEmpty()) {
            for (int winLen = cleanQuery.length() - 1; winLen >= 2; winLen--) {
                for (int i = 0; i + winLen <= cleanQuery.length(); i++) {
                    String sub = cleanQuery.substring(i, i + winLen);
                    for (var entry : reverseIndex.entrySet()) {
                        if (entry.getKey().contains(sub)) {
                            Map<String, Object> m = new HashMap<>();
                            m.put("credit_code", entry.getValue());
                            m.put("company_name", entry.getKey());
                            m.put("_score", 45);
                            results.putIfAbsent(entry.getValue(), m);
                        }
                    }
                }
                if (!results.isEmpty()) break;
            }
        }

        return sortByScore(results);
    }

    private static boolean isSubsequence(String query, String target) {
        int qi = 0;
        for (int i = 0; i < target.length() && qi < query.length(); i++) {
            if (target.charAt(i) == query.charAt(qi)) qi++;
        }
        return qi == query.length();
    }

    private static List<Map<String, Object>> sortByScore(Map<String, Map<String, Object>> results) {
        List<Map<String, Object>> list = new ArrayList<>(results.values());
        list.sort((a, b) -> Integer.compare(
                ((Number) b.get("_score")).intValue(),
                ((Number) a.get("_score")).intValue()));
        return list;
    }
}
