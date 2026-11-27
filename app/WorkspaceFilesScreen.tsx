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
import NativeTaskHost from './specs/NativePocketRunTaskHost';
import NativeWorkspace from './specs/NativePocketRunWorkspace';

type FileSummary = { path: string; bytes: number };

const MAX_EDIT_BYTES = 12 * 1024;

function utf8Bytes(value: string): number {
  let total = 0;
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    if (code < 0x80) total += 1;
    else if (code < 0x800) total += 2;
    else if (code >= 0xd800 && code <= 0xdbff) {
      total += 4;
      i++;
    } else total += 3;
  }
  return total;
}

export default function WorkspaceFilesScreen({
  colors,
  onAskInChat,
}: {
  colors: ThemeColors;
  onAskInChat: (prompt: string) => void;
}) {
  const [files, setFiles] = useState<FileSummary[]>([]);
  const [activePath, setActivePath] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [newPath, setNewPath] = useState('');
  const [content, setContent] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const live = useRef(true);

  const loadList = useCallback(async () => {
    if (!NativeTaskHost) return;
    try {
      const raw = await NativeTaskHost.listWorkspaceFiles();
      if (!live.current) return;
      const parsed: unknown = JSON.parse(raw);
      setFiles(Array.isArray(parsed) ? (parsed as FileSummary[]) : []);
    } catch (failure) {
      if (live.current) {
        setError(failure instanceof Error ? failure.message : '读取工作区文件列表失败。');
      }
    }
  }, []);

  useEffect(() => {
    live.current = true;
    loadList();
    return () => {
      live.current = false;
    };
  }, [loadList]);

  async function openFile(path: string) {
    if (!NativeTaskHost || busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const file = await NativeTaskHost.readWorkspaceFileText(path);
      if (!live.current) return;
      setCreating(false);
      setActivePath(file.path);
      setContent(file.content);
    } catch (failure) {
      if (live.current) {
        setError(failure instanceof Error ? failure.message : '读取文件内容失败（仅支持 12 KiB 内 UTF-8 文本）。');
      }
    } finally {
      if (live.current) setBusy(false);
    }
  }

  async function saveCurrent() {
    const targetPath = (creating ? newPath : activePath ?? '').trim();
    if (!NativeTaskHost || !targetPath || busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const saved = await NativeTaskHost.writeWorkspaceFileText(targetPath, content);
      if (!live.current) return;
      setCreating(false);
      setNewPath('');
      setActivePath(saved.path);
      setContent(saved.content);
      setNotice(`已原子保存 ${saved.path}（${saved.bytes} B）。`);
      await loadList();
    } catch (failure) {
      if (live.current) {
        setError(failure instanceof Error ? failure.message : '保存失败，请检查相对路径与 12 KiB 字节上限。');
      }
    } finally {
      if (live.current) setBusy(false);
    }
  }

  async function handleDelete(path: string) {
    if (!NativeTaskHost || busy) return;
    Alert.alert('删除工作区文件', `确认删除 ${path}？删除后无法恢复。`, [
      { text: '取消', style: 'cancel' },
      {
        text: '删除',
        style: 'destructive',
        onPress: async () => {
          setBusy(true);
          setError(null);
          try {
            const raw = await NativeTaskHost!.deleteWorkspaceFile(path);
            if (!live.current) return;
            const parsed: unknown = JSON.parse(raw);
            setFiles(Array.isArray(parsed) ? (parsed as FileSummary[]) : []);
            if (activePath === path) {
              setActivePath(null);
              setContent('');
            }
            setNotice(`已删除 ${path}。`);
          } catch (failure) {
            if (live.current) {
              setError(failure instanceof Error ? failure.message : '删除文件失败。');
            }
          } finally {
            if (live.current) setBusy(false);
          }
        },
      },
    ]);
  }

  async function initDemoCsv() {
    if (!NativeWorkspace || busy) return;
    setBusy(true);
    setError(null);
    try {
      await NativeWorkspace.prepareWorkspace();
      await loadList();
      if (live.current) setNotice('已初始化私有工作区（已有文件不会被覆盖）。');
    } catch (failure) {
      if (live.current) {
        setError(failure instanceof Error ? failure.message : '初始化工作区失败。');
      }
    } finally {
      if (live.current) setBusy(false);
    }
  }

  const editing = creating || activePath !== null;
  const currentBytes = utf8Bytes(content);

  if (editing) {
    const labelPath = creating ? newPath.trim() : activePath ?? '';
    return (
      <ScrollView
        style={[styles.screen, { backgroundColor: colors.background }]}
        contentContainerStyle={styles.content}
      >
        <View style={styles.toolbar}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="返回文件列表"
            onPress={() => {
              setCreating(false);
              setActivePath(null);
              setContent('');
              setError(null);
              setNotice(null);
            }}
            style={[styles.toolBtn, { backgroundColor: colors.surfaceAlt }]}
          >
            <Text style={[styles.toolBtnText, { color: colors.text }]}>← 返回</Text>
          </Pressable>
          <Text numberOfLines={1} style={[styles.editorTitle, { color: colors.text }]}>
            {creating ? '新建文件' : activePath}
          </Text>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="保存工作区文件"
            disabled={busy || !labelPath || currentBytes > MAX_EDIT_BYTES}
            onPress={saveCurrent}
            style={[
              styles.toolBtn,
              { backgroundColor: colors.accent },
              (busy || !labelPath || currentBytes > MAX_EDIT_BYTES) && styles.dimmed,
            ]}
          >
            <Text style={styles.primaryText}>保存</Text>
          </Pressable>
        </View>

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

        {creating && (
          <TextInput
            accessibilityLabel="新建文件相对路径"
            value={newPath}
            onChangeText={setNewPath}
            autoCapitalize="none"
            placeholder="相对路径，例如 notes/todo.md"
            placeholderTextColor={colors.muted}
            style={[
              styles.pathInput,
              { color: colors.text, backgroundColor: colors.surface, borderColor: colors.border },
            ]}
          />
        )}

        <Text style={[styles.meta, { color: currentBytes > MAX_EDIT_BYTES ? colors.danger : colors.muted }]}>
          UTF-8 大小：{currentBytes} / {MAX_EDIT_BYTES} 字节（仅限私有工作区相对路径）
        </Text>

        <TextInput
          accessibilityLabel="文件正文编辑器"
          value={content}
          onChangeText={setContent}
          multiline
          textAlignVertical="top"
          placeholder="在这里输入或编辑文件内容…"
          placeholderTextColor={colors.muted}
          style={[
            styles.editor,
            { color: colors.text, backgroundColor: colors.surface, borderColor: colors.border },
          ]}
        />

        {!creating && activePath && (
          <View style={styles.row}>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="在对话中处理此文件"
              onPress={() => onAskInChat(`请帮我读取工作区文件 ${activePath} 并总结重点内容`)}
              style={[styles.actionBtn, { backgroundColor: colors.accentSoft }]}
            >
              <Text style={[styles.actionText, { color: colors.accent }]}>
                在对话中处理此文件
              </Text>
            </Pressable>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="删除当前打开的文件"
              disabled={busy}
              onPress={() => handleDelete(activePath)}
              style={[
                styles.actionBtn,
                styles.bordered,
                { backgroundColor: colors.surfaceAlt, borderColor: colors.border },
              ]}
            >
              <Text style={[styles.actionText, { color: colors.danger }]}>删除文件</Text>
            </Pressable>
          </View>
        )}
      </ScrollView>
    );
  }

  return (
    <ScrollView
      style={[styles.screen, { backgroundColor: colors.background }]}
      contentContainerStyle={styles.content}
    >
      <View style={styles.toolbar}>
        <Text style={[styles.heading, { color: colors.text }]}>私有工作区文件</Text>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="新建工作区文件"
          disabled={busy}
          onPress={() => {
            setCreating(true);
            setActivePath(null);
            setNewPath('');
            setContent('');
            setError(null);
            setNotice(null);
          }}
          style={[styles.toolBtn, { backgroundColor: colors.accent }]}
        >
          <Text style={styles.primaryText}>+ 新建</Text>
        </Pressable>
      </View>

      <Text style={[styles.meta, { color: colors.muted }]}>
        位于 App 私有沙箱目录 workspaces/demo（普通 App UID），与 PRoot/Alpine 中的 /workspace 实时同步。
      </Text>

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

      {files.length === 0 ? (
        <View
          style={[
            styles.emptyCard,
            { backgroundColor: colors.surface, borderColor: colors.border },
          ]}
        >
          <Text style={[styles.emptyTitle, { color: colors.text }]}>工作区还是空的</Text>
          <Text style={[styles.meta, { color: colors.muted }]}>
            点上方「+ 新建」写一份笔记，或者先初始化演示 items.csv，再回到对话页让助手用文件工具或 PRoot 命令帮你处理。
          </Text>
        </View>
      ) : (
        <View style={styles.list}>
          {files.map(file => (
            <View
              key={file.path}
              style={[
                styles.fileRow,
                { backgroundColor: colors.surface, borderColor: colors.border },
              ]}
            >
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={`打开文件 ${file.path}`}
                onPress={() => openFile(file.path)}
                style={styles.fileMain}
              >
                <Text style={[styles.fileName, { color: colors.text }]}>{file.path}</Text>
                <Text style={[styles.fileSize, { color: colors.muted }]}>{file.bytes} B</Text>
              </Pressable>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={`删除文件 ${file.path}`}
                onPress={() => handleDelete(file.path)}
                style={styles.deleteBtn}
              >
                <Text style={[styles.deleteText, { color: colors.danger }]}>删除</Text>
              </Pressable>
            </View>
          ))}
        </View>
      )}

      <View style={styles.row}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="刷新文件列表"
          disabled={busy}
          onPress={loadList}
          style={[
            styles.actionBtn,
            styles.bordered,
            { backgroundColor: colors.surfaceAlt, borderColor: colors.border },
          ]}
        >
          <Text style={[styles.actionText, { color: colors.text }]}>刷新列表</Text>
        </Pressable>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="初始化演示 CSV"
          disabled={busy}
          onPress={initDemoCsv}
          style={[styles.actionBtn, { backgroundColor: colors.accentSoft }]}
        >
          <Text style={[styles.actionText, { color: colors.accent }]}>准备演示 CSV</Text>
        </Pressable>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  content: { padding: 16, gap: 12, paddingBottom: 32 },
  toolbar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 10,
  },
  heading: { fontSize: 20, fontWeight: '800' },
  editorTitle: { flex: 1, fontSize: 15, fontWeight: '700', textAlign: 'center' },
  toolBtn: {
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 10,
  },
  toolBtnText: { fontSize: 13, fontWeight: '700' },
  primaryText: { color: '#ffffff', fontSize: 13, fontWeight: '700' },
  meta: { fontSize: 13, lineHeight: 19 },
  banner: { borderRadius: 10, padding: 12 },
  bannerText: { fontSize: 13, lineHeight: 19 },
  bordered: { borderWidth: 1 },
  emptyCard: {
    borderWidth: 1,
    borderRadius: 14,
    padding: 18,
    gap: 6,
  },
  emptyTitle: { fontSize: 16, fontWeight: '700' },
  list: { gap: 8 },
  fileRow: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderRadius: 12,
    paddingHorizontal: 14,
    paddingVertical: 12,
    gap: 8,
  },
  fileMain: { flex: 1, gap: 2 },
  fileName: { fontSize: 15, fontWeight: '600', fontFamily: 'monospace' },
  fileSize: { fontSize: 12 },
  deleteBtn: { paddingHorizontal: 8, paddingVertical: 4 },
  deleteText: { fontSize: 12, fontWeight: '700' },
  pathInput: {
    minHeight: 44,
    borderWidth: 1,
    borderRadius: 10,
    paddingHorizontal: 12,
    fontSize: 14,
    fontFamily: 'monospace',
  },
  editor: {
    minHeight: 280,
    borderWidth: 1,
    borderRadius: 12,
    padding: 12,
    fontSize: 13,
    lineHeight: 20,
    fontFamily: 'monospace',
  },
  row: { flexDirection: 'row', gap: 10 },
  actionBtn: {
    flex: 1,
    minHeight: 44,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 12,
  },
  actionText: { fontSize: 14, fontWeight: '600' },
  dimmed: { opacity: 0.45 },
});
