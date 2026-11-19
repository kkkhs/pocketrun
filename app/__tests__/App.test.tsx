import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import App from '../App';
import NativePocketRunDevice, {
  type DeviceInfo,
} from '../specs/NativePocketRunDevice';

jest.mock(
  'react-native-safe-area-context',
  () => require('react-native-safe-area-context/jest/mock').default,
);
jest.mock('../AdbPanel', () => 'AdbPanel');
jest.mock('../BackendPanel', () => 'BackendPanel');
jest.mock('../DisplayPanel', () => 'DisplayPanel');
jest.mock('../WorkspacePanel', () => 'WorkspacePanel');
jest.mock('../LinuxPanel', () => 'LinuxPanel');
jest.mock('../specs/NativePocketRunDevice', () => ({
  __esModule: true,
  default: { getDeviceInfo: jest.fn() },
}));

const readInfo = NativePocketRunDevice!.getDeviceInfo as jest.MockedFunction<
  NonNullable<typeof NativePocketRunDevice>['getDeviceInfo']
>;
const device: DeviceInfo = {
  androidVersion: '16',
  apiLevel: 36,
  supportedAbis: ['arm64-v8a'],
  appUid: 10123,
};
let screen: ReactTestRenderer.ReactTestRenderer;
const button = () =>
  screen.root.find(
    node =>
      node.props.accessibilityLabel === '检查设备' &&
      typeof node.props.onPress === 'function',
  );

beforeEach(async () => {
  readInfo.mockReset();
  await ReactTestRenderer.act(() => {
    screen = ReactTestRenderer.create(<App />);
  });
});

afterEach(async () => {
  await ReactTestRenderer.act(() => screen.unmount());
});

test('disables the button while reading and displays the native result', async () => {
  let finish!: (value: DeviceInfo) => void;
  readInfo.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        finish = resolve;
      }),
  );

  await ReactTestRenderer.act(() => {
    button().props.onPress();
  });
  expect(readInfo).toHaveBeenCalledTimes(1);
  expect(button().props.disabled).toBe(true);

  await ReactTestRenderer.act(async () => finish(device));
  const output = JSON.stringify(screen.toJSON());
  expect(output).toContain('arm64-v8a');
  expect(output).toContain('10123');
  expect(output).toContain('API Level');
  expect(button().props.disabled).toBe(false);
});

test('shows a native error and allows retry', async () => {
  readInfo.mockRejectedValueOnce(new Error('设备信息读取失败'));
  await ReactTestRenderer.act(async () => {
    await button().props.onPress();
  });
  expect(JSON.stringify(screen.toJSON())).toContain('设备信息读取失败');
  expect(button().props.disabled).toBe(false);

  readInfo.mockResolvedValueOnce(device);
  await ReactTestRenderer.act(async () => {
    await button().props.onPress();
  });
  expect(JSON.stringify(screen.toJSON())).not.toContain('设备信息读取失败');
  expect(JSON.stringify(screen.toJSON())).toContain('10123');
});

test('reports a missing native module without crashing the screen', async () => {
  const bridge = jest.requireMock('../specs/NativePocketRunDevice');
  const original = bridge.default;
  bridge.default = null;
  try {
    await ReactTestRenderer.act(async () => {
      await button().props.onPress();
    });
    expect(JSON.stringify(screen.toJSON())).toContain('原生模块未注册');
    expect(button().props.disabled).toBe(false);
  } finally {
    bridge.default = original;
  }
});
