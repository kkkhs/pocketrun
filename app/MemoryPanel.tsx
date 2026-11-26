import { useEffect, useRef, useState } from 'react';
import { Alert, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import NativeTaskHost, {
  type AgentLoopState,
  type MemorySnapshot,
  type ModelConfiguration,
} from './specs/NativePocketRunTaskHost';

type Colors = { text: string; muted: string };
type Note = { id: string; text: string };
const DEMO_GOAL = '先读取设备信息，再读取固定演示 CSV 行数，用真实结果总结；不要访问其他数据';
const OPERATION_ERROR = '操作失败；当前记忆会重新读取，请核对版本、内容上限和任务资源后再提交。不会自动重试或回显原生错误。';

function parseNotes(value: MemorySnapshot): Note[] {
  const notes: unknown = JSON.parse(value.notesJson);
  if (typeof value.revision !== 'string' || !value.revision ||
    ![value.maxNotes, value.maxNoteBytes, value.maxSelected, value.maxSelectedBytes].every(limit => Number.isSafeInteger(limit) && limit > 0) ||
    value.maxSelected > value.maxNotes ||
    !Array.isArray(notes) || notes.length > value.maxNotes || notes.some(note =>
      !note || typeof note.id !== 'string' || !note.id || typeof note.text !== 'string') ||
    new Set(notes.map(note => note.id)).size !== notes.length) {
    throw new Error('Invalid memory snapshot.');
  }
  return notes;
}

export default function MemoryPanel({ colors }: { colors: Colors }) {
  const [snapshot, setSnapshot] = useState<MemorySnapshot | null>(null);
  const [notes, setNotes] = useState<Note[]>([]);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [editingId, setEditingId] = useState('');
  const [text, setText] = useState('');
  const [configuration, setConfiguration] = useState<ModelConfiguration | null>(null);
  const [agent, setAgent] = useState<AgentLoopState | null>(null);
  const [busy, setBusy] = useState(false);
  const [unreadable, setUnreadable] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const live = useRef(true);
  const generation = useRef(0);
  const draftVersion = useRef(0);
  const actionBusy = useRef(false);
  const polling = useRef(false);

  function applySnapshot(value: MemorySnapshot) {
    const nextNotes = parseNotes(value);
    setSnapshot(value); setNotes(nextNotes); setUnreadable(false);
    setSelectedIds(ids => ids.filter(id => nextNotes.some(note => note.id === id)));
  }

  useEffect(() => {
    live.current = true;
    const initial = ++generation.current;
    NativeTaskHost?.getMemorySnapshot().then(value => {
      if (live.current && initial === generation.current) applySnapshot(value);
    }).catch(() => {
      if (live.current && initial === generation.current) {
        setUnreadable(true);
        setError('记忆不可读取；不会把损坏或未知数据当成空库。可以重新读取，或明确确认后重置。');
      }
    });
    NativeTaskHost?.getModelConfiguration().then(value => {
      if (live.current && initial === generation.current) setConfiguration(value);
    }).catch(() => {
      if (live.current && initial === generation.current) setError('模型配置不可读取；先在第 11 章核对配置。');
    });
    async function poll() {
      if (!NativeTaskHost || !live.current || polling.current || actionBusy.current) return;
      polling.current = true;
      const request = generation.current;
      try {
        const value = await NativeTaskHost.getAgentState();
        if (live.current && request === generation.current) setAgent(value);
      } catch { /* Explicit controls show sanitized errors. */ }
      finally { polling.current = false; }
    }
    poll();
    const timer = setInterval(poll, 250);
    return () => { live.current = false; clearInterval(timer); };
  }, []);

  async function perform(action: (request: number) => Promise<MemorySnapshot | AgentLoopState>, clearDraft?: number) {
    if (!NativeTaskHost || actionBusy.current) return;
    actionBusy.current = true;
    const request = ++generation.current;
    setBusy(true); setError(null);
    try {
      const next = await action(request);
      if (live.current && request === generation.current) {
        if ('revision' in next) applySnapshot(next); else setAgent(next);
        if (clearDraft !== undefined && clearDraft === draftVersion.current) {
          setText(''); setEditingId('');
        }
      }
    } catch {
      if (live.current && request === generation.current) setError(OPERATION_ERROR);
      try {
        const current = await NativeTaskHost.getMemorySnapshot();
        if (live.current && request === generation.current) applySnapshot(current);
      } catch {
        if (live.current && request === generation.current) {
          setUnreadable(true); setSnapshot(null); setNotes([]); setSelectedIds([]);
        }
      }
    } finally {
      actionBusy.current = false;
      if (live.current && request === generation.current) setBusy(false);
    }
  }

  function confirm(title: string, message: string, action: () => void, destructive = false) {
    const expected = generation.current;
    Alert.alert(title, message, [
      { text: '取消', style: 'cancel' },
      { text: '确认', style: destructive ? 'destructive' : 'default', onPress: () => {
        if (live.current && expected === generation.current && !actionBusy.current) action();
      } },
    ], { cancelable: false });
  }

  async function start() {
    if (!NativeTaskHost || actionBusy.current || !snapshot || !selectedIds.length) return;
    const revision = snapshot.revision;
    const ids = [...selectedIds];
    const selected = notes.filter(note => ids.includes(note.id));
    actionBusy.current = true;
    const request = ++generation.current;
    setBusy(true); setError(null);
    try {
      const current = await NativeTaskHost.getModelConfiguration();
      if (!live.current || request !== generation.current) return;
      setConfiguration(current);
      if (!current.hasCredential) throw new Error('Model configuration required.');
      // Consent fixes the notes, revision and destination; native code rechecks them before sending.
      confirm('发送所选记忆并开始任务',
        `模型地址：${current.endpoint}\n模型：${current.model}\n记忆版本：${revision}\n\n${selected.map((note, index) => `${index + 1}. ${note.text}`).join('\n')}\n\n仅发送这些笔记；CSV 工具仍需在手机批准。`,
        () => perform(() => NativeTaskHost!.startAgentWithMemory(DEMO_GOAL, ids, revision, current.endpoint, current.model)));
    } catch {
      if (live.current && request === generation.current) setError('模型配置不可用；先在第 11 章保存合法配置。本次没有发送记忆。');
    } finally {
      actionBusy.current = false;
      if (live.current && request === generation.current) setBusy(false);
    }
  }

  function select(id: string) {
    if (busy || !snapshot) return;
    if (selectedIds.includes(id)) setSelectedIds(ids => ids.filter(value => value !== id));
    else if (selectedIds.length < snapshot.maxSelected) setSelectedIds(ids => [...ids, id]);
    else setError(`每次最多选择 ${snapshot.maxSelected} 条记忆。`);
  }

  function button(label: string, disabled: boolean, action: () => void) {
    return <Pressable accessibilityRole="button" accessibilityLabel={label}
      accessibilityState={{ disabled }} disabled={disabled} onPress={action}
      style={[styles.button, disabled && styles.disabled]}>
      <Text style={styles.buttonText}>{label}</Text>
    </Pressable>;
  }

  const active = agent?.phase === 'model' || agent?.phase === 'tool';
  return <View style={styles.panel}>
    <Text style={[styles.heading, { color: colors.text }]}>第 12 章 · 可管理的跨任务记忆</Text>
    <Text style={{ color: colors.muted }}>只保存自己愿意保留的非敏感笔记。记忆由原生加密保存；只有明确选中并确认的笔记才发送给当前模型，记忆不能批准工具。</Text>
    {button('刷新记忆与模型配置', busy || !NativeTaskHost, () => perform(async request => {
      const [next, current] = await Promise.all([NativeTaskHost!.getMemorySnapshot(), NativeTaskHost!.getModelConfiguration()]);
      if (live.current && request === generation.current) setConfiguration(current);
      return next;
    }))}
    {snapshot && <Text selectable style={{ color: colors.muted }}>记忆版本：{snapshot.revision} · {notes.length}/{snapshot.maxNotes} 条；每条最多 {snapshot.maxNoteBytes} UTF-8 字节，所选编码最多 {snapshot.maxSelectedBytes} 字节</Text>}
    {notes.map((note, index) => <View key={note.id} style={styles.note}>
      <Text selectable style={{ color: colors.text }}>记忆 {index + 1}：{note.text}</Text>
      <Pressable accessibilityRole="checkbox" accessibilityLabel={`选择记忆 ${index + 1}`}
        accessibilityState={{ checked: selectedIds.includes(note.id), disabled: busy }} disabled={busy}
        onPress={() => select(note.id)} style={styles.selection}>
        <Text style={{ color: colors.text }}>{selectedIds.includes(note.id) ? '已选中' : '未选中'}：供下次任务使用</Text>
      </Pressable>
      {button(`编辑记忆 ${index + 1}`, busy, () => {
        draftVersion.current++; setEditingId(note.id); setText(note.text);
      })}
      {button(`删除记忆 ${index + 1}`, busy, () => confirm('删除这条记忆',
        '删除会取消活跃模型任务并释放本地旧记忆上下文；不能撤回服务端已经收到的数据或已提交的工具动作。',
        () => perform(() => NativeTaskHost!.deleteMemoryNote(snapshot!.revision, note.id)), true))}
    </View>)}
    {snapshot && notes.length === 0 && <Text style={{ color: colors.muted }}>当前没有笔记；新增后仍需主动选择。</Text>}
    <TextInput accessibilityLabel="记忆笔记正文" value={text} onChangeText={value => {
      draftVersion.current++; setText(value);
    }} editable={!busy} multiline maxLength={snapshot?.maxNoteBytes ?? 256}
      style={[styles.input, { color: colors.text }]} />
    <Text style={{ color: colors.muted }}>正文上限按 UTF-8 字节检查，不是中文字数。保存或修改会取消活跃循环；失败保留草稿，核对刷新后的版本再提交。</Text>
    {editingId && button('改为新建记忆', busy, () => { draftVersion.current++; setEditingId(''); })}
    {button(editingId ? '保存记忆修改' : '保存新记忆', busy || !snapshot || unreadable || !text.trim() ||
      (!editingId && notes.length >= (snapshot?.maxNotes ?? 8)), () => {
      const expected = snapshot!.revision;
      const id = editingId;
      const content = text;
      const draft = draftVersion.current;
      perform(() => NativeTaskHost!.saveMemoryNote(expected, id, content), draft);
    })}
    {button('清空全部记忆', busy || !snapshot || notes.length === 0, () => confirm('清空全部记忆',
      '这会删除全部笔记并取消活跃模型任务。已发送给模型的数据和已提交的工具动作不能撤回。',
      () => perform(() => NativeTaskHost!.clearMemory(snapshot!.revision)), true))}
    {unreadable && button('重置不可读记忆', busy || !NativeTaskHost, () => confirm('重置不可读记忆',
      '只有确认放弃不可读取的记忆时才重置。此操作会丢弃记忆存储，不修改模型凭据或工作文件。',
      () => perform(() => NativeTaskHost!.resetMemory()), true))}
    <Text style={{ color: colors.muted }}>模型配置（开始前会重新核对）：{configuration?.endpoint ?? '尚未读取'} · {configuration?.model ?? ''}</Text>
    <Text style={{ color: colors.text }}>已选择 {selectedIds.length}/{snapshot?.maxSelected ?? 4} 条；开始前会显示具体内容和发送地址。</Text>
    {button('使用所选记忆开始两步任务', busy || active || !snapshot || unreadable || !selectedIds.length || !configuration?.hasCredential || !NativeTaskHost,
      () => start())}
    {button('取消当前模型任务', busy || !active || !NativeTaskHost,
      () => perform(() => NativeTaskHost!.cancelAgent()))}
    {agent && <View accessibilityLiveRegion="polite" style={styles.note}>
      <Text style={{ color: colors.text }}>循环：{agent.phase} · {agent.code ?? ''} · {agent.taskId ?? '(none)'}</Text>
      <Text selectable style={{ color: colors.muted }}>使用的记忆版本：{agent.memoryRevision ?? '(无记忆)'} · IDs：{agent.memoryIdsJson ?? '[]'} · {agent.memoryBytes ?? 0} 编码字节</Text>
      {agent.finalText && <Text selectable style={{ color: colors.text }}>{agent.finalText}</Text>}
    </View>}
    {error && <Text accessibilityRole="alert" style={{ color: colors.text }}>{error}</Text>}
    <Text style={{ color: colors.muted }}>CSV 手机审批和真实来源显示在第 10 章账本；此面板不自动批准、保存任务总结或学习笔记。</Text>
  </View>;
}

const styles = StyleSheet.create({
  panel: { gap: 12 }, heading: { fontSize: 21, fontWeight: '700' },
  input: { minHeight: 64, borderWidth: 1, borderColor: '#64748b', borderRadius: 8, padding: 10 },
  button: { minHeight: 48, padding: 12, backgroundColor: '#1d4ed8', borderRadius: 8,
    alignItems: 'center', justifyContent: 'center' },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' }, disabled: { opacity: 0.45 },
  note: { gap: 8, borderWidth: 1, borderColor: '#64748b', borderRadius: 8, padding: 10 },
  selection: { minHeight: 48, justifyContent: 'center' },
});
