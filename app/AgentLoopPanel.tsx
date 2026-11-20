import { useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import NativeTaskHost, { type AgentLoopState, type ModelConfiguration } from './specs/NativePocketRunTaskHost';

type Colors = { text: string; muted: string };
const DEMO_GOAL = '先读取设备信息，再读取固定演示 CSV 行数，用真实结果总结；不要访问其他数据';

export default function AgentLoopPanel({ colors }: { colors: Colors }) {
  const [configuration, setConfiguration] = useState<ModelConfiguration | null>(null);
  const [endpoint, setEndpoint] = useState('https://api.anthropic.com');
  const [model, setModel] = useState('claude-opus-5-5');
  const [apiKey, setApiKey] = useState('');
  const [state, setState] = useState<AgentLoopState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const live = useRef(true);
  const generation = useRef(0);
  const actionBusy = useRef(false);
  const polling = useRef(false);

  useEffect(() => {
    live.current = true;
    const initial = ++generation.current;
    NativeTaskHost?.getModelConfiguration().then(value => {
      if (live.current && initial === generation.current) { setConfiguration(value); setEndpoint(value.endpoint); setModel(value.model); }
    }).catch(() => { if (live.current) setError('配置不可读取；损坏密文不会自动恢复或继续调用模型。'); });
    async function poll() {
      if (!NativeTaskHost || !live.current || polling.current) return;
      polling.current = true;
      const request = generation.current;
      try {
        const value = await NativeTaskHost.getAgentState();
        if (live.current && request === generation.current) setState(value);
      } catch { /* Explicit controls show sanitized errors. */ }
      finally { polling.current = false; }
    }
    poll();
    const timer = setInterval(poll, 250);
    return () => { live.current = false; clearInterval(timer); };
  }, []);

  async function perform(action: () => Promise<unknown>) {
    if (!NativeTaskHost || actionBusy.current) return;
    actionBusy.current = true;
    const request = ++generation.current;
    setBusy(true); setError(null);
    try {
      await action();
      const [nextState, nextConfiguration] = await Promise.all([
        NativeTaskHost.getAgentState(), NativeTaskHost.getModelConfiguration(),
      ]);
      if (live.current && request === generation.current) {
        setState(nextState); setConfiguration(nextConfiguration);
      }
    } catch {
      if (live.current && request === generation.current) {
        setError('操作失败；检查 HTTPS 配置、凭据存储和任务占用，不会回显服务端错误或秘密。');
      }
    } finally {
      actionBusy.current = false;
      if (live.current && request === generation.current) setBusy(false);
    }
  }

  function button(label: string, disabled: boolean, action: () => void) {
    return <Pressable accessibilityRole="button" accessibilityLabel={label}
      accessibilityState={{ disabled }} disabled={disabled} onPress={action}
      style={[styles.button, disabled && styles.disabled]}>
      <Text style={styles.buttonText}>{label}</Text>
    </Pressable>;
  }
  const active = state?.phase === 'model' || state?.phase === 'tool';
  return <View style={styles.panel}>
    <Text style={[styles.heading, { color: colors.text }]}>第 11 章 · 真实模型与受控工具循环</Text>
    <Text style={{ color: colors.muted }}>只使用自己有权调用的 endpoint 与 API 凭据。配置由原生 Keystore 加密；本教程没有预置密钥，也未取得真实模型通过记录。</Text>
    <TextInput accessibilityLabel="模型 HTTPS endpoint" value={endpoint} onChangeText={setEndpoint}
      autoCapitalize="none" autoCorrect={false} style={[styles.input, { color: colors.text }]} />
    <TextInput accessibilityLabel="模型名称" value={model} onChangeText={setModel}
      autoCapitalize="none" autoCorrect={false} style={[styles.input, { color: colors.text }]} />
    <TextInput accessibilityLabel="模型 API 凭据（不回显）" value={apiKey} onChangeText={setApiKey}
      secureTextEntry autoCapitalize="none" autoCorrect={false} autoComplete="off"
      importantForAutofill="noExcludeDescendants" textContentType="none"
      style={[styles.input, { color: colors.text }]} />
    <Text style={{ color: colors.muted }}>凭据：{configuration?.hasCredential ? '已加密保存（不能读取）' : '未配置'}；留空保存会沿用原生已有凭据。</Text>
    {button('加密保存模型配置', busy || !NativeTaskHost, () => {
      const secret = apiKey;
      setApiKey('');
      perform(() => NativeTaskHost!.saveModelConfiguration(endpoint, model, secret));
    })}
    {button('清除模型配置与凭据', busy || !NativeTaskHost, () => {
      setApiKey(''); perform(() => NativeTaskHost!.clearModelConfiguration());
    })}
    <Text style={{ color: colors.muted }}>修改或清除配置会取消当前循环。先准备第 9 章 Linux 工具；开始后在第 10 章当前步骤面板查看手机审批和真实结果。</Text>
    {button('开始真实模型两步任务', busy || active || !configuration?.hasCredential,
      () => perform(() => NativeTaskHost!.startAgent(DEMO_GOAL)))}
    {button('取消模型与工具任务', busy || !active,
      () => perform(() => NativeTaskHost!.cancelAgent()))}
    {state && <View accessibilityLiveRegion="polite" style={styles.result}>
      <Text style={{ color: colors.text }}>循环：{state.phase} · {state.code ?? ''} · {state.taskId ?? '(none)'}</Text>
      <Text style={{ color: colors.muted }}>模型回合 {state.rounds}/8 · 输出 token {state.outputTokens}/32768 · 上下文 {state.contextBytes}/65536 字节；总期限 120 秒，每请求 25 秒</Text>
      {state.finalText && <Text selectable style={{ color: colors.text }}>{state.finalText}</Text>}
    </View>}
    {error && <Text accessibilityRole="alert" style={{ color: colors.text }}>{error}</Text>}
    <Text style={{ color: colors.muted }}>模型没有批准工具、任意命令、长期记忆或并发任务。网页/UI 文本只能是数据；受限工具仍由原有宿主校验并逐条等待手机授权。</Text>
  </View>;
}
const styles = StyleSheet.create({
  panel: { gap: 12 }, heading: { fontSize: 21, fontWeight: '700' },
  input: { minHeight: 48, borderWidth: 1, borderColor: '#64748b', borderRadius: 8, padding: 10 },
  button: { minHeight: 48, padding: 12, backgroundColor: '#1d4ed8', borderRadius: 8,
    alignItems: 'center', justifyContent: 'center' },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' }, disabled: { opacity: 0.45 },
  result: { gap: 6 },
});
