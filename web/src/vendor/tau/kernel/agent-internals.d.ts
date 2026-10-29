import type { Agent, AgentEvent, AgentOptions } from "./agent.js";
import type { ImageProcessor } from "./capabilities.js";
import type { CompactionSettings, SummarizationRetry } from "./compaction.js";
import type { CompactionReason, ExtensionCapabilities, ExtensionContext, ExtensionRegistry, ThinkingLevel, UiCapability } from "./extensions.js";
import type { AgentMessage, ModelPricing, Usage } from "./messages.js";
import type { ChatTransport, OpenAICompatConfig, TransportRequest } from "./openai.js";
import type { QueueMode } from "./pending-queue.js";
import type { Platform } from "./platform.js";
import type { ApprovalRequest, PermissionMode, ToolPolicy } from "./policy.js";
import type { RetrySettings } from "./retry.js";
import type { SessionEntry, SessionRecorder } from "./session.js";
import type { PreflightLock } from "./tool-batch.js";
import type { Tool, ToolExecutionMode } from "./tools.js";
/** Agent state and methods the split-out modules use, read live. */
export interface AgentInternals {
    /** The branch's entries: the transcript a restore reads. */
    readonly activePath: readonly SessionEntry[];
    /** Tools the agent was created with; extension tools override them by name (pi). */
    readonly baseTools: Tool[];
    readonly branchSummaryReserveTokens: number | undefined;
    readonly capabilities: ExtensionCapabilities;
    readonly compactionSettings: CompactionSettings;
    readonly config: OpenAICompatConfig;
    createAgent(options: AgentOptions): Agent;
    readonly currentModel: string;
    readonly currentThinkingLevel: ThinkingLevel | undefined;
    /** Every registered tool of the parent (base, extension, MCP). */
    currentTools(): Map<string, Tool>;
    effectiveCompactionSettings(): CompactionSettings;
    enqueueBackground(write: () => Promise<unknown>): void;
    enqueueWrite<T>(write: () => Promise<T>): Promise<T>;
    extensionContext(): ExtensionContext;
    readonly extensions: ExtensionRegistry | undefined;
    flushWrites(): Promise<void>;
    readonly followUpMode: QueueMode;
    readonly hiddenDeclarations: ReadonlySet<string>;
    readonly imageProcessor: ImageProcessor | undefined;
    readonly isProjectTrusted: (() => boolean) | undefined;
    readonly maxTurnsPerPrompt: number;
    readonly messages: readonly AgentMessage[];
    readonly onApproval: ((request: ApprovalRequest) => Promise<boolean>) | undefined;
    readonly permissionMode: PermissionMode;
    readonly platform: Platform;
    /** For whole-tool deny rules (P37a). */
    readonly policy: ToolPolicy;
    readonly preflightLock: PreflightLock;
    priced(usage: Usage): Usage;
    readonly pricing: AgentOptions["pricing"];
    pricingFor(model: string): ModelPricing | undefined;
    providerEventHooks(ctx: ExtensionContext, model: string): Pick<TransportRequest, "onPayload" | "onResponse" | "onStreamEvent"> | undefined;
    providerHeaders(ctx: ExtensionContext): Promise<Record<string, string | null> | undefined>;
    refresh(): Promise<void>;
    /** The tools the new branch's transcript recorded become active (pi). */
    restoreToolsFromTranscript(): void;
    readonly retrySettings: RetrySettings;
    readonly session: SessionRecorder;
    readonly steeringMode: QueueMode;
    summarizationRetry(source: {
        source: "compaction";
        reason: CompactionReason;
    } | {
        source: "branchSummary";
    }, emit?: (event: AgentEvent) => void): SummarizationRetry;
    summaryTransport(): ChatTransport;
    /** The output cap for summary requests (P53c: the summary model's when there is one). */
    summaryMaxTokens(): number | undefined;
    readonly systemPrompt: string | undefined;
    readonly toolExecution: ToolExecutionMode;
    readonly transport: ChatTransport;
    readonly ui: UiCapability | undefined;
}
