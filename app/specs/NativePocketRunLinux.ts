import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type LinuxInfo = {
  token: string;
  installationGeneration: string;
  alpineVersion: string;
  appUid: number;
  rootfsDirectory: string;
  workspaceDirectory: string;
  guestCwd: string;
  installedNow: boolean;
  sampleCreated: boolean;
};

export type LinuxResult = {
  token: string;
  installationGeneration: string;
  status: string;
  cleanupStatus: string;
  stdout: string;
  stderr: string;
  exitCodes: Array<number>;
  stageExitCodes: Array<number>;
  durationMillis: number;
  appUid: number;
  alpineVersion: string | null;
  guestUid: number | null;
  cwd: string | null;
  lineCount: number | null;
};

export interface Spec extends TurboModule {
  prepareLinux(token: string): Promise<LinuxInfo>;
  runLinuxDemo(token: string): Promise<LinuxResult>;
  cancelLinux(token: string): Promise<boolean>;
}

export default TurboModuleRegistry.get<Spec>('PocketRunLinux');
