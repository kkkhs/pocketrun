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
import AdbPanel from './AdbPanel';
import BackendPanel from './BackendPanel';
import DisplayPanel from './DisplayPanel';
import WorkspacePanel from './WorkspacePanel';
import LinuxPanel from './LinuxPanel';
import PtyPanel from './PtyPanel';
import TaskHostPanel from './TaskHostPanel';
import SingleTaskPanel from './SingleTaskPanel';
import AgentLoopPanel from './AgentLoopPanel';
import MemoryPanel from './MemoryPanel';
import CompleteTaskPanel from './CompleteTaskPanel';
import ChatScreen, { type ThemeColors } from './ChatScreen';
import WorkspaceFilesScreen from './WorkspaceFilesScreen';
import SettingsScreen from './SettingsScreen';
import NativePocketRunDevice, {
  type DeviceInfo,
} from './specs/NativePocketRunDevice';

type TabKey = 'chat' | 'files' | 'settings' | 'lab';

export default function App() {
  return (
    <SafeAreaProvider>
      <PocketRunShell />
    </SafeAreaProvider>
  );
}

function PocketRunShell() {
  const insets = useSafeAreaInsets();
  const dark = useColorScheme() === 'dark';
  const colors: ThemeColors = dark
    ? {
        background: '#0f172a',
        surface: '#1e293b',
        surfaceAlt: '#273549',
        border: '#334155',
        text: '#f8fafc',
        muted: '#cbd5e1',
        accent: '#3b82f6',
        accentSoft: '#1e3a5f',
        danger: '#f87171',
        warningBg: '#3b2f15',
        warningBorder: '#b45309',
      }
    : {
        background: '#f8fafc',
        surface: '#ffffff',
        surfaceAlt: '#f1f5f9',
        border: '#e2e8f0',
        text: '#0f172a',
        muted: '#475569',
        accent: '#1d4ed8',
        accentSoft: '#dbeafe',
        danger: '#dc2626',
        warningBg: '#fffbeb',
        warningBorder: '#f59e0b',
      };

  const [tab, setTab] = useState<TabKey>('chat');
  const [queuedPrompt, setQueuedPrompt] = useState<string | null>(null);

  return (
    <View
      style={[
        styles.screen,
        {
          backgroundColor: colors.background,
          paddingTop: insets.top,
          paddingBottom: insets.bottom,
        },
      ]}
    >
      <StatusBar barStyle={dark ? 'light-content' : 'dark-content'} />
      <View
        style={[
          styles.navBar,
          { backgroundColor: colors.surface, borderBottomColor: colors.border },
        ]}
      >
        <Text style={[styles.brand, { color: colors.text }]}>PocketRun</Text>
        <View style={[styles.tabs, { backgroundColor: colors.surfaceAlt }]}>
          {(
            [
              ['chat', '对话'],
              ['files', '文件'],
              ['settings', '设置'],
              ['lab', '实验室'],
            ] as const
          ).map(([key, label]) => {
            const active = tab === key;
            return (
              <Pressable
                key={key}
                accessibilityRole="tab"
                accessibilityLabel={label}
                accessibilityState={{ selected: active }}
                onPress={() => setTab(key)}
                style={[
                  styles.tabBtn,
                  active && { backgroundColor: colors.surface },
                ]}
              >
                <Text
                  style={[
                    styles.tabText,
                    { color: active ? colors.accent : colors.muted },
                  ]}
                >
                  {label}
                </Text>
              </Pressable>
            );
          })}
        </View>
      </View>

      <View style={styles.body}>
        {tab === 'chat' ? (
          <ChatScreen
            colors={colors}
            onOpenSettings={() => setTab('settings')}
            onOpenLab={() => setTab('lab')}
            initialPrompt={queuedPrompt}
            onConsumeInitialPrompt={() => setQueuedPrompt(null)}
          />
        ) : tab === 'files' ? (
          <WorkspaceFilesScreen
            colors={colors}
            onAskInChat={prompt => {
              setQueuedPrompt(prompt);
              setTab('chat');
            }}
          />
        ) : tab === 'settings' ? (
          <SettingsScreen colors={colors} onOpenLab={() => setTab('lab')} />
        ) : (
          <LabScreen colors={colors} />
        )}
      </View>
    </View>
  );
}

export function LabScreen({
  colors,
}: {
  colors: { background: string; text: string; muted: string };
}) {
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
    <ScrollView contentContainerStyle={styles.content}>
      <Text style={[styles.eyebrow, { color: colors.muted }]}>
        PocketRun · 教学实验室（第 1—13 章）
      </Text>
      <Text style={[styles.title, { color: colors.text }]}>读取设备信息</Text>
      <Text style={[styles.description, { color: colors.muted }]}>
        点击按钮，让 TypeScript 调用 Kotlin，再把结果显示回来。
      </Text>

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="检查设备"
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
      <AdbPanel colors={colors} />
      <BackendPanel colors={colors} />
      <DisplayPanel colors={colors} />
      <WorkspacePanel colors={colors} />
      <LinuxPanel colors={colors} />
      <PtyPanel colors={colors} />
      <TaskHostPanel colors={colors} />
      <SingleTaskPanel colors={colors} />
      <AgentLoopPanel colors={colors} />
      <MemoryPanel colors={colors} />
      <CompleteTaskPanel colors={colors} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  navBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderBottomWidth: 1,
    gap: 12,
  },
  brand: { fontSize: 18, fontWeight: '800' },
  tabs: {
    flexDirection: 'row',
    borderRadius: 12,
    padding: 3,
    gap: 4,
  },
  tabBtn: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 9,
  },
  tabText: { fontSize: 13, fontWeight: '700' },
  body: { flex: 1 },
  content: { paddingHorizontal: 24, paddingVertical: 20, gap: 20 },
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
