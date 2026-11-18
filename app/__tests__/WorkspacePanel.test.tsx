import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import WorkspacePanel from '../WorkspacePanel';
import NativePocketRunWorkspace, { type PipelineResult } from '../specs/NativePocketRunWorkspace';

jest.mock('../specs/NativePocketRunWorkspace', () => ({
  __esModule: true,
  default: { prepareWorkspace: jest.fn(), runDemoPipeline: jest.fn(), cancelPipeline: jest.fn() },
}));

const native = NativePocketRunWorkspace!;
const prepare = native.prepareWorkspace as jest.Mock;
const run = native.runDemoPipeline as jest.Mock;
const cancel = native.cancelPipeline as jest.Mock;
const result: PipelineResult = { status: 'completed', stdout: '3\n', stderr: '', exitCodes: [0, 0], durationMillis: 10, appUid: 10123 };
let screen: ReactTestRenderer.ReactTestRenderer;
const button = (label: string) => screen.root.find(node => node.props.accessibilityLabel === label && typeof node.props.onPress === 'function');
const output = () => JSON.stringify(screen.toJSON());

beforeEach(async () => {
  jest.clearAllMocks();
  prepare.mockResolvedValue({ directory: '/private/workspaces/demo', sampleFile: '/private/workspaces/demo/items.csv', sampleCreated: true, appUid: 10123 });
  run.mockResolvedValue(result);
  cancel.mockResolvedValue(true);
  await ReactTestRenderer.act(() => { screen = ReactTestRenderer.create(<WorkspacePanel colors={{ text: '#000', muted: '#777' }} />); });
});
afterEach(async () => { await ReactTestRenderer.act(() => screen.unmount()); });

async function initialize() {
  await ReactTestRenderer.act(async () => { button('初始化工作目录').props.onPress(); });
}

test('requires initialization and displays private workspace metadata', async () => {
  expect(button('运行演示管道').props.disabled).toBe(true);
  await initialize();
  expect(prepare).toHaveBeenCalledWith();
  expect(output()).toContain('/private/workspaces/demo');
  expect(output()).toContain('10123');
  expect(button('运行演示管道').props.disabled).toBe(false);
});

test('runs only the fixed no-argument pipeline and displays all exits', async () => {
  await initialize();
  await ReactTestRenderer.act(async () => { button('运行演示管道').props.onPress(); });
  expect(run).toHaveBeenCalledWith();
  expect(output()).toContain('completed');
  expect(output()).toContain('0, 0');
  expect(output()).toContain('stdout');
});

test('shows native failure and allows retry without resetting the workspace', async () => {
  await initialize();
  run.mockRejectedValueOnce(new Error('private file invalid'));
  await ReactTestRenderer.act(async () => { button('运行演示管道').props.onPress(); });
  expect(output()).toContain('private file invalid');
  expect(button('运行演示管道').props.disabled).toBe(false);
  await ReactTestRenderer.act(async () => { button('运行演示管道').props.onPress(); });
  expect(output()).toContain('completed');
});

test('keeps commands disabled while cancellation waits for the run result', async () => {
  await initialize();
  let finish!: (value: PipelineResult) => void;
  run.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  await ReactTestRenderer.act(() => { button('运行演示管道').props.onPress(); });
  expect(button('初始化工作目录').props.disabled).toBe(true);
  expect(button('运行演示管道').props.disabled).toBe(true);
  expect(button('取消命令管道').props.disabled).toBe(false);
  await ReactTestRenderer.act(async () => { button('取消命令管道').props.onPress(); });
  expect(cancel).toHaveBeenCalledTimes(1);
  expect(button('运行演示管道').props.disabled).toBe(true);
  await ReactTestRenderer.act(async () => { finish({ ...result, status: 'cancelled' }); });
  expect(output()).toContain('cancelled');
  expect(button('运行演示管道').props.disabled).toBe(false);
});

test('ignores a late result after unmount', async () => {
  await initialize();
  let finish!: (value: PipelineResult) => void;
  run.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  await ReactTestRenderer.act(() => { button('运行演示管道').props.onPress(); });
  await ReactTestRenderer.act(() => screen.unmount());
  await ReactTestRenderer.act(async () => { finish(result); });
  expect(screen.toJSON()).toBeNull();
});
