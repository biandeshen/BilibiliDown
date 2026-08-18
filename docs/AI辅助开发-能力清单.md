# AI 辅助开发能力清单

> **文档定位**：基于权威来源整理 AI 在软件开发各阶段的适用能力，作为团队推广 AI 辅助开发的参考依据。
> **权威来源**：Anthropic Claude Code 官方 Best Practices、GitHub Copilot 官方文档、McKinsey 生成式 AI 经济潜力报告、Google DORA State of DevOps。

---

## 一、最擅长的核心开发任务

### 1.1 代码编写与补全（★★★★★）

| 能力 | 说明 | 来源 |
|------|------|------|
| 写测试和重复性代码 | Copilot 官方列为"做得最好的事情"之一 | GitHub Copilot Best Practices |
| 代码补全 | 变量名、函数、片段的智能补全 | GitHub Copilot Best Practices |
| 从自然语言注释生成代码 | 根据 inline comment 生成实现 | GitHub Copilot Best Practices |
| 生成正则表达式 | 复杂正则的可读性生成 | GitHub Copilot Best Practices |
| TDD 场景下生成测试 | 配合测试驱动开发流程 | GitHub Copilot Best Practices |

### 1.2 代码理解与解释（★★★★★）

| 能力 | 说明 | 来源 |
|------|------|------|
| 解释代码 | Copilot 官方列为优势能力 | GitHub Copilot Best Practices |
| 代码库问答 | Claude Code 列为首要沟通模式 "Ask codebase questions" | Claude Code Best Practices |
| 梳理 git 历史 | 总结 API 演进、设计决策来源 | Claude Code Best Practices |
| 为代码添加注释/文档 | 批量补全文档与注释 | Claude Code Best Practices |

### 1.3 调试与错误修复（★★★★★）

| 能力 | 说明 | 来源 |
|------|------|------|
| 定位根因 | "Address root causes, not symptoms" | Claude Code Best Practices |
| 修复语法错误 | 基于 lint / 编译报错修复 | GitHub Copilot Best Practices |
| 基于报错信息修复 bug | 贴报错，让 AI 修根因不抑制错误 | Claude Code Best Practices |
| 写复现失败的测试，再修复 | "write a failing test that reproduces the issue, then fix it" | Claude Code Best Practices |

### 1.4 探索 → 规划 → 实现（Anthropic 核心工作流）

Claude Code 官方推荐的 4 阶段流程，针对复杂任务：

1. **Explore（探索）**：进入 plan mode，读文件、答问题、不改代码
2. **Plan（规划）**：生成详细实现计划
3. **Implement（实现）**：按计划编码，对照验证
4. **Commit（提交）**：写规范 commit message，开 PR

> **何时跳过规划**：scope 清晰、改动很小（typo、日志行、重命名）时直接做；改动多文件、不熟悉代码、方法不确定时才用 plan mode。

---

## 二、McKinsey：四大高价值领域

McKinsey《The economic potential of generative AI》指出，**生成式 AI 约 75% 的价值集中在四个领域**：

| 领域 | 在项目开发中的应用 |
|------|------------------|
| **软件工程** | 代码生成、重构、文档、测试 |
| **客户运营** | 自动回复、工单分类、SLA 监控 |
| **营销与销售** | 创意内容、个性化文案 |
| **研发** | 文献综述、实验设计、数据分析 |

> 生成式 AI 每年可创造 **2.6 万亿–4.4 万亿美元** 经济价值，软件工程是核心赛道之一。

---

## 三、AI 不擅长 / 需要警惕的方面

### 3.1 Copilot 官方明确"不擅长"

- **非编程/技术类问题** — 不应回答
- **不能替代你的专业判断** — "You are in charge, Copilot is a tool at your service"

### 3.2 Anthropic 官方警告的失败模式

| 失败模式 | 风险描述 |
|---------|---------|
| 上下文窗口填满后性能下降 | "Claude may start forgetting earlier instructions or making more mistakes" |
| 没有验证机制时"看起来对但实际不对" | "Without clear success criteria, it might produce something that looks right but actually doesn't work" |
| 直接跳过规划编码 | "Letting Claude jump straight to coding can produce code that solves the wrong problem" |

### 3.3 通用风险（McKinsey 报告）

- **Hallucination（幻觉）**：生成看似合理但错误的内容
- **IP / 版权风险**：可能复用公开代码片段
- **配套缺失**：需要技能转型与流程改造才能兑现价值

---

## 四、提升 AI 效果的关键实践

Anthropic 官方强调：**"让 Claude 能验证自己的工作"是最高杠杆的一件事**。

| 策略 | 反面 | 正面 |
|------|------|------|
| 提供验证标准 | "implement email validator" | "写一个 validateEmail 函数，给测试用例 user@example.com 为 true、invalid 为 false、user@.com 为 false，实现后运行测试" |
| UI 视觉对比 | "make the dashboard look better" | "[贴截图] 实现该设计，截图对比原版，列差异并修复" |
| 根因而非症状 | "the build is failing" | "build 报这个错 [贴错]。修根因，不要抑制错误" |

### 4.1 CLAUDE.md / .cursorrules / Copilot 指令文件最佳实践

Anthropic 官方对 CLAUDE.md 的指导原则，可推广至各类 AI 工具的指令文件：

| ✅ 应包含 | ❌ 不应包含 |
|----------|----------|
| Claude 无法猜到的 Bash 命令 | 读代码就能搞清楚的事 |
| 与默认风格不同的代码风格规则 | Claude 已知的语言惯例 |
| 测试指令和首选 test runner | 详细的 API 文档（应链接而非内嵌） |
| 仓库礼仪（分支命名、PR 约定） | 频繁变化的信息 |
| 项目特定的架构决策 | 逐文件的代码库描述 |
| 开发环境怪癖（必需的环境变量） | 显而易见的事 |
| 常见 gotcha 与非显而易见的行为 | "写干净代码"这类自明的废话 |

> **核心原则**：每行都问自己 "删掉这行，Claude 会犯错吗？" — 不会就删。臃肿的指令文件会让 AI 忽略你真正的指令。

---

## 五、项目全生命周期的 AI 适用度

| 项目阶段 | 适用度 | 最适合的具体任务 |
|---------|--------|----------------|
| **需求分析** | ★★★☆☆ | 文档梳理、用户故事拆解、Epic 细化、需求评审辅助 |
| **架构设计** | ★★★☆☆ | 方案对比、正反审查、技术选型参考、设计文档草拟 |
| **编码** | ★★★★★ | 补全、生成、重构、命名、正则、样板代码 |
| **测试** | ★★★★★ | 单测、TDD、边界用例、覆盖率提升、测试数据生成 |
| **调试** | ★★★★★ | 报错分析、根因定位、复现脚本、堆栈解读 |
| **代码审查** | ★★★★☆ | PR review、安全扫描、风格检查、最佳实践对照 |
| **文档** | ★★★★★ | API 文档、注释、README、变更日志、ADR |
| **CI/CD** | ★★★☆☆ | 流水线脚本、配置生成、故障排查、YAML 生成 |
| **运维** | ★★★☆☆ | 日志分析、告警归因、容量预估、SOP 起草 |
| **部署决策** | ★★☆☆☆ | 数据分析辅助，最终决策需人工 |

---

## 六、AI 工具能力对比

| 工具类型 | 代表产品 | 最适合场景 | 局限 |
|---------|---------|----------|------|
| **IDE 内嵌补全** | GitHub Copilot, Cursor, JetBrains AI | 行内补全、片段生成、快速重构 | 上下文有限，复杂任务力不从心 |
| **AI 对话助手** | ChatGPT, Claude.ai, Gemini | 方案设计、代码解释、学习、文档 | 不能直接操作代码库 |
| **Agent 编码环境** | Claude Code, Cursor Agent, Aider | 多文件改动、自主探索、自主验证 | 上下文窗口易满，需主动管理 |
| **CI/CD 集成** | CodeRabbit, Graphite, Copilot for PR | 自动 PR review、代码质量门禁 | 配置成本高，规则需调优 |
| **领域专用** | AlphaCode, Tabnine, Amazon Q | 竞赛编程、企业知识库、AWS 集成 | 通用性弱 |

---

## 七、关键参考文档

| 文档 | 提供方 | 链接 |
|------|-------|------|
| Claude Code Best Practices | Anthropic 官方 | https://code.claude.com/docs/en/best-practices |
| Best practices for using GitHub Copilot | GitHub 官方 | https://docs.github.com/en/copilot/using-github-copilot/best-practices-for-using-github-copilot |
| The economic potential of generative AI | McKinsey | https://www.mckinsey.com/capabilities/mckinsey-digital/our-insights/the-economic-potential-of-generative-ai-the-next-productivity-frontier |
| The state of AI in 2025 | McKinsey | https://www.mckinsey.com/capabilities/quantumblack/our-insights/the-state-of-ai |
| Google Cloud State of DevOps Report | Google DORA | https://cloud.google.com/devops/state-of-devops/ |
| AlphaCode | Google DeepMind | https://deepmind.google/discover/blog/competitive-programming-with-alphacode/ |

---

## 八、版本记录

| 版本 | 日期 | 修订 |
|------|------|------|
| v1.0 | 2026-08-18 | 首版发布，基于权威来源整理 |
