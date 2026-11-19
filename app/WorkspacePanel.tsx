import { useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import NativePocketRunWorkspace, {
  type PipelineResult,
  type WorkspaceInfo,
} from './specs/NativePocketRunWorkspace';

type Colors = { text: string; muted: string };

export default function WorkspacePanel({ colors }: { colors: Colors }) {
  const [info, setInfo] = useState<WorkspaceInfo | null>(null);
  const [result, setResult] = useState<PipelineResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [operation, setOperation] = useState<'prepare' | 'run' | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const generation = useRef(0);
  const active = useRef(false);

  useEffect(() => () => { generation.current += 1; }, []);

  async function perform(kind: 'prepare' | 'run') {
    if (active.current) { return; }
    active.current = true;
    const epoch = ++generation.current;
    setOperation(kind);
    setError(null);
    setResult(null);
    try {
      if (!NativePocketRunWorkspace) {
        throw new Error('工作目录模块未注册，请重新构建 Android 应用。');
      }
      if (kind === 'prepare') {
        const next = await NativePocketRunWorkspace.prepareWorkspace();
        if (epoch === generation.current) { setInfo(next); }
      } else {
        const next = await NativePocketRunWorkspace.runDemoPipeline();
        if (epoch === generation.current) { setResult(next); }
      }
    } catch (failure) {
      if (epoch === generation.current) {
        setError(failure instanceof Error ? failure.message : '工作目录操作失败。');
      }
    } finally {
      active.current = false;
      if (epoch === generation.current) {
        setOperation(null);
        setCancelling(false);
      }
    }
  }

  async function cancel() {
    if (operation !== 'run' || cancelling || !NativePocketRunWorkspace) { return; }
    const epoch = generation.current;
    setCancelling(true);
    try {
      const accepted = await NativePocketRunWorkspace.cancelPipeline();
      if (!accepted && epoch === generation.current) {
        setError('管道已结束，没有可取消的任务。');
      }
    } catch (failure) {
      if (epoch === generation.current) {
        setError(failure instanceof Error ? failure.message : '取消失败。');
      }
    } finally {
      if (epoch === generation.current) { setCancelling(false); }
    }
  }

  function button(label: string, disabled: boolean, action: () => void) {
    return (
      <Pressable accessibilityRole="button" accessibilityLabel={label}
        accessibilityState={{ disabled }} disabled={disabled} onPress={action}
        style={[styles.button, disabled && styles.disabled]}>
        <Text style={styles.buttonText}>{label}</Text>
      </Pressable>
    );
  }

  return (
    <View style={styles.panel}>
      <Text style={[styles.heading, { color: colors.text }]}>第 6 章 · 工作目录与命令管道</Text>
      <Text style={{ color: colors.muted }}>在普通 App UID 的私有目录执行固定演示管道，不经过 ADB 或 shell 后端。</Text>
      {button('初始化工作目录', operation !== null, () => perform('prepare'))}
      {button('运行演示管道', operation !== null || !info, () => perform('run'))}
      {button('取消命令管道', operation !== 'run' || cancelling, () => cancel())}
      {info && <View style={styles.result}>
        <Text selectable style={{ color: colors.text }}>App UID：{info.appUid}</Text>
        <Text selectable style={{ color: colors.text }}>工作目录：{info.directory}</Text>
        <Text selectable style={{ color: colors.text }}>演示文件：{info.sampleFile}</Text>
        <Text style={{ color: colors.muted }}>{info.sampleCreated ? '首次写入演示 CSV。' : '保留已有演示文件，没有覆盖。'}</Text>
      </View>}
      {operation && <Text style={{ color: colors.muted }}>{operation === 'run' ? '管道运行中…' : '准备工作目录…'}</Text>}
      {result && <View accessibilityLiveRegion="polite" style={styles.result}>
        <Text style={{ color: colors.text }}>状态：{result.status}</Text>
        <Text selectable style={{ color: colors.text }}>stdout：{result.stdout.trim()}</Text>
        <Text selectable style={{ color: colors.text }}>stderr：{result.stderr || '(empty)'}</Text>
        <Text style={{ color: colors.text }}>退出码：{result.exitCodes.join(', ')}</Text>
        <Text style={{ color: colors.muted }}>耗时：{result.durationMillis} ms · App UID {result.appUid}</Text>
      </View>}
      {error && <Text accessibilityRole="alert" style={{ color: colors.text }}>{error}</Text>}
      <Text style={{ color: colors.muted }}>固定管道：cat items.csv → wc -l。上限 5 秒、输出 64 KiB；取消或关闭只回收进程，不删除工作数据。</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  panel: { gap: 12 },
  heading: { fontSize: 21, fontWeight: '700' },
  button: { minHeight: 48, padding: 12, backgroundColor: '#1d4ed8', borderRadius: 8, alignItems: 'center', justifyContent: 'center' },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' },
  disabled: { opacity: 0.45 },
  result: { gap: 8 },
});
