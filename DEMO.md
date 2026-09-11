# Mission Control - Hackathon Demo Script

This script provides a reliable, deterministic 3-minute presentation flow. It does not require an LLM or cloud connection, ensuring zero network risk during the presentation.

## Setup
1. Build the plugin: `./gradlew buildPluginJar`
2. Install to your BossConsole dev directory (`~/.boss_debug/plugins/`).
3. Start BossConsole: `./gradlew run`

## Script

### 0:00–0:20: The Problem
*(Show terminal or standard Agent panel without Mission Control)*
"When agents execute long-running tasks, they often encounter decisions that require human judgment—like approving a staging deployment. Currently, agents either block the main terminal thread or abort the task. Mission Control solves this by giving the agent a semantic way to pause and request human intervention while maintaining state."

### 0:20–0:45: Starting the Mission
*(Invoke `mission_create` via MCP tool manually or using a deterministic script)*
"The agent begins by creating a mission. Notice the Mission Control UI instantly reacts, showing the overarching goal: 'Deploy application to staging', and the status 'RUNNING'."

### 0:45–1:10: Live Progress
*(Invoke `mission_update` several times)*
"As the agent works, it updates its semantic step. It is 'Analyzing project', 'Running tests', and 'Preparing staging deployment'. The UI tracks this progress without requiring the user to read raw terminal logs."

### 1:10–1:40: Human Handoff
*(Invoke `mission_handoff`)*
"The agent encounters a critical juncture: it requires permission to deploy. It invokes the `mission_handoff` MCP tool. The UI immediately transforms into a WAITING_FOR_HUMAN state, highlighting the exact reason. Under the hood, the MCP invocation uses a cooperative 10-second polling timeout to maintain the logical handoff without wedging the host."

### 1:40–2:00: Human Resolves Handoff
*(Click 'RETURN CONTROL' in the UI)*
"The human reviews the request and clicks 'Return Control'. The pending MCP invocation immediately resolves and returns the human's response to the agent."

### 2:00–2:20: Completion
*(Invoke `mission_update` with status COMPLETED)*
"The agent resumes execution and completes the deployment. The mission enters the COMPLETED state."

### 2:20–2:40: Safety / Cancellation
*(Briefly show Cancel Mission)*
"If the human decides the agent is doing something dangerous during a handoff or execution, the 'Cancel Mission' button instantly terminates the logical handoff and aborts the agent's MCP call, securing the workspace."
