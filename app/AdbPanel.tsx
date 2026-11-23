import { useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import NativeAdb, { type AdbStatus } from './specs/NativePocketRunAdb';

type Colors = { text: string; muted: string };

export default function AdbPanel({ colors }: { colors: Colors }) {
  const [pairPort, setPairPort] = useState('');
  const [code, setCode] = useState('');
  const [connectPort, setConnectPort] = useState('');
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<AdbStatus | null>(null);
  const [message, setMessage] = useState('尚未连接。');
  const generation = useRef(0);

  useEffect(() => {
    const counter = generation;
    let live = true;
    NativeAdb?.getStatus()
      .then(snapshot => {
        if (live) {
          setStatus(snapshot);
          setBusy(snapshot.busy);
        }
      })
      .catch(() => {
        if (live) setMessage('无法读取连接状态。');
      });
    return () => {
      live = false;
      counter.current++;
    };
  }, []);

  async function operate(kind: 'pair' | 'connect' | 'probe' | 'disconnect') {
    const request = ++generation.current;
    setBusy(true);
    setMessage(
      kind === 'disconnect' ? '正在关闭连接…' : '正在操作，最多等待 15 秒…',
    );
    try {
      if (!NativeAdb) throw new Error('ADB 原生模块未注册，请重新构建 APK。');
      let uid: number | undefined;
      if (kind === 'pair') await NativeAdb.pair(Number(pairPort), code);
      else if (kind === 'connect')
        uid = await NativeAdb.connect(Number(connectPort));
      else if (kind === 'probe') uid = await NativeAdb.probe();
      else await NativeAdb.disconnect();
      if (request !== generation.current) return;
      const snapshot = await NativeAdb.getStatus();
      if (request !== generation.current) return;
      setStatus(snapshot);
      setMessage(
        kind === 'pair'
          ? '配对完成。现在使用无线调试主页的连接端口。'
          : kind === 'disconnect'
          ? '连接已关闭。已经完成的系统配对不会自动撤销。'
          : `连接已验证，连接端 UID：${uid}`,
      );
    } catch (error) {
      if (request !== generation.current) return;
      setMessage(error instanceof Error ? error.message : 'ADB 操作失败。');
      const snapshot = await NativeAdb?.getStatus().catch(() => null);
      if (request === generation.current) setStatus(snapshot ?? null);
    } finally {
      if (request === generation.current) {
        setCode('');
        setBusy(false);
      }
    }
  }

  function control(
    label: string,
    action: 'pair' | 'connect' | 'probe' | 'disconnect',
    disabled: boolean,
  ) {
    return (
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={label}
        accessibilityState={{ disabled }}
        disabled={disabled}
        onPress={() => operate(action)}
        style={[styles.button, disabled && styles.disabled]}
      >
        <Text style={styles.buttonText}>{label}</Text>
      </Pressable>
    );
  }

  return (
    <View style={styles.panel}>
      <Text style={[styles.title, { color: colors.text }]}>
        第 2 章 · 无线 ADB
      </Text>
      <Text style={[styles.text, { color: colors.muted }]}>
        只连接本机
        127.0.0.1。先在系统开启无线调试，配对端口和连接端口不是同一个。
      </Text>
      <Text style={[styles.text, { color: colors.text }]}>配对端口</Text>
      <TextInput
        accessibilityLabel="配对端口"
        value={pairPort}
        onChangeText={setPairPort}
        keyboardType="number-pad"
        editable={!busy}
        maxLength={5}
        autoComplete="off"
        style={[
          styles.input,
          { color: colors.text, borderColor: colors.muted },
        ]}
      />
      <Text style={[styles.text, { color: colors.text }]}>六位配对码</Text>
      <TextInput
        accessibilityLabel="六位配对码"
        value={code}
        onChangeText={setCode}
        keyboardType="number-pad"
        editable={!busy}
        maxLength={6}
        secureTextEntry
        autoComplete="off"
        style={[
          styles.input,
          { color: colors.text, borderColor: colors.muted },
        ]}
      />
      {control('配对', 'pair', busy || status?.connected === true)}
      <Text style={[styles.text, { color: colors.text }]}>连接端口</Text>
      <TextInput
        accessibilityLabel="连接端口"
        value={connectPort}
        onChangeText={setConnectPort}
        keyboardType="number-pad"
        editable={!busy}
        maxLength={5}
        autoComplete="off"
        style={[
          styles.input,
          { color: colors.text, borderColor: colors.muted },
        ]}
      />
      {control('连接', 'connect', busy || status?.connected === true)}
      {control('检查连接', 'probe', busy || status?.connected !== true)}
      {control('取消 / 断开', 'disconnect', false)}
      <Text
        testID="adb-operation-status"
        accessibilityLiveRegion="polite"
        style={[styles.text, { color: colors.text }]}
      >
        {message}
      </Text>
      <Text style={[styles.text, { color: colors.muted }]}>
        状态是上一次检查的结果。关闭系统无线调试后，请再次检查连接。本章只发送固定的
        id -u，不提供任意命令。
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  panel: { gap: 12, paddingTop: 20 },
  title: { fontSize: 24, fontWeight: '700' },
  text: { fontSize: 16, lineHeight: 25 },
  input: {
    borderWidth: 1,
    borderRadius: 8,
    minHeight: 48,
    padding: 12,
    fontSize: 18,
  },
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
