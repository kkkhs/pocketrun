import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type AdbStatus = {
  connected: boolean;
  busy: boolean;
  port: number;
  uid: number;
};

export interface Spec extends TurboModule {
  pair(port: number, code: string): Promise<void>;
  connect(port: number): Promise<number>;
  probe(): Promise<number>;
  disconnect(): Promise<void>;
  getStatus(): Promise<AdbStatus>;
}

export default TurboModuleRegistry.get<Spec>('PocketRunAdb');
