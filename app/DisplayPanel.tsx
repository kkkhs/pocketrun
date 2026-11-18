import { useCallback, useEffect, useRef, useState } from 'react';
import { Image, Pressable, StyleSheet, Text, View } from 'react-native';
import NativeAdb, {
  type DisplayInfo,
  type ScreenshotInfo,
  type UiTreeInfo,
} from './specs/NativePocketRunAdb';

type Colors = { text: string; muted: string };
type Action = 'create' | 'check' | 'release' | 'screenshot' | 'tree' | 'tap' | 'text';
type Target = { x: number; y: number };
type TreeView = UiTreeInfo & { target: Target | null };
const INPUT_TEXT = 'PocketRun_Ditto_5';
const MAX_JSON_CHARS = 2 * 1024 * 1024;
const JSON_PREVIEW_CHARS = 8192;
const messages: Record<Action, string> = {
  create: '正在由 shell 后端创建虚拟屏并启动 Demo…',
  check: '正在通过 Binder 检查虚拟屏…',
  release: '正在释放虚拟屏…',
  screenshot: '正在读取虚拟屏截图…',
  tree: '正在读取有界 UI 树…',
  tap: '正在点击演示输入框中心…',
  text: '正在输入固定 ASCII 文本…',
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseTree(value: UiTreeInfo, expectedId: number): TreeView {
  if (value.displayId !== expectedId || typeof value.json !== 'string' || value.json.length > MAX_JSON_CHARS) {
    throw new Error('UI 树 displayId 已失效或 JSON 过大。');
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(value.json);
  } catch {
    throw new Error('UI 树 JSON 无法解析，请重新获取。');
  }
  if (!isRecord(parsed) || parsed.displayId !== expectedId || parsed.status !== value.status ||
      parsed.truncated !== value.truncated || !Array.isArray(parsed.nodes) ||
      parsed.nodes.length !== value.nodeCount || value.nodeCount > 512) {
    throw new Error('UI 树 JSON 元数据无效。');
  }
  const matches = parsed.nodes.filter(isRecord).filter(node =>
    node.contentDescription === '演示输入框' && isRecord(node.flags) && node.flags.editable === true,
  );
  let target: Target | null = null;
  if (value.status === 'available' && !value.truncated && matches.length === 1) {
    const node = matches[0];
    const bounds = node.bounds;
    if (isRecord(node.flags) && node.flags.enabled === true && isRecord(bounds) &&
        typeof bounds.left === 'number' && typeof bounds.top === 'number' &&
        typeof bounds.right === 'number' && typeof bounds.bottom === 'number' &&
        [bounds.left, bounds.top, bounds.right, bounds.bottom].every(Number.isInteger) &&
        bounds.left >= 0 && bounds.top >= 0 && bounds.right <= 720 && bounds.bottom <= 1280 &&
        bounds.left < bounds.right && bounds.top < bounds.bottom) {
      target = {
        x: Math.floor((bounds.left + bounds.right) / 2),
        y: Math.floor((bounds.top + bounds.bottom) / 2),
      };
    }
  }
  return { ...value, target };
}

export default function DisplayPanel({ colors }: { colors: Colors }) {
  const [busy, setBusy] = useState(false);
  const [nativeBusy, setNativeBusy] = useState(false);
  const [info, setInfo] = useState<DisplayInfo | null>(null);
  const [screenshot, setScreenshot] = useState<ScreenshotInfo | null>(null);
  const [tree, setTree] = useState<TreeView | null>(null);
  const [tappedId, setTappedId] = useState<number | null>(null);
  const [message, setMessage] = useState('先启动 shell 后端，再创建演示虚拟屏。');
  const generation = useRef(0);
  const working = useRef(false);
  const needsRefresh = useRef(true);
  const currentInfo = useRef<DisplayInfo | null>(null);

  const clearResults = useCallback(() => {
    setScreenshot(null);
    setTree(null);
    setTappedId(null);
  }, []);

  const updateInfo = useCallback((next: DisplayInfo | null) => {
    if (!next?.active || next.displayId !== currentInfo.current?.displayId) clearResults();
    currentInfo.current = next;
    needsRefresh.current = next === null;
    setInfo(next);
  }, [clearResults]);

  useEffect(() => {
    const counter = generation;
    const initial = counter.current;
    let live = true;
    let checking = false;
    NativeAdb?.getDisplayStatus()
      .then(status => {
        if (live && initial === counter.current) {
          updateInfo(status);
          if (status.active) setMessage(`虚拟屏仍在运行，displayId：${status.displayId}`);
        }
      })
      .catch(() => undefined);
    const timer = setInterval(async () => {
      if (!live || checking || !NativeAdb) return;
      checking = true;
      const request = counter.current;
      try {
        const [adb, backend] = await Promise.all([NativeAdb.getStatus(), NativeAdb.getBackendStatus()]);
        if (!live || request !== counter.current) return;
        setNativeBusy(adb.busy);
        if (!backend.running && currentInfo.current?.active) {
          counter.current++;
          updateInfo(null);
          setBusy(false);
          setMessage('shell 后端已失效，旧 displayId、截图和 UI 树已清除。');
        } else if (backend.running && !adb.busy && !working.current &&
            (currentInfo.current?.active || needsRefresh.current)) {
          const status = await NativeAdb.getDisplayStatus();
          if (live && request === counter.current) updateInfo(status);
        }
      } catch (error) {
        if (live && request === counter.current) {
          needsRefresh.current = true;
          if (isRecord(error) && error.code === 'E_ADB_BUSY') {
            setNativeBusy(true);
          } else {
            counter.current++;
            updateInfo(null);
            setBusy(false);
            setMessage('无法确认后端状态，旧结果已清除，空闲后将重新检查。');
          }
        }
      } finally {
        checking = false;
      }
    }, 1000);
    return () => {
      live = false;
      counter.current++;
      clearInterval(timer);
    };
  }, [updateInfo]);

  async function operate(action: Action) {
    if (working.current || nativeBusy) return;
    const expectedId = currentInfo.current?.active ? currentInfo.current.displayId : null;
    if (action !== 'create' && (!Number.isInteger(expectedId) || expectedId === null || expectedId <= 0)) return;
    const request = ++generation.current;
    working.current = true;
    setBusy(true);
    setMessage(messages[action]);
    if (action === 'release' || action === 'create') clearResults();
    if (action === 'tree') {
      setTree(null);
      setTappedId(null);
    }
    try {
      if (!NativeAdb) throw new Error('ADB 原生模块未注册，请重新构建 APK。');
      if (action === 'create' || action === 'release' || action === 'check') {
        const next = action === 'create'
          ? await NativeAdb.createDemoDisplay()
          : action === 'release'
          ? await NativeAdb.releaseDisplay()
          : await NativeAdb.getDisplayStatus();
        if (request !== generation.current) return;
        updateInfo(next);
        setMessage(next.active
          ? `Demo 已启动到虚拟屏，displayId：${next.displayId}`
          : '虚拟屏已释放，旧 displayId 已失效。');
      } else {
        if (expectedId === null) throw new Error('没有有效的 expectedDisplayId。');
        if (action === 'screenshot') {
          const next = await NativeAdb.captureScreenshot(expectedId);
          if (request !== generation.current) return;
          if (next.displayId !== expectedId || next.width !== 720 || next.height !== 1280 ||
              !/^[1-9][0-9]*$/.test(next.frameTimestampNanos) || !next.uri.startsWith('file://')) {
            throw new Error('截图元数据或 displayId 无效。');
          }
          setScreenshot(next);
          setMessage(`已读取截图，displayId：${expectedId}`);
        } else if (action === 'tree') {
          const next = await NativeAdb.dumpUiTree(expectedId);
          if (request !== generation.current) return;
          setTree(parseTree(next, expectedId));
          setMessage(`已读取 UI 树，displayId：${expectedId}`);
        } else {
          if (tree?.displayId !== expectedId || !tree.target || (action === 'text' && tappedId !== expectedId)) {
            throw new Error('请获取完整 UI 树并点击唯一的演示输入框。');
          }
          const ok = action === 'tap'
            ? await NativeAdb.injectTap(expectedId, tree.target.x, tree.target.y)
            : await NativeAdb.injectText(expectedId, INPUT_TEXT);
          if (request !== generation.current) return;
          if (!ok) throw new Error('后端未确认输入成功，请重新获取 UI 树。');
          if (action === 'tap') setTappedId(expectedId);
          setMessage(action === 'tap' ? '演示输入框点击成功。' : `已输入：${INPUT_TEXT}`);
        }
      }
    } catch (error) {
      if (request !== generation.current) return;
      clearResults();
      setMessage(error instanceof Error ? error.message : '虚拟屏操作失败。');
      needsRefresh.current = true;
      try {
        const status = await NativeAdb?.getDisplayStatus();
        if (request === generation.current) updateInfo(status ?? null);
      } catch (statusError) {
        if (request === generation.current) {
          if (isRecord(statusError) && statusError.code === 'E_ADB_BUSY') {
            setNativeBusy(true);
          } else {
            updateInfo(null);
          }
        }
      }
    } finally {
      working.current = false;
      if (request === generation.current) setBusy(false);
    }
  }

  function control(label: string, action: Action, disabled: boolean) {
    return (
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={label}
        accessibilityState={{ disabled, busy: busy || nativeBusy }}
        disabled={disabled}
        onPress={() => operate(action)}
        style={[styles.button, disabled && styles.disabled]}
      >
        <Text style={styles.buttonText}>{label}</Text>
      </Pressable>
    );
  }

  const active = info?.active === true && Number.isInteger(info.displayId) && info.displayId > 0;
  const unavailable = busy || nativeBusy || !active;
  const targetAvailable = tree?.displayId === info?.displayId && tree?.target != null;
  return (
    <View style={styles.panel}>
      <Text style={[styles.title, { color: colors.text }]}>第 4 章 · 演示虚拟屏</Text>
      <Text style={[styles.text, { color: colors.muted }]}>TS UI → App Kotlin → Binder → shell 后端；VirtualDisplay 由后端持有。</Text>
      {control('创建演示虚拟屏', 'create', busy || nativeBusy || active)}
      {control('检查虚拟屏', 'check', unavailable)}
      {control('释放虚拟屏', 'release', unavailable)}
      <Text style={[styles.title, { color: colors.text }]}>第 5 章 · 画面、UI 树与输入</Text>
      {control('读取虚拟屏截图', 'screenshot', unavailable)}
      {control('读取 UI 树', 'tree', unavailable)}
      {control('点击演示输入框', 'tap', unavailable || !targetAvailable)}
      {control('输入固定 ASCII 文本', 'text', unavailable || !targetAvailable || tappedId !== info?.displayId)}
      <Text accessibilityLiveRegion="polite" style={[styles.text, { color: colors.text }]}>{message}</Text>
      {screenshot && (
        <>
          <Image accessibilityLabel="虚拟屏截图" source={{ uri: screenshot.uri }} style={styles.screenshot} resizeMode="contain" />
          <Text selectable style={[styles.text, { color: colors.muted }]}>
            {`displayId：${screenshot.displayId} · ${screenshot.width}×${screenshot.height} · frameTimestampNanos：${screenshot.frameTimestampNanos}`}
          </Text>
        </>
      )}
      {tree && (
        <>
          <Text style={[styles.text, { color: colors.text }]}>
            {`UI 树状态：${tree.status} · 节点：${tree.nodeCount} · truncated：${tree.truncated}`}
          </Text>
          <Text style={[styles.text, { color: colors.muted }]}>
            {tree.target ? '已定位唯一可编辑的演示输入框。' : '未定位到唯一、启用且界内的演示输入框；输入已禁用。'}
          </Text>
          <Text selectable style={[styles.json, { color: colors.muted }]}>
            {tree.json.slice(0, JSON_PREVIEW_CHARS)}
            {tree.json.length > JSON_PREVIEW_CHARS ? '\n…展示已截断（最多 8192 字符）' : ''}
          </Text>
        </>
      )}
      <Text style={[styles.text, { color: colors.muted }]}>仅通过带 expectedDisplayId 的 Binder API 操作 Ditto 演示屏，不执行任意 shell。</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  panel: { gap: 12, paddingTop: 20 },
  title: { fontSize: 24, fontWeight: '700' },
  text: { fontSize: 16, lineHeight: 25 },
  json: { fontFamily: 'monospace', fontSize: 12, lineHeight: 18 },
  screenshot: { width: '100%', height: 400 },
  button: {
    backgroundColor: '#1d4ed8',
    borderRadius: 8,
    minHeight: 48,
    padding: 14,
    alignItems: 'center',
  },
  buttonText: { color: '#fff', fontSize: 17, fontWeight: '600' },
  disabled: { opacity: 0.5 },
});
