import { useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import NativeTaskHost, {
  type TaskHostReadiness,
  type TaskHostState,
} from './specs/NativePocketRunTaskHost';

type Colors = { text: string; muted: string };
type ToolChoice = 'device' | 'linux' | 'observe' | 'demoText';
const FIXED_DEMO_TEXT = 'PocketRun_TaskHost_9';

function taskHostModule() {
  if (!NativeTaskHost) throw new Error('TaskHost 原生模块未注册，请重新构建 Android 应用。');
  return NativeTaskHost;
}

function displayArguments(readiness: TaskHostReadiness, action: 'observe' | 'demo_text'): string {
  if (!readiness.displayReady || readiness.displayId === null || readiness.displayId <= 0 ||
      readiness.displayEpoch === null || !/^[1-9][0-9]*$/.test(readiness.displayEpoch)) {
    throw new Error('App 自有 typed-Binder 虚拟屏尚未就绪；不会回退默认屏或主机 ADB。');
  }
  const suffix = action === 'demo_text' ? `,"text":${JSON.stringify(FIXED_DEMO_TEXT)}` : '';
  return `{"action":"${action}","expectedDisplayId":${readiness.displayId},` +
    `"expectedDisplayEpoch":${readiness.displayEpoch}${suffix}}`;
}

export default function TaskHostPanel({ colors }: { colors: Colors }) {
  const [readiness, setReadiness] = useState<TaskHostReadiness | null>(null);
  const [state, setState] = useState<TaskHostState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const live = useRef(true);
  const inFlight = useRef(false);
  const actionBusy = useRef(false);
  const generation = useRef(0);

  useEffect(() => {
    live.current = true;
    const initial = generation.current;
    if (NativeTaskHost) {
      Promise.all([NativeTaskHost.getHostReadiness(), NativeTaskHost.getTaskState()])
        .then(([nextReadiness, nextState]) => {
          if (live.current && initial === generation.current) {
            setReadiness(nextReadiness); setState(nextState);
          }
        }).catch(failure => {
          if (live.current && initial === generation.current) {
            setError(failure instanceof Error ? failure.message : 'TaskHost 状态读取失败。');
          }
        });
    }
    const timer = setInterval(async () => {
      if (!live.current || inFlight.current || !NativeTaskHost) return;
      inFlight.current = true;
      const request = generation.current;
      try {
        const next = await NativeTaskHost.getTaskState();
        if (live.current && request === generation.current) setState(next);
      } catch { /* Explicit actions surface errors; polling never manufactures state. */ }
      finally { inFlight.current = false; }
    }, 250);
    return () => { live.current = false; clearInterval(timer); };
  }, []);

  async function perform(action: () => Promise<TaskHostState | TaskHostReadiness>) {
    if (actionBusy.current) return;
    actionBusy.current = true;
    const request = ++generation.current;
    setBusy(true);
    setError(null);
    try {
      if (!NativeTaskHost) throw new Error('TaskHost 原生模块未注册，请重新构建 Android 应用。');
      const value = await action();
      if (!live.current || request !== generation.current) return;
      if ('phase' in value) setState(value); else setReadiness(value);
    } catch (failure) {
      if (live.current && request === generation.current) {
        setError(failure instanceof Error ? failure.message : 'TaskHost 操作失败。');
      }
    } finally {
      actionBusy.current = false;
      if (live.current && request === generation.current) setBusy(false);
    }
  }

  function submit(choice: ToolChoice) {
    const native = NativeTaskHost;
    if (!native || !readiness) return;
    try {
      const call = (() => {
        switch (choice) {
          case 'device': return ['android.device_info', '{}'] as const;
          case 'linux': return ['linux.count_demo_csv', '{}'] as const;
          case 'observe': return ['android.virtual_display_observe_or_demo_input',
            displayArguments(readiness, 'observe')] as const;
          case 'demoText': return ['android.virtual_display_observe_or_demo_input',
            displayArguments(readiness, 'demo_text')] as const;
        }
      })();
      perform(() => native.submitTool(call[0], call[1]));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '工具目标无效。');
    }
  }

  function button(label: string, disabled: boolean, action: () => void) {
    return <Pressable accessibilityRole="button" accessibilityLabel={label}
      accessibilityState={{ disabled }} disabled={disabled} onPress={action}
      style={[styles.button, disabled && styles.disabled]}>
      <Text style={styles.buttonText}>{label}</Text>
    </Pressable>;
  }

  const active = state?.phase === 'pending_approval' || state?.phase === 'executing';
  const pending = state?.phase === 'pending_approval' && state.requestNonce !== null;
  const cleanupUnproven = state?.cleanupState === 'unproven';
  let result: Record<string, unknown> = {};
  try { result = state?.resultJson ? JSON.parse(state.resultJson) : {}; } catch { result = {}; }

  return <View style={styles.panel}>
    <Text style={[styles.heading, { color: colors.text }]}>第 9 章 · 确定性工具宿主与手机审批</Text>
    <Text style={{ color: colors.muted }}>宿主只有三个固定工具；不运行模型循环，不接受任意命令、argv、环境、绑定路径或可执行文件。</Text>
    {button('准备固定 Linux 工具', busy || active || readiness?.linuxReady === true,
      () => perform(() => taskHostModule().prepareHost()))}
    <Text style={{ color: colors.text }}>App UID：{readiness?.packageUid ?? '(未读取)'}</Text>
    <Text style={{ color: colors.text }}>Linux：{readiness?.linuxReady
      ? `已校验 · generation ${readiness.rootfsGeneration}` : '未准备'}</Text>
    <Text style={{ color: colors.text }}>typed Binder 虚拟屏：{readiness?.displayReady
      ? `display ${readiness.displayId} · epoch ${readiness.displayEpoch}`
      : '不可用（明确拒绝，不回退默认屏）'}</Text>

    {button('运行 android.device_info', busy || active || cleanupUnproven, () => submit('device'))}
    {button('申请读取固定演示 CSV 行数', busy || active || cleanupUnproven || !readiness?.linuxReady,
      () => submit('linux'))}
    {button('观察 App 演示虚拟屏', busy || active || cleanupUnproven || !readiness?.displayReady,
      () => submit('observe'))}
    {button('申请输入固定演示文本', busy || active || cleanupUnproven || !readiness?.displayReady,
      () => submit('demoText'))}

    {pending && <View style={styles.approval}>
      <Text accessibilityLiveRegion="polite" style={{ color: colors.text }}>待审批工具：{state?.tool}</Text>
      <Text selectable style={{ color: colors.text }}>目标：{state?.target}</Text>
      <Text selectable style={{ color: colors.muted }}>request hash：{state?.requestHash}</Text>
      <Text style={{ color: colors.muted }}>审批 actor：{state?.approvalActor}（固定手机 App UI）；nonce 仅当前请求一次有效。</Text>
      <View style={styles.row}>
        {button('同意当前请求', busy, () => state?.requestNonce &&
          perform(() => taskHostModule().decideFromAppUi(state.requestNonce!, 'approve')))}
        {button('拒绝当前请求', busy, () => state?.requestNonce &&
          perform(() => taskHostModule().decideFromAppUi(state.requestNonce!, 'reject')))}
      </View>
    </View>}

    {button('取消当前请求', busy || !active || state?.requestNonce === null,
      () => state?.requestNonce &&
        perform(() => taskHostModule().cancelFromAppUi(state.requestNonce!)))}
    {button('刷新过期状态', busy, () => perform(() => taskHostModule().refreshTask()))}
    {cleanupUnproven && button('重试资源清理', busy,
      () => perform(() => taskHostModule().retryCleanup()))}

    {state && <View accessibilityLiveRegion="polite" style={styles.result}>
      <Text style={{ color: colors.text }}>任务状态：{state.phase} · 审批：{state.approvalState ?? '(none)'}</Text>
      <Text style={{ color: colors.text }}>数据源状态：{String(result.sourceStatus ?? state.code ?? '(none)')}</Text>
      <Text style={{ color: colors.text }}>资源清理：{String(result.cleanupStatus ?? state.cleanupState)}</Text>
      <Text selectable style={{ color: colors.muted }}>绑定：{state.backendEpoch ?? '(none)'}</Text>
      {'lineCount' in result && <Text style={{ color: colors.text }}>固定 /workspace/items.csv 行数：{String(result.lineCount)}</Text>}
      {'androidVersion' in result && <Text style={{ color: colors.text }}>Android：{String(result.androidVersion)} · API {String(result.apiLevel)}</Text>}
      {state.message && <Text accessibilityRole="alert" style={{ color: colors.text }}>{state.message}</Text>}
    </View>}
    {error && <Text accessibilityRole="alert" style={{ color: colors.text }}>{error}</Text>}
    <Text style={{ color: colors.muted }}>CSV 是本 App 私有演示数据；读取仍通过手机界面逐次审批。外部账号、用户数据、电脑代批和自动确认均不在本章边界内。</Text>
  </View>;
}

const styles = StyleSheet.create({
  panel: { gap: 12 },
  heading: { fontSize: 21, fontWeight: '700' },
  row: { flexDirection: 'row', gap: 8, flexWrap: 'wrap' },
  button: { minHeight: 48, padding: 12, backgroundColor: '#1d4ed8', borderRadius: 8,
    alignItems: 'center', justifyContent: 'center', flexGrow: 1 },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' },
  disabled: { opacity: 0.45 },
  approval: { gap: 8, borderWidth: 1, borderColor: '#64748b', borderRadius: 8, padding: 10 },
  result: { gap: 6 },
});
