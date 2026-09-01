package com.IDDagent.service;

import com.IDDagent.config.AppConfig;
import com.IDDagent.model.Message;
import com.IDDagent.skill.IntentMatcher;
import com.IDDagent.skill.IntentMatcher.SkillCandidate;
import com.IDDagent.skill.IntentTree;
import com.IDDagent.skill.IntentTreeMatcher;
import com.IDDagent.skill.SkillRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern JSON_PATTERN = Pattern.compile("\\{[\\s\\S]*\\}");
    private static final double CLARIFY_CONFIDENCE_THRESHOLD = 0.6;
    /** 级联升级阈值：light 模型 skill 置信度低于此值，升级主模型完整规则兜底重判 */
    private static final double ESCALATE_CONFIDENCE_THRESHOLD = 0.6;

    private final SkillRegistry skillRegistry;
    private final IntentTreeMatcher intentTreeMatcher;
    private final WebClient webClient;
    private final AppConfig config;
    private final ContextMemoryService contextMemoryService;

    public CoordinatorService(SkillRegistry skillRegistry, IntentTreeMatcher intentTreeMatcher,
                              WebClient webClient, AppConfig config,
                              ContextMemoryService contextMemoryService) {
        this.skillRegistry = skillRegistry;
        this.intentTreeMatcher = intentTreeMatcher;
        this.webClient = webClient;
        this.config = config;
        this.contextMemoryService = contextMemoryService;
    }

    /**
     * 三层路由入口（非阻塞响应式）
     * ① IntentTreeMatcher 分层意图树确定性前置匹配 → ② LLM 仲裁 / ③ LLM 完整规则兜底
     */
    public Mono<Map<String, Object>> routeIntent(String userMessage, List<Message> history,
                                                  String conversationId) {
        String apiKey = config.getDeepseek().getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("DEEPSEEK_API_KEY not set, defaulting to chat");
            return Mono.just(fallbackMap("API key not configured", false));
        }

        // 路由耗时观测（方案 B）：树层匹配为同步微秒级，LLM 段（仲裁/兜底/参数提取）为秒级主耗时；
        // 各出口 doOnNext 统一输出 ROUTE_ELAPSED，stage 标识路由路径便于按路径聚合分析
        long routeStart = System.nanoTime();
        long treeStart = routeStart;
        // ① 确定性前置匹配（分层意图树：域分组 + 叶子条件，defaultLeaf 兜底，matchable 预留）
        List<SkillCandidate> candidates = intentTreeMatcher.match(userMessage);
        long treeElapsedMs = (System.nanoTime() - treeStart) / 1_000_000;
        log.info("IntentTreeMatcher candidates for '{}': {}", userMessage, candidates);

        if (candidates.isEmpty()) {
            // ③ 无候选 → LLM 完整规则兜底（含多意图检测）
            return callLLM(buildFullRulePrompt(userMessage, history), userMessage, history)
                    .doOnNext(d -> logRouteElapsed("llm-fallback", userMessage, treeElapsedMs, routeStart));
        }

        // 核实对象不明候选（verify_ambiguous，B 域无对象核实兜底叶子）：generic verify 不硬映射
        // 任何具体核实技能，一律返回携带候选卡片（营业执照核实/身份证核实）的 clarify 由用户选择；
        // 与风险/历史等其他意图并存时同样先澄清（v1.2 语义：核实部分无法路由，如"核实信息并做风险识别"）
        if (candidates.stream().anyMatch(c -> IntentTree.VERIFY_AMBIGUOUS.equals(c.skillName()))) {
            // 例外：核实对象为风险（"帮我核实一下小米科技的风险"，口语语义=查风险）→ 移除
            // verify_ambiguous 直接路由 risk；仅当核实与风险为并列结构（中间含并/和等连接词）
            // 才保持澄清（如"信息核实并做风险识别"）
            if (isRiskAsVerifyObject(userMessage, candidates)) {
                candidates = candidates.stream()
                        .filter(c -> !IntentTree.VERIFY_AMBIGUOUS.equals(c.skillName()))
                        .toList();
                log.info("Verify object is risk, dropping verify_ambiguous: {}", userMessage);
            }
        }
        if (candidates.stream().anyMatch(c -> IntentTree.VERIFY_AMBIGUOUS.equals(c.skillName()))) {
            log.info("Ambiguous verify intent hit, returning verify clarify candidates");
            return Mono.just(buildVerifyClarifyDecision())
                    .doOnNext(d -> logRouteElapsed("tree-clarify", userMessage, treeElapsedMs, routeStart));
        }

        if (candidates.size() == 1) {
            SkillCandidate c = candidates.get(0);
            // matchable 预留节点（skillName=null，如 branch_9~12 尽调业务）→ 返回"该业务暂未开通"提示
            if (c.skillName() == null) {
                log.info("Matchable reserved branch hit: label={}, returning not-opened hint", c.label());
                return Mono.just(notOpenedDecision(c.label()))
                        .doOnNext(d -> logRouteElapsed("tree-matchable", userMessage, treeElapsedMs, routeStart));
            }
            // 预留叶子：树节点已建但技能未注册（数据未就绪，如 verify_natural_person）→ 同样返回未开通提示
            if (skillRegistry.get(c.skillName()) == null) {
                log.info("Reserved branch hit: skill '{}' not registered, returning not-opened hint", c.skillName());
                return Mono.just(notOpenedDecision(c.label()))
                        .doOnNext(d -> logRouteElapsed("tree-reserved", userMessage, treeElapsedMs, routeStart));
            }
            // 唯一候选（可执行技能）→ 优先直接提取参数（跳过 LLM，零延迟）。
            // 树结构已承担互斥与并存（跨子域并存会产出多候选），findTriggeredButExcluded 补检不再需要
            Map<String, Object> directParams = tryDirectParamExtraction(c, userMessage, conversationId);
            if (directParams != null) {
                log.info("Direct param extraction succeeded for skill '{}': {}", c.skillName(), directParams);
                Map<String, Object> decision = new LinkedHashMap<>();
                decision.put("action", "skill");
                decision.put("skill", c.skillName());
                decision.put("params", directParams);
                decision.put("confidence", 0.95);
                decision.put("reason", "IntentTreeMatcher 确定性匹配 + 直接参数提取");
                return Mono.just(decision)
                        .doOnNext(d -> logRouteElapsed("tree-direct", userMessage, treeElapsedMs, routeStart));
            }
            // 直接提取失败 → 回退到 LLM 提取参数
            log.info("Direct param extraction failed for skill '{}', falling back to LLM", c.skillName());
            return callLLM(buildParamExtractionPrompt(c, userMessage, history), userMessage, history)
                    .doOnNext(d -> logRouteElapsed("tree-llm-params", userMessage, treeElapsedMs, routeStart));
        }

        // ② 多候选 → LLM 仲裁（含多意图检测；matchable 预留候选并入列表，仲裁不得选中其技能名）
        return callLLM(buildArbitrationPrompt(candidates, userMessage, history), userMessage, history)
                .doOnNext(d -> logRouteElapsed("llm-arbitration", userMessage, treeElapsedMs, routeStart));
    }

    /**
     * 路由耗时观测日志（方案 B）：tree=树层确定性匹配（同步微秒级），llm=LLM 调用段（仲裁/兜底/
     * 参数提取，秒级主耗时），total=routeIntent 入口到决策产出的总耗时；stage 标识路由路径，
     * 便于按路径（直路由/仲裁/兜底）聚合统计耗时分布。
     */
    private void logRouteElapsed(String stage, String userMessage, long treeElapsedMs, long routeStartNanos) {
        long totalMs = (System.nanoTime() - routeStartNanos) / 1_000_000;
        long llmMs = Math.max(0L, totalMs - treeElapsedMs);
        log.info("ROUTE_ELAPSED stage={} tree={}ms llm={}ms total={}ms msg={}",
                stage, treeElapsedMs, llmMs, totalMs, userMessage);
    }

    /**
     * matchable 预留 / 技能未注册叶子命中 → "该业务暂未开通" chat 决策。
     * preset_reply 为固定提示文案，由 ChatController 直接展示（不调用 LLM 闲聊）。
     */
    private Map<String, Object> notOpenedDecision(String label) {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", "chat");
        decision.put("reason", "业务暂未开通");
        decision.put("preset_reply", "「" + label + "」业务暂未开通，敬请期待。");
        return decision;
    }

    /**
     * 调用 LLM 并解析响应（模型级联：先便宜模型，低置信度/复杂场景升级主模型）。
     * 第一层 light 模型按给定 prompt（参数提取/仲裁/完整规则）决策；
     * 需要升级时第二层主模型改用完整规则 prompt 兜底重判，结果直接返回不再递归。
     */
    private Mono<Map<String, Object>> callLLM(String systemPrompt, String userMessage, List<Message> history) {
        return callModel(config.getModel().getLight(), systemPrompt, userMessage, history)
                .flatMap(decision -> {
                    if (shouldEscalate(decision)) {
                        log.info("Light model insufficient (action={}, confidence={}, reason={}), escalating to main model",
                                decision.get("action"), decision.get("confidence"), decision.get("reason"));
                        return callModel(config.getModel().getCoordinator(),
                                buildFullRulePrompt(userMessage, history), userMessage, history);
                    }
                    return Mono.just(decision);
                });
    }

    /**
     * 实际调用指定模型并解析响应
     */
    private Mono<Map<String, Object>> callModel(String model, String systemPrompt, String userMessage, List<Message> history) {
        String baseUrl = config.getDeepseek().getBaseUrl();
        String apiKey = config.getDeepseek().getApiKey();

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // 追加最近的历史消息（限最近 8 条）
        if (history != null && !history.isEmpty()) {
            List<Message> recentHistory = history.size() > 8
                    ? history.subList(history.size() - 8, history.size())
                    : history;
            for (Message msg : recentHistory) {
                String content = msg.getContent();
                if (content == null || content.isEmpty()) continue;
                if (content.startsWith("{")) {
                    content = "[系统返回了结构化卡片结果]";
                }
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...(截断)";
                }
                messages.add(Map.of("role", msg.getRole(), "content", content));
            }
        }

        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("thinking", Map.of("type", "disabled"));
        requestBody.put("temperature", 0.1);
        requestBody.put("max_tokens", 300);

        return webClient.post()
                .uri(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseResponse)
                .onErrorResume(e -> {
                    log.error("Coordinator LLM call failed (model={}): {}", model, e.getMessage());
                    // 调用失败不升级：主模型大概率同样失败，直接 fallback chat
                    return Mono.just(fallbackMap("意图识别请求失败", false));
                });
    }

    /**
     * 级联升级判定：light 模型决策是否需要主模型兜底重判。
     * - skill 置信度低于阈值 → 升级（便宜模型拿不准）
     * - clarify / multi → 升级（拿不准或多意图属于复杂场景，主模型完整规则更可靠）
     * - chat → 仅解析失败兜底（degraded）才升级；模型明确判定闲聊时不浪费主模型
     * - 未知 action / 异常 → 升级
     */
    static boolean shouldEscalate(Map<String, Object> decision) {
        if (decision == null) return true;
        String action = (String) decision.get("action");
        if (action == null) return true;
        switch (action) {
            case "skill":
                return getConfidence(decision) < ESCALATE_CONFIDENCE_THRESHOLD;
            case "clarify":
            case "multi":
                return true;
            case "chat":
                return Boolean.TRUE.equals(decision.get("degraded"));
            default:
                return true;
        }
    }

    /**
     * 解析 DeepSeek 返回的文本，提取决策 JSON
     * 支持四种 action：skill / chat / clarify / multi
     */
    private Map<String, Object> parseResponse(String response) {
        try {
            Map<String, Object> respMap = mapper.readValue(response, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respMap.get("choices");
            String text = "";
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                text = (String) message.getOrDefault("content", "");
            }

            Matcher jsonMatch = JSON_PATTERN.matcher(text);
            if (jsonMatch.find()) {
                Map<String, Object> decision = mapper.readValue(jsonMatch.group(), new TypeReference<>() {});
                String action = (String) decision.get("action");

                // 兼容：LLM 直接将技能名作为 action
                if (action != null && skillRegistry.get(action) != null) {
                    decision.put("action", "skill");
                    decision.put("skill", action);
                    log.info("Coerced action from '{}' to skill", action);
                    return decision;
                }

                if ("skill".equals(action)) {
                    String skillName = (String) decision.getOrDefault("skill", "");
                    if (skillRegistry.get(skillName) == null) {
                        log.warn("LLM returned unknown skill '{}', falling back to chat", skillName);
                        return fallbackMap("意图识别返回未知技能", true);
                    }
                    // 置信度检查
                    double confidence = getConfidence(decision);
                    if (confidence > 0 && confidence < CLARIFY_CONFIDENCE_THRESHOLD) {
                        log.info("Low confidence {} for skill '{}', converting to clarify", confidence, skillName);
                        return buildClarifyDecision(decision);
                    }
                    log.info("Coordinator intent: skill={}, confidence={}, reason: {}",
                            skillName, confidence, decision.getOrDefault("reason", "unknown"));
                    return decision;
                }

                if ("chat".equals(action)) {
                    log.info("Coordinator intent: chat, reason: {}", decision.getOrDefault("reason", "unknown"));
                    return decision;
                }

                if ("clarify".equals(action)) {
                    log.info("Coordinator intent: clarify, reason: {}", decision.getOrDefault("reason", "unknown"));
                    return decision;
                }

                if ("multi".equals(action)) {
                    log.info("Coordinator intent: multi, reason: {}", decision.getOrDefault("reason", "unknown"));
                    return decision;
                }
            }

            log.warn("No valid decision JSON found in response: {}", text);
            return fallbackMap("未提取到有效意图", true);

        } catch (Exception e) {
            log.warn("JSON parse error: {}", e.getMessage());
            return fallbackMap("意图识别解析失败", true);
        }
    }

    private static double getConfidence(Map<String, Object> decision) {
        Object conf = decision.get("confidence");
        if (conf instanceof Number) return ((Number) conf).doubleValue();
        if (conf instanceof String) {
            try { return Double.parseDouble((String) conf); } catch (Exception e) { return 1.0; }
        }
        return 1.0; // 无 confidence 字段视为高置信度
    }

    /**
     * 澄清卡点选后的技能参数提取（SELECT_INTENT 协议用）：按用户原始输入恢复技能参数。
     * 不试规则式直接提取——多意图复合句（如"帮我核实星河信息并查一下历史尽调报告"）经
     * 清洗链会产出脏名（"星河信息并历史"），故统一走 LLM 提取；LLM 未给出 params 时
     * 返回空 Map，由调用方依赖上下文自动填充（多轮中指代上一轮企业属正常语义）。
     */
    public Mono<Map<String, Object>> extractParamsForSkill(String skillName, String userMessage,
                                                           List<Message> history, String conversationId) {
        long start = System.nanoTime();
        SkillCandidate candidate = new SkillCandidate(skillName, skillRegistry.getSkillLabel(skillName),
                "", 0, List.of(), 0);
        return callLLM(buildParamExtractionPrompt(candidate, userMessage, history), userMessage, history)
                .map(decision -> {
                    Object params = decision.get("params");
                    if (params instanceof Map<?, ?> m) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        m.forEach((k, v) -> result.put(String.valueOf(k), v));
                        log.info("LLM param extraction for skill '{}': {}", skillName, result);
                        log.info("PARAM_EXTRACT_ELAPSED skill={} elapsed={}ms",
                                skillName, (System.nanoTime() - start) / 1_000_000);
                        return result;
                    }
                    log.info("LLM param extraction for skill '{}' returned no params", skillName);
                    log.info("PARAM_EXTRACT_ELAPSED skill={} elapsed={}ms",
                            skillName, (System.nanoTime() - start) / 1_000_000);
                    return Map.<String, Object>of();
                });
    }

    /**
     * 低置信度 → 改写为 clarify 决策
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildClarifyDecision(Map<String, Object> decision) {
        Map<String, Object> clarify = new LinkedHashMap<>();
        clarify.put("action", "clarify");
        clarify.put("reason", decision.getOrDefault("reason", "置信度不足，转意图澄清"));

        List<Map<String, Object>> candidates = new ArrayList<>();
        // 首选技能
        String skillName = (String) decision.getOrDefault("skill", "");
        if (!skillName.isEmpty()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("skill", skillName);
            c.put("label", skillRegistry.getSkillLabel(skillName));
            c.put("description", skillRegistry.get(skillName) != null
                    ? skillRegistry.get(skillName).getDescription() : "");
            candidates.add(c);
        }
        // 备选技能
        List<String> alternatives = (List<String>) decision.getOrDefault("alternatives", List.of());
        for (String alt : alternatives) {
            if (skillRegistry.get(alt) != null) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("skill", alt);
                c.put("label", skillRegistry.getSkillLabel(alt));
                c.put("description", skillRegistry.get(alt).getDescription());
                candidates.add(c);
            }
        }
        clarify.put("candidates", candidates);
        clarify.put("message", "您的问题可能有多种理解，请选择您想要的操作：");
        return clarify;
    }

    /**
     * 核实→风险 宾语豁免判定："帮我核实一下小米科技的风险"（核实对象=风险，口语语义=查风险）
     * 应直接路由 check_company_risk；而"信息核实并做风险识别"（核实与风险为并列意图）保持 clarify。
     * 判定：候选含 check_company_risk，且消息中"风险"位于核实类词之后、二者之间无并列连接词。
     */
    private boolean isRiskAsVerifyObject(String userMessage, List<SkillCandidate> candidates) {
        if (userMessage == null || !userMessage.contains("风险")
                || candidates.stream().noneMatch(c -> "check_company_risk".equals(c.skillName()))) {
            return false;
        }
        int verifyIdx = -1;
        for (String w : List.of("核实", "核验", "核查", "核对")) {
            int i = userMessage.lastIndexOf(w);
            if (i > verifyIdx) verifyIdx = i;
        }
        int riskIdx = userMessage.indexOf("风险");
        if (verifyIdx < 0 || riskIdx <= verifyIdx) return false;
        String between = userMessage.substring(verifyIdx, riskIdx);
        return !List.of("并", "和", "与", "还", "再", "也", "以及", "然后", "随后")
                .stream().anyMatch(between::contains);
    }

    /**
     * 核实对象不明 → clarify 决策（携带候选卡片：营业执照核实 / 身份证核实）。
     * 与 LLM 兜底规则 e 同语义：verify_natural_person 未注册但允许列出，用户点击后由
     * ChatController SELECT_INTENT 分支返回"暂未开通"引导文案，而非回退路由。
     */
    private Map<String, Object> buildVerifyClarifyDecision() {
        Map<String, Object> clarify = new LinkedHashMap<>();
        clarify.put("action", "clarify");
        clarify.put("reason", "核实对象不明，请选择核实类型");
        clarify.put("message", "您的问题可能有多种理解，请选择您想要的操作：");

        List<Map<String, Object>> candidates = new ArrayList<>();
        // 营业执照核实：已注册技能，选择后可直接执行
        Map<String, Object> bl = new LinkedHashMap<>();
        bl.put("skill", "verify_business_license");
        bl.put("label", "营业执照核实");
        bl.put("description", "上传营业执照图片，逐项核实企业信息");
        candidates.add(bl);
        // 身份证核实：预留未开通，选择后返回"暂未开通"引导
        Map<String, Object> np = new LinkedHashMap<>();
        np.put("skill", "verify_natural_person");
        np.put("label", "身份证核实");
        np.put("description", "核实法定代表人身份信息（暂未开通）");
        candidates.add(np);

        clarify.put("candidates", candidates);
        return clarify;
    }

    /**
     * 解析失败兜底：action=chat + degraded 标记。
     * degraded=true 表示模型有输出但解析不出有效决策（light 能力不足，可升级主模型）；
     * degraded=false 表示调用失败（升级无意义，直接兜底）。
     */
    private Map<String, Object> fallbackMap(String reason, boolean degraded) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("action", "chat");
        fallback.put("reason", reason);
        fallback.put("degraded", degraded);
        return fallback;
    }

    // ============================================================
    // 直接参数提取（不依赖 LLM）
    // ============================================================

    /**
     * 尝试直接从用户消息中提取技能参数（跳过 LLM 调用）。
     * 成功返回参数 Map；失败返回 null（调用方应回退到 LLM）。
     */
    private Map<String, Object> tryDirectParamExtraction(SkillCandidate candidate,
                                                         String userMessage,
                                                         String conversationId) {
        Map<String, Object> params = new LinkedHashMap<>();

        // 1. 尝试提取统一信用代码（优先"统一信用代码:XXX"字段，其次 18 位裸代码）
        String creditCode = CompanyNameExtractor.extractCreditCode(userMessage);
        if (!creditCode.isEmpty()) {
            params.put("credit_code", creditCode);
        }

        // 2. 尝试从消息中提取企业名称（统一公共清洗链）
        String companyName = CompanyNameExtractor.extractCompanyName(
                userMessage, null, null, candidate.matchedKeywords());

        if (companyName != null && !companyName.isEmpty()) {
            params.put("company_name", companyName);
        }

        // 3. 如果消息中未提取到企业名，检查上下文记忆
        if (!params.containsKey("company_name") && !params.containsKey("credit_code")) {
            ContextMemoryService.ConversationContext ctx = contextMemoryService.get(conversationId);
            if (!ctx.isEmpty()) {
                // 消息中包含上下文引用词（如"这家""该公司"），说明用户指的是上一轮的企业
                boolean hasContextRef = ContextMemoryService.isContextReference(userMessage);
                if (hasContextRef) {
                    if (ctx.creditCode != null && !ctx.creditCode.isEmpty()) {
                        params.put("credit_code", ctx.creditCode);
                    }
                    if (ctx.companyName != null && !ctx.companyName.isEmpty()) {
                        params.put("company_name", ctx.companyName);
                    }
                    log.info("Context memory fill: creditCode={}, companyName={}",
                            ctx.creditCode, ctx.companyName);
                } else {
                    // 无法从消息提取，也无上下文引用 → 直接提取失败
                    return null;
                }
            } else {
                // 无上下文记忆 → 直接提取失败
                return null;
            }
        }

        return params.isEmpty() ? null : params;
    }

    // ============================================================
    // 意图穿插：补充信息 vs 新意图二分类（classifyPipelineInput 第 ⑤ 步兜底）
    // ============================================================

    /**
     * 管道暂停期间判定用户消息是"补充信息"还是"新意图"（二分类兜底）。
     * 供 ChatController.classifyPipelineInput 前四级确定性判定全部失败时调用。
     * prompt 携带：当前暂停技能名、已收集参数、等待补充的提示文案、用户最新消息。
     * 返回 Mono&lt;Map&gt;，含 "class"（supplement / new_intent）与 "reason"。
     * LLM 调用失败时保守返回 supplement（与管道暂停期原有"全部视为补充"行为一致）。
     */
    public Mono<Map<String, Object>> classifyIntentInterrupt(String userMessage, String pendingSkillName,
                                                              Map<String, Object> pendingParams,
                                                              String pendingInputHint) {
        String apiKey = config.getDeepseek().getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("DEEPSEEK_API_KEY not set, defaulting classifyIntentInterrupt to supplement");
            return Mono.just(Map.of("class", "supplement", "reason", "API key not configured"));
        }
        String prompt = buildInterruptClassifyPrompt(userMessage, pendingSkillName, pendingParams, pendingInputHint);
        log.info("Interrupt classify (pendingSkill={}): {}", pendingSkillName, userMessage);
        return callBinaryClassify(config.getModel().getLight(), prompt, userMessage);
    }

    /**
     * 构建意图穿插二分类 prompt：核心是让模型区分"回答系统追问"与"发起新请求"。
     */
    private String buildInterruptClassifyPrompt(String userMessage, String pendingSkillName,
                                                 Map<String, Object> pendingParams, String pendingInputHint) {
        String hint = (pendingInputHint == null || pendingInputHint.isEmpty()) ? "（无）" : pendingInputHint;
        String params = (pendingParams == null || pendingParams.isEmpty()) ? "{}（无已收集参数）" : String.valueOf(pendingParams);
        return """
                你是一个意图分类器。当前系统正在等待用户为一个未完成的技能任务补充信息，
                请判断用户最新消息是"补充信息"还是"新意图"。

                ## 当前暂停的技能
                %s

                ## 已收集的参数
                %s

                ## 等待用户补充的内容（系统提示文案）
                %s

                ## 分类规则
                1. 如果用户消息是在回答系统的问题（提供参数、选择候选企业、上传附件、
                   回复日期范围/企业名/信用代码、简短确认词如"好""可以"）→ class = "supplement"
                2. 如果用户消息是一个与当前技能无关的新请求（如查询另一家企业、
                   发起其他操作、明确切换话题）→ class = "new_intent"

                ## 输出格式
                只输出 JSON：{"class": "supplement" 或 "new_intent", "reason": "<中文理由>"}
                不要输出其他文本，不要包裹在代码块中。
                """.formatted(pendingSkillName, params, hint);
    }

    /**
     * 调用指定模型做二分类并解析 {"class", "reason"}。
     * 与 callModel 独立：parseResponse 面向 routeIntent 决策 JSON（action 字段），
     * 二分类输出 class 字段，复用会导致解析失败，故单独实现。
     */
    private Mono<Map<String, Object>> callBinaryClassify(String model, String systemPrompt, String userMessage) {
        String baseUrl = config.getDeepseek().getBaseUrl();
        String apiKey = config.getDeepseek().getApiKey();

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("thinking", Map.of("type", "disabled"));
        requestBody.put("temperature", 0.1);
        requestBody.put("max_tokens", 200);

        return webClient.post()
                .uri(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseBinaryClassify)
                .onErrorResume(e -> {
                    log.error("Interrupt classify LLM call failed: {}", e.getMessage());
                    return Mono.just(Map.of("class", "supplement", "reason", "LLM 调用失败，保守视为补充信息"));
                });
    }

    /**
     * 解析二分类响应：提取 {"class": "supplement" | "new_intent", "reason": ...}。
     * 解析失败时保守返回 supplement，避免打断管道补充流程。
     */
    private Map<String, Object> parseBinaryClassify(String response) {
        try {
            Map<String, Object> respMap = mapper.readValue(response, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respMap.get("choices");
            String text = "";
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                text = (String) message.getOrDefault("content", "");
            }

            Matcher jsonMatch = JSON_PATTERN.matcher(text);
            if (jsonMatch.find()) {
                Map<String, Object> decision = mapper.readValue(jsonMatch.group(), new TypeReference<>() {});
                String cls = (String) decision.getOrDefault("class", "");
                if ("new_intent".equals(cls) || "supplement".equals(cls)) {
                    return decision;
                }
            }
            log.warn("No valid class JSON found in classify response: {}", text);
        } catch (Exception e) {
            log.warn("Binary classify JSON parse error: {}", e.getMessage());
        }
        return Map.of("class", "supplement", "reason", "解析失败，保守视为补充信息");
    }

    // ============================================================
    // Prompt 构建
    // ============================================================

    /**
     * 唯一候选 → LLM 仅提取参数
     */
    private String buildParamExtractionPrompt(SkillCandidate candidate, String userMessage, List<Message> history) {
        String skillsPrompt = skillRegistry.getSkillsPrompt();
        return """
                你是一个参数提取器。用户意图已确定为技能「%s」（%s）。
                请从用户输入和对话历史中提取该技能所需的参数。

                ## 上下文记忆
                系统维护了当前会话的上下文记忆（最近操作的企业主体）。即使用户没有在当前消息中明确提及企业名称，你仍然应该从历史中提取。

                ## 可用技能描述
                %s

                ## 企业名提取规则
                - company_name 必须是真实企业主体名，允许简称（如"星河"代表"北京星河科技有限公司"）
                - 查询对象词/动作词不得进入企业名："信息、资料、情况、报告、风险、尽调、核实、核验、核查、
                  查询、查一下、核查"等是查询对象或动作，必须剔除
                - 示例："帮我核实星河信息并查一下历史尽调报告"应提取 "星河"，而不是 "星河信息"
                - 若输入片段以"公司/集团/中心/厂/工作室/研究院"等企业组织词结尾，视为完整企业名，
                  保留原名不做剔除（如"帮我核实星河信息有限公司"→"星河信息有限公司"），
                  否则剔除后缀后可能误伤真实含"信息"的企业名（如"XX信息有限公司"）
                - 若输入无任何企业主体名（如仅"核实一下"、"上传的附件帮我核实"），company_name 不填，
                  留待上下文记忆补全或向用户询问

                ## 输出格式
                只输出 JSON，不要输出其他文本：
                {"action": "skill", "skill": "%s", "params": {...}, "confidence": 0.95, "reason": "<中文理由>"}

                如果无法提取任何参数，params 为空对象 {}。
                不要包裹在 ```json 代码块中。
                """.formatted(candidate.skillName(), candidate.label(), skillsPrompt, candidate.skillName());
    }

    /**
     * 多候选 → LLM 仲裁（含多意图检测）
     */
    private String buildArbitrationPrompt(List<SkillCandidate> candidates, String userMessage, List<Message> history) {
        return buildArbitrationPrompt(candidates, userMessage, history, List.of());
    }

    /**
     * 多候选 → LLM 仲裁（含多意图检测）；可附加"被排除词否决但触发词命中的技能"供多意图判定。
     */
    private String buildArbitrationPrompt(List<SkillCandidate> candidates, String userMessage, List<Message> history,
                                          List<IntentMatcher.ExcludedSkill> excludedTriggers) {
        String skillsPrompt = skillRegistry.getSkillsPrompt();
        StringBuilder candidateDesc = new StringBuilder();
        for (SkillCandidate c : candidates) {
            if (c.skillName() == null) {
                // matchable 预留节点（branch_9~12 等）：并入候选供理解语境，但不得路由为技能
                candidateDesc.append("- （预留业务）").append(c.label())
                        .append(": 该业务暂未开通，不得路由为技能（除非用户明确询问该业务，输出 chat 提示暂未开通）\n");
                continue;
            }
            candidateDesc.append("- ").append(c.skillName()).append("（").append(c.label()).append("）: ")
                    .append("触发词命中: ").append(c.matchedKeywords()).append("\n");
        }

        // 被排除词否决但触发词命中的技能：提示 LLM 判断用户是否同时表达了这些意图
        StringBuilder exclusionDesc = new StringBuilder();
        if (excludedTriggers != null && !excludedTriggers.isEmpty()) {
            exclusionDesc.append("\n## 被排除词否决但触发词命中的技能\n")
                    .append("以下技能触发了关键词但被其排除词否决，仅凭规则无法断定用户意图：\n");
            for (IntentMatcher.ExcludedSkill es : excludedTriggers) {
                exclusionDesc.append("- ").append(es.skillName()).append("（").append(es.label()).append("）: ")
                        .append("触发词命中: ").append(es.matchedKeywords())
                        .append("，被排除词否决: ").append(es.excludedBy()).append("\n");
            }
            exclusionDesc.append("若用户确实同时表达了这些意图（如\"查X风险和信息\"包含风险与基本信息两个意图），"
                    + "请按决策规则 2 输出 multi 包含对应技能；否则忽略。");
        }

        return """
                你是一个任务规划主控智能体。用户的输入可能匹配多个技能，请判断用户真实意图。

                ## 候选技能
                %s
                %s

                ## 上下文记忆
                系统维护了当前会话的上下文记忆（最近操作的企业主体）。

                ## 对话历史
                以下是最近对话历史，请结合理解用户意图。

                ## 决策规则
                1. 如果用户意图明确指向某一个候选技能 → 输出 {"action":"skill","skill":"<技能名>","params":{...},"confidence":0.8,"reason":"<中文理由>"}
                2. 如果用户同时表达了多个互不排斥的意图 → 输出 {"action":"multi","skills":[{"skill":"<技能名1>","params":{...}},{"skill":"<技能名2>","params":{...}}],"reason":"<中文理由>"}
                3. 如果无法确定用户意图 → 输出 {"action":"clarify","candidates":[{"skill":"<技能名>","label":"<中文标签>","description":"<描述>"}],"message":"您的问题可能有多种理解，请选择您想要的操作：","reason":"<中文理由>"}
                4. 如果明显是普通聊天 → 输出 {"action":"chat","reason":"<中文理由>"}

                ## 可用技能
                %s

                ## 重要规则
                - 只输出 JSON，不要输出其他文本
                - 不要包裹在 ```json 代码块中
                - reason 字段必须用中文
                - confidence 范围 0~1，表示你对判断的信心
                - 提取 company_name 时不要包含"查询"、"的"等模板词语
                """.formatted(candidateDesc, exclusionDesc, skillsPrompt);
    }

    /**
     * 无候选 → LLM 完整规则兜底（含多意图检测）
     */
    private String buildFullRulePrompt(String userMessage, List<Message> history) {
        String skillsPrompt = skillRegistry.getSkillsPrompt();
        return """
                你是一个任务规划主控智能体。分析用户输入（含对话历史上下文），判断意图并做出路由决策。

                ## 上下文记忆
                系统维护了当前会话的上下文记忆（最近操作的企业主体）。即使用户没有在当前消息中明确提及企业名称，只要意图明确（如「查下风险」），你仍然应该路由到对应的技能。系统会自动从上下文记忆中补充缺失的企业参数。

                ## 对话历史
                以下是当前会话的最近对话历史。请结合历史消息理解用户意图：
                - 如果用户说"换一家"、"再看另一家"、"查另一家"等 → 表示想切换企业，应匹配到最近使用的同类型技能
                - 如果用户说"再查2024年的"、"换个时间"等 → 表示想变更查询条件，应匹配到最近使用的同类型技能
                - 如果用户说的内容与最近的技能不相关 → 按正常规则判断为新意图

                ## 决策规则（严格遵守）

                1. **除非意图明确匹配，否则 chat 或 clarify**：**只有**当用户输入中的关键词明确且唯一地指向某个技能时，才路由到该技能。
                   - 意图明确 → 路由技能：{"action": "skill", "skill": "<技能名>", "params": {}, "confidence": 0.9, "reason": "<中文理由>"}
                   - **意图不明确但存在多种合理解读 → 输出 clarify**：用户表达了意图倾向但缺少具体对象/子项，或输入存在歧义。典型场景：
                     a. 仅业务词无动作：如"报告"、"尽调"（生成 or 历史查询？）
                     b. 仅查询行为词无查询对象：如"帮我查一下报告"、"我想查一下"、"帮我看下这家公司"
                     c. 上义词无子项：如"查询海关信息"（认证 or 失信？）、"小米科技的账户情况"（冻结/账管/授信？）
                     d. 核实类无核实对象：如"信息核查一下北京星河科技"（核实什么？）
                     e. 核实类无核实对象的澄清可列出两个候选：verify_business_license（营业执照核实）与 verify_natural_person（身份证核实）。verify_natural_person 暂未开通（不在下方"可用技能"清单中），但核实类澄清允许列出，用户选择后系统会提示"暂未开通"。除核实类澄清外，candidates 只能从下方"可用技能"清单中选择。
                     格式：{"action": "clarify", "candidates": [{"skill":"<候选技能>","label":"<中文标签>","description":"<描述>"}], "message": "您的问题可能有多种理解，请选择您想要的操作：", "reason": "<中文理由>"}
                   - **与技能完全无关的普通聊天**（问候/闲聊/仅企业名或人名而无任何意图词，如"你好"、"北京星河科技怎么样"）→ chat：{"action": "chat", "reason": "<中文理由>"}

                2. 风险类意图（C1 风险子域，分支互斥）：
                   a. 当用户输入中包含"风险"，且同时包含"历史"、"记录"、"存量"等关键词时 → query_risk_history 技能（暂无数据，命中后返回"该业务暂未开通"提示，不得路由为其他技能）。
                   b. 当用户输入中包含"风险"，且同时包含"评价"、"得分"、"打分"等关键词时 → query_risk_score 技能（暂无数据，命中后返回"该业务暂未开通"提示）。
                   c. 其他包含"风险"、"风险识别"、"企业风险"、"风险预查"等关键词的输入，必须匹配为 check_company_risk 技能。

                3. 如果是普通聊天（问候/闲聊/仅含公司名而无意图词）→ 一律返回：
                   {"action": "chat", "reason": "<中文理由>"}
                   不要将{"action": "chat"}写成其他格式。
                   注意：意图模糊但存在多种合理解读时，按规则 1 输出 clarify，不要输出 chat。

                4. 当用户输入中包含"生成报告"、"尽调报告"、"财务分析报告"、"授信评估"、"报告模板"、"生成尽调"、"智能尽调"、"上传资料生成报告"、"产品尽调"、"生成"等关键词时，必须匹配为 generate_report 技能；若输入同时包含"历史"、"查询"、"查一下"、"查看"、"查找"等词（如"查询历史尽调报告"），不得匹配为 generate_report，应判定为规则 8 的历史尽调报告查询。

                5. generate_report 技能的多轮交互参数提取：
                   a. 当用户选择了模板（消息中包含"选择"+"模板"、"使用"+"模板"或"(ID:"），从模板名称或ID中提取 template_id。
                   b. 当用户触发生成（消息中包含"为"+"生成"），提取 template_id、company_name，并设置 action="generate"
                   c. 如果消息中包含"附件文件ID:"，提取逗号分隔的文件ID列表填充到 attachment_file_ids 参数
                   d. 如果消息中包含"统一信用代码:"，提取信用代码填充到 credit_code 参数

                6. 核实类意图（B 核实域）：
                   a. 当用户输入中包含"营业执照"且同时包含"核实"、"核对"、"核验"等关键词时，必须匹配为 verify_business_license 技能。
                   b. 当用户输入中包含"法定代表人"、"授权代理人"、"财务主管"中任一，且包含"身份"、"个人信息"中任一，且包含"核实"、"核对"、"核验"中任一 → verify_natural_person 技能（暂无数据，返回"该业务暂未开通"提示）。
                   c. 当用户输入中包含"通讯"、"电话"、"手机"、"号码"中任一，且包含"核实"、"核对"、"核验"中任一 → verify_contact_info 技能（暂无数据，返回"该业务暂未开通"提示）。

                7. **多轮对话路由**：结合**上一轮交互语境**：
                    a. 如果上一轮助手消息是技能结果，且当前用户输入简短，则路由到**上一轮相同的技能**
                    b. 如果上轮路由为 chat，则按正常规则判断本轮意图
                    c. 如果用户明确表达了新的意图，按新意图路由

                8. 当用户输入中包含"历史尽调"、"查询历史"、"尽调记录"、"历史报告"、"历史"等关键词时，必须匹配为 query_due_diligence_reports 技能（generate_report 已被排除词互斥，本技能无需反向排除）。

                9. 当用户输入中包含"股东"、"股权结构"、"股权分布"等关键词时，必须匹配为 query_shareholder_info 技能。

                10. 当用户输入中包含"受益人"、"实际控制人"、"受益所有人"等关键词时，必须匹配为 query_beneficiary_info 技能。

                11. 当用户输入中包含"企业族谱"、"家族图谱"、"关联企业图谱"等关键词时，必须匹配为 query_company_genealogy 技能。

                12. 海关类意图（C3 海关子域）：
                    a. 当用户输入中包含"海关"且同时包含"认证"、"高级认证"、"AEO"等关键词时，必须匹配为 query_customs_auth 技能。
                    b. 当用户输入中包含"海关"且同时包含"失信"、"黑名单"等关键词时，必须匹配为 query_customs_blacklist 技能。

                13. 当用户输入中包含"冻结"、"司法冻结"、"账户冻结"等关键词，且同时包含"账户"、"账号"等关键词时，必须匹配为 query_account_freeze_tag 技能。

                14. 当用户输入中包含"授信"、"授信额度"、"综合授信"、"授信余额"等关键词时，必须匹配为 query_credit_granting 技能。

                15. 当用户输入中包含"人行"、"人民银行"、"中国人民银行"、"央行"中任一，且同时包含"账管"、"账户管理"、"账户管控"中任一关键词时，必须匹配为 query_pboc_account_control 技能（支持分隔写法，如"人行账户管控"）。

                16. 当用户输入中包含"查询"、"查一下"、"查查"、"提供"、"获取"、"看一下"、"看看"、"了解一下"等查询行为词，且**能确定查询对象企业**（用户输入中明确企业名称，或可从上下文记忆获取企业），且**不包含**规则 2~15、19 中的任何技能关键词时，必须匹配为 query_company_basic_info 技能。仅行为词而无企业对象（如"我想查一下"）→ 按规则 1 输出 clarify。

                17. **附件未说明用途必须返回 chat**：当用户消息仅包含附件信息且**不包含**"核实"、"核验"、"核查"、"报告"、"生成"等明确用途关键词时，一律返回 chat。

                18. **多意图识别**：当用户输入中包含多个互不排斥的意图（如"核实信息并做风险识别"），输出：
                    {"action": "multi", "skills": [{"skill": "<技能名1>", "params": {...}}, {"skill": "<技能名2>", "params": {...}}], "reason": "<中文理由>"}

                19. **尽调业务预留（branch_9~12）**：当用户输入中包含"e缴费"、"银企互联"、"融e聚"、"财智账户卡"、"财智卡"等业务词，且同时包含"尽调"、"尽职调查"时，对应业务暂未开通，返回 chat 并提示"该业务暂未开通"（不得路由为 generate_report 或其他技能）。

                ## 可用技能

                """ + skillsPrompt + """

                ## 重要规则

                - **只输出 JSON，不要输出任何其他文本**
                - 不要包裹在 ```json 代码块中
                - reason 字段必须用中文简述理由
                - 提取 company_name 时不要包含"查询"、"的"等模板词语
                - confidence 范围 0~1，表示你对判断的信心""";
    }
}
