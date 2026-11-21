import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type PtyResult = {
  token: string;
  status: string;
  cleanupStatus: string;
  output: string;
  exitKind: string | null;
  exitCode: number | null;
  childPid: number | null;
  rootReaped: boolean;
  columns: number;
  rows: number;
  appUid: number;
  durationMillis: number;
};

export type PtySnapshot = {
  token: string;
  output: string;
  ready: boolean;
  columns: number;
  rows: number;
  cleanupPending: boolean;
};

export interface Spec extends TurboModule {
  startPty(token: string, columns: number, rows: number): Promise<PtyResult>;
  writePty(token: string, text: string, control: string): Promise<boolean>;
  getPtyOutput(token: string): Promise<PtySnapshot>;
  resizePty(token: string, columns: number, rows: number): Promise<boolean>;
  closePty(token: string): Promise<boolean>;
}

export default TurboModuleRegistry.get<Spec>('PocketRunPty');
