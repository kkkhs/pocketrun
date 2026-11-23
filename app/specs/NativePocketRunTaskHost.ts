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

export interface Spec extends TurboModule {
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
