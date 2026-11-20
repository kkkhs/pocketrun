import { useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import NativePocketRunPty, { type PtyResult, type PtySnapshot } from './specs/NativePocketRunPty';

type Colors = { text: string; muted: string };
type Control = '' | 'interrupt' | 'eof' | 'suspend' | 'quit';
let nextPanelId = 0;

function bridgeFailure(failure: unknown, fallback: string) {
  const record = typeof failure === 'object' && failure !== null
    ? failure as { code?: unknown; message?: unknown }
    : null;
  const message = typeof record?.message === 'string' ? record.message
    : failure instanceof Error ? failure.message : fallback;
  return {
    message,
    cleanupUnproven: record?.code === 'E_PTY_CLEANUP_UNPROVEN' || message.includes('cleanup_unproven:'),
  };
}

export default function PtyPanel({ colors }: { colors: Colors }) {
  const [result, setResult] = useState<PtyResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [running, setRunning] = useState(false);
  const [cleanupPending, setCleanupPending] = useState(false);
  const [ready, setReady] = useState(false);
  const [liveOutput, setLiveOutput] = useState('');
  const [input, setInput] = useState("printf 'PocketRun PTY input OK\\n'\n");
  const [size, setSize] = useState({ columns: 100, rows: 32 });
  const [sending, setSending] = useState(false);
  const panelId = useRef<number | null>(null);
  if (panelId.current === null) { panelId.current = ++nextPanelId; }
  const generation = useRef(0);
  const activeToken = useRef<string | null>(null);
  const pollTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  function stopPolling() {
    if (pollTimer.current !== null) { clearTimeout(pollTimer.current); pollTimer.current = null; }
  }

  useEffect(() => () => {
    generation.current++;
    stopPolling();
    const token = activeToken.current;
    activeToken.current = null;
    if (token && NativePocketRunPty) { NativePocketRunPty.closePty(token).catch(() => {}); }
  }, []);

  function beginPolling(token: string, current: number) {
    stopPolling();
    const poll = async () => {
      if (current !== generation.current || activeToken.current !== token || !NativePocketRunPty) { return; }
      try {
        const snapshot: PtySnapshot = await NativePocketRunPty.getPtyOutput(token);
        if (current !== generation.current || activeToken.current !== token || snapshot.token !== token) { return; }
        setLiveOutput(snapshot.output);
        setReady(snapshot.ready);
        setSize({ columns: snapshot.columns, rows: snapshot.rows });
        setCleanupPending(snapshot.cleanupPending);
      } catch {
        // startPty owns terminal settlement; a stale poll must not replace its structured result/error.
      }
      if (current === generation.current && activeToken.current === token) {
        pollTimer.current = setTimeout(poll, 400);
      }
    };
    poll().catch(() => {});
  }

  async function start() {
    if (activeToken.current) { return; }
    const current = ++generation.current;
    const token = `pty-${panelId.current}-${current}`;
    const initialSize = size;
    activeToken.current = token;
    setRunning(true);
    setReady(false);
    setLiveOutput('');
    setCleanupPending(false);
    setResult(null);
    setError(null);
    try {
      if (!NativePocketRunPty) { throw new Error('PTY 模块未注册，请重新构建 Android 应用。'); }
      beginPolling(token, current);
      const next = await NativePocketRunPty.startPty(token, initialSize.columns, initialSize.rows);
      if (current !== generation.current) { return; }
      if (next.token !== token) { throw new Error('PTY 结果已失效。'); }
      setLiveOutput(next.output);
      setSize({ columns: next.columns, rows: next.rows });
      setResult(next);
      setCleanupPending(next.cleanupStatus !== 'done');
      if (next.cleanupStatus === 'done') { activeToken.current = null; }
    } catch (failure) {
      if (current !== generation.current) { return; }
      const detail = bridgeFailure(failure, 'PTY 操作失败。');
      setError(detail.message);
      setCleanupPending(detail.cleanupUnproven);
      if (!detail.cleanupUnproven) { activeToken.current = null; }
    } finally {
      if (current === generation.current) {
        stopPolling();
        setReady(false);
        setRunning(false);
        setSending(false);
      }
    }
  }

  async function send(text: string, control: Control = '') {
    const token = activeToken.current;
    if (!token || sending || !NativePocketRunPty) { return; }
    setSending(true);
    setError(null);
    try { await NativePocketRunPty.writePty(token, text, control); }
    catch (failure) { setError(failure instanceof Error ? failure.message : 'PTY 输入失败。'); }
    finally { setSending(false); }
  }

  async function resize(columns: number, rows: number) {
    const token = activeToken.current;
    if (!token) { setSize({ columns, rows }); return; }
    if (!ready || sending || !NativePocketRunPty) { return; }
    setSending(true);
    setError(null);
    try {
      await NativePocketRunPty.resizePty(token, columns, rows);
      if (activeToken.current === token) { setSize({ columns, rows }); }
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'PTY resize 失败。');
    } finally { setSending(false); }
  }

  async function close() {
    const token = activeToken.current;
    if (!token || !NativePocketRunPty) { return; }
    setSending(true);
    setError(null);
    try {
      const accepted = await NativePocketRunPty.closePty(token);
      if (!accepted) { setError('PTY 已退出，没有匹配的会话。'); return; }
      if (cleanupPending) {
        activeToken.current = null;
        setCleanupPending(false);
      }
    } catch (failure) {
      const detail = bridgeFailure(failure, 'PTY 关闭失败。');
      setError(detail.message);
      setCleanupPending(current => current || detail.cleanupUnproven);
    } finally { setSending(false); }
  }

  function button(label: string, disabled: boolean, action: () => void) {
    return <Pressable accessibilityRole="button" accessibilityLabel={label}
      accessibilityState={{ disabled }} disabled={disabled} onPress={action}
      style={[styles.button, disabled && styles.disabled]}>
      <Text style={styles.buttonText}>{label}</Text>
    </Pressable>;
  }

  const hasOwnedSession = activeToken.current !== null;
  return <View style={styles.panel}>
    <Text style={[styles.heading, { color: colors.text }]}>第 8 章 · 交互式 PTY</Text>
    <Text style={{ color: colors.muted }}>固定启动 App 私有 PRoot / Alpine BusyBox shell。JS 只能提供终端文字、16..400 尺寸和有限控制字符，不能指定可执行文件、环境、绑定目录或 PID。</Text>
    <Text style={{ color: colors.text }}>终端尺寸：{size.columns} × {size.rows}</Text>
    <View style={styles.row}>
      {button('使用 80×24', (hasOwnedSession && !ready) || sending, () => resize(80, 24))}
      {button('使用 100×32', (hasOwnedSession && !ready) || sending, () => resize(100, 32))}
    </View>
    {button('启动交互终端', hasOwnedSession, () => start())}
    <TextInput accessibilityLabel="PTY 输入" value={input} onChangeText={setInput} editable={hasOwnedSession && ready}
      multiline maxLength={4096} autoCapitalize="none" autoCorrect={false}
      style={[styles.input, { color: colors.text, borderColor: colors.muted }]}/>
    {button('发送 PTY 文本', !hasOwnedSession || !ready || sending || input.length === 0, () => send(input))}
    <View style={styles.row}>
      {button('发送 Ctrl-C', !hasOwnedSession || !ready || sending, () => send('', 'interrupt'))}
      {button('发送 Ctrl-D', !hasOwnedSession || !ready || sending, () => send('', 'eof'))}
    </View>
    {button(cleanupPending ? '重试 PTY 清理' : '关闭 PTY', !hasOwnedSession || sending, () => close())}
    {running && <Text style={{ color: colors.muted }}>{ready ? 'PTY ready：可实时输入、发送控制字符和调整尺寸。' : '正在校验 Linux 并启动 PTY…'}</Text>}
    {hasOwnedSession && <Text selectable accessibilityLiveRegion="polite" style={{ color: colors.text }}>PTY 实时合流输出：{liveOutput || '(waiting)'}</Text>}
    {result && <View accessibilityLiveRegion="polite" style={styles.result}>
      <Text style={{ color: colors.text }}>状态：{result.status} · 退出：{result.exitKind ?? '(none)'} / {result.exitCode ?? '(none)'}</Text>
      <Text style={{ color: colors.text }}>资源清理：{result.cleanupStatus} · root reaped：{String(result.rootReaped)}</Text>
      {result.cleanupStatus !== 'done' && <Text accessibilityRole="alert" style={{ color: colors.text }}>清理未确认；保留当前 token 和 RUN 目录，只允许重试关闭，不能启动新终端。</Text>}
      <Text style={{ color: colors.muted }}>App UID {result.appUid} · native child {result.childPid ?? '(none)'} · {result.durationMillis} ms</Text>
      <Text selectable style={{ color: colors.text }}>PTY 合流输出：{result.output || '(empty)'}</Text>
    </View>}
    {error && <Text accessibilityRole="alert" style={{ color: colors.text }}>{error}</Text>}
    <Text style={{ color: colors.muted }}>输入 UTF-8 上限 4 KiB，实时合流输出总上限 64 KiB，固定 120 秒终端截止时间；超时明确显示 timed_out。shell 持久历史已固定关闭，不修改已校验 rootfs。PTY 天然合并 stdout / stderr。直接 native tracer 回收不等于整棵 tracee 树已在设备上证明消失；清理不明确时不会删除 RUN 目录。</Text>
  </View>;
}

const styles = StyleSheet.create({
  panel: { gap: 12 },
  heading: { fontSize: 21, fontWeight: '700' },
  row: { flexDirection: 'row', gap: 8, flexWrap: 'wrap' },
  button: { minHeight: 48, padding: 12, backgroundColor: '#1d4ed8', borderRadius: 8, alignItems: 'center', justifyContent: 'center', flexGrow: 1 },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' },
  disabled: { opacity: 0.45 },
  input: { minHeight: 96, borderWidth: 1, borderRadius: 8, padding: 10, textAlignVertical: 'top' },
  result: { gap: 8 },
});
