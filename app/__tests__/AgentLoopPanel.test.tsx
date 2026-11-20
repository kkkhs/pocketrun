import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import AgentLoopPanel from '../AgentLoopPanel';
import NativeTaskHost from '../specs/NativePocketRunTaskHost';

jest.mock('../specs/NativePocketRunTaskHost', () => ({ __esModule: true, default: {
  getModelConfiguration: jest.fn(), saveModelConfiguration: jest.fn(), clearModelConfiguration: jest.fn(),
  getAgentState: jest.fn(), startAgent: jest.fn(), cancelAgent: jest.fn(),
} }));
const native = NativeTaskHost!;
const config = { endpoint: 'https://api.anthropic.com', model: 'claude-opus-5-5', hasCredential: false };
const state = { phase: 'idle', taskId: null, rounds: 0, outputTokens: 0, contextBytes: 0, finalText: null, code: null };
let screen: ReactTestRenderer.ReactTestRenderer;
const button = (label: string) => screen.root.find(node => node.props.accessibilityLabel === label && typeof node.props.onPress === 'function');
const input = () => screen.root.find(node => node.props.accessibilityLabel === '模型 API 凭据（不回显）' && typeof node.props.onChangeText === 'function');
beforeEach(async () => {
  jest.resetAllMocks();
  (native.getModelConfiguration as jest.Mock).mockResolvedValue(config);
  (native.getAgentState as jest.Mock).mockResolvedValue(state);
  await ReactTestRenderer.act(async () => { screen = ReactTestRenderer.create(<AgentLoopPanel colors={{ text: '#000', muted: '#777' }} />); });
});
afterEach(async () => { await ReactTestRenderer.act(() => screen.unmount()); });

it('requires configuration and submits credential once through a masked transient input', async () => {
  expect(button('开始真实模型两步任务').props.disabled).toBe(true);
  expect(input().props.secureTextEntry).toBe(true);
  expect(input().props.autoComplete).toBe('off');
  await ReactTestRenderer.act(() => input().props.onChangeText('demo-not-a-real-api-credential'));
  (native.getModelConfiguration as jest.Mock).mockResolvedValue({ ...config, hasCredential: true });
  await ReactTestRenderer.act(async () => button('加密保存模型配置').props.onPress());
  expect(native.saveModelConfiguration).toHaveBeenCalledWith(config.endpoint, config.model, 'demo-not-a-real-api-credential');
  expect(input().props.value).toBe('');
  expect(button('开始真实模型两步任务').props.disabled).toBe(false);
  expect(JSON.stringify(screen.toJSON())).not.toContain('demo-not-a-real-api-credential');
});

it('starts only the fixed demo goal and offers cancellation rather than approval automation', async () => {
  (native.getModelConfiguration as jest.Mock).mockResolvedValue({ ...config, hasCredential: true });
  await ReactTestRenderer.act(async () => button('加密保存模型配置').props.onPress());
  (native.getAgentState as jest.Mock).mockResolvedValue({ ...state, phase: 'model', taskId: 'task-1', rounds: 1 });
  await ReactTestRenderer.act(async () => button('开始真实模型两步任务').props.onPress());
  expect(native.startAgent).toHaveBeenCalledWith('先读取设备信息，再读取固定演示 CSV 行数，用真实结果总结；不要访问其他数据');
  await ReactTestRenderer.act(async () => button('取消模型与工具任务').props.onPress());
  expect(native.cancelAgent).toHaveBeenCalledWith();
});

it('never renders SDK error messages or credential-bearing server text', async () => {
  (native.saveModelConfiguration as jest.Mock).mockRejectedValue(new Error('server echoed demo-secret-value'));
  await ReactTestRenderer.act(async () => button('加密保存模型配置').props.onPress());
  expect(JSON.stringify(screen.toJSON())).not.toContain('demo-secret-value');
  expect(JSON.stringify(screen.toJSON())).toContain('操作失败');
});
