# BOSS Mission Control Plugin

Mission Control introduces a shared human-agent workspace for long-running workflows. It provides a visual dashboard for active tasks and a mechanism for agents to safely request human approval before executing sensitive operations.

## The Problem
When autonomous agents perform complex, multi-step tasks, they frequently encounter decisions requiring human judgment or permissions (e.g., approving a deployment, resolving a merge conflict, providing 2FA codes). Traditional terminal-based interaction forces the agent to block execution or abort. Mission Control provides a stateful, UI-driven handoff abstraction.

## The Solution
Mission Control operates via a standalone Compose Multiplatform panel and three MCP tools. It allows an agent to broadcast its semantic goal and current step, and to pause execution using a cooperative timeout model until a human resolves the handoff.

## Architecture

```text
             External Agent
                   │
                   │ MCP
                   ▼
            MissionMcpTools
                   │
                   ▼
             MissionManager
             │           │
             │           └── HandoffRequest
             │
             ▼
            StateFlow
                   │
                   ▼
            Compose UI
                   │
          ┌────────┴────────┐
          ▼                 ▼
   Return Control      Cancel Mission
```

## Technical Design
* **Mission State**: A single source of truth managed by `MissionManager` and exposed as a Kotlin `StateFlow`.
* **MCP Invocation**: The agent's side of the tool execution. Uses cooperative 10-second timeouts (`PENDING`) to avoid locking the host server for hours while waiting for human input.
* **HandoffRequest**: The durable logical boundary storing the human's response via a `CompletableDeferred`. Persists across multiple cooperative MCP polling cycles.
* **CompletableDeferred**: A thread-safe synchronization primitive connecting the human's Compose UI click to the suspended MCP request.

## MCP Tools

1. `mission_create(mission_id, goal)`: Establishes a new mission in the `RUNNING` state.
2. `mission_update(mission_id, step, status)`: Updates the current execution step. Status defaults to `RUNNING`.
3. `mission_handoff(mission_id, reason)`: Suspends the workflow and transitions the UI to `WAITING_FOR_HUMAN`.

## Example Workflow

```text
create
→ update
→ handoff
→ human response
→ update
→ complete
```

## Limitations
* **No OS Process Termination**: 'Cancel Mission' cancels the *mission state* and returns an error to the MCP tool. It does not send `SIGKILL` or `SIGINT` to the underlying OS process running the agent.
* **One Active Mission**: The MVP supports exactly one active mission globally.
* **In-Memory State**: Mission state is transient and does not persist across application restarts.
* **Cooperative Timeout/Polling**: The LLM must support handling `PENDING` states and re-polling.

## Installation
Build the plugin from source:
```bash
./gradlew buildPluginJar
```
Install the resulting JAR (`build/libs/mission-control-0.1.0.jar`) into your BOSS plugin directory.

## Testing
Comprehensive test suites cover domain logic, synchronization, and MCP schema validation.
```bash
./gradlew test
```
