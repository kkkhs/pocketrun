import { useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import NativeTaskHost, { type SingleTaskState } from './specs/NativePocketRunTaskHost';

type Colors = { text: string; muted: string };
type Step = {
  number: number; tool: string; phase: string; requestNonce?: string;
  requestHash?: string; target?: string; backendEpoch?: string;
  approvalState?: string; cleanupState: string; result: Record<string, string>;
};
const DEMO_GOAL = '读取设备信息和固定演示 CSV 行数，核对两步结果';

export default function SingleTaskPanel({ colors }: { colors: Colors }) {
  const [state, setState] = useState<SingleTaskState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const live = useRef(true);
  const generation = useRef(0);
  const actionBusy = useRef(false);
  const polling = useRef(false);

  useEffect(() => {
    live.current = true;
    async function poll() {
      if (!NativeTaskHost || polling.current || !live.current) return;
      polling.current = true;
      const request = generation.current;
      try {
        const value = await NativeTaskHost.getSingleTaskState();
        if (live.current && request === generation.current) setState(value);
      } catch { /* Explicit actions expose failures; no synthetic successful state. */ }
      finally { polling.current = false; }
    }
    poll();
    const timer = setInterval(poll, 250);
    return () => { live.current = false; clearInterval(timer); };
  }, []);

  async function perform(action: () => Promise<unknown>) {
    if (actionBusy.current || !NativeTaskHost) return;
    actionBusy.current = true;
    const request = ++generation.current;
    setBusy(true); setError(null);
    try {
      await action();
      const value = await NativeTaskHost.getSingleTaskState();
      if (live.current && request === generation.current) setState(value);
    } catch (failure) {
      if (live.current && request === generation.current) {
        setError(failure instanceof Error ? failure.message : '任务操作失败。');
      }
    } finally {
      actionBusy.current = false;
      if (live.current && request === generation.current) setBusy(false);
    }
  }

  let steps: Step[] = [];
  try { steps = state?.stepsJson ? JSON.parse(state.stepsJson) : []; } catch { /* Fail closed below. */ }
  const current = steps[steps.length - 1];
  const active = state?.phase === 'ready' || state?.phase === 'running';
  const ready = state?.phase === 'ready';
  const pending = current?.phase === 'pending_approval' && current.requestNonce;
  function button(label: string, disabled: boolean, action: () => void) {
    return <Pressable accessibilityRole="button" accessibilityLabel={label}
      accessibilityState={{ disabled }} disabled={disabled} onPress={action}
      style={[styles.button, disabled && styles.disabled]}>
      <Text style={styles.buttonText}>{label}</Text>
    </Pressable>;
  }

  return <View style={styles.panel}>
    <Text style={[styles.heading, { color: colors.text }]}>第 10 章 · 一个目标，多个工具步骤</Text>
    <Text style={{ color: colors.muted }}>先在第 9 章准备固定 Linux 工具，再开始任务；本章手动选下一步，不调用模型，不重放失败动作。</Text>
    {button('开始两步演示任务', busy || active || !NativeTaskHost,
      () => perform(() => NativeTaskHost!.beginTask(DEMO_GOAL)))}
    {state && <>
      <Text selectable style={{ color: colors.text }}>目标：{state.goal ?? '(尚未开始)'}</Text>
      <Text style={{ color: colors.text }}>任务：{state.taskId ?? '(none)'} · {state.phase} · {state.code ?? ''}</Text>
      <Text style={{ color: colors.muted }}>上限：{state.maxSteps} 步 / {state.maxDurationMillis / 1000} 秒 / 结果 {state.maxResultBytes} 字节；上下文 {state.contextBytes}/{state.maxContextBytes} 字节</Text>
    </>}
    {button('步骤 1：读取设备信息', busy || !ready || steps.length !== 0,
      () => perform(() => NativeTaskHost!.submitTaskStep(state!.taskId!, 'android.device_info', '{}')))}
    {button('步骤 2：申请读取演示 CSV', busy || !ready || steps.length !== 1,
      () => perform(() => NativeTaskHost!.submitTaskStep(state!.taskId!, 'linux.count_demo_csv', '{}')))}
    {steps.map(step => <View key={step.number} style={styles.step}>
      <Text style={{ color: colors.text }}>步骤 {step.number} · {step.tool} · {step.phase}</Text>
      <Text selectable style={{ color: colors.muted }}>目标绑定：{step.target} · {step.backendEpoch}</Text>
      <Text style={{ color: colors.text }}>审批：{step.approvalState} · 清理：{step.cleanupState}</Text>
      {Object.entries(step.result).map(([key, value]) =>
        <Text key={key} selectable style={{ color: colors.text }}>{key}：{value}</Text>)}
    </View>)}
    {pending && <View style={styles.step}>
      <Text selectable style={{ color: colors.text }}>当前请求 hash：{current.requestHash}</Text>
      <Text style={{ color: colors.muted }}>只能由手机界面批准这一条请求；任务目标不是授权。</Text>
      {button('同意任务当前步骤', busy, () => perform(() =>
        NativeTaskHost!.decideFromAppUi(current.requestNonce!, 'approve')))}
      {button('拒绝任务当前步骤', busy, () => perform(() =>
        NativeTaskHost!.decideFromAppUi(current.requestNonce!, 'reject')))}
    </View>}
    {button('核对两步结果并结束任务', busy || !ready || steps.length !== 2,
      () => perform(() => NativeTaskHost!.finishTask(state!.taskId!)))}
    {button('取消整个任务', busy || !active,
      () => perform(() => NativeTaskHost!.cancelTask(state!.taskId!)))}
    {current?.cleanupState === 'unproven' && button('重试任务资源清理', busy,
      () => perform(() => NativeTaskHost!.retryCleanup()))}
    {error && <Text accessibilityRole="alert" style={{ color: colors.text }}>{error}</Text>}
    <Text style={{ color: colors.muted }}>账本只在本次 App 会话内保留。完成只表示受控步骤成功并已核对，不证明真机验收、长期记忆或自动恢复。</Text>
  </View>;
}

const styles = StyleSheet.create({
  panel: { gap: 12 },
  heading: { fontSize: 21, fontWeight: '700' },
  button: { minHeight: 48, padding: 12, backgroundColor: '#1d4ed8', borderRadius: 8,
    alignItems: 'center', justifyContent: 'center' },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' },
  disabled: { opacity: 0.45 },
  step: { gap: 6, borderWidth: 1, borderColor: '#64748b', borderRadius: 8, padding: 10 },
});
