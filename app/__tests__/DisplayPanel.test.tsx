import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import DisplayPanel from '../DisplayPanel';
import NativeAdb from '../specs/NativePocketRunAdb';

jest.mock('../specs/NativePocketRunAdb', () => ({
  __esModule: true,
  default: {
    createDemoDisplay: jest.fn(),
    getDisplayStatus: jest.fn(),
    releaseDisplay: jest.fn(),
  },
}));
const adb = NativeAdb as jest.Mocked<NonNullable<typeof NativeAdb>>;
let screen: ReactTestRenderer.ReactTestRenderer;
const stopped = { active: false, displayId: -1 };
const active = { active: true, displayId: 7 };
const control = (label: string) =>
  screen.root.find(
    node =>
      node.props.accessibilityLabel === label &&
      typeof node.props.onPress === 'function',
  );

beforeEach(async () => {
  jest.resetAllMocks();
  adb.getDisplayStatus.mockResolvedValue(stopped);
  await ReactTestRenderer.act(async () => {
    screen = ReactTestRenderer.create(
      <DisplayPanel colors={{ text: '#111', muted: '#555' }} />,
    );
  });
});

afterEach(async () => {
  await ReactTestRenderer.act(() => screen.unmount());
});

test('creates the fixed demo display and shows its displayId', async () => {
  adb.createDemoDisplay.mockResolvedValueOnce(active);
  await ReactTestRenderer.act(async () => {
    await control('创建演示虚拟屏').props.onPress();
  });
  expect(adb.createDemoDisplay).toHaveBeenCalledTimes(1);
  expect(JSON.stringify(screen.toJSON())).toContain('displayId：7');
  expect(control('检查虚拟屏').props.disabled).toBe(false);
});

test('releases the display and invalidates its old ID', async () => {
  adb.getDisplayStatus.mockResolvedValueOnce(active);
  await ReactTestRenderer.act(async () => {
    screen.unmount();
    screen = ReactTestRenderer.create(
      <DisplayPanel colors={{ text: '#111', muted: '#555' }} />,
    );
  });
  adb.releaseDisplay.mockResolvedValueOnce(stopped);
  await ReactTestRenderer.act(async () => {
    await control('释放虚拟屏').props.onPress();
  });
  expect(JSON.stringify(screen.toJSON())).toContain('旧 displayId 已失效');
  expect(control('创建演示虚拟屏').props.disabled).toBe(false);
});

test('shows native errors and ignores a result after unmount', async () => {
  adb.createDemoDisplay.mockRejectedValueOnce(new Error('请先启动 shell 后端。'));
  await ReactTestRenderer.act(async () => {
    await control('创建演示虚拟屏').props.onPress();
  });
  expect(JSON.stringify(screen.toJSON())).toContain('请先启动 shell 后端');

  let finish!: (value: typeof active) => void;
  adb.createDemoDisplay.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        finish = resolve;
      }),
  );
  await ReactTestRenderer.act(() => {
    control('创建演示虚拟屏').props.onPress();
  });
  await ReactTestRenderer.act(() => screen.unmount());
  await ReactTestRenderer.act(async () => finish(active));
});
