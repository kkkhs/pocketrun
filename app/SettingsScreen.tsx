import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import type { ThemeColors } from './ChatScreen';
import NativeTaskHost, {
  type MemorySnapshot,
  type ModelConfiguration,
  type TaskHostReadiness,
} from './specs/NativePocketRunTaskHost';
import NativeWorkspace from './specs/NativePocketRunWorkspace';

type MemoryItem = { id: string; text: string };
type WorkspaceFileSummary = { path: string; bytes: number };
type ProtocolType = 'anthropic' | 'openai';

const PRESETS: ReadonlyArray<{
  label: string;
  protocol: ProtocolType;
  endpoint: string;
  model: string;
}> = [
  {
    label: '智谱免费',
    protocol: 'openai',
    endpoint: 'https://open.bigmodel.cn/api/paas/v4',
    model: 'glm-4-flash',
  },
  {
    label: 'Gemini 免费',
    protocol: 'openai',
    endpoint: 'https://generativelanguage.googleapis.com/v1beta/openai',
    model: 'gemini-2.5-flash',
  },
  {
    label: 'DeepSeek',
    protocol: 'openai',
    endpoint: 'https://api.deepseek.com/v1',
    model: 'deepseek-chat',
  },
  {
    label: 'OpenAI',
    protocol: 'openai',
    endpoint: 'https://api.openai.com/v1',
    model: 'gpt-4o-mini',
  },
  {
    label: 'Anthropic',
    protocol: 'anthropic',
    endpoint: 'https://api.anthropic.com',
    model: 'claude-opus-5-5',
  },
  {
    label: '本地 Debug',
    protocol: 'anthropic',
    endpoint: 'http://127.0.0.1:8790',
    model: 'claude-doubao-seed-2-1-turbo',
  },
];

export default function SettingsScreen({
  colors,
  onOpenLab,
}: {
  colors: ThemeColors;
  onOpenLab: () => void;
}) {
  const [config, setConfig] = useState<ModelConfiguration | null>(null);
  const [protocol, setProtocol] = useState<ProtocolType>('anthropic');
  const [endpoint, setEndpoint] = useState('https://api.anthropic.com');
  const [model, setModel] = useState('claude-opus-5-5');
  const [apiKey, setApiKey] = useState('');
  const [readiness, setReadiness] = useState<TaskHostReadiness | null>(null);
  const [files, setFiles] = useState<WorkspaceFileSummary[]>([]);
  const [memory, setMemory] = useState<MemorySnapshot | null>(null);
  const [notes, setNotes] = useState<MemoryItem[]>([]);
  const [newNote, setNewNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const live = useRef(true);

  const loadAll = useCallback(async () => {
    if (!NativeTaskHost) return;
    try {
      const [cfg, ready, mem, rawFiles] = await Promise.all([
        NativeTaskHost.getModelConfiguration().catch(() => null),
        NativeTaskHost.getHostReadiness().catch(() => null),
        NativeTaskHost.getMemorySnapshot().catch(() => null),
        NativeTaskHost.listWorkspaceFiles().catch(() => '[]'),
      ]);
      if (!live.current) return;
      if (cfg) {
        setConfig(cfg);
        setProtocol(cfg.protocol === 'openai' ? 'openai' : 'anthropic');
        setEndpoint(cfg.endpoint);
        setModel(cfg.model);
      }
      if (ready) setReadiness(ready);
      if (mem) {
        setMemory(mem);
        try {
          const parsed = JSON.parse(mem.notesJson);
          setNotes(Array.isArray(parsed) ? parsed : []);
        } catch {
          setNotes([]);
        }
      }
      try {
        const parsedFiles = JSON.parse(rawFiles);
        setFiles(Array.isArray(parsedFiles) ? parsedFiles : []);
      } catch {
        setFiles([]);
      }
    } catch (failure) {
      if (live.current) {
        setError(failure instanceof Error ? failure.message : '读取设置失败。');
      }
    }
  }, []);

  useEffect(() => {
    live.current = true;
    loadAll();
    return () => {
      live.current = false;
    };
  }, [loadAll]);

  async function saveModel() {
    if (!NativeTaskHost || busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const saved = await NativeTaskHost.saveModelConfigurationWithProtocol(
        endpoint.trim(),
        model.trim(),
        apiKey.trim(),
        protocol,
      );
      if (!live.current) return;
      setConfig(saved);
      setProtocol(saved.protocol === 'openai' ? 'openai' : 'anthropic');
      setApiKey('');
      setNotice('模型配置已通过 Android Keystore 加密保存。');
    } catch (failure) {
      if (live.current) {
        setError(
          failure instanceof Error
            ? failure.message
            : '保存失败，请确认使用 HTTPS 地址（或 Debug 本机 127.0.0.1:8790）及有效 API Key。',
        );
      }
    } finally {
      if (live.current) setBusy(false);
    }
  }

  async function clearModel() {
    if (!NativeTaskHost || busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const cleared = await NativeTaskHost.clearModelConfiguration();
      if (!live.current) return;
      setConfig(cleared);
      setEndpoint(cleared.endpoint);
      setModel(cleared.model);
      setApiKey('');
      setNotice('已清除已保存的模型凭据。');
    } catch (failure) {
      if (live.current) {
        setError(failure instanceof Error ? failure.message : '清除模型配置失败。');
      }
    } finally {
      if (live.current) setBusy(false);
    }
  }

  async function addMemory() {
    if (!NativeTaskHost || !memory || !newNote.trim() || busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const next = await NativeTaskHost.saveMemoryNote(memory.revision, '', newNote.trim());
      if (!live.current) return;
      setMemory(next);
      setNotes(JSON.parse(next.notesJson));
      setNewNote('');
      setNotice('已加密保存偏好记忆；在对话页底部打开「记忆」开关即可带上它。');
    } catch (failure) {
      if (live.current) {
        setError(failure instanceof Error ? failure.message : '保存记忆失败。');
      }
    } finally {
      if (live.current) setBusy(false);
    }
  }

  async function removeMemory(id: string) {
    if (!NativeTaskHost || !memory || busy) return;
    setBusy(true);
    setError(null);
    try {
      const next = await NativeTaskHost.deleteMemoryNote(memory.revision, id);
      if (!live.current) return;
      setMemory(next);
      setNotes(JSON.parse(next.notesJson));
    } catch (failure) {
      if (live.current) {
        setError(failure instanceof Error ? failure.message : '删除记忆失败。');
      }
    } finally {
      if (live.current) setBusy(false);
    }
  }

  async function prepareDemoWorkspace() {
    if (!NativeWorkspace || busy) return;
    setBusy(true);
    setError(null);
    try {
      await NativeWorkspace.prepareWorkspace();
      await loadAll();
      if (live.current) setNotice('私有工作区已准备就绪（已有文件不会被覆盖）。');
    } catch (failure) {
      if (live.current) {
        setError(failure instanceof Error ? failure.message : '初始化工作区失败。');
      }
    } finally {
      if (live.current) setBusy(false);
    }
  }

  async function handleResetConversations() {
    if (!NativeTaskHost || busy) return;
    Alert.alert('重置不可读对话存储', '仅在本地对话文件损坏时使用，不会影响模型凭据或记忆。', [
      { text: '取消', style: 'cancel' },
      {
        text: '确认重置',
        style: 'destructive',
        onPress: async () => {
          setBusy(true);
          try {
            await NativeTaskHost!.resetConversations();
            if (live.current) setNotice('对话存储已重置。');
          } catch (failure) {
            if (live.current) {
              setError(failure instanceof Error ? failure.message : '当前对话存储正常，无需重置。');
            }
          } finally {
            if (live.current) setBusy(false);
          }
        },
      },
    ]);
  }

  return (
    <ScrollView
      style={[styles.screen, { backgroundColor: colors.background }]}
      contentContainerStyle={styles.content}
    >
      {notice && (
        <View style={[styles.banner, { backgroundColor: colors.accentSoft }]}>
          <Text style={[styles.bannerText, { color: colors.accent }]}>{notice}</Text>
        </View>
      )}
      {error && (
        <View
          accessibilityRole="alert"
          style={[styles.banner, styles.bordered, { borderColor: colors.danger }]}
        >
          <Text style={[styles.bannerText, { color: colors.danger }]}>{error}</Text>
        </View>
      )}

      <View
        style={[
          styles.card,
          { backgroundColor: colors.surface, borderColor: colors.border },
        ]}
      >
        <Text style={[styles.cardTitle, { color: colors.text }]}>大模型配置</Text>
        <Text style={[styles.cardDesc, { color: colors.muted }]}>
          状态：{config?.hasCredential ? '已加密保存凭据' : '尚未配置'} · 协议：
          {protocol === 'openai' ? 'OpenAI Chat Completions' : 'Anthropic Messages'}
        </Text>
        <View style={styles.row}>
          {(
            [
              ['anthropic', 'Anthropic 协议'],
              ['openai', 'OpenAI 兼容协议'],
            ] as const
          ).map(([key, label]) => {
            const selected = protocol === key;
            return (
              <Pressable
                key={key}
                accessibilityRole="radio"
                accessibilityLabel={label}
                accessibilityState={{ selected }}
                onPress={() => setProtocol(key)}
                style={[
                  styles.protocolBtn,
                  styles.bordered,
                  {
                    backgroundColor: selected ? colors.accentSoft : colors.surfaceAlt,
                    borderColor: selected ? colors.accent : colors.border,
                  },
                ]}
              >
                <Text
                  style={[
                    styles.protocolText,
                    { color: selected ? colors.accent : colors.muted },
                  ]}
                >
                  {label}
                </Text>
              </Pressable>
            );
          })}
        </View>
        <View style={styles.presetRow}>
          {PRESETS.map(preset => (
            <Pressable
              key={preset.label}
              accessibilityRole="button"
              accessibilityLabel={`预设 ${preset.label}`}
              onPress={() => {
                setProtocol(preset.protocol);
                setEndpoint(preset.endpoint);
                setModel(preset.model);
              }}
              style={[
                styles.presetChip,
                styles.bordered,
                { backgroundColor: colors.surfaceAlt, borderColor: colors.border },
              ]}
            >
              <Text style={[styles.presetText, { color: colors.text }]}>{preset.label}</Text>
            </Pressable>
          ))}
        </View>
        <TextInput
          accessibilityLabel="设置模型地址"
          value={endpoint}
          onChangeText={setEndpoint}
          autoCapitalize="none"
          placeholder="https://api.anthropic.com"
          placeholderTextColor={colors.muted}
          style={[
            styles.input,
            { color: colors.text, backgroundColor: colors.surfaceAlt, borderColor: colors.border },
          ]}
        />
        <TextInput
          accessibilityLabel="设置模型名"
          value={model}
          onChangeText={setModel}
          autoCapitalize="none"
          placeholder="claude-opus-5-5"
          placeholderTextColor={colors.muted}
          style={[
            styles.input,
            { color: colors.text, backgroundColor: colors.surfaceAlt, borderColor: colors.border },
          ]}
        />
        <TextInput
          accessibilityLabel="设置模型 API Key"
          value={apiKey}
          onChangeText={setApiKey}
          autoCapitalize="none"
          secureTextEntry
          placeholder={config?.hasCredential ? '留空则沿用已加密保存的凭据' : '输入 API Key'}
          placeholderTextColor={colors.muted}
          style={[
            styles.input,
            { color: colors.text, backgroundColor: colors.surfaceAlt, borderColor: colors.border },
          ]}
        />
        <View style={styles.row}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="保存设置中的模型配置"
            disabled={busy}
            onPress={saveModel}
            style={[styles.btn, { backgroundColor: colors.accent }, busy && styles.dimmed]}
          >
            <Text style={styles.primaryText}>加密保存</Text>
          </Pressable>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="清除设置中的模型配置"
            disabled={busy || !config?.hasCredential}
            onPress={clearModel}
            style={[
              styles.btn,
              styles.bordered,
              { backgroundColor: colors.surfaceAlt, borderColor: colors.border },
              (busy || !config?.hasCredential) && styles.dimmed,
            ]}
          >
            <Text style={[styles.secondaryText, { color: colors.danger }]}>清除凭据</Text>
          </Pressable>
        </View>
      </View>

      <View
        style={[
          styles.card,
          { backgroundColor: colors.surface, borderColor: colors.border },
        ]}
      >
        <Text style={[styles.cardTitle, { color: colors.text }]}>
          私有工作区与本机能力
        </Text>
        <Text style={[styles.cardDesc, { color: colors.muted }]}>
          App UID：{readiness?.packageUid ?? '-'} · Linux：
          {readiness?.linuxReady ? '已就绪' : '未准备'} · 虚拟屏：
          {readiness?.displayReady ? `display ${readiness.displayId}` : '未连接'}
        </Text>
        {files.length === 0 ? (
          <Text style={[styles.cardDesc, { color: colors.muted }]}>
            工作区目前为空，可初始化演示文件或直接在聊天中让助手创建文件。
          </Text>
        ) : (
          <View style={styles.fileList}>
            {files.map(file => (
              <Text key={file.path} style={[styles.fileItem, { color: colors.text }]}>
                • {file.path}（{file.bytes} B）
              </Text>
            ))}
          </View>
        )}
        <View style={styles.row}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="准备私有工作区"
            disabled={busy}
            onPress={prepareDemoWorkspace}
            style={[styles.btn, { backgroundColor: colors.accentSoft }]}
          >
            <Text style={[styles.secondaryText, { color: colors.accent }]}>
              准备演示工作区
            </Text>
          </Pressable>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="前往实验室配置 ADB 和虚拟屏"
            onPress={onOpenLab}
            style={[
              styles.btn,
              styles.bordered,
              { backgroundColor: colors.surfaceAlt, borderColor: colors.border },
            ]}
          >
            <Text style={[styles.secondaryText, { color: colors.text }]}>
              ADB / 虚拟屏配置
            </Text>
          </Pressable>
        </View>
      </View>

      <View
        style={[
          styles.card,
          { backgroundColor: colors.surface, borderColor: colors.border },
        ]}
      >
        <Text style={[styles.cardTitle, { color: colors.text }]}>
          长期偏好记忆 ({notes.length}/{memory?.maxNotes ?? 8})
        </Text>
        <Text style={[styles.cardDesc, { color: colors.muted }]}>
          仅保存非敏感偏好笔记；由 Android Keystore 独立加密，不会自动授予工具权限。
        </Text>
        {notes.map(note => (
          <View
            key={note.id}
            style={[styles.noteRow, { backgroundColor: colors.surfaceAlt }]}
          >
            <Text style={[styles.noteText, { color: colors.text }]}>{note.text}</Text>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={`删除记忆笔记 ${note.text}`}
              onPress={() => removeMemory(note.id)}
              style={styles.deleteChip}
            >
              <Text style={[styles.deleteChipText, { color: colors.danger }]}>
                删除
              </Text>
            </Pressable>
          </View>
        ))}
        <View style={styles.row}>
          <TextInput
            accessibilityLabel="新增偏好记忆输入"
            value={newNote}
            onChangeText={setNewNote}
            placeholder="例如：回复尽量简洁，优先使用中文"
            placeholderTextColor={colors.muted}
            style={[
              styles.input,
              styles.flexInput,
              { color: colors.text, backgroundColor: colors.surfaceAlt, borderColor: colors.border },
            ]}
          />
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="添加偏好记忆"
            disabled={busy || !newNote.trim()}
            onPress={addMemory}
            style={[
              styles.addBtn,
              { backgroundColor: colors.accent },
              (busy || !newNote.trim()) && styles.dimmed,
            ]}
          >
            <Text style={styles.primaryText}>添加</Text>
          </Pressable>
        </View>
      </View>

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="刷新设置状态"
        disabled={busy}
        onPress={loadAll}
        style={[
          styles.fullBtn,
          { backgroundColor: colors.surfaceAlt, borderColor: colors.border },
        ]}
      >
        <Text style={[styles.secondaryText, { color: colors.text }]}>刷新全部状态</Text>
      </Pressable>

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="重置损坏的对话存储"
        disabled={busy}
        onPress={handleResetConversations}
        style={[
          styles.fullBtn,
          { backgroundColor: colors.surface, borderColor: colors.border },
        ]}
      >
        <Text style={[styles.secondaryText, { color: colors.muted }]}>
          仅当对话文件损坏时重置存储
        </Text>
      </Pressable>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  content: { padding: 16, gap: 16, paddingBottom: 32 },
  banner: { borderRadius: 12, padding: 12 },
  bannerText: { fontSize: 13, lineHeight: 19, fontWeight: '500' },
  card: {
    borderWidth: 1,
    borderRadius: 16,
    padding: 16,
    gap: 10,
  },
  cardTitle: { fontSize: 17, fontWeight: '700' },
  cardDesc: { fontSize: 13, lineHeight: 19 },
  input: {
    minHeight: 44,
    borderWidth: 1,
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 9,
    fontSize: 14,
  },
  flexInput: { flex: 1 },
  row: { flexDirection: 'row', gap: 10, alignItems: 'center' },
  btn: {
    flex: 1,
    minHeight: 44,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 12,
  },
  addBtn: {
    minWidth: 68,
    minHeight: 44,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 14,
  },
  fullBtn: {
    minHeight: 44,
    borderWidth: 1,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  primaryText: { color: '#ffffff', fontSize: 14, fontWeight: '700' },
  secondaryText: { fontSize: 14, fontWeight: '600' },
  fileList: { gap: 4 },
  fileItem: { fontSize: 13 },
  noteRow: {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 10,
    gap: 8,
  },
  noteText: { flex: 1, fontSize: 14 },
  deleteChip: { paddingHorizontal: 6, paddingVertical: 4 },
  deleteChipText: { fontSize: 12, fontWeight: '600' },
  protocolBtn: {
    flex: 1,
    minHeight: 40,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 8,
  },
  protocolText: { fontSize: 13, fontWeight: '700' },
  presetRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  presetChip: {
    borderRadius: 14,
    paddingHorizontal: 10,
    paddingVertical: 5,
  },
  presetText: { fontSize: 12, fontWeight: '600' },
  bordered: { borderWidth: 1 },
  dimmed: { opacity: 0.45 },
});
