import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import WorkspacePanel from '../WorkspacePanel';
import NativePocketRunWorkspace from '../specs/NativePocketRunWorkspace';

jest.mock('../specs/NativePocketRunWorkspace', () => ({
  __esModule: true,
  default: { prepareWorkspace: jest.fn(), runDemoPipeline: jest.fn(), cancelPipeline: jest.fn() },
}));

test('old cancellation receipt cannot change a new run or its successful result', async () => {
  const native = NativePocketRunWorkspace!;
  (native.prepareWorkspace as jest.Mock).mockResolvedValue({ directory: '/private/demo', sampleFile: '/private/demo/items.csv', sampleCreated: true, appUid: 10001 });
  let finishFirst!: (value: unknown) => void;
  let finishSecond!: (value: unknown) => void;
  let finishCancel!: (value: boolean) => void;
  (native.runDemoPipeline as jest.Mock)
    .mockImplementationOnce(() => new Promise(resolve => { finishFirst = resolve; }))
    .mockImplementationOnce(() => new Promise(resolve => { finishSecond = resolve; }));
  (native.cancelPipeline as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { finishCancel = resolve; }));
  let screen!: ReactTestRenderer.ReactTestRenderer;
  const button = (label: string) => screen.root.find(node => node.props.accessibilityLabel === label && typeof node.props.onPress === 'function');
  const result = { status: 'completed', stdout: '3\n', stderr: '', exitCodes: [0, 0], durationMillis: 1, appUid: 10001 };
  await ReactTestRenderer.act(() => { screen = ReactTestRenderer.create(<WorkspacePanel colors={{ text: '#000', muted: '#777' }} />); });
  try {
    await ReactTestRenderer.act(async () => { button('初始化工作目录').props.onPress(); });
    await ReactTestRenderer.act(() => { button('运行演示管道').props.onPress(); });
    await ReactTestRenderer.act(() => { button('取消命令管道').props.onPress(); });
    await ReactTestRenderer.act(async () => { finishFirst(result); });
    await ReactTestRenderer.act(() => { button('运行演示管道').props.onPress(); });
    await ReactTestRenderer.act(async () => { finishCancel(false); });
    expect(button('运行演示管道').props.disabled).toBe(true);
    expect(JSON.stringify(screen.toJSON())).not.toContain('管道已结束');
    await ReactTestRenderer.act(async () => { finishSecond(result); });
    expect(JSON.stringify(screen.toJSON())).toContain('completed');
    expect(JSON.stringify(screen.toJSON())).not.toContain('管道已结束');
  } finally { await ReactTestRenderer.act(() => screen.unmount()); }
});
