import { useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  useColorScheme,
  View,
} from 'react-native';
import {
  SafeAreaProvider,
  useSafeAreaInsets,
} from 'react-native-safe-area-context';
import NativePocketRunDevice, {
  type DeviceInfo,
} from './specs/NativePocketRunDevice';

export default function App() {
  return (
    <SafeAreaProvider>
      <DeviceScreen />
    </SafeAreaProvider>
  );
}

function DeviceScreen() {
  const insets = useSafeAreaInsets();
  const dark = useColorScheme() === 'dark';
  const colors = dark
    ? { background: '#111827', text: '#f9fafb', muted: '#d1d5db' }
    : { background: '#ffffff', text: '#111827', muted: '#4b5563' };
  const [info, setInfo] = useState<DeviceInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function readDeviceInfo() {
    if (loading) {
      return;
    }
    setLoading(true);
    setError(null);
    try {
      if (!NativePocketRunDevice) {
        throw new Error('原生模块未注册，请重新构建 Android 应用。');
      }
      setInfo(await NativePocketRunDevice.getDeviceInfo());
    } catch (failure) {
      setInfo(null);
      setError(
        failure instanceof Error ? failure.message : '读取失败，请重试。',
      );
    } finally {
      setLoading(false);
    }
  }

  return (
    <View style={[styles.screen, { backgroundColor: colors.background }]}>
      <StatusBar barStyle={dark ? 'light-content' : 'dark-content'} />
      <ScrollView
        contentContainerStyle={[
          styles.content,
          { paddingTop: insets.top + 28, paddingBottom: insets.bottom + 28 },
        ]}
      >
        <Text style={[styles.eyebrow, { color: colors.muted }]}>
          PocketRun · 第 1 章
        </Text>
        <Text style={[styles.title, { color: colors.text }]}>读取设备信息</Text>
        <Text style={[styles.description, { color: colors.muted }]}>
          点击按钮，让 TypeScript 调用 Kotlin，再把结果显示回来。
        </Text>

        <Pressable
          accessibilityRole="button"
          accessibilityState={{ disabled: loading, busy: loading }}
          disabled={loading}
          onPress={readDeviceInfo}
          style={({ pressed }) => [
            styles.button,
            (pressed || loading) && styles.buttonDimmed,
          ]}
        >
          <Text style={styles.buttonText}>
            {loading ? '读取中…' : '检查设备'}
          </Text>
        </Pressable>
        {loading && <ActivityIndicator accessibilityLabel="正在读取设备信息" />}

        <View accessibilityLiveRegion="polite" style={styles.result}>
          {error ? (
            <Text
              accessibilityRole="alert"
              style={[styles.value, { color: colors.text }]}
            >
              {error}
            </Text>
          ) : info ? (
            <>
              <Text selectable style={[styles.value, { color: colors.text }]}>
                Android 版本：{info.androidVersion}
              </Text>
              <Text selectable style={[styles.value, { color: colors.text }]}>
                API Level：{info.apiLevel}
              </Text>
              <Text selectable style={[styles.value, { color: colors.text }]}>
                支持的 ABI：{info.supportedAbis.join(', ')}
              </Text>
              <Text selectable style={[styles.value, { color: colors.text }]}>
                App UID：{info.appUid}
              </Text>
            </>
          ) : (
            <Text style={[styles.value, { color: colors.muted }]}>
              尚未读取设备信息。
            </Text>
          )}
        </View>

        <Text style={[styles.description, { color: colors.muted }]}>
          UID 是当前应用进程的身份。原生桥接不会让应用获得 shell 或 root 权限。
        </Text>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  content: { paddingHorizontal: 24, gap: 20 },
  eyebrow: { fontSize: 14 },
  title: { fontSize: 28, fontWeight: '700' },
  description: { fontSize: 16, lineHeight: 25 },
  button: {
    minHeight: 48,
    padding: 14,
    borderRadius: 8,
    backgroundColor: '#1d4ed8',
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonDimmed: { opacity: 0.75 },
  buttonText: { color: '#ffffff', fontSize: 17, fontWeight: '600' },
  result: { gap: 12, minHeight: 32 },
  value: { fontSize: 17, lineHeight: 26 },
});
