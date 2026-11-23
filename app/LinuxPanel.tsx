import { useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import NativePocketRunLinux, { type LinuxInfo, type LinuxResult } from './specs/NativePocketRunLinux';

type Colors = { text: string; muted: string };
let nextPanelId = 0;

export default function LinuxPanel({ colors }: { colors: Colors }) {
  const [info, setInfo] = useState<LinuxInfo | null>(null);
  const [result, setResult] = useState<LinuxResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [operation, setOperation] = useState<'prepare' | 'run' | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const panelId = useRef<number | null>(null);
  if (panelId.current === null) { panelId.current = ++nextPanelId; }
  const epoch = useRef(0);
  const activeToken = useRef<string | null>(null);

  useEffect(() => () => {
    epoch.current++;
    const token = activeToken.current;
    activeToken.current = null;
    if (token && NativePocketRunLinux) {
      NativePocketRunLinux.cancelLinux(token).catch(() => {});
    }
  }, []);

  async function perform(kind: 'prepare' | 'run') {
    if (activeToken.current) { return; }
    const generation = ++epoch.current;
    const token = `linux-${panelId.current}-${generation}`;
    activeToken.current = token;
    setOperation(kind);
    setError(null);
    setResult(null);
    try {
      if (!NativePocketRunLinux) { throw new Error('Linux 模块未注册，请重新构建 Android 应用。'); }
      if (kind === 'prepare') {
        const next = await NativePocketRunLinux.prepareLinux(token);
        if (generation === epoch.current) {
          if (next.token !== token) { throw new Error('Linux 安装结果不属于当前操作。'); }
          setInfo(next);
        }
      } else {
        const next = await NativePocketRunLinux.runLinuxDemo(token);
        if (generation === epoch.current) {
          if (next.token !== token || next.installationGeneration !== info?.installationGeneration) {
            throw new Error('Linux 运行结果已失效。');
          }
          setResult(next);
        }
      }
    } catch (failure) {
      if (generation === epoch.current) {
        setError(failure instanceof Error ? failure.message : 'Linux 操作失败。');
      }
    } finally {
      if (generation === epoch.current) {
        activeToken.current = null;
        setOperation(null);
        setCancelling(false);
      }
    }
  }

  async function cancel() {
    const token = activeToken.current;
    if (!token || cancelling || !NativePocketRunLinux) { return; }
    const generation = epoch.current;
    setCancelling(true);
    try {
      const accepted = await NativePocketRunLinux.cancelLinux(token);
      if (!accepted && generation === epoch.current) { setError('Linux 操作已结束，没有可取消的任务。'); }
    } catch (failure) {
      if (generation === epoch.current) { setError(failure instanceof Error ? failure.message : '取消失败。'); }
    } finally {
      if (generation === epoch.current) { setCancelling(false); }
    }
  }

  function button(label: string, disabled: boolean, action: () => void) {
    return <Pressable accessibilityRole="button" accessibilityLabel={label}
      accessibilityState={{ disabled }} disabled={disabled} onPress={action}
      style={[styles.button, disabled && styles.disabled]}>
      <Text style={styles.buttonText}>{label}</Text>
    </Pressable>;
  }

  return <View style={styles.panel}>
    <Text style={[styles.heading, { color: colors.text }]}>第 7 章 · PRoot 与 Alpine</Text>
    <Text style={{ color: colors.muted }}>需要 Android 14 / API 34 及 ARM64 App 进程。普通 App UID 运行最小 Alpine 命令环境（BusyBox / musl），不支持 apk add 或动态安装程序。PRoot 不提供 Android root，也不是额外的安全沙箱。</Text>
    {button('准备 Linux 环境', operation !== null, () => perform('prepare'))}
    {button('运行 Linux 演示', operation !== null || !info, () => perform('run'))}
    {button('取消 Linux 操作', operation === null || cancelling, () => cancel())}
    {operation && <Text style={{ color: colors.muted }}>{operation === 'prepare' ? '校验并安装 Linux…' : 'Linux 演示运行中…'}</Text>}
    {info && <View style={styles.result}>
      <Text selectable style={{ color: colors.text }}>安装版本：Alpine {info.alpineVersion} · App UID {info.appUid}</Text>
      <Text selectable style={{ color: colors.text }}>映射：{info.workspaceDirectory} → {info.guestCwd}</Text>
      <Text style={{ color: colors.muted }}>{info.installedNow ? '首次完整安装。' : '复用已校验安装。'}{info.sampleCreated ? '首次写入演示 CSV。' : '保留已有工作数据。'}</Text>
    </View>}
    {result && <View accessibilityLiveRegion="polite" style={styles.result}>
      <Text style={{ color: colors.text }}>命令状态：{result.status}</Text>
      <Text style={{ color: colors.text }}>资源清理：{result.cleanupStatus}</Text>
      {result.cleanupStatus !== 'done' && <Text accessibilityRole="alert" style={{ color: colors.text }}>资源清理未确认，本轮不能作为完整验收通过；保留临时目录等待安全恢复。</Text>}
      <Text selectable style={{ color: colors.text }}>Alpine：{result.alpineVersion ?? '(未取得)'}</Text>
      <Text selectable style={{ color: colors.text }}>实际 UID：{result.guestUid ?? '(未取得)'} · cwd：{result.cwd ?? '(未取得)'}</Text>
      <Text style={{ color: colors.text }}>CSV 行数：{result.lineCount ?? '(未取得)'}</Text>
      <Text style={{ color: colors.text }}>阶段退出码（版本、pwd、uid、cat、wc）：{result.stageExitCodes.join(', ')}</Text>
      <Text style={{ color: colors.text }}>PRoot 退出码：{result.exitCodes.join(', ')} · {result.durationMillis} ms</Text>
      <Text selectable style={{ color: colors.text }}>stdout：{result.stdout.trim()}</Text>
      <Text selectable style={{ color: colors.text }}>stderr：{result.stderr || '(empty)'}</Text>
    </View>}
    {error && <Text accessibilityRole="alert" style={{ color: colors.text }}>{error}</Text>}
    <Text style={{ color: colors.muted }}>固定演示：Alpine 版本 → pwd → id -u → cat items.csv | wc -l。上限 5 秒、输出 64 KiB；取消不删除工作数据；仅确认本轮目录没有活跃进程引用后才回收，无法确认时保留等待恢复。RN 只调用固定入口，不传命令、环境或绑定路径。</Text>
  </View>;
}

const styles = StyleSheet.create({
  panel: { gap: 12 },
  heading: { fontSize: 21, fontWeight: '700' },
  button: { minHeight: 48, padding: 12, backgroundColor: '#1d4ed8', borderRadius: 8, alignItems: 'center', justifyContent: 'center' },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' },
  disabled: { opacity: 0.45 },
  result: { gap: 8 },
});
