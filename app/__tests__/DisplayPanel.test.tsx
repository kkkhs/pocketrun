import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import DisplayPanel from '../DisplayPanel';
import NativeAdb, { type ScreenshotInfo, type UiTreeInfo } from '../specs/NativePocketRunAdb';

jest.mock('../specs/NativePocketRunAdb', () => ({
  __esModule: true,
  default: {
    createDemoDisplay: jest.fn(),
    getDisplayStatus: jest.fn(),
    releaseDisplay: jest.fn(),
    getStatus: jest.fn(),
    getBackendStatus: jest.fn(),
    captureScreenshot: jest.fn(),
    dumpUiTree: jest.fn(),
    injectTap: jest.fn(),
    injectText: jest.fn(),
  },
}));
const adb = NativeAdb as jest.Mocked<NonNullable<typeof NativeAdb>>;
let screen: ReactTestRenderer.ReactTestRenderer;
const stopped = { active: false, displayId: -1 };
const active = { active: true, displayId: 7 };
const frame: ScreenshotInfo = {
  displayId: 7, width: 720, height: 1280,
  frameTimestampNanos: '9223372036854775807', uri: 'file:///private/cache/frame.jpg',
};
const inputNode = {
  parentIndex: -1, windowId: 1, depth: 0,
  className: 'android.widget.EditText', packageName: 'io.github.kkkhs.pocketrun',
  viewId: '', text: '', contentDescription: '演示输入框',
  bounds: { left: 20, top: 100, right: 700, bottom: 200 },
  flags: { clickable: true, editable: true, enabled: true, focused: false },
};
function treeResult(nodes = [inputNode], status = 'available', truncated = false): UiTreeInfo {
  return {
    displayId: 7, status, truncated, nodeCount: nodes.length,
    json: JSON.stringify({ displayId: 7, status, truncated, nodes }),
  };
}
function deferred<T>() {
  let finish!: (value: T) => void;
  const promise = new Promise<T>(resolve => { finish = resolve; });
  return { promise, finish };
}
const control = (label: string) => screen.root.find(node =>
  node.props.accessibilityLabel === label && typeof node.props.onPress === 'function',
);
const output = () => JSON.stringify(screen.toJSON());
async function press(label: string) {
  await ReactTestRenderer.act(async () => { await control(label).props.onPress(); });
}
async function createDisplay() {
  adb.createDemoDisplay.mockResolvedValueOnce(active);
  await press('创建演示虚拟屏');
  adb.getDisplayStatus.mockResolvedValue(active);
}
async function tick() {
  await ReactTestRenderer.act(async () => { jest.advanceTimersByTime(1000); });
}

beforeEach(async () => {
  jest.useFakeTimers();
  jest.resetAllMocks();
  adb.getDisplayStatus.mockResolvedValue(stopped);
  adb.getStatus.mockResolvedValue({ connected: false, busy: false, port: 0, uid: -1 });
  adb.getBackendStatus.mockResolvedValue({ running: true, uid: 2000, pid: 1, reply: 'pong' });
  adb.captureScreenshot.mockResolvedValue(frame);
  adb.dumpUiTree.mockResolvedValue(treeResult());
  adb.injectTap.mockResolvedValue(true);
  adb.injectText.mockResolvedValue(true);
  await ReactTestRenderer.act(async () => {
    screen = ReactTestRenderer.create(<DisplayPanel colors={{ text: '#111', muted: '#555' }} />);
  });
});

afterEach(async () => {
  await ReactTestRenderer.act(() => screen.unmount());
  jest.useRealTimers();
});

test('retains create/check/release and never calls a data method for an inactive ID', async () => {
  expect(control('读取虚拟屏截图').props.disabled).toBe(true);
  await press('读取虚拟屏截图');
  expect(adb.captureScreenshot).not.toHaveBeenCalled();
  await createDisplay();
  expect(adb.createDemoDisplay).toHaveBeenCalledTimes(1);
  expect(output()).toContain('displayId：7');
  expect(control('检查虚拟屏').props.disabled).toBe(false);
  await press('检查虚拟屏');
  adb.releaseDisplay.mockResolvedValueOnce(stopped);
  await press('释放虚拟屏');
  expect(adb.releaseDisplay).toHaveBeenCalledTimes(1);
  expect(output()).toContain('旧 displayId 已失效');
  expect(control('创建演示虚拟屏').props.disabled).toBe(false);
});

test('passes the captured ID to screenshot/tree and renders URI, precise timestamp and bounded JSON', async () => {
  await createDisplay();
  await press('读取虚拟屏截图');
  expect(adb.captureScreenshot).toHaveBeenCalledWith(7);
  expect(screen.root.findByProps({ accessibilityLabel: '虚拟屏截图' }).props.source).toEqual({ uri: frame.uri });
  expect(output()).toContain(frame.frameTimestampNanos);
  expect(output()).toContain('720×1280');
  await press('读取 UI 树');
  expect(adb.dumpUiTree).toHaveBeenCalledWith(7);
  expect(output()).toContain('UI 树状态：available');
  expect(output()).toContain('parentIndex');
  expect(output()).toContain('已定位唯一可编辑');
});

test('taps the unique editable description at its center then injects only fixed ASCII', async () => {
  await createDisplay();
  expect(control('点击演示输入框').props.disabled).toBe(true);
  await press('读取 UI 树');
  expect(control('输入固定 ASCII 文本').props.disabled).toBe(true);
  await press('点击演示输入框');
  expect(adb.injectTap).toHaveBeenCalledWith(7, 360, 150);
  await press('输入固定 ASCII 文本');
  expect(adb.injectText).toHaveBeenCalledWith(7, 'PocketRun_Ditto_5');
  expect(output()).toContain('已输入：PocketRun_Ditto_5');
});

test.each([
  ['duplicate descriptions', treeResult([inputNode, inputNode])],
  ['bounds outside the display', treeResult([{ ...inputNode, bounds: { left: 0, top: 0, right: 721, bottom: 200 } }])],
  ['disabled editor', treeResult([{ ...inputNode, flags: { ...inputNode.flags, enabled: false } }])],
  ['truncated tree', treeResult([inputNode], 'available', true)],
  ['unavailable tree', treeResult([], 'timeout')],
])('does not enable input for %s', async (_name, tree) => {
  await createDisplay();
  adb.dumpUiTree.mockResolvedValueOnce(tree);
  await press('读取 UI 树');
  expect(control('点击演示输入框').props.disabled).toBe(true);
  await press('点击演示输入框');
  expect(adb.injectTap).not.toHaveBeenCalled();
});

test('reports malformed JSON and stale screenshot IDs without crashing or retaining old results', async () => {
  await createDisplay();
  await press('读取虚拟屏截图');
  adb.dumpUiTree.mockResolvedValueOnce({ ...treeResult(), json: '{broken' });
  await press('读取 UI 树');
  expect(output()).toContain('UI 树 JSON 无法解析');
  expect(output()).not.toContain(frame.uri);
  adb.captureScreenshot.mockResolvedValueOnce({ ...frame, displayId: 8 });
  await press('读取虚拟屏截图');
  expect(output()).toContain('截图元数据或 displayId 无效');
  expect(output()).not.toContain(frame.uri);
});

test('limits the JSON preview without dropping the parseable tree contract', async () => {
  await createDisplay();
  const nodes = Array.from({ length: 32 }, (_, index) => ({
    ...inputNode, contentDescription: index === 0 ? '演示输入框' : `其他节点${index}`, text: 'x'.repeat(200),
  }));
  adb.dumpUiTree.mockResolvedValueOnce(treeResult(nodes));
  await press('读取 UI 树');
  expect(output()).toContain('展示已截断');
  expect(control('点击演示输入框').props.disabled).toBe(false);
});

test('disables all actions while local or native worker is busy and rejects double presses', async () => {
  await createDisplay();
  const pending = deferred<ScreenshotInfo>();
  adb.captureScreenshot.mockReturnValueOnce(pending.promise);
  await ReactTestRenderer.act(() => { control('读取虚拟屏截图').props.onPress(); });
  expect(control('读取 UI 树').props.disabled).toBe(true);
  expect(control('释放虚拟屏').props.disabled).toBe(true);
  await press('读取 UI 树');
  expect(adb.dumpUiTree).not.toHaveBeenCalled();
  await ReactTestRenderer.act(async () => pending.finish(frame));
  adb.getStatus.mockResolvedValue({ connected: false, busy: true, port: 0, uid: -1 });
  await tick();
  expect(control('读取虚拟屏截图').props.disabled).toBe(true);
  expect(control('检查虚拟屏').props.disabled).toBe(true);
});

test('clears screenshot/tree on release and cannot resurrect them with a late initial status', async () => {
  await createDisplay();
  await press('读取虚拟屏截图');
  await press('读取 UI 树');
  adb.releaseDisplay.mockResolvedValueOnce(stopped);
  await press('释放虚拟屏');
  expect(output()).not.toContain(frame.uri);
  expect(output()).not.toContain('UI 树状态：');

  const pending = deferred<typeof active>();
  adb.getDisplayStatus.mockReturnValueOnce(pending.promise);
  await ReactTestRenderer.act(async () => {
    screen.unmount();
    screen = ReactTestRenderer.create(<DisplayPanel colors={{ text: '#111', muted: '#555' }} />);
  });
  await createDisplay();
  adb.releaseDisplay.mockResolvedValueOnce(stopped);
  await press('释放虚拟屏');
  await ReactTestRenderer.act(async () => pending.finish(active));
  expect(control('读取虚拟屏截图').props.disabled).toBe(true);
  expect(output()).toContain('旧 displayId 已失效');
});

test('backend loss clears displayed data and discards an in-flight screenshot', async () => {
  await createDisplay();
  await press('读取虚拟屏截图');
  await press('读取 UI 树');
  const pending = deferred<ScreenshotInfo>();
  adb.captureScreenshot.mockReturnValueOnce(pending.promise);
  await ReactTestRenderer.act(() => { control('读取虚拟屏截图').props.onPress(); });
  adb.getBackendStatus.mockResolvedValue({ running: false, uid: -1, pid: -1, reply: '' });
  await tick();
  expect(output()).not.toContain(frame.uri);
  expect(output()).not.toContain('UI 树状态：');
  expect(control('点击演示输入框').props.disabled).toBe(true);
  await ReactTestRenderer.act(async () => pending.finish(frame));
  expect(output()).not.toContain(frame.uri);
  expect(output()).toContain('shell 后端已失效');
});

test('discards stale data when the active display changes', async () => {
  await createDisplay();
  await press('读取虚拟屏截图');
  await press('读取 UI 树');
  adb.getDisplayStatus.mockResolvedValue({ active: true, displayId: 8 });
  await tick();
  expect(output()).not.toContain(frame.uri);
  expect(control('点击演示输入框').props.disabled).toBe(true);
  adb.captureScreenshot.mockResolvedValueOnce({ ...frame, displayId: 8 });
  await press('读取虚拟屏截图');
  expect(adb.captureScreenshot).toHaveBeenLastCalledWith(8);
});

test.each(['screenshot', 'tree'] as const)('ignores late %s after unmount', async action => {
  await createDisplay();
  const pending = deferred<ScreenshotInfo & UiTreeInfo>();
  if (action === 'screenshot') adb.captureScreenshot.mockReturnValueOnce(pending.promise);
  else adb.dumpUiTree.mockReturnValueOnce(pending.promise);
  await ReactTestRenderer.act(() => { control(action === 'screenshot' ? '读取虚拟屏截图' : '读取 UI 树').props.onPress(); });
  await ReactTestRenderer.act(() => screen.unmount());
  await ReactTestRenderer.act(async () => pending.finish({ ...frame, ...treeResult() }));
  expect(screen.toJSON()).toBeNull();
});

test('recovers after a timed-out RPC keeps native busy until its late completion', async () => {
  await createDisplay();
  await press('读取虚拟屏截图');
  adb.captureScreenshot.mockRejectedValueOnce(Object.assign(new Error('虚拟屏操作超时'), { code: 'E_BACKEND_TIMEOUT' }));
  adb.getDisplayStatus.mockRejectedValueOnce(Object.assign(new Error('已有操作进行中'), { code: 'E_ADB_BUSY' }));
  adb.getStatus.mockResolvedValue({ connected: false, busy: true, port: 0, uid: -1 });
  await press('读取虚拟屏截图');
  expect(output()).toContain('虚拟屏操作超时');
  expect(output()).not.toContain(frame.uri);
  expect(control('读取虚拟屏截图').props.disabled).toBe(true);
  await tick();
  expect(control('释放虚拟屏').props.disabled).toBe(true);
  adb.getStatus.mockResolvedValue({ connected: false, busy: false, port: 0, uid: -1 });
  await tick();
  expect(control('读取虚拟屏截图').props.disabled).toBe(false);
  expect(control('释放虚拟屏').props.disabled).toBe(false);
  adb.releaseDisplay.mockResolvedValueOnce(stopped);
  await press('释放虚拟屏');
  expect(output()).toContain('旧 displayId 已失效');
});

test('recovers when a polling display RPC races with a user action and both report busy', async () => {
  await createDisplay();
  const pending = deferred<typeof active>();
  adb.getDisplayStatus.mockReturnValueOnce(pending.promise);
  await tick();
  adb.captureScreenshot.mockRejectedValueOnce(Object.assign(new Error('已有操作进行中'), { code: 'E_ADB_BUSY' }));
  adb.getDisplayStatus.mockRejectedValueOnce(Object.assign(new Error('已有操作进行中'), { code: 'E_ADB_BUSY' }));
  await press('读取虚拟屏截图');
  expect(control('读取虚拟屏截图').props.disabled).toBe(true);
  await ReactTestRenderer.act(async () => pending.finish(active));
  await tick();
  expect(control('释放虚拟屏').props.disabled).toBe(false);
  await press('读取虚拟屏截图');
  expect(adb.captureScreenshot).toHaveBeenLastCalledWith(7);
  expect(output()).toContain(frame.uri);
});

test('recovers an unknown display state after the backend becomes idle', async () => {
  adb.getDisplayStatus.mockRejectedValueOnce(new Error('暂时无法读取状态'));
  await ReactTestRenderer.act(async () => {
    screen.unmount();
    screen = ReactTestRenderer.create(<DisplayPanel colors={{ text: '#111', muted: '#555' }} />);
  });
  adb.getDisplayStatus.mockResolvedValue(active);
  await tick();
  expect(control('释放虚拟屏').props.disabled).toBe(false);
  await press('读取虚拟屏截图');
  expect(adb.captureScreenshot).toHaveBeenCalledWith(7);
});

test('does not keep querying a confirmed inactive display or a stopped backend', async () => {
  const calls = adb.getDisplayStatus.mock.calls.length;
  await tick();
  await tick();
  expect(adb.getDisplayStatus).toHaveBeenCalledTimes(calls);
  adb.getBackendStatus.mockResolvedValue({ running: false, uid: -1, pid: -1, reply: '' });
  await tick();
  expect(adb.getDisplayStatus).toHaveBeenCalledTimes(calls);
});

test('shows native failures and does not claim success when injection returns false', async () => {
  adb.createDemoDisplay.mockRejectedValueOnce(new Error('请先启动 shell 后端。'));
  await press('创建演示虚拟屏');
  expect(output()).toContain('请先启动 shell 后端');
  await createDisplay();
  await press('读取 UI 树');
  adb.injectTap.mockResolvedValueOnce(false);
  await press('点击演示输入框');
  expect(output()).toContain('后端未确认输入成功');
  expect(control('输入固定 ASCII 文本').props.disabled).toBe(true);
});
