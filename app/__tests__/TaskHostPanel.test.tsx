import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import TaskHostPanel from '../TaskHostPanel';
import NativeTaskHost, {
  type TaskHostReadiness,
  type TaskHostState,
} from '../specs/NativePocketRunTaskHost';

jest.mock('../specs/NativePocketRunTaskHost', () => ({
  __esModule: true,
  default: {
    prepareHost: jest.fn(), getHostReadiness: jest.fn(), submitTool: jest.fn(),
    getTaskState: jest.fn(), decideFromAppUi: jest.fn(), cancelFromAppUi: jest.fn(),
    refreshTask: jest.fn(), retryCleanup: jest.fn(),
  },
}));

const native = NativeTaskHost!;
const readiness = (overrides: Partial<TaskHostReadiness> = {}): TaskHostReadiness => ({
  packageUid: 10469, linuxReady: false, rootfsGeneration: null,
  displayReady: false, displayEpoch: null, displayId: null, ...overrides,
});
const state = (overrides: Partial<TaskHostState> = {}): TaskHostState => ({
  phase: 'idle', tool: null, requestNonce: null, requestHash: null, backendEpoch: null,
  target: null, approvalActor: null, approvalState: null, cleanupState: 'none',
  deadlineNanos: null, code: null, message: null, resultJson: '{}', ...overrides,
});
const pendingLinux = state({
  phase: 'pending_approval', tool: 'linux.count_demo_csv', requestNonce: '1_nonce',
  requestHash: 'a'.repeat(64), backendEpoch: 'rootfs:gen-1:10469',
  target: '/workspace/items.csv · rootfs gen-1 · UID 10469',
  approvalActor: 'app_phone_ui', approvalState: 'pending_app_ui',
});
let screen: ReactTestRenderer.ReactTestRenderer;
const text = () => JSON.stringify(screen.toJSON());
const button = (label: string) => screen.root.find(node =>
  node.props.accessibilityLabel === label && typeof node.props.onPress === 'function');

beforeEach(async () => {
  jest.resetAllMocks();
  (native.getHostReadiness as jest.Mock).mockResolvedValue(readiness());
  (native.getTaskState as jest.Mock).mockResolvedValue(state());
  (native.refreshTask as jest.Mock).mockResolvedValue(state());
  await ReactTestRenderer.act(async () => {
    screen = ReactTestRenderer.create(<TaskHostPanel colors={{ text: '#000', muted: '#777' }} />);
    await Promise.resolve();
  });
});

afterEach(async () => {
  if (screen.toJSON()) await ReactTestRenderer.act(() => screen.unmount());
});

it('prepares only the fixed Linux host and exposes actual readiness metadata', async () => {
  const ready = readiness({ linuxReady: true, rootfsGeneration: 'gen-1' });
  (native.prepareHost as jest.Mock).mockResolvedValue(ready);
  await ReactTestRenderer.act(async () => button('准备固定 Linux 工具').props.onPress());
  expect(native.prepareHost).toHaveBeenCalledWith();
  expect(text()).toContain('generation gen-1');
  expect(text()).toContain('App UID');
});

it('submits fixed CSV with no argv env or path and never auto-approves it', async () => {
  (native.getHostReadiness as jest.Mock).mockResolvedValue(readiness({
    linuxReady: true, rootfsGeneration: 'gen-1',
  }));
  await ReactTestRenderer.act(async () => {
    screen.unmount();
    screen = ReactTestRenderer.create(<TaskHostPanel colors={{ text: '#000', muted: '#777' }} />);
    await Promise.resolve();
  });
  (native.submitTool as jest.Mock).mockResolvedValue(pendingLinux);
  await ReactTestRenderer.act(async () => button('申请读取固定演示 CSV 行数').props.onPress());
  expect(native.submitTool).toHaveBeenCalledWith('linux.count_demo_csv', '{}');
  expect(native.decideFromAppUi).not.toHaveBeenCalled();
  expect(text()).toContain('/workspace/items.csv');
  expect(text()).toContain('app_phone_ui');
});

it('consumes the current nonce only through explicit App UI approve or reject buttons', async () => {
  (native.submitTool as jest.Mock).mockResolvedValue(pendingLinux);
  (native.decideFromAppUi as jest.Mock).mockResolvedValue(state({
    phase: 'executing', tool: pendingLinux.tool, requestNonce: pendingLinux.requestNonce,
    approvalState: 'approved_by_app_ui', cleanupState: 'none',
  }));
  (native.getHostReadiness as jest.Mock).mockResolvedValue(readiness({ linuxReady: true, rootfsGeneration: 'gen-1' }));
  await ReactTestRenderer.act(async () => {
    screen.unmount();
    screen = ReactTestRenderer.create(<TaskHostPanel colors={{ text: '#000', muted: '#777' }} />);
    await Promise.resolve();
  });
  await ReactTestRenderer.act(async () => button('申请读取固定演示 CSV 行数').props.onPress());
  expect(native.decideFromAppUi).not.toHaveBeenCalled();
  await ReactTestRenderer.act(async () => button('同意当前请求').props.onPress());
  expect(native.decideFromAppUi).toHaveBeenCalledWith('1_nonce', 'approve');
  expect((native.decideFromAppUi as jest.Mock).mock.calls[0]).toHaveLength(2);
});

it('supports explicit reject and cancel without an actor argument', async () => {
  (native.submitTool as jest.Mock).mockResolvedValue(pendingLinux);
  (native.decideFromAppUi as jest.Mock).mockResolvedValue(state({ phase: 'rejected',
    approvalState: 'rejected_by_app_ui', code: 'USER_REJECTED' }));
  (native.cancelFromAppUi as jest.Mock).mockResolvedValue(state({ phase: 'cancelled',
    approvalState: 'cancelled_by_app_ui', code: 'USER_CANCELLED' }));
  (native.getHostReadiness as jest.Mock).mockResolvedValue(readiness({ linuxReady: true, rootfsGeneration: 'gen-1' }));
  await ReactTestRenderer.act(async () => {
    screen.unmount();
    screen = ReactTestRenderer.create(<TaskHostPanel colors={{ text: '#000', muted: '#777' }} />);
    await Promise.resolve();
  });
  await ReactTestRenderer.act(async () => button('申请读取固定演示 CSV 行数').props.onPress());
  await ReactTestRenderer.act(async () => button('拒绝当前请求').props.onPress());
  expect(native.decideFromAppUi).toHaveBeenCalledWith('1_nonce', 'reject');
  expect((native.decideFromAppUi as jest.Mock).mock.calls[0]).toHaveLength(2);
  await ReactTestRenderer.act(async () => button('申请读取固定演示 CSV 行数').props.onPress());
  await ReactTestRenderer.act(async () => button('取消当前请求').props.onPress());
  expect(native.cancelFromAppUi).toHaveBeenCalledWith('1_nonce');
  expect((native.cancelFromAppUi as jest.Mock).mock.calls[0]).toHaveLength(1);
});

it('keeps typed Binder unavailable and never falls back to default display or host ADB', () => {
  expect(button('观察 App 演示虚拟屏').props.disabled).toBe(true);
  expect(button('申请输入固定演示文本').props.disabled).toBe(true);
  expect(text()).toContain('明确拒绝，不回退默认屏');
  expect(native.submitTool).not.toHaveBeenCalledWith(
    'android.virtual_display_observe_or_demo_input', expect.anything());
});

it('renders source and cleanup separately and retries an unproven owner before new work', async () => {
  const failed = state({ phase: 'failed', cleanupState: 'unproven',
    code: 'RESOURCE_CLEANUP_UNPROVEN', resultJson: JSON.stringify({
      sourceStatus: 'completed', cleanupStatus: 'unproven', lineCount: '3',
    }) });
  (native.submitTool as jest.Mock).mockResolvedValue(failed);
  (native.retryCleanup as jest.Mock).mockResolvedValue(state({ phase: 'failed',
    cleanupState: 'proven', code: 'CLEANUP_RECOVERED' }));
  await ReactTestRenderer.act(async () => button('运行 android.device_info').props.onPress());
  expect(text()).toContain('数据源状态');
  expect(text()).toContain('资源清理');
  await ReactTestRenderer.act(async () => button('重试资源清理').props.onPress());
  expect(native.retryCleanup).toHaveBeenCalledWith();
});

it('documents the single deterministic host boundary', () => {
  expect(text()).toContain('不运行模型循环');
  expect(text()).toContain('不接受任意命令、argv、环境、绑定路径或可执行文件');
  expect(text()).toContain('电脑代批和自动确认均不在本章边界内');
});
