import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import BackendPanel from '../BackendPanel';
import NativeAdb from '../specs/NativePocketRunAdb';

jest.mock('../specs/NativePocketRunAdb', () => ({
  __esModule: true,
  default: {
    startBackend: jest.fn(),
    pingBackend: jest.fn(),
    stopBackend: jest.fn(),
    getBackendStatus: jest.fn(),
  },
}));
const adb = NativeAdb as jest.Mocked<NonNullable<typeof NativeAdb>>;
let screen: ReactTestRenderer.ReactTestRenderer;
const stopped = { running: false, uid: -1, pid: -1, reply: '' };
const running = { running: true, uid: 2000, pid: 4321, reply: 'pong' };
const control = (label: string) =>
  screen.root.find(
    node =>
      node.props.accessibilityLabel === label &&
      typeof node.props.onPress === 'function',
  );

beforeEach(async () => {
  jest.resetAllMocks();
  adb.getBackendStatus.mockResolvedValue(stopped);
  await ReactTestRenderer.act(async () => {
    screen = ReactTestRenderer.create(
      <BackendPanel colors={{ text: '#111', muted: '#555' }} />,
    );
  });
});

afterEach(async () => {
  await ReactTestRenderer.act(() => screen.unmount());
});

test('starts the shell backend and shows its Binder identity', async () => {
  adb.startBackend.mockResolvedValueOnce(running);
  await ReactTestRenderer.act(async () => {
    await control('启动 shell 后端').props.onPress();
  });
  expect(adb.startBackend).toHaveBeenCalledTimes(1);
  expect(JSON.stringify(screen.toJSON())).toContain('后端 UID：2000');
  expect(JSON.stringify(screen.toJSON())).toContain('PID：4321');
  expect(control('通过 Binder 检查 shell 后端').props.disabled).toBe(false);
});

test('checks the retained Binder without opening another ADB connection', async () => {
  adb.getBackendStatus.mockResolvedValueOnce(running);
  await ReactTestRenderer.act(async () => {
    screen.unmount();
    screen = ReactTestRenderer.create(
      <BackendPanel colors={{ text: '#111', muted: '#555' }} />,
    );
  });
  adb.pingBackend.mockResolvedValueOnce(running);
  await ReactTestRenderer.act(async () => {
    await control('通过 Binder 检查 shell 后端').props.onPress();
  });
  expect(adb.pingBackend).toHaveBeenCalledTimes(1);
  expect(JSON.stringify(screen.toJSON())).toContain('Binder pong');
});

test('shows backend death and ignores a result after unmount', async () => {
  adb.getBackendStatus.mockResolvedValueOnce(running);
  await ReactTestRenderer.act(async () => {
    screen.unmount();
    screen = ReactTestRenderer.create(
      <BackendPanel colors={{ text: '#111', muted: '#555' }} />,
    );
  });
  adb.pingBackend.mockRejectedValueOnce(new Error('shell 后端已经退出。'));
  adb.getBackendStatus.mockResolvedValueOnce(stopped);
  await ReactTestRenderer.act(async () => {
    await control('通过 Binder 检查 shell 后端').props.onPress();
  });
  expect(JSON.stringify(screen.toJSON())).toContain('shell 后端已经退出');

  let finish!: (value: typeof running) => void;
  adb.startBackend.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        finish = resolve;
      }),
  );
  await ReactTestRenderer.act(() => {
    control('启动 shell 后端').props.onPress();
  });
  await ReactTestRenderer.act(() => screen.unmount());
  await ReactTestRenderer.act(async () => finish(running));
});
