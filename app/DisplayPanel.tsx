import { useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import NativeAdb, { type DisplayInfo } from './specs/NativePocketRunAdb';

type Colors = { text: string; muted: string };
type Action = 'create' | 'check' | 'release';

export default function DisplayPanel({ colors }: { colors: Colors }) {
  const [busy, setBusy] = useState(false);
  const [info, setInfo] = useState<DisplayInfo | null>(null);
  const [message, setMessage] = useState('先启动 shell 后端，再创建演示虚拟屏。');
  const generation = useRef(0);

  useEffect(() => {
    const counter = generation;
    let live = true;
    NativeAdb?.getDisplayStatus()
      .then(status => {
        if (live) {
          setInfo(status);
          if (status.active) setMessage(`虚拟屏仍在运行，displayId：${status.displayId}`);
        }
      })
      .catch(() => undefined);
    return () => {
      live = false;
      counter.current++;
    };
  }, []);

  async function operate(action: Action) {
    const request = ++generation.current;
    setBusy(true);
    setMessage(
      action === 'create'
        ? '正在由 shell 后端创建虚拟屏并启动 Demo…'
        : action === 'release'
        ? '正在释放虚拟屏…'
        : '正在通过 Binder 检查虚拟屏…',
    );
    try {
      if (!NativeAdb) throw new Error('ADB 原生模块未注册，请重新构建 APK。');
      const next =
        action === 'create'
          ? await NativeAdb.createDemoDisplay()
          : action === 'release'
          ? await NativeAdb.releaseDisplay()
          : await NativeAdb.getDisplayStatus();
      if (request !== generation.current) return;
      setInfo(next);
      setMessage(
        next.active
          ? `Demo 已启动到虚拟屏，displayId：${next.displayId}`
          : '虚拟屏已释放，旧 displayId 已失效。',
      );
    } catch (error) {
      if (request !== generation.current) return;
      setMessage(error instanceof Error ? error.message : '虚拟屏操作失败。');
      const status = await NativeAdb?.getDisplayStatus().catch(() => null);
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

  const active = info?.active === true;
  return (
    <View style={styles.panel}>
      <Text style={[styles.title, { color: colors.text }]}>第 4 章 · 演示虚拟屏</Text>
      <Text style={[styles.text, { color: colors.muted }]}>TS UI → App Kotlin → Binder → shell 后端；VirtualDisplay 由后端持有。</Text>
      {control('创建演示虚拟屏', 'create', busy || active)}
      {control('检查虚拟屏', 'check', busy || !active)}
      {control('释放虚拟屏', 'release', busy || !active)}
      <Text accessibilityLiveRegion="polite" style={[styles.text, { color: colors.text }]}>
        {message}
      </Text>
      <Text style={[styles.text, { color: colors.muted }]}>本章只验证 Demo Activity 属于这个 displayId；第 5 章再读取画面、UI 树并注入输入。</Text>
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
