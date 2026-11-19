import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import LinuxPanel from '../LinuxPanel';
import NativePocketRunLinux from '../specs/NativePocketRunLinux';

jest.mock('../specs/NativePocketRunLinux', () => ({
  __esModule: true,
  default: { prepareLinux: jest.fn(), runLinuxDemo: jest.fn(), cancelLinux: jest.fn() },
}));

const native = NativePocketRunLinux!;
const info = (token: string) => ({ token, installationGeneration: 'generation-1', alpineVersion: '3.22.8',
  appUid: 10469, rootfsDirectory: '/private/linux/rootfs/data', workspaceDirectory: '/private/workspaces/demo',
  guestCwd: '/workspace', installedNow: true, sampleCreated: false });
const result = (token: string) => ({ token, installationGeneration: 'generation-1', status: 'completed', cleanupStatus: 'done',
  stdout: '3.22.8\n/workspace\n10469\n3\nPR_EXITS:0,0,0,0,0\n', stderr: '', exitCodes: [0],
  stageExitCodes: [0, 0, 0, 0, 0], durationMillis: 10, appUid: 10469, alpineVersion: '3.22.8',
  guestUid: 10469, cwd: '/workspace', lineCount: 3 });
let screen: ReactTestRenderer.ReactTestRenderer;
const button = (label: string) => screen.root.find(node => node.props.accessibilityLabel === label && typeof node.props.onPress === 'function');
const text = () => JSON.stringify(screen.toJSON());

beforeEach(async () => {
  jest.resetAllMocks();
  (native.prepareLinux as jest.Mock).mockImplementation(async token => info(token));
  (native.runLinuxDemo as jest.Mock).mockImplementation(async token => result(token));
  (native.cancelLinux as jest.Mock).mockResolvedValue(true);
  await ReactTestRenderer.act(() => { screen = ReactTestRenderer.create(<LinuxPanel colors={{ text: '#000', muted: '#777' }} />); });
});
afterEach(async () => { await ReactTestRenderer.act(() => screen.unmount()); });

it('requires preparation and exposes structured real guest fields via fixed token-only calls', async () => {
  expect(button('运行 Linux 演示').props.disabled).toBe(true);
  await ReactTestRenderer.act(async () => { button('准备 Linux 环境').props.onPress(); });
  expect(button('运行 Linux 演示').props.disabled).toBe(false);
  await ReactTestRenderer.act(async () => { button('运行 Linux 演示').props.onPress(); });
  expect(text()).toContain('completed');
  expect(text()).toContain('10469');
  expect(text()).toContain('/workspace');
  expect(text()).toContain('0, 0, 0, 0, 0');
  expect(text()).toContain('保留已有工作数据');
  const args = (native.runLinuxDemo as jest.Mock).mock.calls[0];
  expect(args).toHaveLength(1);
  expect(args[0]).toMatch(/^linux-\d+-\d+$/);
  expect((native.prepareLinux as jest.Mock).mock.calls[0][0]).not.toBe(args[0]);
});

it('prevents duplicate presses while preparing and cancels the exact pending token', async () => {
  let finish!: (value: unknown) => void;
  (native.prepareLinux as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  await ReactTestRenderer.act(() => { button('准备 Linux 环境').props.onPress(); button('准备 Linux 环境').props.onPress(); });
  expect(native.prepareLinux).toHaveBeenCalledTimes(1);
  const token = (native.prepareLinux as jest.Mock).mock.calls[0][0];
  await ReactTestRenderer.act(async () => { button('取消 Linux 操作').props.onPress(); });
  expect(native.cancelLinux).toHaveBeenCalledWith(token);
  await ReactTestRenderer.act(async () => { finish(info(token)); });
});

it('rejects mismatched run correlation and installation generation', async () => {
  await ReactTestRenderer.act(async () => { button('准备 Linux 环境').props.onPress(); });
  (native.runLinuxDemo as jest.Mock).mockImplementationOnce(async token => ({ ...result(token), token: 'older-run' }));
  await ReactTestRenderer.act(async () => { button('运行 Linux 演示').props.onPress(); });
  expect(text()).toContain('Linux 运行结果已失效');
  expect(text()).not.toContain('completed');
  (native.runLinuxDemo as jest.Mock).mockImplementationOnce(async token => ({ ...result(token), installationGeneration: 'older-installation' }));
  await ReactTestRenderer.act(async () => { button('运行 Linux 演示').props.onPress(); });
  expect(text()).toContain('Linux 运行结果已失效');
});

it('late cancellation receipt cannot alter a newer running operation or result', async () => {
  await ReactTestRenderer.act(async () => { button('准备 Linux 环境').props.onPress(); });
  let firstDone!: (value: unknown) => void;
  let secondDone!: (value: unknown) => void;
  let cancelDone!: (value: boolean) => void;
  (native.runLinuxDemo as jest.Mock)
    .mockImplementationOnce(() => new Promise(resolve => { firstDone = resolve; }))
    .mockImplementationOnce(() => new Promise(resolve => { secondDone = resolve; }));
  (native.cancelLinux as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { cancelDone = resolve; }));
  await ReactTestRenderer.act(() => { button('运行 Linux 演示').props.onPress(); });
  const firstToken = (native.runLinuxDemo as jest.Mock).mock.calls[0][0];
  await ReactTestRenderer.act(() => { button('取消 Linux 操作').props.onPress(); });
  await ReactTestRenderer.act(async () => { firstDone(result(firstToken)); });
  await ReactTestRenderer.act(() => { button('运行 Linux 演示').props.onPress(); });
  await ReactTestRenderer.act(async () => { cancelDone(false); });
  expect(button('运行 Linux 演示').props.disabled).toBe(true);
  expect(text()).not.toContain('没有可取消');
  const secondToken = (native.runLinuxDemo as jest.Mock).mock.calls[1][0];
  await ReactTestRenderer.act(async () => { secondDone(result(secondToken)); });
  expect(text()).toContain('completed');
  expect(text()).not.toContain('没有可取消');
});

it('unmount cancels only its own active operation and ignores its late result', async () => {
  let finish!: (value: unknown) => void;
  (native.prepareLinux as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  await ReactTestRenderer.act(() => { button('准备 Linux 环境').props.onPress(); });
  const token = (native.prepareLinux as jest.Mock).mock.calls[0][0];
  await ReactTestRenderer.act(() => screen.unmount());
  expect(native.cancelLinux).toHaveBeenCalledWith(token);
  await ReactTestRenderer.act(async () => { finish(info(token)); });
  expect(screen.toJSON()).toBeNull();
});

it('shows the Android14 ARM64 boundary and keeps run disabled after unsupported preparation', async () => {
  expect(text()).toContain('Android 14 / API 34');
  expect(text()).toContain('ARM64 App 进程');
  (native.prepareLinux as jest.Mock).mockRejectedValueOnce(new Error('Linux 最小运行环境需要 Android 14 / API 34 及 ARM64 App 进程。'));
  await ReactTestRenderer.act(async () => { button('准备 Linux 环境').props.onPress(); });
  expect(button('运行 Linux 演示').props.disabled).toBe(true);
  expect(text()).toContain('Linux 最小运行环境需要');
});

it('shows unproven cleanup as an acceptance failure while preserving successful command output', async () => {
  await ReactTestRenderer.act(async () => { button('准备 Linux 环境').props.onPress(); });
  (native.runLinuxDemo as jest.Mock).mockImplementationOnce(async token => ({ ...result(token), cleanupStatus: 'unproven' }));
  await ReactTestRenderer.act(async () => { button('运行 Linux 演示').props.onPress(); });
  expect(text()).toContain('completed');
  expect(text()).toContain('PR_EXITS:0,0,0,0,0');
  expect(text()).toContain('unproven');
  expect(text()).toContain('不能作为完整验收通过');
});

it('reports preparation failure without enabling run', async () => {
  (native.prepareLinux as jest.Mock).mockRejectedValueOnce(new Error('rootfs SHA-256 校验失败'));
  await ReactTestRenderer.act(async () => { button('准备 Linux 环境').props.onPress(); });
  expect(text()).toContain('SHA-256');
  expect(button('运行 Linux 演示').props.disabled).toBe(true);
});
