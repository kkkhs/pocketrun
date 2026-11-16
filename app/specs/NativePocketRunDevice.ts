import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type DeviceInfo = {
  androidVersion: string;
  apiLevel: number;
  supportedAbis: Array<string>;
  appUid: number;
};

export interface Spec extends TurboModule {
  getDeviceInfo(): Promise<DeviceInfo>;
}

export default TurboModuleRegistry.get<Spec>('PocketRunDevice');
