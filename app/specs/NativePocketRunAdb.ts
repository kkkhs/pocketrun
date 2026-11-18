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

export type ScreenshotInfo = {
  displayId: number;
  width: number;
  height: number;
  frameTimestampNanos: string;
  uri: string;
};

export type UiTreeInfo = {
  displayId: number;
  status: string;
  truncated: boolean;
  nodeCount: number;
  json: string;
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
  captureScreenshot(expectedDisplayId: number): Promise<ScreenshotInfo>;
  dumpUiTree(expectedDisplayId: number): Promise<UiTreeInfo>;
  injectTap(expectedDisplayId: number, x: number, y: number): Promise<boolean>;
  injectText(expectedDisplayId: number, text: string): Promise<boolean>;
}

export default TurboModuleRegistry.get<Spec>('PocketRunAdb');
