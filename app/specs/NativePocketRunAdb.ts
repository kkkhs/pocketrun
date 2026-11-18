import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type AdbStatus = {
  connected: boolean;
  busy: boolean;
  port: number;
  uid: number;
};

export type BackendInfo = {
  running: boolean;
  uid: number;
  pid: number;
  reply: string;
};

export type DisplayInfo = {
  active: boolean;
  displayId: number;
};

export interface Spec extends TurboModule {
  pair(port: number, code: string): Promise<void>;
  connect(port: number): Promise<number>;
  probe(): Promise<number>;
  disconnect(): Promise<void>;
  getStatus(): Promise<AdbStatus>;
  startBackend(): Promise<BackendInfo>;
  pingBackend(): Promise<BackendInfo>;
  stopBackend(): Promise<void>;
  getBackendStatus(): Promise<BackendInfo>;
  createDemoDisplay(): Promise<DisplayInfo>;
  getDisplayStatus(): Promise<DisplayInfo>;
  releaseDisplay(): Promise<DisplayInfo>;
}

export default TurboModuleRegistry.get<Spec>('PocketRunAdb');
