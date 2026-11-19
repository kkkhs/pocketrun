import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import PtyPanel from '../PtyPanel';
import NativePocketRunPty from '../specs/NativePocketRunPty';

jest.mock('../specs/NativePocketRunPty', () => ({
  __esModule: true,
  default: { startPty: jest.fn(), writePty: jest.fn(), getPtyOutput: jest.fn(), resizePty: jest.fn(), closePty: jest.fn() },
}));

const native = NativePocketRunPty!;
const result = (token: string, cleanupStatus = 'done') => ({ token, status: 'exited', cleanupStatus,
  output: 'PocketRun PTY input OK\n32 100\n', exitKind: 'exited', exitCode: 0, childPid: 2345,
  rootReaped: true, columns: 100, rows: 32, appUid: 10469, durationMillis: 25 });
let screen: ReactTestRenderer.ReactTestRenderer;
const button = (label: string) => screen.root.find(node =>
  node.props.accessibilityLabel === label && typeof node.props.onPress === 'function');
const text = () => JSON.stringify(screen.toJSON());

beforeEach(async () => {
  jest.resetAllMocks();
  (native.writePty as jest.Mock).mockResolvedValue(true);
  (native.getPtyOutput as jest.Mock).mockImplementation(() => new Promise(() => {}));
  (native.resizePty as jest.Mock).mockResolvedValue(true);
  (native.closePty as jest.Mock).mockResolvedValue(true);
  await ReactTestRenderer.act(() => { screen = ReactTestRenderer.create(<PtyPanel colors={{ text: '#000', muted: '#777' }} />); });
});
afterEach(async () => { if (screen.toJSON()) await ReactTestRenderer.act(() => screen.unmount()); });

it('uses only token, fixed dimensions, bounded text and control APIs while the real start promise is pending', async () => {
  let finish!: (value: unknown) => void;
  let snapshot!: (value: unknown) => void;
  (native.startPty as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  (native.getPtyOutput as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { snapshot = resolve; }));
  await ReactTestRenderer.act(() => { button('启动交互终端').props.onPress(); });
  const startedToken = (native.startPty as jest.Mock).mock.calls[0][0];
  await ReactTestRenderer.act(async () => snapshot({ token: startedToken, output: 'PTY LIVE\n', ready: true,
    columns: 100, rows: 32, cleanupPending: false }));
  expect(native.startPty).toHaveBeenCalledTimes(1);
  const [token, columns, rows] = (native.startPty as jest.Mock).mock.calls[0];
  expect(token).toMatch(/^pty-\d+-1$/);
  expect([columns, rows]).toEqual([100, 32]);
  expect((native.startPty as jest.Mock).mock.calls[0]).toHaveLength(3);
  await ReactTestRenderer.act(async () => button('发送 PTY 文本').props.onPress());
  expect(native.writePty).toHaveBeenCalledWith(token, "printf 'PocketRun PTY input OK\\n'\n", '');
  await ReactTestRenderer.act(async () => button('发送 Ctrl-C').props.onPress());
  expect(native.writePty).toHaveBeenLastCalledWith(token, '', 'interrupt');
  await ReactTestRenderer.act(async () => finish(result(token)));
  expect(text()).toContain('PocketRun PTY input OK');
  expect(text()).toContain('root reaped');
});

it('polls bounded live output and applies resize while the terminal is ready', async () => {
  let finish!: (value: unknown) => void;
  let snapshot!: (value: unknown) => void;
  (native.startPty as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  (native.getPtyOutput as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { snapshot = resolve; }));
  await ReactTestRenderer.act(() => { button('启动交互终端').props.onPress(); });
  const token = (native.startPty as jest.Mock).mock.calls[0][0];
  await ReactTestRenderer.act(async () => snapshot({ token, output: 'PTY LIVE\n', ready: true,
    columns: 100, rows: 32, cleanupPending: false }));
  expect(native.getPtyOutput).toHaveBeenCalledWith(token);
  expect(text()).toContain('PTY LIVE');
  await ReactTestRenderer.act(async () => button('使用 80×24').props.onPress());
  expect(native.resizePty).toHaveBeenCalledWith(token, 80, 24);
  expect(text()).toContain('"80"," × ","24"');
  await ReactTestRenderer.act(async () => finish({ ...result(token), columns: 80, rows: 24 }));
});

it('ignores an output snapshot from an older token', async () => {
  let finish!: (value: unknown) => void;
  (native.startPty as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  (native.getPtyOutput as jest.Mock).mockResolvedValueOnce({ token: 'older-token', output: 'STALE OUTPUT', ready: true,
    columns: 80, rows: 24, cleanupPending: false });
  await ReactTestRenderer.act(() => { button('启动交互终端').props.onPress(); });
  await ReactTestRenderer.act(async () => { await Promise.resolve(); });
  expect(text()).not.toContain('STALE OUTPUT');
  expect(button('发送 PTY 文本').props.disabled).toBe(true);
  const token = (native.startPty as jest.Mock).mock.calls[0][0];
  await ReactTestRenderer.act(async () => finish(result(token)));
});

it('selects a real initial resize within the fixed range before launch', async () => {
  let finish!: (value: unknown) => void;
  (native.startPty as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  await ReactTestRenderer.act(() => button('使用 80×24').props.onPress());
  await ReactTestRenderer.act(() => { button('启动交互终端').props.onPress(); });
  const [token, columns, rows] = (native.startPty as jest.Mock).mock.calls[0];
  expect([columns, rows]).toEqual([80, 24]);
  await ReactTestRenderer.act(async () => finish({ ...result(token), columns: 80, rows: 24 }));
  expect(text()).toContain('"80"," × ","24"');
});

it('renders an explicit timed_out result and releases a proven-clean slot', async () => {
  (native.startPty as jest.Mock).mockImplementationOnce(async token => ({ ...result(token), status: 'timed_out',
    exitKind: null, exitCode: null, childPid: null, rootReaped: false }));
  await ReactTestRenderer.act(async () => button('启动交互终端').props.onPress());
  expect(text()).toContain('timed_out');
  expect(text()).not.toContain('状态：","cancelled');
  expect(button('启动交互终端').props.disabled).toBe(false);
});

it('keeps the exact token and blocks a new terminal while cleanup is unproven', async () => {
  (native.startPty as jest.Mock).mockImplementationOnce(async token => result(token, 'unproven'));
  await ReactTestRenderer.act(async () => button('启动交互终端').props.onPress());
  const token = (native.startPty as jest.Mock).mock.calls[0][0];
  expect(text()).toContain('保留当前 token');
  expect(button('启动交互终端').props.disabled).toBe(true);
  await ReactTestRenderer.act(async () => button('重试 PTY 清理').props.onPress());
  expect(native.closePty).toHaveBeenCalledWith(token);
  expect(button('启动交互终端').props.disabled).toBe(false);
});

it('retains the retry token when bridge code reports ordinary open I/O plus unproven lease cleanup', async () => {
  const failure = Object.assign(new Error('open failed with ordinary I/O error'), { code: 'E_PTY_CLEANUP_UNPROVEN' });
  (native.startPty as jest.Mock).mockRejectedValueOnce(failure);
  await ReactTestRenderer.act(async () => button('启动交互终端').props.onPress());
  const token = (native.startPty as jest.Mock).mock.calls[0][0];
  expect(text()).toContain('ordinary I/O error');
  expect(text()).not.toContain('cleanup_unproven:');
  expect(button('启动交互终端').props.disabled).toBe(true);
  expect(button('重试 PTY 清理').props.disabled).toBe(false);
  await ReactTestRenderer.act(async () => button('重试 PTY 清理').props.onPress());
  expect(native.closePty).toHaveBeenCalledWith(token);
});

it('does not manufacture a new slot after a cleanup_unproven rejection', async () => {
  (native.startPty as jest.Mock).mockRejectedValueOnce(new Error('cleanup_unproven: native handle retained'));
  await ReactTestRenderer.act(async () => button('启动交互终端').props.onPress());
  expect(button('启动交互终端').props.disabled).toBe(true);
  expect(text()).toContain('cleanup_unproven');
  const token = (native.startPty as jest.Mock).mock.calls[0][0];
  await ReactTestRenderer.act(async () => button('重试 PTY 清理').props.onPress());
  expect(native.closePty).toHaveBeenCalledWith(token);
});

it('deduplicates start and closes only its own active token on unmount', async () => {
  let finish!: (value: unknown) => void;
  (native.startPty as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  await ReactTestRenderer.act(() => {
    button('启动交互终端').props.onPress();
    button('启动交互终端').props.onPress();
  });
  expect(native.startPty).toHaveBeenCalledTimes(1);
  const token = (native.startPty as jest.Mock).mock.calls[0][0];
  await ReactTestRenderer.act(() => screen.unmount());
  expect(native.closePty).toHaveBeenCalledWith(token);
  await ReactTestRenderer.act(async () => finish(result(token)));
  expect(screen.toJSON()).toBeNull();
});

it('documents the PTY merge and keeps arbitrary executable, env and bind parameters out of JS', () => {
  expect(text()).toContain('PTY 天然合并 stdout / stderr');
  expect(text()).toContain('不能指定可执行文件、环境、绑定目录或 PID');
  expect((native.writePty as jest.Mock).mock.calls).toHaveLength(0);
});
