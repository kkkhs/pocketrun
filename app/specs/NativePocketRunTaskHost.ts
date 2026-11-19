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

export interface Spec extends TurboModule {
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
