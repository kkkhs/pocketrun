import React from 'react';
import { Alert, Text } from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import CompleteTaskPanel from '../CompleteTaskPanel';
import NativeTaskHost, {
  type AgentLoopState, type CompleteTaskState, type SingleTaskState, type TaskHostState,
} from '../specs/NativePocketRunTaskHost';

jest.mock('../specs/NativePocketRunTaskHost', () => ({ __esModule: true, default: {
  getCompleteTaskState: jest.fn(), prepareCompleteTask: jest.fn(), startCompleteAgent: jest.fn(),
  resetCompleteJournal: jest.fn(), getSingleTaskState: jest.fn(), getAgentState: jest.fn(),
  getHostReadiness: jest.fn(), getModelConfiguration: jest.fn(), decideFromAppUi: jest.fn(),
  cancelAgent: jest.fn(), cancelTask: jest.fn(), retryCleanup: jest.fn(),
} }));
const native = NativeTaskHost!;
const runA = `run_${'a'.repeat(32)}`;
const runB = `run_${'b'.repeat(32)}`;
const runC = `run_${'c'.repeat(32)}`;
const report = 'items=2;quantity=5';
const configuration = { endpoint: 'https://model-a.example', model: 'model-a', hasCredential: true };
const ready = { packageUid: 10001, linuxReady: false, rootfsGeneration: null,
  displayReady: true, displayId: 7, displayEpoch: '3' };
const loop: AgentLoopState = { phase: 'idle', taskId: null, rounds: 0, outputTokens: 0,
  contextBytes: 0, finalText: null, code: null };
const host: TaskHostState = { phase: 'idle', tool: null, requestNonce: null, requestHash: null,
  backendEpoch: null, target: null, approvalActor: null, approvalState: null, cleanupState: 'none',
  deadlineNanos: null, code: null, message: null, resultJson: '{}' };
function journal(overrides: Partial<CompleteTaskState> = {}): CompleteTaskState {
  return { runId: runA, taskId: null, phase: 'interrupted', stage: 'csv_read', pendingEffect: 'none',
    inputDigest: '1'.repeat(64), expectedText: report, outputDigest: '', code: 'PROCESS_INTERRUPTED',
    displayId: 7, displayEpoch: '3', packageUid: 10001, ...overrides };
}
function context(overrides: Partial<SingleTaskState> = {}): SingleTaskState {
  return { taskId: null, goal: null, phase: 'idle', code: null, stepsJson: '[]', contextBytes: 0,
    maxContextBytes: 65536, maxResultBytes: 16384, maxSteps: 8, maxDurationMillis: 120000, ...overrides };
}
function step(number: number, nonce: string) {
  return { number, tool: 'workspace.read_demo_csv', phase: 'pending_approval', requestNonce: nonce,
    requestHash: `hash-${nonce}`, target: 'fixed private items.csv', approvalState: 'pending_app_ui',
    cleanupState: 'none', result: {} };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(yes => { resolve = yes; });
  return { promise, resolve };
}
let screen: ReactTestRenderer.ReactTestRenderer;
let alerts: jest.SpyInstance;
const matchesButton = (label: string) => (node: ReactTestRenderer.ReactTestInstance) =>
  node.props.accessibilityLabel === label && typeof node.props.onPress === 'function';
const control = (label: string) => screen.root.find(matchesButton(label));
const shown = () => screen.root.findAllByType(Text).map(node =>
  React.Children.toArray(node.props.children).filter(child =>
    typeof child === 'string' || typeof child === 'number').join('')).join('\n');
async function press(label: string) {
  const button = control(label);
  expect(button.props.disabled).not.toBe(true);
  await ReactTestRenderer.act(async () => { button.props.onPress(); });
}
async function confirm(label: string) {
  const buttons = alerts.mock.calls[alerts.mock.calls.length - 1][2];
  expect(buttons[0].style).toBe('cancel');
  await ReactTestRenderer.act(async () => { buttons.find((button: { text: string }) => button.text === label).onPress(); });
}
async function mount() {
  await ReactTestRenderer.act(async () => {
    screen = ReactTestRenderer.create(<CompleteTaskPanel colors={{ text: '#000', muted: '#777' }} />);
  });
}
async function remount() {
  await ReactTestRenderer.act(() => screen.unmount());
  await mount();
}
async function poll() {
  await ReactTestRenderer.act(async () => { jest.advanceTimersByTime(750); });
}

beforeEach(async () => {
  jest.useFakeTimers();
  jest.resetAllMocks();
  alerts = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
  (native.getCompleteTaskState as jest.Mock).mockResolvedValue(journal());
  (native.prepareCompleteTask as jest.Mock).mockResolvedValue(journal());
  (native.startCompleteAgent as jest.Mock).mockResolvedValue(journal());
  (native.getSingleTaskState as jest.Mock).mockResolvedValue(context());
  (native.getAgentState as jest.Mock).mockResolvedValue(loop);
  (native.getHostReadiness as jest.Mock).mockResolvedValue(ready);
  (native.getModelConfiguration as jest.Mock).mockResolvedValue(configuration);
  (native.decideFromAppUi as jest.Mock).mockResolvedValue(host);
  await mount();
});
afterEach(async () => {
  await ReactTestRenderer.act(() => screen.unmount());
  alerts.mockRestore();
  jest.useRealTimers();
});

it('loads interrupted facts without starting, approving or replaying any action', async () => {
  expect(shown()).toContain(runA);
  expect(shown()).toContain('原生核验：interrupted');
  expect(shown()).toContain('持久事实');
  await poll();
  expect(native.startCompleteAgent).not.toHaveBeenCalled();
  expect(native.prepareCompleteTask).not.toHaveBeenCalled();
  expect(native.decideFromAppUi).not.toHaveBeenCalled();
  expect(native.resetCompleteJournal).not.toHaveBeenCalled();
  expect(alerts).not.toHaveBeenCalled();
});

it('requires phone confirmation and binds fresh run, display and model snapshots', async () => {
  const current = { endpoint: 'https://model-b.example', model: 'model-b', hasCredential: true };
  (native.getCompleteTaskState as jest.Mock).mockResolvedValue(journal({ runId: runB, phase: 'failed', pendingEffect: 'publish' }));
  (native.getHostReadiness as jest.Mock).mockResolvedValue({ ...ready, displayId: 11, displayEpoch: '8' });
  (native.getModelConfiguration as jest.Mock).mockResolvedValue(current);
  await press('核对并开始完整模型任务');
  expect(native.startCompleteAgent).not.toHaveBeenCalled();
  const body = alerts.mock.calls[0][1];
  expect(body).toContain(runB);
  expect(body).toContain(current.endpoint);
  expect(body).toContain(current.model);
  expect(body).toContain('11 / epoch 8');
  expect(body).toContain('各自手机批准');
  // Later configuration reads cannot silently replace the destination already disclosed.
  (native.getModelConfiguration as jest.Mock).mockResolvedValue({ ...current, endpoint: 'https://changed.example' });
  await confirm('确认开始新任务');
  expect(native.startCompleteAgent).toHaveBeenCalledTimes(1);
  expect(native.startCompleteAgent).toHaveBeenCalledWith(runB, 11, '8', current.endpoint, current.model);
  expect(native.decideFromAppUi).not.toHaveBeenCalled();
});

it('invalidates old phone consent after a newer explicit refresh', async () => {
  await press('核对并开始完整模型任务');
  (native.getCompleteTaskState as jest.Mock).mockResolvedValue(journal({ runId: runB }));
  await press('刷新完整任务记录');
  await confirm('确认开始新任务');
  expect(native.startCompleteAgent).not.toHaveBeenCalled();
  expect(shown()).toContain(runB);
});

it('shows only the matching current step and never retargets a captured nonce', async () => {
  const previous = { ...step(1, 'previous-nonce'), phase: 'succeeded', cleanupState: 'proven' };
  const current = step(2, 'current-nonce');
  (native.getCompleteTaskState as jest.Mock).mockResolvedValue(journal({ taskId: 'task-1', phase: 'running', pendingEffect: 'observe' }));
  (native.getSingleTaskState as jest.Mock).mockResolvedValue(context({ taskId: 'task-1', phase: 'running',
    stepsJson: JSON.stringify([previous, current]) }));
  await press('刷新完整任务记录');
  expect(shown()).toContain('hash-current-nonce');
  expect(shown()).not.toContain('hash-previous-nonce');
  expect(native.decideFromAppUi).not.toHaveBeenCalled();
  const capturedApprove = control('同意完整任务当前步骤').props.onPress;
  const next = step(3, 'next-nonce');
  (native.getSingleTaskState as jest.Mock).mockResolvedValue(context({ taskId: 'task-1', phase: 'running',
    stepsJson: JSON.stringify([previous, { ...current, phase: 'succeeded' }, next]) }));
  await poll();
  (native.decideFromAppUi as jest.Mock).mockRejectedValue(new Error('stale nonce private-native-detail'));
  await ReactTestRenderer.act(async () => { capturedApprove(); });
  expect(native.decideFromAppUi).toHaveBeenLastCalledWith('current-nonce', 'approve');
  expect(shown()).not.toContain('private-native-detail');
  await press('拒绝完整任务当前步骤');
  expect(native.decideFromAppUi).toHaveBeenLastCalledWith('next-nonce', 'reject');
  (native.getCompleteTaskState as jest.Mock).mockResolvedValue(journal({ taskId: 'other-task', phase: 'running' }));
  await press('刷新完整任务记录');
  expect(screen.root.findAll(matchesButton('同意完整任务当前步骤'))).toHaveLength(0);
});

it('fences late polling after a newer action or an unmounted page', async () => {
  const initialContext = deferred<SingleTaskState>();
  (native.getSingleTaskState as jest.Mock).mockReturnValueOnce(initialContext.promise);
  await remount();
  (native.getCompleteTaskState as jest.Mock).mockResolvedValue(journal({ runId: runB }));
  await press('刷新完整任务记录');
  await ReactTestRenderer.act(async () => initialContext.resolve(context({ taskId: 'obsolete-task', phase: 'completed',
    stepsJson: JSON.stringify([{ ...step(1, 'old'), result: { value: 'OBSOLETE POLL RESULT' } }]) })));
  expect(shown()).toContain(runB);
  expect(shown()).not.toContain('OBSOLETE POLL RESULT');
  const obsoleteJournal = deferred<CompleteTaskState>();
  (native.getCompleteTaskState as jest.Mock).mockReturnValueOnce(obsoleteJournal.promise);
  await remount();
  (native.getCompleteTaskState as jest.Mock).mockResolvedValue(journal({ runId: runC }));
  await remount();
  await ReactTestRenderer.act(async () => obsoleteJournal.resolve(journal()));
  expect(shown()).toContain(runC);
  expect(shown()).not.toContain(runA);
});

it('keeps unreadable storage blocked until explicit destructive journal-only reset', async () => {
  (native.getCompleteTaskState as jest.Mock).mockRejectedValue(new Error('corrupt ciphertext demo-secret'));
  await remount();
  expect(shown()).not.toContain('demo-secret');
  expect(control('核对并开始完整模型任务').props.disabled).toBe(true);
  (native.resetCompleteJournal as jest.Mock).mockImplementation(async () => {
    const empty = journal({ runId: null, phase: 'none', stage: 'none', inputDigest: '', expectedText: '',
      outputDigest: '', code: null, displayId: null, displayEpoch: null });
    (native.getCompleteTaskState as jest.Mock).mockResolvedValue(empty);
    return empty;
  });
  await press('重置不可读运行记录');
  expect(native.resetCompleteJournal).not.toHaveBeenCalled();
  expect(alerts.mock.calls[0][2][1].style).toBe('destructive');
  expect(alerts.mock.calls[0][1]).toContain('不删除 CSV、输出、模型凭据或知识记忆');
  await confirm('确认重置记录');
  expect(native.resetCompleteJournal).toHaveBeenCalledTimes(1);
  expect(shown()).toContain('原生核验：none');
  expect(native.startCompleteAgent).not.toHaveBeenCalled();
  expect(native.prepareCompleteTask).not.toHaveBeenCalled();
});

it('rejects malformed journal fields and an incomplete completed claim', async () => {
  const invalid = [journal({ phase: 'unknown' }), journal({ inputDigest: 'not-a-digest' }),
    journal({ phase: 'completed', stage: 'none', pendingEffect: 'none', inputDigest: '', expectedText: '', outputDigest: '' })];
  for (const value of invalid) {
    (native.getCompleteTaskState as jest.Mock).mockResolvedValue(value);
    await remount();
    expect(control('核对并开始完整模型任务').props.disabled).toBe(true);
    expect(shown()).not.toContain('原生核验：completed');
    expect(screen.root.findAll(matchesButton('重置不可读运行记录'))).toHaveLength(1);
  }
  expect(native.startCompleteAgent).not.toHaveBeenCalled();
});

it('sanitizes failed starts and never retries or approves automatically', async () => {
  (native.startCompleteAgent as jest.Mock).mockRejectedValue(new Error('provider secret and raw SDK failure'));
  await press('核对并开始完整模型任务');
  await confirm('确认开始新任务');
  await poll();
  await poll();
  expect(native.startCompleteAgent).toHaveBeenCalledTimes(1);
  expect(native.decideFromAppUi).not.toHaveBeenCalled();
  expect(shown()).not.toContain('provider secret');
  expect(shown()).toContain('不会自动重放或覆盖');
});

it('renders model wording separately from the native verified completion record', async () => {
  (native.getCompleteTaskState as jest.Mock).mockResolvedValue(journal({ taskId: 'task-1', phase: 'running' }));
  (native.getSingleTaskState as jest.Mock).mockResolvedValue(context({ taskId: 'task-1', phase: 'ready' }));
  (native.getAgentState as jest.Mock).mockResolvedValue({ ...loop, taskId: 'task-1', phase: 'completed',
    finalText: 'MODEL SAYS ALL DONE' });
  await press('刷新完整任务记录');
  expect(shown()).toContain('模型总结：MODEL SAYS ALL DONE');
  expect(shown()).toContain('原生核验：running');
  expect(shown()).not.toContain('原生核验：completed');
  (native.getCompleteTaskState as jest.Mock).mockResolvedValue(journal({ taskId: 'task-1', phase: 'completed',
    stage: 'output_verified', outputDigest: '2'.repeat(64), pendingEffect: 'none', code: 'OUTPUT_PUBLISHED' }));
  (native.getSingleTaskState as jest.Mock).mockResolvedValue(context({ taskId: 'task-1', phase: 'completed' }));
  await press('刷新完整任务记录');
  expect(shown()).toContain('原生核验：completed');
  expect(shown()).toContain(`原生核对的报告：${report}`);
  expect(shown()).toContain('2'.repeat(64));
});

it('does not keep an old approval usable after current step decoding fails', async () => {
  (native.getCompleteTaskState as jest.Mock).mockResolvedValue(journal({ taskId: 'task-1', phase: 'running', pendingEffect: 'read' }));
  (native.getSingleTaskState as jest.Mock).mockResolvedValue(context({ taskId: 'task-1', phase: 'running',
    stepsJson: JSON.stringify([step(1, 'old-pending')]) }));
  await press('刷新完整任务记录');
  expect(control('同意完整任务当前步骤').props.disabled).toBe(false);
  (native.getSingleTaskState as jest.Mock).mockResolvedValue(context({ taskId: 'task-1', phase: 'running',
    stepsJson: JSON.stringify([{ ...step(1, 'unknown-current'), result: { value: 42 } }]) }));
  await press('刷新完整任务记录');
  const approvals = screen.root.findAll(matchesButton('同意完整任务当前步骤'));
  expect(approvals.length === 0 || approvals.every(button => button.props.disabled)).toBe(true);
  expect(shown()).toContain('状态或操作未能核对');
  expect(native.decideFromAppUi).not.toHaveBeenCalled();
});
