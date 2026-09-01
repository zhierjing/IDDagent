# IDDagent_my 意图穿插融合改造实施文档

## 1. 文档用途

本文档用于指导 AI 对当前 `IDDagent_my` 项目的意图穿插机制进行升级。

本次改造不是推翻现有 Pipeline 架构，也不是将旧项目 `D:\shixi\IDDagent` 的 Plan 挂起模型整体迁移过来。

总体原则是：

> **以 IDDagent_my 当前 Pipeline + Deque 挂起栈作为执行底座，吸收旧 IDDagent 中 PlanStep 状态机、结构化交互协议、显式穿插规则、planId 定位等优点。**

最终形成：

> **ExecutionFrame 是完整任务运行现场，Pipeline 是实际执行事实，Plan 是任务状态视图，Deque 是穿插挂起栈，DeferredEvent 负责异步结果补偿。**

---

## 2. AI 修改前必须遵守的原则

在修改任何代码之前，必须先完整阅读和梳理当前项目已有实现。

重点定位并分析以下逻辑：

- Pipeline 创建逻辑；
- `pendingPipeline`；
- `pipelinePlan`；
- `pendingSkill`；
- `waitingReportTask`；
- `pendingReportDone`；
- 挂起栈 `Deque`；
- `PipelineSnapshot` 或对应快照对象；
- `classifyPipelineInput`；
- `CoordinatorService.classifyIntentInterrupt`；
- `IntentMatcher`；
- `interruptAskPending`；
- `interruptAskCheck`；
- Pipeline 恢复逻辑；
- 报告生成和 `report-complete` 回调；
- 企业 `companyName / creditCode` 保存与恢复；
- `TaskProgressCard`；
- `planning/resume` SSE；
- 前端卡片历史更新机制；
- 当前文本协议 `【管道恢复】继续`、`【管道恢复】放弃`。

在未确认这些代码的实际调用链之前：

> **禁止直接大范围重构。**

必须先输出：

1. 当前相关类；
2. 当前相关方法；
3. 当前调用链；
4. 当前状态字段；
5. 本文档设计与现有代码的映射关系；
6. 实际需要修改的文件列表。

之后再开始代码修改。

---

## 3. 本次改造目标

最终系统必须同时具备以下能力：

- 普通多任务 Pipeline 自动执行；
- 当前任务缺参数时等待用户补充；
- 用户输入能够区分“当前任务补充”和“新意图穿插”；
- 支持穿插过程中再次穿插；
- 使用 LIFO 顺序逐层恢复；
- 当前企业和其他上下文不会被穿插任务污染；
- 报告等异步任务在挂起期间完成不会丢失；
- 恢复后能够继续正确推进 Pipeline；
- Plan 能明确表示每一步当前状态；
- 前端卡片可以通过 ID 精确定位；
- 旧卡点击不会误操作当前任务；
- 恢复操作具有明确 `frameId`；
- 规则可以判断的问题不调用 LLM；
- 只有规则无法判断时才调用 LLM；
- 不因为本次升级破坏现有 Pipeline 核心执行机制。

---

## 4. 本次改造明确不做什么

第一阶段不得：

- 用 Plan 替换 Pipeline；
- 删除现有 Deque 挂起能力；
- 重新采用单一 `suspendedPlan`；
- 改回单层穿插；
- 删除现有报告 deferred 能力；
- 将所有输入统一交给 LLM 分类；
- 一次性重写全部 Pipeline；
- 一次性删除现有恢复文本协议；
- 一次性删除所有旧字段；
- 让前端承担状态正确性的唯一责任。

本次改造必须采取：

> **兼容式、渐进式升级。**

---

## 5. 最终总体架构

最终 Session 建议形成：

```text
ConversationSession
│
├── currentFrame
│
└── suspendedFrames : Deque<ExecutionFrame>
```

每一个独立任务对应一个：

```text
ExecutionFrame
```

Frame 内包含：

```text
ExecutionFrame
│
├── frameId
├── parentFrameId
├── FrameStatus
│
├── PlanRuntime
│
├── ExecutionRuntime
│   ├── pendingPipeline
│   ├── pipelinePlan / execution tasks
│   └── pendingSkill
│
├── BusinessContext
│
├── PendingInteraction
│
├── PendingExternalTask
│
├── DeferredEvent
│
└── InterruptMetadata
```

以后真正压入 Deque 的对象应该逐步统一为：

```text
ExecutionFrame
```

而不是只保存部分 Pipeline 数据。

---

## 6. 核心概念职责划分

### 6.1 ExecutionFrame

ExecutionFrame 表示：

> 一个可以被完整保存、挂起、恢复、放弃的任务运行现场。

Frame 是整个穿插机制的核心单位。

### 6.2 Pipeline

Pipeline 继续负责：

- 下一步执行哪个任务；
- Skill 调用；
- 任务队列推进；
- 参数准备；
- 执行结果传递；
- 自动链式执行。

必须坚持：

> **Pipeline 是实际执行事实。**

### 6.3 Plan

Plan 只负责表示：

- 当前任务包含哪些步骤；
- 哪一步正在执行；
- 哪一步等待输入；
- 哪一步等待异步结果；
- 哪一步完成；
- 哪一步失败。

必须坚持：

> **Plan 是 Pipeline 的状态投影，不是新的执行引擎。**

### 6.4 suspendedFrames

`suspendedFrames` 使用：

```java
Deque<ExecutionFrame>
```

继续使用 LIFO。

例如：

```text
A 被 B 穿插
B 被 C 穿插
```

状态：

```text
Stack:
A
B

Current:
C
```

C 结束以后优先处理 B，然后才是 A。

---

## 7. 第一阶段：引入 frameId

这是整个改造中优先级最高的一项。

所有新创建的独立执行任务必须生成：

```text
frameId
```

例如：

```text
F_01JXXX
```

具体 UUID、雪花 ID 或其他方案由项目现有规范决定。

不要在本文档中强行引入新的 ID 库。

### 7.1 frameId 必须逐步贯穿以下对象

至少覆盖：

- ExecutionFrame；
- Pipeline；
- PipelineTask；
- Plan；
- Skill 执行；
- PendingInteraction；
- ReportTask；
- 异步回调；
- SSE；
- TaskProgressCard；
- 恢复卡；
- 企业候选卡；
- 用户交互 Action。

如果目前某些对象暂时无法全部修改：

> 应优先保证后端 Runtime、报告回调、恢复协议和前端任务卡拥有 frameId。

---

## 8. 新增 ExecutionFrame

如果当前项目已经存在 `PipelineSnapshot`，不要立即删除。

建议：

> 第一阶段让 `ExecutionFrame` 包含或兼容现有 PipelineSnapshot。

例如：

```java
public class ExecutionFrame {

    private String frameId;

    private String parentFrameId;

    private FrameStatus status;

    private PlanRuntime planRuntime;

    private ExecutionRuntime executionRuntime;

    private BusinessContext businessContext;

    private List<PendingInteraction> pendingInteractions;

    private Map<String, PendingExternalTask> externalTasks;

    private List<DeferredEvent> deferredEvents;

    private InterruptMetadata interruptMetadata;

    private Instant createdAt;

    private Instant suspendedAt;

    private Instant resumedAt;
}
```

如果现有代码改动过大，可以第一阶段使用：

```java
public class ExecutionFrame {

    private String frameId;

    private PipelineSnapshot pipelineSnapshot;

    private FrameStatus status;

    private BusinessContext businessContext;

    ...
}
```

之后再逐步拆分。

要求：

> 不允许为了追求类结构漂亮而一次性重写成熟 Pipeline 代码。

---

## 9. FrameStatus

新增：

```java
public enum FrameStatus {

    RUNNING,

    WAITING_INPUT,

    WAITING_EXTERNAL,

    SUSPENDED,

    RESUME_CONFIRMING,

    COMPLETED,

    FAILED,

    ABANDONED
}
```

以后逐步减少：

```text
interruptAskPending
isWaitingXXX
resumePending
reportXXXPending
```

这类互相组合的 boolean。

但第一阶段可以保留兼容字段。

---

## 10. PlanRuntime 与 PlanStepStatus

从旧项目吸收 PlanStep 状态思想。

建议新增或升级：

```java
public class PlanRuntime {

    private String planId;

    private String frameId;

    private List<PlanStep> steps;

    private Integer currentStepIndex;

    private PlanStatus status;
}
```

PlanStep：

```java
public class PlanStep {

    private String stepId;

    private String executionTaskId;

    private String skillName;

    private String title;

    private PlanStepStatus status;

    private String statusMessage;
}
```

状态：

```java
public enum PlanStepStatus {

    PENDING,

    RUNNING,

    WAITING_INPUT,

    WAITING_EXTERNAL,

    DONE,

    FAILED,

    CANCELLED
}
```

---

## 11. Plan 不得控制 Pipeline

必须建立单向关系：

```text
Pipeline execution
        ↓
Plan state projection
```

禁止设计为：

```text
Plan state
        ↓
反过来决定 Pipeline 应该执行什么
```

例如 PipelineTask 开始：

```text
PlanStep = RUNNING
```

Skill 请求参数：

```text
PlanStep = WAITING_INPUT
```

报告已提交：

```text
PlanStep = WAITING_EXTERNAL
```

任务成功：

```text
PlanStep = DONE
```

因此建议新增：

```text
PlanProjectionService
```

负责根据 Runtime 状态更新 Plan。

---

## 12. PipelineTask 与 PlanStep 建立 ID 映射

建议：

```java
public class PipelineTask {

    private String taskId;

    private String frameId;

    private String stepId;

    private String skillName;

    ...
}
```

对应：

```java
public class PlanStep {

    private String stepId;

    private String executionTaskId;

    ...
}
```

形成：

```text
PlanStep.stepId
       ↕
PipelineTask.stepId
```

以后不要主要靠：

```text
title
skill 文本
plan 内容
```

推断对应关系。

---

## 13. 意图穿插分类器重新设计

现有：

```text
classifyPipelineInput
```

继续保留作为主要入口，但内部逻辑升级。

最终判定顺序必须是：

```text
1. Structured Protocol
2. Explicit Interrupt Signal
3. Expected Input Matcher
4. IntentMatcher
5. Context Shift Detector
6. LLM Fallback
```

注意：

> 顺序非常重要，不允许随意调整。

---

## 14. 第一层：Structured Protocol

所有机器可确定的交互优先处理。

例如：

```json
{
  "action": "resume_frame",
  "frameId": "F001"
}
```

```json
{
  "action": "abandon_frame",
  "frameId": "F001"
}
```

```json
{
  "action": "select_candidate",
  "frameId": "F001",
  "interactionId": "I003",
  "candidateId": "C02"
}
```

结构化协议一旦识别成功：

> **禁止继续进入普通意图穿插分类。**

---

## 15. 第二层：Explicit Interrupt Detector

从旧项目吸收显式穿插语言规则。

建议检测：

```text
顺便
另外
再帮我
同时
换个问题
先查一下
先别管这个
这个先放一下
对了
还有一个事
```

但：

> 不允许简单地只要命中单个词就判定 NEW_INTENT。

例如：

```text
帮我选择第二个
```

不能因为出现“帮我”就判穿插。

需要组合判断：

```text
明显穿插信号
+
存在独立任务请求
```

才直接：

```text
NEW_INTENT
```

---

## 16. 第三层：Expected Input Matcher

如果当前 Frame：

```text
WAITING_INPUT
```

必须优先判断：

> 用户当前输入是否能够满足正在等待的参数。

例如等待：

```text
companyName
```

用户输入：

```text
北京字节跳动科技有限公司
```

必须优先判：

```text
SUPPLEMENT
```

等待：

```text
candidate selection
```

用户：

```text
第二个
```

判：

```text
SUPPLEMENT
```

等待：

```text
date range
```

用户：

```text
2025年1月到2025年12月
```

判：

```text
SUPPLEMENT
```

---

## 17. Expected Input 优先级必须高于 Context Shift

这是必须保证的业务规则。

例如系统当前问：

```text
请输入需要查询的企业名称。
```

用户：

```text
阿里巴巴
```

虽然检测到了新的企业名称，但这是系统正在等待的参数。

所以：

```text
SUPPLEMENT
```

绝不能因为：

```text
company changed
```

误判成：

```text
NEW_INTENT
```

---

## 18. 第四层：IntentMatcher

Expected Input 无法匹配后，再调用现有：

```text
IntentMatcher
```

如果明确命中另外一个独立 Skill：

当前：

```text
query_shareholder
```

用户：

```text
生成风险报告
```

命中：

```text
generate_report
```

则：

```text
NEW_INTENT
```

---

## 19. 同族 Skill 不得简单判定穿插

保留旧项目关于：

```text
query_*
```

同族技能的谨慎策略。

例如：

```text
query_company
query_shareholder
query_risk
```

不能仅因为 skillName 不同就立即判穿插。

必须结合：

- 当前 Plan；
- 当前等待状态；
- 企业对象；
- 用户请求是否是独立动作；
- 是否属于原 Plan 中的自然后续任务。

由现有业务实际 Skill 定义进行调整。

---

## 20. 第五层：Context Shift Detector

当 Skill 相同但业务对象发生变化时，也可能是穿插。

例如当前：

```text
查询腾讯股东
```

用户：

```text
阿里的股东呢？
```

虽然两者都是：

```text
query_shareholder
```

但：

```text
腾讯 → 阿里
```

已经发生独立企业切换。

因此：

```text
NEW_INTENT
```

---

## 21. Context Shift 需要结合 Waiting 状态

例如当前：

```text
WAITING_INPUT
Expected:
companyName
```

用户：

```text
阿里
```

这里不得使用 Context Shift 判新意图。

必须由 Expected Input Matcher 优先消费。

---

## 22. 第六层：LLM Fallback

只有前五层无法可靠判断时，调用：

```text
CoordinatorService.classifyIntentInterrupt
```

继续输出：

```text
supplement
new_intent
```

建议逐步升级返回：

```json
{
  "type": "new_intent",
  "confidence": 0.91,
  "reason": "用户提出独立企业查询",
  "evidence": {
    "currentCompany": "腾讯",
    "newCompany": "阿里"
  }
}
```

业务逻辑只依赖：

```text
type
confidence
```

不要依赖 LLM reason 来执行核心业务。

---

## 23. 分类结果不得继续只使用 boolean

如果当前类似：

```java
boolean interrupt;
```

建议升级为：

```java
public enum InputDisposition {

    SUPPLEMENT,

    NEW_INTENT,

    RESUME,

    ABANDON,

    STRUCTURED_ACTION,

    UNKNOWN
}
```

以及：

```java
public class InputClassification {

    private InputDisposition disposition;

    private double confidence;

    private ClassificationSource source;

    private String matchedIntent;

    private String reason;
}
```

来源：

```java
public enum ClassificationSource {

    PROTOCOL,

    EXPLICIT_MARKER,

    EXPECTED_INPUT,

    INTENT_MATCHER,

    CONTEXT_SHIFT,

    LLM
}
```

这个设计方便：

- 日志；
- 调试；
- 单元测试；
- 后续统计误判来源。

---

## 24. NEW_INTENT 处理

如果分类结果：

```text
NEW_INTENT
```

由专门的：

```text
FrameManager
```

完成挂起。

建议流程：

```text
currentFrame
      ↓
snapshot / sync runtime
      ↓
status = SUSPENDED
      ↓
push suspendedFrames
      ↓
create new ExecutionFrame
      ↓
currentFrame = newFrame
      ↓
start new pipeline
```

伪代码：

```java
public void interruptWithNewIntent(Intent intent) {

    ExecutionFrame oldFrame = session.getCurrentFrame();

    if (oldFrame != null) {

        synchronizeFrame(oldFrame);

        oldFrame.setStatus(FrameStatus.SUSPENDED);
        oldFrame.setSuspendedAt(Instant.now());

        session.getSuspendedFrames().push(oldFrame);
    }

    ExecutionFrame newFrame =
        executionFrameFactory.create(intent);

    if (oldFrame != null) {
        newFrame.setParentFrameId(oldFrame.getFrameId());
    }

    session.setCurrentFrame(newFrame);

    pipelineExecutor.start(newFrame);
}
```

---

## 25. SUPPLEMENT 处理

如果：

```text
SUPPLEMENT
```

必须满足：

- 不创建新 Frame；
- 不改变 frameId；
- 不 push Deque；
- 不创建新的独立 Plan。

而是继续当前 Frame。

流程：

```text
User Input
   ↓
PendingInteraction / PendingSkill
   ↓
fill parameter
   ↓
WAITING_INPUT → RUNNING
   ↓
continue pipeline
```

---

## 26. FrameManager

建议增加统一：

```text
FrameManager
```

至少负责：

```text
createFrame
suspendFrame
resumeFrame
abandonFrame
getCurrentFrame
peekSuspendedFrame
```

不要让：

```text
IntentClassifier
```

直接操作：

```text
Deque
```

分类器只负责分类。

FrameManager 负责状态修改。

---

## 27. 任务稳定点继续使用 interruptAskCheck

必须保留当前项目：

```text
interruptAskCheck
```

这种统一收口设计。

不要退回旧项目：

```text
每个 Skill 完成点
每个报告完成点
每个业务 Controller
```

各自调用恢复逻辑。

统一规则：

> 当前 Frame 到达稳定结束状态以后，统一进入 interruptAskCheck。

稳定状态包括：

```text
COMPLETED
FAILED
ABANDONED
```

具体 FAILED 是否直接恢复上层任务，可根据现有业务行为保留。

---

## 28. 恢复机制

如果：

```text
suspendedFrames.isEmpty()
```

无需恢复。

如果不为空：

```text
candidate = suspendedFrames.peek()
```

发送恢复询问。

该候选 Frame 进入：

```text
RESUME_CONFIRMING
```

不要立即 pop。

只有用户明确继续或放弃以后才修改栈。

---

## 29. 恢复按钮升级为结构化协议

当前主协议：

```text
【管道恢复】继续
【管道恢复】放弃
```

逐步升级。

继续：

```json
{
  "action": "resume_frame",
  "frameId": "F001"
}
```

放弃：

```json
{
  "action": "abandon_frame",
  "frameId": "F001"
}
```

旧文本协议：

```text
【管道恢复】继续
【管道恢复】放弃
```

第一阶段继续兼容。

不要一次删除。

---

## 30. 自然语言恢复作为兜底

继续支持：

```text
继续
继续之前的
回到刚才
恢复刚才那个
好的继续
```

以及：

```text
不用了
放弃
别查了
之前那个不要了
```

但自然语言解析属于 fallback。

优先级：

```text
Structured Action
>
Natural Language
```

---

## 31. 恢复必须校验 frameId

用户点击：

```json
{
  "action": "resume_frame",
  "frameId": "A"
}
```

后端必须检查：

```text
suspendedFrames.peek().frameId == A
```

如果不是：

```text
STALE_ACTION
```

禁止跳过栈顶直接恢复深层 Frame。

---

## 32. 嵌套恢复规则

假设：

```text
Stack:
A
B

Current:
C
```

C 完成：

```text
先询问 B
```

用户放弃 B：

```text
B = ABANDONED
pop B
```

继续：

```text
询问 A
```

用户继续：

```text
pop A
currentFrame = A
resume A
```

禁止：

```text
C 完成以后直接恢复 A。
```

---

## 33. RESUME_CONFIRMING 期间再次穿插

如果 B 已完成，现在正在询问是否恢复 A。

此时用户说：

```text
先帮我查一下百度
```

必须允许创建：

```text
Frame C
```

A 继续保留在 Stack。

C 完成后再次询问 A。

不要因为处于恢复询问阶段就丢失 A。

---

## 34. interruptAskPending 的处理

当前如果存在：

```text
interruptAskPending
```

第一阶段不要求立即删除。

但是要逐步将语义迁移为：

```text
FrameStatus.RESUME_CONFIRMING
```

目标是减少：

```text
多个 boolean 组合形成隐性状态机。
```

---

## 35. 企业上下文必须 Frame 化

当前已经支持挂起快照保存：

```text
companyName
creditCode
```

这一能力必须保留。

逐步抽象：

```java
public class BusinessContext {

    private String companyName;

    private String creditCode;

    private Map<String, Object> resolvedEntities;

    private Map<String, Object> parameters;
}
```

每个 Frame 保存自己的 BusinessContext。

---

## 36. 恢复时必须先还原 Context

顺序应该：

```text
pop Frame
     ↓
restore BusinessContext
     ↓
restore pending interaction
     ↓
consume deferred event
     ↓
resume pipeline
```

禁止：

```text
先 resume pipeline
再恢复 company context
```

否则可能串企业。

---

## 37. 报告机制第一阶段不得破坏

当前：

```text
waitingReportTask
pendingReportDone
deferred
```

机制属于当前新项目重要优势。

第一阶段：

> 只给 ReportTask 增加 frameId，并保证 callback 可以准确找到所属 Frame。

禁止为了泛化异步事件直接删除现有工作正常的 Report 逻辑。

---

## 38. 报告 Callback 必须根据 frameId 定位

报告提交时写入：

```text
externalTaskId
frameId
stepId
```

报告完成时：

```text
REPORT_COMPLETED
frameId = A
```

不能根据：

```text
session.currentFrame
current company
current pipeline
```

判断属于谁。

---

## 39. 第二阶段泛化 DeferredEvent

报告机制稳定以后，再逐步抽象：

```java
public class PendingExternalTask {

    private String externalTaskId;

    private String frameId;

    private String stepId;

    private ExternalTaskType type;

    private ExternalTaskStatus status;

    private Object metadata;
}
```

以及：

```java
public class DeferredEvent {

    private String eventId;

    private String frameId;

    private String externalTaskId;

    private EventType type;

    private Object payload;

    private Instant occurredAt;
}
```

支持：

```text
REPORT_COMPLETED
ASYNC_QUERY_COMPLETED
EXPORT_COMPLETED
APPROVAL_COMPLETED
CALLBACK_RECEIVED
```

---

## 40. DeferredEvent 处理原则

如果目标 Frame：

```text
RUNNING
```

根据现有业务正常消费。

如果：

```text
SUSPENDED
```

则：

```text
frame.deferredEvents.add(event)
```

禁止推进被挂起 Frame。

恢复该 Frame 时：

```text
consumeDeferredEvents(frame)
```

然后再决定：

```text
继续 Pipeline
或
等待其他事件
```

---

## 41. 前端 TaskProgressCard 改造

当前项目没有旧项目独立 Plan Panel，因此第一阶段继续使用：

```text
TaskProgressCard
```

但卡片必须逐步携带：

```text
frameId
planId
```

步骤级卡片可增加：

```text
stepId
```

---

## 42. 禁止继续主要通过 Plan 文本匹配历史卡

当前恢复时如果通过：

```text
plan 内容相同
```

匹配历史 TaskProgressCard，应逐步替换。

目标：

```text
frameId
+
planId
```

精确定位。

---

## 43. PendingInteraction

建议将：

```text
企业候选选择
参数补充
模板选择
日期输入
恢复确认
```

逐步统一成：

```java
public class PendingInteraction {

    private String interactionId;

    private String frameId;

    private String stepId;

    private InteractionType type;

    private InteractionStatus status;

    private Object payload;
}
```

第一阶段至少要求：

```text
frameId + interactionId
```

能够定位卡片属于哪个任务。

---

## 44. 旧卡点击

例如：

```text
Frame A = SUSPENDED
Frame B = RUNNING
```

用户点击 A 的候选卡。

后端检查：

```text
interaction.frameId = A
A.status = SUSPENDED
```

应该返回类似：

```text
INTERACTION_SUSPENDED
```

而不是把该值传给 B。

---

## 45. 前端禁用只是 UX，不是安全保证

前端可以继续：

```text
disable suspended frame cards
```

方便用户理解。

但后端仍必须执行：

```text
frameId
interactionId
FrameStatus
```

校验。

必须坚持：

> 前端负责体验，后端负责状态正确性。

---

## 46. 最大穿插层数

继续允许嵌套穿插，但增加限制。

建议配置：

```text
MAX_INTERRUPT_DEPTH
```

默认：

```text
5
```

如果已有配置体系，加入配置文件。

超过上限提示：

```text
当前已有多个暂存任务，请先完成或放弃一个任务，再开始新的任务。
```

不要硬编码到多个业务类。

---

## 47. 推荐后端模块结构

最终建议逐步形成：

```text
InputRouter
│
├── ProtocolActionHandler
│
├── PipelineInputClassifier
│   ├── ExplicitInterruptDetector
│   ├── ExpectedInputMatcher
│   ├── IntentMatcher
│   ├── ContextShiftDetector
│   └── LlmInterruptJudge
│
├── FrameManager
│
├── PipelineExecutor
│
├── PlanProjectionService
│
├── InteractionManager
│
├── ExternalTaskManager
│
├── DeferredEventManager
│
└── InterruptResumeCoordinator
```

不要强制为了这个名字重命名所有现有类。

应优先：

> 根据现有项目模块职责映射这些概念。

---

## 48. 推荐修改阶段

### Phase 0：现状分析

AI 必须先输出：

```text
当前 Pipeline 主入口
当前 Session 状态
当前挂起对象
当前恢复入口
当前意图分类器
当前报告 callback
当前前端事件
```

并给出文件路径和方法名。

本阶段不修改代码。

### Phase 1：加入 frameId

目标：

```text
一个独立意图 = 一个 frameId
```

打通：

```text
Runtime
→ Suspend
→ Restore
→ Report
→ SSE
→ Frontend Card
```

暂时保持原业务逻辑。

### Phase 2：建立 ExecutionFrame

将现有挂起快照逐步包装为：

```text
ExecutionFrame
```

保留当前字段和逻辑。

不得大规模删除旧模型。

### Phase 3：Plan 状态投影

加入：

```text
PlanStepStatus
PlanProjectionService
```

让 Pipeline 状态可以同步到 Plan。

暂时不让 Plan 参与调度。

### Phase 4：升级 classifyPipelineInput

调整为：

```text
Protocol
↓
Explicit Interrupt
↓
Expected Input
↓
IntentMatcher
↓
Context Shift
↓
LLM
```

并添加单元测试。

### Phase 5：结构化恢复协议

加入：

```text
resume_frame
abandon_frame
```

并携带：

```text
frameId
```

旧文本协议继续兼容。

### Phase 6：交互 ID 化

企业选择、模板选择、恢复卡等加入：

```text
interactionId
frameId
```

后端增加旧卡校验。

### Phase 7：报告事件泛化

在不破坏当前 Report 机制前提下：

```text
waitingReportTask
pendingReportDone
```

逐步迁移到：

```text
PendingExternalTask
DeferredEvent
```

### Phase 8：清理旧逻辑

只有前面所有测试通过之后，再考虑清理：

```text
interruptAskPending
文本卡片匹配
旧 resume special branch
重复 pendingXXX
兼容协议
```

不能提前删除。

---

## 49. 必须增加的日志

重要状态变化必须输出结构化日志。

例如：

```text
FRAME_CREATED
frameId=F2
parentFrameId=F1
intent=query_risk
```

```text
FRAME_SUSPENDED
frameId=F1
reason=NEW_INTENT
```

```text
INPUT_CLASSIFIED
frameId=F1
result=NEW_INTENT
source=CONTEXT_SHIFT
```

```text
FRAME_RESUMED
frameId=F1
```

```text
EXTERNAL_EVENT_DEFERRED
frameId=F1
event=REPORT_COMPLETED
```

```text
DEFERRED_EVENT_CONSUMED
frameId=F1
event=REPORT_COMPLETED
```

方便之后排查状态污染。

---

## 50. 必须增加的测试

以下场景全部必须测试。

### Case 1：普通候选补充

```text
Agent：请选择公司
User：第二个
```

预期：

```text
SUPPLEMENT
```

不能创建新 Frame。

### Case 2：显式穿插

```text
Agent：请选择公司
User：先别管这个，顺便查一下阿里
```

预期：

```text
NEW_INTENT
```

旧 Frame 入栈。

### Case 3：等待企业名时输入企业

```text
Agent：请输入企业名称
User：阿里巴巴
```

预期：

```text
SUPPLEMENT
```

不能因为企业变化判穿插。

### Case 4：同 Skill 切企业

当前：

```text
腾讯股东
```

User：

```text
阿里的股东呢？
```

预期：

```text
NEW_INTENT
```

### Case 5：不同 Skill

当前：

```text
查询股东
```

User：

```text
顺便生成风险报告
```

预期：

```text
NEW_INTENT
```

### Case 6：普通自然语言补充

当前正在等待日期。

User：

```text
就查去年一整年
```

预期：

```text
SUPPLEMENT
```

### Case 7：一层穿插

```text
A → B
```

B 完成后询问是否恢复 A。

### Case 8：两层穿插

```text
A → B → C
```

C 结束后先处理 B。

不能直接 A。

### Case 9：放弃栈顶

```text
A → B → C
```

C 完成。

用户放弃 B。

随后询问 A。

### Case 10：恢复询问期间再次穿插

正在询问是否恢复 A。

用户：

```text
先查百度
```

预期：

```text
A 保留
新建 C
```

C 完成后继续询问 A。

### Case 11：企业上下文恢复

A：

```text
腾讯
```

B：

```text
阿里
```

恢复 A 后：

```text
companyName = 腾讯
creditCode = 腾讯对应 code
```

不能残留阿里。

### Case 12：报告挂起期间完成

A 等报告。

用户穿插 B。

A 报告完成。

预期：

```text
事件保存到 A
不推进 A
```

恢复 A：

```text
消费报告完成事件
继续 Pipeline
```

### Case 13：旧恢复按钮

当前允许恢复 B。

用户点击旧 A 卡：

```text
resume_frame(A)
```

预期：

```text
STALE_ACTION
```

不能恢复 A。

### Case 14：挂起旧交互卡

A 挂起。

当前 B。

用户点击 A 的 CompanyCandidateCard。

预期：

```text
INTERACTION_SUSPENDED
```

不能污染 B。

### Case 15：达到最大嵌套层数

达到：

```text
MAX_INTERRUPT_DEPTH
```

再请求新意图。

预期：

```text
拒绝继续入栈
```

原有 Stack 不受影响。

---

## 51. LLM 调用测试

必须确保：

以下场景：

```text
第二个
阿里巴巴有限公司
2025年1月至12月
继续
放弃
结构化按钮
明显顺便查询
```

原则上不调用 LLM。

只有规则不明确的复杂语言才进入：

```text
CoordinatorService.classifyIntentInterrupt
```

---

## 52. 回归测试要求

除了新增穿插测试，必须保证现有功能不回退：

- 普通单 Skill；
- 普通 Pipeline；
- 多任务自动链式执行；
- 企业候选选择；
- 模板选择；
- 报告生成；
- 报告 SSE；
- TaskProgressCard；
- 普通自然语言输入；
- 原有 `【管道恢复】继续`；
- 原有 `【管道恢复】放弃`；
- 当前已有嵌套穿插；
- 企业上下文恢复。

---

## 53. 完成标准

只有满足以下条件才能认为融合完成。

### 运行模型

```text
ExecutionFrame
```

成为独立任务的统一运行上下文。

### 穿插模型

```text
Deque<ExecutionFrame>
```

能够稳定实现 LIFO。

### 分类模型

执行顺序：

```text
Protocol
→ Explicit
→ Expected Input
→ IntentMatcher
→ Context Shift
→ LLM
```

### 上下文

企业和任务参数 Frame 隔离。

### 异步

报告在 Frame 挂起期间完成不会推动错误任务。

### ID

至少建立：

```text
frameId
planId
stepId
interactionId
```

主要链路。

### 前端

历史任务卡不再主要依赖文本匹配。

### 恢复

结构化：

```text
resume_frame
abandon_frame
```

成为主协议。

### 兼容

旧文本协议在过渡期间仍可使用。

---

## 54. AI 执行时的强制要求

AI 不得收到本文档后直接一次修改几十个文件。

必须按照以下工作方式执行。

第一步：

> 阅读代码并输出当前实现分析。

第二步：

> 输出“现有实现 → 本方案”的字段和类映射表。

第三步：

> 给出实际修改阶段和文件列表。

第四步：

> 从 Phase 1 开始实施。

每完成一个 Phase：

- 编译；
- 检查调用链；
- 运行相关测试；
- 输出修改内容；
- 输出风险点；
- 确认没有破坏旧功能；
- 再进入下一 Phase。

---

## 55. AI 不得自行做的事情

未经现有代码证明，不得自行假设：

- 类名；
- Controller 名；
- Service 名；
- DTO 名；
- SSE event 名；
- 前端组件具体路径；
- PipelineTask 实际数据结构；
- Report callback 实现；
- Session 存储方式。

如果本文档中的示例类名与实际代码不同：

> 优先沿用现有项目命名，只实现对应职责。

---

## 56. AI 修改时的核心判断标准

如果一个改动存在两种实现方式：

### 方案 A

需要大规模改 Pipeline。

### 方案 B

可以在现有 Pipeline 外增加 Frame/ID/Projection 层。

优先：

```text
方案 B
```

如果一个改动：

### 方案 A

让 LLM 判断。

### 方案 B

已有可靠规则能够判断。

优先：

```text
方案 B
```

如果一个状态：

### 方案 A

继续新增 boolean。

### 方案 B

可以清晰表达为 FrameStatus / InteractionStatus / ExternalTaskStatus。

优先：

```text
方案 B
```

但旧 boolean 不要求立即删除，应渐进迁移。

---

## 57. 最终目标模型

最终项目应逐步接近：

```text
                   ConversationSession
                           │
              ┌────────────┴────────────┐
              │                         │
        Current Frame            Suspended Stack
              │                         │
              └────────ExecutionFrame───┘
                           │
             ┌─────────────┼──────────────┐
             │             │              │
         PlanRuntime   ExecutionRuntime   Context
             │             │              │
         PlanStep[]      Pipeline       Company
                           │
                     PendingSkill
                           │
                ┌──────────┴──────────┐
                │                     │
        PendingInteraction     ExternalTask
                                      │
                                DeferredEvent
```

---

## 58. 架构核心定义

本项目融合后的核心原则统一定义为：

> **Pipeline 是事实，Plan 是视图。**

> **Frame 是恢复单位，Step 是展示单位。**

> **Context 属于 Frame，而不是临时共享状态。**

> **Stack 解决同步穿插，DeferredEvent 解决异步穿插。**

> **规则负责确定性判断，LLM 只负责模糊仲裁。**

> **所有异步回调和交互通过 ID 找任务，而不是通过当前 Session 状态猜任务。**

---

## 59. 最重要的迁移原则

整个升级最重要的一条要求：

> **不要先重构 Pipeline，再做穿插；应该先在现有 Pipeline 外建立 ExecutionFrame 和 frameId，把现有成熟执行逻辑包起来。**

因此第一阶段必须优先做到：

```text
现有 Pipeline 行为不变
+
每个独立任务拥有 frameId
+
现有挂起快照归属于 ExecutionFrame
```

确认稳定以后，再逐步增加：

```text
PlanStepStatus
Structured Action
ExpectedInputMatcher
DeferredEvent
InteractionId
```

这样才能最大程度避免破坏当前已经实现好的：

- 嵌套穿插；
- Pipeline 自动执行；
- 报告 deferred；
- 企业上下文恢复；
- interruptAskCheck 统一恢复入口。

---

## 60. 给代码修改 AI 的最终指令

请严格依据本文档改造现有项目。

不要直接套用本文档中的示例代码，因为示例代码只表达目标职责。

首先阅读项目现有实现，并基于实际代码回答：

1. 当前意图穿插完整调用链是什么？
2. 当前 Pipeline 状态分别保存在哪里？
3. 当前挂起栈元素是什么类型？
4. 当前企业上下文如何保存和恢复？
5. 当前报告生成、完成回调、deferred 的完整调用链是什么？
6. 当前 `classifyPipelineInput` 的真实判断顺序是什么？
7. 当前 `interruptAskCheck` 在哪些稳定点调用？
8. 当前前端 TaskProgressCard 如何创建、更新和恢复？
9. 哪些位置需要新增 frameId？
10. 哪些现有类可以直接扩展，哪些确实需要新建？

然后输出：

```text
现状分析
↓
问题清单
↓
目标映射
↓
修改文件清单
↓
Phase 1 修改方案
```

在完成以上分析之前：

> **不要直接修改代码。**

之后按照：

```text
Phase 1 frameId
Phase 2 ExecutionFrame
Phase 3 Plan 状态投影
Phase 4 IntentClassifier
Phase 5 Structured Resume
Phase 6 Interaction Identity
Phase 7 ExternalTask / DeferredEvent
Phase 8 Legacy Cleanup
```

逐阶段实施。

每个阶段都必须确保：

```text
可编译
可回归
旧能力不退化
新增能力有测试
```

最终目标不是把两个旧方案机械合并，而是形成一个统一且可继续扩展的：

```text
Execution Context Stack Architecture
```

即：

> **以 ExecutionFrame 保存完整现场，以 Pipeline 驱动执行，以 Plan 描述状态，以 Stack 支持嵌套穿插，以 DeferredEvent 处理挂起期间异步完成事件。**
