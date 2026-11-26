import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type TaskHostState = {
  phase: string;
  tool: string | null;
  requestNonce: string | null;
  requestHash: string | null;
  backendEpoch: string | null;
  target: string | null;
  approvalActor: string | null;
  approvalState: string | null;
  cleanupState: string;
  deadlineNanos: string | null;
  code: string | null;
  message: string | null;
  resultJson: string;
};

export type TaskHostReadiness = {
  packageUid: number;
  linuxReady: boolean;
  rootfsGeneration: string | null;
  displayReady: boolean;
  displayEpoch: string | null;
  displayId: number | null;
};

export type SingleTaskState = {
  taskId: string | null;
  goal: string | null;
  phase: string;
  code: string | null;
  stepsJson: string;
  contextBytes: number;
  maxContextBytes: number;
  maxResultBytes: number;
  maxSteps: number;
  maxDurationMillis: number;
};

export type ModelConfiguration = {
  endpoint: string;
  model: string;
  hasCredential: boolean;
};

export type AgentLoopState = {
  phase: string;
  taskId: string | null;
  rounds: number;
  outputTokens: number;
  contextBytes: number;
  finalText: string | null;
  code: string | null;
  memoryRevision?: string | null;
  memoryIdsJson?: string;
  memoryBytes?: number;
};

export type MemorySnapshot = {
  revision: string;
  notesJson: string;
  maxNotes: number;
  maxNoteBytes: number;
  maxSelected: number;
  maxSelectedBytes: number;
};

export type CompleteTaskState = {
  runId: string | null;
  taskId: string | null;
  phase: string;
  stage: string;
  pendingEffect: string;
  inputDigest: string;
  expectedText: string;
  outputDigest: string;
  code: string | null;
  displayId: number | null;
  displayEpoch: string | null;
  packageUid: number;
};

export interface Spec extends TurboModule {
  getCompleteTaskState(): Promise<CompleteTaskState>;
  prepareCompleteTask(): Promise<CompleteTaskState>;
  beginCompleteTask(expectedRunId: string, expectedDisplayId: number,
    expectedDisplayEpoch: string): Promise<CompleteTaskState>;
  startCompleteAgent(expectedRunId: string, expectedDisplayId: number, expectedDisplayEpoch: string,
    expectedEndpoint: string, expectedModel: string): Promise<CompleteTaskState>;
  resetCompleteJournal(): Promise<CompleteTaskState>;
  getModelConfiguration(): Promise<ModelConfiguration>;
  saveModelConfiguration(endpoint: string, model: string, apiKey: string): Promise<ModelConfiguration>;
  clearModelConfiguration(): Promise<ModelConfiguration>;
  startAgent(goal: string): Promise<AgentLoopState>;
  getMemorySnapshot(): Promise<MemorySnapshot>;
  saveMemoryNote(expectedRevision: string, noteId: string, text: string): Promise<MemorySnapshot>;
  deleteMemoryNote(expectedRevision: string, noteId: string): Promise<MemorySnapshot>;
  clearMemory(expectedRevision: string): Promise<MemorySnapshot>;
  resetMemory(): Promise<MemorySnapshot>;
  startAgentWithMemory(goal: string, noteIds: ReadonlyArray<string>, expectedRevision: string,
    expectedEndpoint: string, expectedModel: string): Promise<AgentLoopState>;
  getAgentState(): Promise<AgentLoopState>;
  cancelAgent(): Promise<AgentLoopState>;
  beginTask(goal: string): Promise<SingleTaskState>;
  submitTaskStep(taskId: string, tool: string, argumentsJson: string): Promise<SingleTaskState>;
  getSingleTaskState(): Promise<SingleTaskState>;
  finishTask(taskId: string): Promise<SingleTaskState>;
  cancelTask(taskId: string): Promise<SingleTaskState>;
  prepareHost(): Promise<TaskHostReadiness>;
  getHostReadiness(): Promise<TaskHostReadiness>;
  submitTool(tool: string, argumentsJson: string): Promise<TaskHostState>;
  getTaskState(): Promise<TaskHostState>;
  decideFromAppUi(requestNonce: string, decision: string): Promise<TaskHostState>;
  cancelFromAppUi(requestNonce: string): Promise<TaskHostState>;
  refreshTask(): Promise<TaskHostState>;
  retryCleanup(): Promise<TaskHostState>;
}

export default TurboModuleRegistry.get<Spec>('PocketRunTaskHost');
