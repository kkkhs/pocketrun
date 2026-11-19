import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type WorkspaceInfo = {
  directory: string;
  sampleFile: string;
  sampleCreated: boolean;
  appUid: number;
};

export type PipelineResult = {
  status: string;
  stdout: string;
  stderr: string;
  exitCodes: Array<number>;
  durationMillis: number;
  appUid: number;
};

export interface Spec extends TurboModule {
  prepareWorkspace(): Promise<WorkspaceInfo>;
  runDemoPipeline(): Promise<PipelineResult>;
  cancelPipeline(): Promise<boolean>;
}

export default TurboModuleRegistry.get<Spec>('PocketRunWorkspace');
