import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import SingleTaskPanel from '../SingleTaskPanel';
import NativeTaskHost, { type SingleTaskState } from '../specs/NativePocketRunTaskHost';

jest.mock('../specs/NativePocketRunTaskHost', () => ({
  __esModule: true,
  default: { getSingleTaskState: jest.fn(), beginTask: jest.fn(), submitTaskStep: jest.fn(),
    finishTask: jest.fn(), cancelTask: jest.fn(), decideFromAppUi: jest.fn(), retryCleanup: jest.fn() },
}));
const native = NativeTaskHost!;
const state = (overrides: Partial<SingleTaskState> = {}): SingleTaskState => ({
  taskId: null, goal: null, phase: 'idle', code: null, stepsJson: '[]', contextBytes: 0,
  maxSteps: 8, maxContextBytes: 65536, maxResultBytes: 16384, maxDurationMillis: 120000, ...overrides,
});
let screen: ReactTestRenderer.ReactTestRenderer;
const button = (label: string) => screen.root.find(node =>
  node.props.accessibilityLabel === label && typeof node.props.onPress === 'function');
const text = () => JSON.stringify(screen.toJSON());
beforeEach(async () => {
  jest.resetAllMocks();
  (native.getSingleTaskState as jest.Mock).mockResolvedValue(state());
  await ReactTestRenderer.act(async () => {
    screen = ReactTestRenderer.create(<SingleTaskPanel colors={{ text: '#000', muted: '#777' }} />);
  });
});
afterEach(async () => { await ReactTestRenderer.act(() => screen.unmount()); });

it('keeps one task goal and sends fixed next steps without automated approval', async () => {
  const ready = state({ taskId: 'task-1', goal: 'demo', phase: 'ready' });
  (native.getSingleTaskState as jest.Mock).mockResolvedValue(ready);
  await ReactTestRenderer.act(async () => button('开始两步演示任务').props.onPress());
  expect(native.beginTask).toHaveBeenCalledWith('读取设备信息和固定演示 CSV 行数，核对两步结果');
  await ReactTestRenderer.act(async () => button('步骤 1：读取设备信息').props.onPress());
  expect(native.submitTaskStep).toHaveBeenCalledWith('task-1', 'android.device_info', '{}');
  (native.getSingleTaskState as jest.Mock).mockResolvedValue(state({ ...ready, stepsJson: JSON.stringify([
    { number: 1, tool: 'android.device_info', phase: 'succeeded', cleanupState: 'proven', result: { apiLevel: '34' } },
  ]) }));
  await ReactTestRenderer.act(async () => button('步骤 1：读取设备信息').props.onPress());
  await ReactTestRenderer.act(async () => button('步骤 2：申请读取演示 CSV').props.onPress());
  expect(native.submitTaskStep).toHaveBeenLastCalledWith('task-1', 'linux.count_demo_csv', '{}');
  expect(native.decideFromAppUi).not.toHaveBeenCalled();
  expect(text()).toContain('apiLevel');
});

it('only explicit phone buttons consume the current nonce; rejection never completes a task', async () => {
  (native.getSingleTaskState as jest.Mock).mockResolvedValue(state({ taskId: 'task-1', phase: 'running',
    stepsJson: JSON.stringify([{ number: 1, tool: 'linux.count_demo_csv', phase: 'pending_approval',
      requestNonce: 'current-nonce', requestHash: 'hash', target: '/workspace/items.csv',
      cleanupState: 'none', result: {} }]) }));
  await ReactTestRenderer.act(async () => button('开始两步演示任务').props.onPress());
  expect(native.decideFromAppUi).not.toHaveBeenCalled();
  expect(button('核对两步结果并结束任务').props.disabled).toBe(true);
  await ReactTestRenderer.act(async () => button('拒绝任务当前步骤').props.onPress());
  expect(native.decideFromAppUi).toHaveBeenCalledWith('current-nonce', 'reject');
  expect(native.finishTask).not.toHaveBeenCalled();
  await ReactTestRenderer.act(async () => button('取消整个任务').props.onPress());
  expect(native.cancelTask).toHaveBeenCalledWith('task-1');
});

it('ignores late action responses after unmount', async () => {
  let finish!: () => void;
  (native.beginTask as jest.Mock).mockReturnValue(new Promise<void>(resolve => { finish = resolve; }));
  await ReactTestRenderer.act(async () => { button('开始两步演示任务').props.onPress(); });
  await ReactTestRenderer.act(() => screen.unmount());
  await ReactTestRenderer.act(async () => { finish(); });
  expect(screen.toJSON()).toBeNull();
});
