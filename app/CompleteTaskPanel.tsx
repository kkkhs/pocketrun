import { useEffect, useRef, useState } from 'react';
import { Alert, Pressable, StyleSheet, Text, View } from 'react-native';
import NativeTaskHost, {
  type AgentLoopState, type CompleteTaskState, type SingleTaskState,
} from './specs/NativePocketRunTaskHost';

type Colors = { text: string; muted: string };
type Step = {
  number: number; tool: string; phase: string; requestNonce?: string; requestHash?: string;
  target?: string; approvalState?: string; cleanupState: string; result: Record<string, string>;
};
const FAILURE = '状态或操作未能核对；保留原记录和文件。请刷新并检查输入、虚拟屏、输出及资源清理，不会自动重放或覆盖。';

function checkedJournal(value: CompleteTaskState): CompleteTaskState {
  if (!['none', 'ready', 'running', 'interrupted', 'completed', 'cancelled', 'failed'].includes(value.phase) ||
    !['none', 'csv_read', 'demo_observed', 'tap_accepted', 'text_accepted', 'demo_verified', 'output_verified'].includes(value.stage) ||
    !['none', 'read', 'observe', 'tap', 'text', 'publish'].includes(value.pendingEffect) ||
    (value.runId !== null && !/^run_[a-f0-9]{32}$/.test(value.runId)) ||
    ![value.inputDigest, value.outputDigest].every(text => text === '' || /^[a-f0-9]{64}$/.test(text)) ||
    (value.expectedText !== '' && (value.expectedText.length > 64 || !/^items=(0|[1-9][0-9]*);quantity=(0|[1-9][0-9]*)$/.test(value.expectedText))) ||
    (value.phase === 'none' && (value.runId !== null || value.stage !== 'none' || value.pendingEffect !== 'none' ||
      value.inputDigest !== '' || value.outputDigest !== '' || value.expectedText !== '')) ||
    (value.phase !== 'none' && value.runId === null) ||
    (value.stage !== 'none' && (!value.inputDigest || !value.expectedText)) ||
    (value.phase === 'completed' && (value.stage !== 'output_verified' || value.pendingEffect !== 'none' ||
      !value.inputDigest || !value.outputDigest || !value.expectedText))) {
    throw new Error('Invalid complete-task state.');
  }
  return value;
}

function checkedSteps(value: SingleTaskState): Step[] {
  const steps: unknown = JSON.parse(value.stepsJson);
  if (!Array.isArray(steps) || steps.length > 16 || steps.some(step => !step ||
    !Number.isSafeInteger(step.number) || typeof step.tool !== 'string' || typeof step.phase !== 'string' ||
    typeof step.cleanupState !== 'string' || !step.result || typeof step.result !== 'object' ||
    Object.values(step.result).some(text => typeof text !== 'string'))) throw new Error('Invalid task steps.');
  return steps;
}

export default function CompleteTaskPanel({ colors }: { colors: Colors }) {
  const [journal, setJournal] = useState<CompleteTaskState | null>(null);
  const [task, setTask] = useState<SingleTaskState | null>(null);
  const [steps, setSteps] = useState<Step[]>([]);
  const [agent, setAgent] = useState<AgentLoopState | null>(null);
  const [busy, setBusy] = useState(false);
  const [unreadable, setUnreadable] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const live = useRef(true);
  const generation = useRef(0);
  const actionBusy = useRef(false);
  const polling = useRef(false);

  async function read(request: number) {
    if (!NativeTaskHost) return;
    let next: CompleteTaskState;
    try { next = checkedJournal(await NativeTaskHost.getCompleteTaskState()); }
    catch {
      if (live.current && request === generation.current) {
        setJournal(null); setUnreadable(true); setError(FAILURE);
      }
      return;
    }
    try {
      const [context, loop] = await Promise.all([NativeTaskHost.getSingleTaskState(), NativeTaskHost.getAgentState()]);
      const records = checkedSteps(context);
      if (live.current && request === generation.current) {
        setJournal(next); setUnreadable(false); setTask(context); setSteps(records); setAgent(loop);
      }
    } catch {
      if (live.current && request === generation.current) {
        setJournal(next); setUnreadable(false); setTask(null); setSteps([]); setAgent(null); setError(FAILURE);
      }
    }
  }

  useEffect(() => {
    live.current = true;
    async function poll() {
      if (!NativeTaskHost || !live.current || polling.current || actionBusy.current) return;
      polling.current = true;
      const request = generation.current;
      try { await read(request); }
      catch { if (live.current && request === generation.current) setError(FAILURE); }
      finally { polling.current = false; }
    }
    poll();
    const timer = setInterval(poll, 750);
    return () => { live.current = false; clearInterval(timer); };
  }, []);

  async function perform(action: () => Promise<unknown>) {
    if (!NativeTaskHost || actionBusy.current) return;
    actionBusy.current = true;
    const request = ++generation.current;
    setBusy(true); setError(null);
    try { await action(); await read(request); }
    catch { if (live.current && request === generation.current) setError(FAILURE); }
    finally {
      actionBusy.current = false;
      if (live.current && request === generation.current) setBusy(false);
    }
  }

  async function start() {
    if (!NativeTaskHost || actionBusy.current) return;
    actionBusy.current = true;
    const request = ++generation.current;
    setBusy(true); setError(null);
    try {
      const [record, ready, configuration] = await Promise.all([
        NativeTaskHost.getCompleteTaskState(), NativeTaskHost.getHostReadiness(), NativeTaskHost.getModelConfiguration(),
      ]);
      checkedJournal(record);
      if (!live.current || request !== generation.current) return;
      if (!ready.displayReady || !ready.displayId || !ready.displayEpoch || !configuration.hasCredential) {
        throw new Error('Owned Demo and model must be ready.');
      }
      setJournal(record);
      Alert.alert('核对后开始新的完整任务',
        `模型地址：${configuration.endpoint}\n模型：${configuration.model}\n当前虚拟屏：${ready.displayId} / epoch ${ready.displayEpoch}\n` +
        `上一记录：${record.runId ?? '(无)'} · ${record.phase} / ${record.stage} · 意图 ${record.pendingEffect}\n\n` +
        '新任务会重新读取 CSV、观察自有 Demo、核对输入并无覆盖发布 result.txt。CSV 和 Demo 必要内容会在各自手机批准后发送给此模型。旧授权不会恢复；相同现状只核对，不重复输入。',
        [{ text: '取消', style: 'cancel' }, { text: '确认开始新任务', onPress: () => {
          if (live.current && request === generation.current && !actionBusy.current) perform(() =>
            NativeTaskHost!.startCompleteAgent(record.runId ?? '', ready.displayId!, ready.displayEpoch!, configuration.endpoint, configuration.model));
        } }], { cancelable: false });
    } catch { if (live.current && request === generation.current) setError(FAILURE); }
    finally {
      actionBusy.current = false;
      if (live.current && request === generation.current) setBusy(false);
    }
  }

  function reset() {
    const request = generation.current;
    Alert.alert('放弃不可读运行记录',
      '仅重置不可读取的运行 journal，不删除 CSV、输出、模型凭据或知识记忆，不解除仍未确认清理的资源。旧动作仍不会恢复。',
      [{ text: '取消', style: 'cancel' }, { text: '确认重置记录', style: 'destructive', onPress: () => {
        if (live.current && request === generation.current && !actionBusy.current) perform(() => NativeTaskHost!.resetCompleteJournal());
      } }], { cancelable: false });
  }

  function button(label: string, disabled: boolean, action: () => void) {
    return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled }}
      disabled={disabled} onPress={action} style={[styles.button, disabled && styles.disabled]}>
      <Text style={styles.buttonText}>{label}</Text>
    </Pressable>;
  }
  const active = task?.phase === 'ready' || task?.phase === 'running' || agent?.phase === 'model' || agent?.phase === 'tool';
  const belongs = journal?.taskId != null && journal.taskId === task?.taskId;
  const current = belongs ? steps[steps.length - 1] : undefined;
  const pending = current?.phase === 'pending_approval' && current.requestNonce;

  return <View style={styles.panel}>
    <Text style={[styles.heading, { color: colors.text }]}>第 13 章 · CSV → Demo → 文件</Text>
    <Text style={{ color: colors.muted }}>先在第 4—5 章打开自有虚拟屏 Demo，在第 11 章配置合法模型。此任务使用普通 App UID 的私有 CSV，不需要额外启动 Linux 会话。</Text>
    {button('准备完整任务私有目录', busy || active || !NativeTaskHost, () => perform(() => NativeTaskHost!.prepareCompleteTask()))}
    {button('刷新完整任务记录', busy || !NativeTaskHost, () => perform(async () => {}))}
    {journal && <View accessibilityLiveRegion="polite" style={styles.record}>
      <Text selectable style={{ color: colors.text }}>运行：{journal.runId ?? '(无记录)'} · 原生核验：{journal.phase}</Text>
      <Text style={{ color: colors.text }}>已确认阶段：{journal.stage} · 尚未确认的意图：{journal.pendingEffect}</Text>
      <Text selectable style={{ color: colors.muted }}>记录目标：display {journal.displayId ?? '(无)'} / epoch {journal.displayEpoch ?? '(无)'} · UID {journal.packageUid}</Text>
      <Text selectable style={{ color: colors.muted }}>输入摘要：{journal.inputDigest || '(尚未读取)'} · 输出摘要：{journal.outputDigest || '(尚未确认)'}</Text>
      <Text selectable style={{ color: colors.text }}>原生核对的报告：{journal.expectedText || '(尚未核对)'} · {journal.code ?? ''}</Text>
      {journal.phase === 'interrupted' && <Text style={{ color: colors.text }}>这是持久事实，不是可恢复的授权。核对后开始会创建新的 run、上下文、目标绑定、期限与批准。</Text>}
    </View>}
    {button('核对并开始完整模型任务', busy || active || unreadable || !journal || !task || !agent || !NativeTaskHost, () => start())}
    {belongs && steps.map(step => <View key={step.number} style={styles.record}>
      <Text style={{ color: colors.text }}>完整步骤 {step.number} · {step.tool} · {step.phase}</Text>
      <Text selectable style={{ color: colors.muted }}>目标：{step.target} · 审批：{step.approvalState} · 清理：{step.cleanupState}</Text>
      {Object.entries(step.result).map(([key, value]) => <Text key={key} selectable style={{ color: colors.text }}>{key}：{value}</Text>)}
    </View>)}
    {pending && <View style={styles.record}>
      <Text selectable style={{ color: colors.text }}>仅批准当前步骤 · hash：{current.requestHash}</Text>
      {button('同意完整任务当前步骤', busy, () => perform(() => NativeTaskHost!.decideFromAppUi(current.requestNonce!, 'approve')))}
      {button('拒绝完整任务当前步骤', busy, () => perform(() => NativeTaskHost!.decideFromAppUi(current.requestNonce!, 'reject')))}
    </View>}
    {button('取消完整任务', busy || !active || !NativeTaskHost, () => perform(() =>
      agent?.phase === 'model' || agent?.phase === 'tool' ? NativeTaskHost!.cancelAgent() : NativeTaskHost!.cancelTask(task!.taskId!)))}
    {current?.cleanupState === 'unproven' && button('重试完整任务资源清理', busy, () => perform(() => NativeTaskHost!.retryCleanup()))}
    {belongs && agent?.taskId === task?.taskId && agent?.finalText && <Text selectable style={{ color: colors.text }}>模型总结：{agent.finalText}</Text>}
    {unreadable && button('重置不可读运行记录', busy || active || !NativeTaskHost, reset)}
    {error && <Text accessibilityRole="alert" style={{ color: colors.text }}>{error}</Text>}
    <Text style={{ color: colors.muted }}>现状不同、部分输入、输入版本改变或遗留临时文件会停止并保留。模型宣称完成不能替代两节点、文件读回和清理的原生核验。</Text>
  </View>;
}

const styles = StyleSheet.create({
  panel: { gap: 12 }, heading: { fontSize: 21, fontWeight: '700' },
  button: { minHeight: 48, padding: 12, backgroundColor: '#1d4ed8', borderRadius: 8,
    alignItems: 'center', justifyContent: 'center' },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' }, disabled: { opacity: 0.45 },
  record: { gap: 6, borderWidth: 1, borderColor: '#64748b', borderRadius: 8, padding: 10 },
});
