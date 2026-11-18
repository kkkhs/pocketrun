import { useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import NativeAdb, { type BackendInfo } from './specs/NativePocketRunAdb';

type Colors = { text: string; muted: string };
type Action = 'start' | 'ping' | 'stop';

export default function BackendPanel({ colors }: { colors: Colors }) {
  const [busy, setBusy] = useState(false);
  const [info, setInfo] = useState<BackendInfo | null>(null);
  const [message, setMessage] = useState('尚未启动 shell 后端。');
  const generation = useRef(0);

  useEffect(() => {
    const counter = generation;
    let live = true;
    NativeAdb?.getBackendStatus()
      .then(status => {
        if (live) {
          setInfo(status);
          if (status.running) {
            setMessage(
              `后端仍在运行，UID：${status.uid}，PID：${status.pid}`,
            );
          }
        }
      })
      .catch(() => {
        if (live) setMessage('无法读取 shell 后端状态。');
      });
    return () => {
      live = false;
      counter.current++;
    };
  }, []);

  async function operate(action: Action) {
    const request = ++generation.current;
    setBusy(true);
    setMessage(
      action === 'start'
        ? '正在通过 ADB 启动后端，最多等待 15 秒…'
        : action === 'stop'
        ? '正在停止 shell 后端…'
        : '正在通过 Binder 检查 shell 后端…',
    );
    try {
      if (!NativeAdb) throw new Error('ADB 原生模块未注册，请重新构建 APK。');
      const next =
        action === 'start'
          ? await NativeAdb.startBackend()
          : action === 'ping'
          ? await NativeAdb.pingBackend()
          : (await NativeAdb.stopBackend(), await NativeAdb.getBackendStatus());
      if (request !== generation.current) return;
      setInfo(next);
      setMessage(
        action === 'stop'
          ? 'shell 后端已停止。'
          : `Binder ${next.reply}，后端 UID：${next.uid}，PID：${next.pid}`,
      );
    } catch (error) {
      if (request !== generation.current) return;
      setMessage(error instanceof Error ? error.message : 'shell 后端操作失败。');
      const status = await NativeAdb?.getBackendStatus().catch(() => null);
      if (request === generation.current) setInfo(status ?? null);
    } finally {
      if (request === generation.current) setBusy(false);
    }
  }

  function control(label: string, action: Action, disabled: boolean) {
    return (
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={label}
        accessibilityState={{ disabled, busy }}
        disabled={disabled}
        onPress={() => operate(action)}
        style={[styles.button, disabled && styles.disabled]}
      >
        <Text style={styles.buttonText}>{label}</Text>
      </Pressable>
    );
  }

  const running = info?.running === true;
  return (
    <View style={styles.panel}>
      <Text style={[styles.title, { color: colors.text }]}>第 3 章 · shell 后端</Text>
      <Text style={[styles.text, { color: colors.muted }]}>ADB 只负责启动；启动后，App 通过 Binder 与 UID 2000 的后端通信。</Text>
      {control('启动 shell 后端', 'start', busy || running)}
      {control('通过 Binder 检查 shell 后端', 'ping', busy || !running)}
      {control('停止 shell 后端', 'stop', busy || !running)}
      <Text accessibilityLiveRegion="polite" style={[styles.text, { color: colors.text }]}>
        {message}
      </Text>
      <Text style={[styles.text, { color: colors.muted }]}>可以先断开 ADB，再通过 Binder 检查 shell 后端；已建立的 Binder 不依赖 ADB 连接继续存在。</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  panel: { gap: 12, paddingTop: 20 },
  title: { fontSize: 24, fontWeight: '700' },
  text: { fontSize: 16, lineHeight: 25 },
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
