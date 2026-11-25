import React from 'react';
import { Alert } from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import MemoryPanel from '../MemoryPanel';
import NativeTaskHost, { type AgentLoopState, type MemorySnapshot } from '../specs/NativePocketRunTaskHost';

jest.mock('../specs/NativePocketRunTaskHost', () => ({ __esModule: true, default: {
  getMemorySnapshot: jest.fn(), saveMemoryNote: jest.fn(), deleteMemoryNote: jest.fn(),
  clearMemory: jest.fn(), resetMemory: jest.fn(), startAgentWithMemory: jest.fn(),
  getModelConfiguration: jest.fn(), getAgentState: jest.fn(), cancelAgent: jest.fn(),
  startAgent: jest.fn(), decideFromAppUi: jest.fn(), clearModelConfiguration: jest.fn(),
} }));
const native = NativeTaskHost!;
const configuration = { endpoint: 'https://model-a.example', model: 'model-a', hasCredential: true };
const state: AgentLoopState = { phase: 'idle', taskId: null, rounds: 0, outputTokens: 0, contextBytes: 0, finalText: null, code: null };
const note = { id: 'note-a', text: '总结以演示报告 A 开头' };
function snapshot(revision = 'revision-a', notes = [note]): MemorySnapshot {
  return { revision, notesJson: JSON.stringify(notes), maxNotes: 8, maxNoteBytes: 256,
    maxSelected: 4, maxSelectedBytes: 2048 };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
let screen: ReactTestRenderer.ReactTestRenderer;
let alerts: jest.SpyInstance;
const control = (label: string) => screen.root.find(node => node.props.accessibilityLabel === label &&
  typeof node.props.onPress === 'function');
const input = () => screen.root.find(node => node.props.accessibilityLabel === '记忆笔记正文' &&
  typeof node.props.onChangeText === 'function');
const rendered = () => JSON.stringify(screen.toJSON());
async function press(label: string) {
  const button = control(label);
  expect(button.props.disabled).not.toBe(true);
  await ReactTestRenderer.act(async () => { button.props.onPress(); });
}
async function confirm() {
  const buttons = alerts.mock.calls[alerts.mock.calls.length - 1][2];
  expect(buttons[0].style).toBe('cancel');
  await ReactTestRenderer.act(async () => { buttons.find((button: { text: string }) => button.text === '确认').onPress(); });
}
async function mount() {
  await ReactTestRenderer.act(async () => {
    screen = ReactTestRenderer.create(<MemoryPanel colors={{ text: '#000', muted: '#777' }} />);
  });
}
async function remount() {
  await ReactTestRenderer.act(() => screen.unmount());
  await mount();
}

beforeEach(async () => {
  jest.useFakeTimers();
  jest.resetAllMocks();
  alerts = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
  (native.getMemorySnapshot as jest.Mock).mockResolvedValue(snapshot());
  (native.getModelConfiguration as jest.Mock).mockResolvedValue(configuration);
  (native.getAgentState as jest.Mock).mockResolvedValue(state);
  await mount();
});
afterEach(async () => {
  await ReactTestRenderer.act(() => screen.unmount());
  alerts.mockRestore();
  jest.useRealTimers();
});

it('views native notes and uses snapshot revisions for create and correction', async () => {
  expect(rendered()).toContain(note.text);
  expect(rendered()).toContain('UTF-8');
  expect(control('使用所选记忆开始两步任务').props.disabled).toBe(true);
  await ReactTestRenderer.act(() => input().props.onChangeText('另一条非敏感笔记'));
  const added = { id: 'note-b', text: '另一条非敏感笔记' };
  (native.saveMemoryNote as jest.Mock).mockResolvedValueOnce(snapshot('revision-b', [note, added]));
  await press('保存新记忆');
  expect(native.saveMemoryNote).toHaveBeenCalledWith('revision-a', '', added.text);
  expect(input().props.value).toBe('');
  expect(rendered()).toContain(added.text);

  await press('编辑记忆 1');
  expect(input().props.value).toBe(note.text);
  const corrected = { ...note, text: '总结以演示报告 B 开头' };
  await ReactTestRenderer.act(() => input().props.onChangeText(corrected.text));
  (native.saveMemoryNote as jest.Mock).mockResolvedValueOnce(snapshot('revision-c', [corrected, added]));
  await press('保存记忆修改');
  expect(native.saveMemoryNote).toHaveBeenLastCalledWith('revision-b', note.id, corrected.text);
  expect(input().props.value).toBe('');
  expect(rendered()).toContain(corrected.text);
  expect(native.startAgentWithMemory).not.toHaveBeenCalled();
  expect(native.decideFromAppUi).not.toHaveBeenCalled();
});

it('deletes only after phone confirmation and removes the deleted selection', async () => {
  await press('选择记忆 1');
  (native.deleteMemoryNote as jest.Mock).mockResolvedValue(snapshot('revision-b', []));
  await press('删除记忆 1');
  expect(native.deleteMemoryNote).not.toHaveBeenCalled();
  expect(alerts.mock.calls[0][1]).toContain('不能撤回');
  await confirm();
  expect(native.deleteMemoryNote).toHaveBeenCalledWith('revision-a', note.id);
  expect(rendered()).toContain('当前没有笔记');
  expect(rendered()).not.toContain(note.text);
  expect(control('使用所选记忆开始两步任务').props.disabled).toBe(true);
});

it('clears all notes only after explicit confirmation with the current revision', async () => {
  (native.clearMemory as jest.Mock).mockResolvedValue(snapshot('revision-b', []));
  await press('清空全部记忆');
  expect(native.clearMemory).not.toHaveBeenCalled();
  await confirm();
  expect(native.clearMemory).toHaveBeenCalledWith('revision-a');
  expect(rendered()).toContain('revision-b');
  expect(native.clearModelConfiguration).not.toHaveBeenCalled();
});

it('discloses only selected notes to a freshly checked destination before starting', async () => {
  const other = { id: 'note-b', text: '未选择的笔记不发送' };
  (native.getMemorySnapshot as jest.Mock).mockResolvedValue(snapshot('revision-a', [note, other]));
  await press('刷新记忆与模型配置');
  await press('选择记忆 1');
  const current = { ...configuration, endpoint: 'https://model-b.example', model: 'model-b' };
  (native.getModelConfiguration as jest.Mock).mockResolvedValue(current);
  (native.startAgentWithMemory as jest.Mock).mockResolvedValue({ ...state, phase: 'model', taskId: 'task-1',
    memoryRevision: 'revision-a', memoryIdsJson: JSON.stringify([note.id]), memoryBytes: 100 });
  await press('使用所选记忆开始两步任务');
  expect(native.startAgentWithMemory).not.toHaveBeenCalled();
  const body = alerts.mock.calls[0][1];
  expect(body).toContain(current.endpoint);
  expect(body).toContain(current.model);
  expect(body).toContain(note.text);
  expect(body).not.toContain(other.text);
  await confirm();
  expect(native.startAgentWithMemory).toHaveBeenCalledWith(
    '先读取设备信息，再读取固定演示 CSV 行数，用真实结果总结；不要访问其他数据',
    [note.id], 'revision-a', current.endpoint, current.model,
  );
  expect(native.startAgent).not.toHaveBeenCalled();
  expect(native.decideFromAppUi).not.toHaveBeenCalled();
  expect(rendered()).toContain('revision-a');
  expect(rendered()).toContain('task-1');
  (native.cancelAgent as jest.Mock).mockResolvedValue({ ...state, phase: 'cancelled' });
  await press('取消当前模型任务');
  expect(native.cancelAgent).toHaveBeenCalledWith();
});

it('passes the consent destination to native and does not retry a rejected start', async () => {
  await press('选择记忆 1');
  await press('使用所选记忆开始两步任务');
  (native.startAgentWithMemory as jest.Mock).mockRejectedValue(new Error('changed destination demo-provider-secret'));
  (native.getModelConfiguration as jest.Mock).mockResolvedValue({ ...configuration, endpoint: 'https://changed.example' });
  await confirm();
  expect(native.startAgentWithMemory).toHaveBeenCalledTimes(1);
  expect(native.startAgentWithMemory).toHaveBeenCalledWith(expect.any(String), [note.id], 'revision-a',
    configuration.endpoint, configuration.model);
  expect(rendered()).not.toContain('demo-provider-secret');
  expect(rendered()).toContain('不会自动重试');
});

it('does not reuse old consent after a later snapshot refresh', async () => {
  await press('选择记忆 1');
  await press('使用所选记忆开始两步任务');
  (native.getMemorySnapshot as jest.Mock).mockResolvedValue(snapshot('revision-b', [{ ...note, text: '已纠正的笔记' }]));
  await press('刷新记忆与模型配置');
  await confirm();
  expect(native.startAgentWithMemory).not.toHaveBeenCalled();
  expect(rendered()).toContain('revision-b');
});

it('retains the draft on a stale save and refreshes without silently resubmitting', async () => {
  await press('编辑记忆 1');
  await ReactTestRenderer.act(() => input().props.onChangeText('用户未保存的修改'));
  (native.saveMemoryNote as jest.Mock).mockRejectedValue(new Error('STALE_REVISION demo-secret-text'));
  (native.getMemorySnapshot as jest.Mock).mockResolvedValue(snapshot('revision-b', [{ ...note, text: '别处已经修改' }]));
  await press('保存记忆修改');
  expect(native.saveMemoryNote).toHaveBeenCalledTimes(1);
  expect(native.saveMemoryNote).toHaveBeenCalledWith('revision-a', note.id, '用户未保存的修改');
  expect(input().props.value).toBe('用户未保存的修改');
  expect(rendered()).toContain('revision-b');
  expect(rendered()).not.toContain('demo-secret-text');
  expect(rendered()).toContain('核对版本');
});

it('keeps a changed draft when an older save finishes', async () => {
  const pending = deferred<MemorySnapshot>();
  (native.saveMemoryNote as jest.Mock).mockReturnValueOnce(pending.promise);
  await ReactTestRenderer.act(() => input().props.onChangeText('正在保存'));
  await press('保存新记忆');
  expect(input().props.editable).toBe(false);
  await ReactTestRenderer.act(() => input().props.onChangeText('后来的草稿'));
  await ReactTestRenderer.act(async () => pending.resolve(snapshot('revision-b')));
  expect(input().props.value).toBe('后来的草稿');
});

it('does not overwrite a draft during delayed initial loading or accept an old failure', async () => {
  const initial = deferred<MemorySnapshot>();
  (native.getMemorySnapshot as jest.Mock).mockReturnValueOnce(initial.promise);
  await remount();
  await ReactTestRenderer.act(() => input().props.onChangeText('初始化期间的草稿'));
  await press('刷新记忆与模型配置');
  await ReactTestRenderer.act(async () => initial.reject(new Error('late corrupt demo-sensitive-message')));
  expect(input().props.value).toBe('初始化期间的草稿');
  expect(rendered()).not.toContain('demo-sensitive-message');
  expect(screen.root.findAll(node => node.props.accessibilityRole === 'alert')).toHaveLength(0);
  expect(screen.root.findAll(node => node.props.accessibilityLabel === '重置不可读记忆')).toHaveLength(0);
});

it('keeps the user draft when the initial snapshot arrives later', async () => {
  const initial = deferred<MemorySnapshot>();
  (native.getMemorySnapshot as jest.Mock).mockReturnValueOnce(initial.promise);
  await remount();
  await ReactTestRenderer.act(() => input().props.onChangeText('先写好的草稿'));
  await ReactTestRenderer.act(async () => initial.resolve(snapshot('loaded-revision')));
  expect(input().props.value).toBe('先写好的草稿');
  expect(rendered()).toContain('loaded-revision');
  expect(rendered()).toContain(note.text);
});

it('does not keep a cached snapshot usable after an action and reload both fail', async () => {
  await ReactTestRenderer.act(() => input().props.onChangeText('保留这个草稿'));
  (native.saveMemoryNote as jest.Mock).mockRejectedValue(new Error('operation failed'));
  (native.getMemorySnapshot as jest.Mock).mockRejectedValue(new Error('store unreadable'));
  await press('保存新记忆');
  expect(input().props.value).toBe('保留这个草稿');
  expect(rendered()).not.toContain(note.text);
  expect(control('保存新记忆').props.disabled).toBe(true);
  expect(control('使用所选记忆开始两步任务').props.disabled).toBe(true);
  expect(control('重置不可读记忆').props.disabled).toBe(false);
});

it('fences stale polling and callbacks after unmount', async () => {
  const oldPoll = deferred<typeof state>();
  (native.getAgentState as jest.Mock).mockReturnValueOnce(oldPoll.promise);
  await remount();
  const save = deferred<MemorySnapshot>();
  (native.saveMemoryNote as jest.Mock).mockReturnValueOnce(save.promise);
  await ReactTestRenderer.act(() => input().props.onChangeText('旧页面草稿'));
  await press('保存新记忆');
  await ReactTestRenderer.act(async () => oldPoll.resolve({ ...state, phase: 'completed', finalText: 'OLD POLL RESPONSE' }));
  expect(rendered()).not.toContain('OLD POLL RESPONSE');
  (native.getMemorySnapshot as jest.Mock).mockResolvedValue(snapshot('revision-b'));
  await remount();
  await ReactTestRenderer.act(() => input().props.onChangeText('新页面草稿'));
  await ReactTestRenderer.act(async () => save.resolve(snapshot('obsolete-revision')));
  expect(input().props.value).toBe('新页面草稿');
  expect(rendered()).toContain('revision-b');
  expect(rendered()).not.toContain('obsolete-revision');
});

it('offers an unreadable-memory reset only after explicit destructive confirmation', async () => {
  (native.getMemorySnapshot as jest.Mock).mockRejectedValue(new Error('corrupt encrypted demo-sensitive-body'));
  await remount();
  expect(rendered()).toContain('不会把损坏或未知数据当成空库');
  expect(rendered()).not.toContain('demo-sensitive-body');
  expect(control('使用所选记忆开始两步任务').props.disabled).toBe(true);
  (native.resetMemory as jest.Mock).mockResolvedValue(snapshot('revision-reset', []));
  await press('重置不可读记忆');
  expect(native.resetMemory).not.toHaveBeenCalled();
  expect(alerts.mock.calls[0][2][1].style).toBe('destructive');
  await confirm();
  expect(native.resetMemory).toHaveBeenCalledWith();
  expect(rendered()).toContain('revision-reset');
  expect(rendered()).toContain('当前没有笔记');
  expect(native.clearModelConfiguration).not.toHaveBeenCalled();
});

it('refuses malformed native note data instead of treating it as an empty usable store', async () => {
  (native.getMemorySnapshot as jest.Mock).mockResolvedValue({ ...snapshot(), notesJson: '[{"id":"a","text":42}]' });
  await remount();
  expect(rendered()).toContain('记忆不可读取');
  expect(control('使用所选记忆开始两步任务').props.disabled).toBe(true);
  expect(control('保存新记忆').props.disabled).toBe(true);
});

it('enforces the reported selection limit and requires a fresh usable model configuration', async () => {
  const notes = Array.from({ length: 5 }, (_, index) => ({ id: `note-${index}`, text: `笔记 ${index}` }));
  (native.getMemorySnapshot as jest.Mock).mockResolvedValue(snapshot('revision-a', notes));
  await press('刷新记忆与模型配置');
  for (let index = 1; index <= 5; index++) await press(`选择记忆 ${index}`);
  expect(control('选择记忆 5').props.accessibilityState.checked).toBe(false);
  expect(rendered()).toContain('每次最多选择');
  (native.getModelConfiguration as jest.Mock).mockResolvedValue({ ...configuration, hasCredential: false });
  await press('使用所选记忆开始两步任务');
  expect(native.startAgentWithMemory).not.toHaveBeenCalled();
  expect(alerts).not.toHaveBeenCalled();
  expect(rendered()).toContain('本次没有发送记忆');
});
