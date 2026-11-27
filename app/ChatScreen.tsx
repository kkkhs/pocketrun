import { type ComponentRef, useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import NativeTaskHost, {
  type ChatRuntimeState,
  type ModelConfiguration,
} from './specs/NativePocketRunTaskHost';
import MarkdownView from './MarkdownView';

export type ThemeColors = {
  background: string;
  surface: string;
  surfaceAlt: string;
  border: string;
  text: string;
  muted: string;
  accent: string;
  accentSoft: string;
  danger: string;
  warningBg: string;
  warningBorder: string;
};

type ConversationSummary = {
  id: string;
  title: string;
  createdAtMillis: number;
  updatedAtMillis: number;
  messageCount: number;
  lastPreview: string;
};

type ToolTrace = {
  tool: string;
  status: string;
  summary: string;
};

type MessageItem = {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  timestampMillis: number;
  tools: ToolTrace[];
  outputTokens: number;
};

const SUGGESTIONS = [
  '查看当前手机的系统版本、API 和 CPU 架构',
  '列出私有工作区里有哪些文件',
  '读取 items.csv 并把汇总结果写入 summary.md',
  '用 PRoot Linux 命令查看 /etc/alpine-release 和工作区文件列表',
];

function parseSummaries(raw: string): ConversationSummary[] {
  const parsed: unknown = JSON.parse(raw);
  return Array.isArray(parsed) ? (parsed as ConversationSummary[]) : [];
}

function parseMessages(raw: string): MessageItem[] {
  const parsed: unknown = JSON.parse(raw);
  return Array.isArray(parsed) ? (parsed as MessageItem[]) : [];
}

function parseTraces(raw: string): ToolTrace[] {
  try {
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as ToolTrace[]) : [];
  } catch {
    return [];
  }
}

function toolDisplayName(wireName: string): string {
  switch (wireName) {
    case 'android.device_info':
      return '读取设备信息';
    case 'workspace.list_files':
      return '列出工作区文件';
    case 'workspace.read_file':
      return '读取工作区文件';
    case 'workspace.write_file':
      return '写入工作区文件';
    case 'linux.count_demo_csv':
      return 'PRoot 统计演示 CSV';
    case 'linux.run_shell':
      return 'PRoot/Alpine 执行命令';
    case 'android.virtual_display_observe_or_demo_input':
      return '虚拟屏观察 / 输入';
    default:
      return wireName;
  }
}

export default function ChatScreen({
  colors,
  onOpenSettings,
  initialPrompt,
  onConsumeInitialPrompt,
}: {
  colors: ThemeColors;
  onOpenSettings: () => void;
  initialPrompt?: string | null;
  onConsumeInitialPrompt?: () => void;
}) {
  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [currentId, setCurrentId] = useState<string | null>(null);
  const [currentTitle, setCurrentTitle] = useState('新对话');
  const [messages, setMessages] = useState<MessageItem[]>([]);
  const [runtime, setRuntime] = useState<ChatRuntimeState | null>(null);
  const [config, setConfig] = useState<ModelConfiguration | null>(null);
  const [memoryCount, setMemoryCount] = useState(0);
  const [includeMemory, setIncludeMemory] = useState(false);
  const [draft, setDraft] = useState('');
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const live = useRef(true);
  const currentIdRef = useRef<string | null>(null);
  const wasActiveRef = useRef(false);
  const scrollRef = useRef<ComponentRef<typeof ScrollView> | null>(null);

  currentIdRef.current = currentId;

  useEffect(() => {
    if (initialPrompt) {
      setDraft(initialPrompt);
      onConsumeInitialPrompt?.();
    }
  }, [initialPrompt, onConsumeInitialPrompt]);

  const loadConversation = useCallback(async (id: string) => {
    if (!NativeTaskHost) return;
    const detail = await NativeTaskHost.getConversation(id);
    if (!live.current) return;
    setCurrentId(detail.id);
    setCurrentTitle(detail.title);
    setMessages(parseMessages(detail.messagesJson));
  }, []);

  const refreshAll = useCallback(
    async (selectId?: string | null) => {
      if (!NativeTaskHost) return;
      try {
        const [listRes, cfg, memSnap, rt] = await Promise.all([
          NativeTaskHost.listConversations(),
          NativeTaskHost.getModelConfiguration().catch(() => null),
          NativeTaskHost.getMemorySnapshot().catch(() => null),
          NativeTaskHost.getChatRuntimeState().catch(() => null),
        ]);
        if (!live.current) return;
        const list = parseSummaries(listRes.conversationsJson);
        setConversations(list);
        if (cfg) setConfig(cfg);
        if (memSnap) {
          try {
            const notes = JSON.parse(memSnap.notesJson);
            setMemoryCount(Array.isArray(notes) ? notes.length : 0);
          } catch {
            setMemoryCount(0);
          }
        }
        if (rt) setRuntime(rt);
        const target = selectId !== undefined ? selectId : currentIdRef.current ?? list[0]?.id ?? null;
        if (target && list.some(item => item.id === target)) {
          await loadConversation(target);
        } else {
          setCurrentId(null);
          setCurrentTitle('新对话');
          setMessages([]);
        }
      } catch (failure) {
        if (live.current) {
          setError(failure instanceof Error ? failure.message : '加载对话记录失败，可在设置中检查存储。');
        }
      }
    },
    [loadConversation],
  );

  useEffect(() => {
    live.current = true;
    refreshAll();
    const timer = setInterval(async () => {
      if (!NativeTaskHost || !live.current) return;
      try {
        const rt = await NativeTaskHost.getChatRuntimeState();
        if (!live.current) return;
        setRuntime(rt);
        const activeNow =
          rt.phase === 'streaming' ||
          rt.phase === 'waiting_approval' ||
          rt.phase === 'tool_executing';
        if (wasActiveRef.current && !activeNow) {
          await refreshAll(rt.conversationId ?? currentIdRef.current);
        }
        wasActiveRef.current = activeNow;
      } catch {
        // Ignore transient poll errors; explicit actions surface errors.
      }
    }, 180);
    return () => {
      live.current = false;
      clearInterval(timer);
    };
  }, [refreshAll]);

  const active =
    runtime?.phase === 'streaming' ||
    runtime?.phase === 'waiting_approval' ||
    runtime?.phase === 'tool_executing';

  async function startNewChat() {
    if (!NativeTaskHost || busy || active) return;
    setError(null);
    setDrawerOpen(false);
    setCurrentId(null);
    setCurrentTitle('新对话');
    setMessages([]);
  }

  async function handleDeleteConversation(id: string) {
    if (!NativeTaskHost || busy) return;
    Alert.alert('删除这段对话', '删除后该对话的本地消息记录将无法恢复。', [
      { text: '取消', style: 'cancel' },
      {
        text: '删除',
        style: 'destructive',
        onPress: async () => {
          setBusy(true);
          try {
            const res = await NativeTaskHost!.deleteConversation(id);
            if (!live.current) return;
            const list = parseSummaries(res.conversationsJson);
            setConversations(list);
            if (currentIdRef.current === id) {
              const next = list[0]?.id ?? null;
              if (next) await loadConversation(next);
              else {
                setCurrentId(null);
                setCurrentTitle('新对话');
                setMessages([]);
              }
            }
          } catch (failure) {
            if (live.current) {
              setError(failure instanceof Error ? failure.message : '删除对话失败。');
            }
          } finally {
            if (live.current) setBusy(false);
          }
        },
      },
    ]);
  }

  async function handleSend(textOverride?: string) {
    const prompt = (textOverride ?? draft).trim();
    if (!NativeTaskHost || !prompt || busy || active) return;
    setBusy(true);
    setError(null);
    try {
      const nextRuntime = await NativeTaskHost.sendChatMessage(
        currentId ?? '',
        prompt,
        includeMemory && memoryCount > 0,
      );
      if (!live.current) return;
      wasActiveRef.current = true;
      setDraft('');
      setRuntime(nextRuntime);
      const convId = nextRuntime.conversationId;
      if (convId) {
        setCurrentId(convId);
        await refreshAll(convId);
      }
      scrollRef.current?.scrollToEnd({ animated: true });
    } catch (failure) {
      if (live.current) {
        setError(failure instanceof Error ? failure.message : '发送消息失败，请检查模型配置。');
      }
    } finally {
      if (live.current) setBusy(false);
    }
  }

  async function handleStop() {
    if (!NativeTaskHost || !active) return;
    setBusy(true);
    try {
      const next = await NativeTaskHost.cancelChatTurn();
      if (live.current) setRuntime(next);
    } catch (failure) {
      if (live.current) {
        setError(failure instanceof Error ? failure.message : '停止生成失败。');
      }
    } finally {
      if (live.current) setBusy(false);
    }
  }

  async function handleDecision(decision: 'approve' | 'reject') {
    const nonce = runtime?.pendingNonce;
    if (!NativeTaskHost || !nonce || busy) return;
    setBusy(true);
    setError(null);
    try {
      await NativeTaskHost.decideFromAppUi(nonce, decision);
      const next = await NativeTaskHost.getChatRuntimeState();
      if (live.current) setRuntime(next);
    } catch (failure) {
      if (live.current) {
        setError(failure instanceof Error ? failure.message : '提交审批决定失败。');
      }
    } finally {
      if (live.current) setBusy(false);
    }
  }

  const activeTraces = runtime ? parseTraces(runtime.activeToolsJson) : [];
  const showActiveTurn = active && (!currentId || runtime?.conversationId === currentId);

  return (
    <View style={[styles.container, { backgroundColor: colors.background }]}>
      <View style={[styles.header, { borderBottomColor: colors.border }]}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="打开历史对话列表"
          onPress={() => setDrawerOpen(open => !open)}
          style={[styles.headerBtn, { backgroundColor: colors.surfaceAlt }]}
        >
          <Text style={[styles.headerBtnText, { color: colors.text }]}>
            {drawerOpen ? '关闭' : `会话 (${conversations.length})`}
          </Text>
        </Pressable>
        <Text
          numberOfLines={1}
          style={[styles.headerTitle, { color: colors.text }]}
        >
          {currentTitle}
        </Text>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="新建对话"
          disabled={busy || active}
          onPress={startNewChat}
          style={[
            styles.headerBtn,
            { backgroundColor: colors.accentSoft },
            (busy || active) && styles.dimmed,
          ]}
        >
          <Text style={[styles.headerBtnText, { color: colors.accent }]}>
            + 新对话
          </Text>
        </Pressable>
      </View>

      {drawerOpen && (
        <View
          style={[
            styles.drawer,
            { backgroundColor: colors.surface, borderBottomColor: colors.border },
          ]}
        >
          <Text style={[styles.drawerTitle, { color: colors.muted }]}>
            已加密保存的本地对话
          </Text>
          {conversations.length === 0 ? (
            <Text style={[styles.drawerEmpty, { color: colors.muted }]}>
              还没有历史对话，在下方直接提问即可开始。
            </Text>
          ) : (
            <ScrollView style={styles.drawerList}>
              {conversations.map(item => {
                const selected = item.id === currentId;
                return (
                  <View
                    key={item.id}
                    style={[
                      styles.drawerItem,
                      {
                        backgroundColor: selected
                          ? colors.accentSoft
                          : colors.surfaceAlt,
                      },
                    ]}
                  >
                    <Pressable
                      accessibilityRole="button"
                      accessibilityLabel={`切换对话 ${item.title}`}
                      style={styles.drawerItemMain}
                      onPress={async () => {
                        setDrawerOpen(false);
                        await loadConversation(item.id);
                      }}
                    >
                      <Text
                        numberOfLines={1}
                        style={[styles.drawerItemTitle, { color: colors.text }]}
                      >
                        {item.title}
                      </Text>
                      <Text
                        numberOfLines={1}
                        style={[styles.drawerItemSub, { color: colors.muted }]}
                      >
                        {item.messageCount} 条消息
                        {item.lastPreview ? ` · ${item.lastPreview}` : ''}
                      </Text>
                    </Pressable>
                    <Pressable
                      accessibilityRole="button"
                      accessibilityLabel={`删除对话 ${item.title}`}
                      onPress={() => handleDeleteConversation(item.id)}
                      style={styles.drawerDeleteBtn}
                    >
                      <Text style={[styles.drawerDeleteText, { color: colors.danger }]}>
                        删除
                      </Text>
                    </Pressable>
                  </View>
                );
              })}
            </ScrollView>
          )}
        </View>
      )}

      <ScrollView
        ref={scrollRef}
        style={styles.feed}
        contentContainerStyle={styles.feedContent}
        onContentSizeChange={() => {
          if (showActiveTurn) scrollRef.current?.scrollToEnd({ animated: false });
        }}
      >
        {config && !config.hasCredential && (
          <View
            style={[
              styles.setupBanner,
              { backgroundColor: colors.warningBg, borderColor: colors.warningBorder },
            ]}
          >
            <Text style={[styles.setupTitle, { color: colors.text }]}>
              先配置模型，再开始对话
            </Text>
            <Text style={[styles.setupDesc, { color: colors.muted }]}>
              填写你有权使用的 Anthropic 兼容 API 地址、模型名和 API Key（由 Android Keystore 加密保存）。
            </Text>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="前往模型设置"
              onPress={onOpenSettings}
              style={[styles.setupBtn, { backgroundColor: colors.accent }]}
            >
              <Text style={styles.primaryBtnText}>前往设置</Text>
            </Pressable>
          </View>
        )}

        {messages.length === 0 && !showActiveTurn ? (
          <View style={styles.welcome}>
            <Text style={[styles.welcomeHeading, { color: colors.text }]}>
              你好，我是 PocketRun
            </Text>
            <Text style={[styles.welcomeSub, { color: colors.muted }]}>
              运行在你手机上的本机 AI 助手：支持自由多轮对话、工作区文件读写与受控设备工具，敏感操作始终由你亲手批准。
            </Text>
            <View style={styles.chips}>
              {SUGGESTIONS.map(item => (
                <Pressable
                  key={item}
                  accessibilityRole="button"
                  accessibilityLabel={`快捷提问：${item}`}
                  disabled={busy || active || !config?.hasCredential}
                  onPress={() => handleSend(item)}
                  style={[
                    styles.chip,
                    { backgroundColor: colors.surface, borderColor: colors.border },
                    (busy || active || !config?.hasCredential) && styles.dimmed,
                  ]}
                >
                  <Text style={[styles.chipText, { color: colors.text }]}>{item}</Text>
                </Pressable>
              ))}
            </View>
          </View>
        ) : (
          messages.map(msg => (
            <View
              key={msg.id}
              style={[
                styles.msgRow,
                msg.role === 'user' ? styles.userRow : styles.assistantRow,
              ]}
            >
              {msg.role === 'user' ? (
                <View style={[styles.userBubble, { backgroundColor: colors.accent }]}>
                  <Text selectable style={styles.userBubbleText}>
                    {msg.text}
                  </Text>
                </View>
              ) : (
                <View
                  style={[
                    styles.assistantCard,
                    { backgroundColor: colors.surface, borderColor: colors.border },
                  ]}
                >
                  {msg.tools.length > 0 && (
                    <View style={styles.traceList}>
                      {msg.tools.map((trace, idx) => (
                        <View
                          key={`${msg.id}-t-${idx}`}
                          style={[styles.tracePill, { backgroundColor: colors.surfaceAlt }]}
                        >
                          <Text style={[styles.traceText, { color: colors.muted }]}>
                            {trace.status === 'succeeded' ? '✓' : '✕'}{' '}
                            {toolDisplayName(trace.tool)} · {trace.summary}
                          </Text>
                        </View>
                      ))}
                    </View>
                  )}
                  <MarkdownView text={msg.text} colors={colors} />
                  {msg.outputTokens > 0 && (
                    <Text style={[styles.metaFooter, { color: colors.muted }]}>
                      {msg.outputTokens} output tokens
                    </Text>
                  )}
                </View>
              )}
            </View>
          ))
        )}

        {showActiveTurn && runtime && (
          <View style={styles.assistantRow}>
            <View
              style={[
                styles.assistantCard,
                { backgroundColor: colors.surface, borderColor: colors.border },
              ]}
            >
              {activeTraces.length > 0 && (
                <View style={styles.traceList}>
                  {activeTraces.map((trace, idx) => (
                    <View
                      key={`live-t-${idx}`}
                      style={[styles.tracePill, { backgroundColor: colors.surfaceAlt }]}
                    >
                      <Text style={[styles.traceText, { color: colors.muted }]}>
                        ✓ {toolDisplayName(trace.tool)} · {trace.summary}
                      </Text>
                    </View>
                  ))}
                </View>
              )}

              {runtime.phase === 'waiting_approval' && runtime.pendingNonce ? (
                <View
                  accessibilityLiveRegion="polite"
                  style={[
                    styles.approvalCard,
                    { backgroundColor: colors.warningBg, borderColor: colors.warningBorder },
                  ]}
                >
                  <Text style={[styles.approvalTitle, { color: colors.text }]}>
                    需要你在手机上确认此工具调用
                  </Text>
                  <Text style={[styles.approvalLine, { color: colors.text }]}>
                    动作：{toolDisplayName(runtime.pendingTool ?? '')}
                  </Text>
                  {runtime.pendingTarget ? (
                    <Text selectable style={[styles.approvalLine, { color: colors.muted }]}>
                      目标：{runtime.pendingTarget}
                    </Text>
                  ) : null}
                  {runtime.pendingArgs ? (
                    <Text selectable style={[styles.approvalLine, { color: colors.muted }]}>
                      参数：{runtime.pendingArgs}
                    </Text>
                  ) : null}
                  <View style={styles.approvalActions}>
                    <Pressable
                      accessibilityRole="button"
                      accessibilityLabel="拒绝工具执行"
                      disabled={busy}
                      onPress={() => handleDecision('reject')}
                      style={[
                        styles.approvalBtn,
                        { backgroundColor: colors.surfaceAlt, borderColor: colors.border },
                      ]}
                    >
                      <Text style={[styles.approvalRejectText, { color: colors.danger }]}>
                        拒绝
                      </Text>
                    </Pressable>
                    <Pressable
                      accessibilityRole="button"
                      accessibilityLabel="允许工具执行"
                      disabled={busy}
                      onPress={() => handleDecision('approve')}
                      style={[styles.approvalBtn, { backgroundColor: colors.accent }]}
                    >
                      <Text style={styles.primaryBtnText}>允许执行</Text>
                    </Pressable>
                  </View>
                </View>
              ) : (
                <View style={styles.streamingBox}>
                  {runtime.streamingText ? (
                    <MarkdownView text={runtime.streamingText} colors={colors} />
                  ) : (
                    <View style={styles.thinkingRow}>
                      <ActivityIndicator size="small" color={colors.accent} />
                      <Text style={[styles.thinkingText, { color: colors.muted }]}>
                        {runtime.phase === 'tool_executing' ? '正在执行本机工具…' : '正在思考并流式生成…'}
                      </Text>
                    </View>
                  )}
                </View>
              )}
            </View>
          </View>
        )}

        {(error || (runtime && (runtime.phase === 'failed' || runtime.phase === 'budget_exhausted') && runtime.errorMessage)) && (
          <View
            accessibilityRole="alert"
            style={[styles.errorCard, { borderColor: colors.danger, backgroundColor: colors.surface }]}
          >
            <Text style={[styles.errorText, { color: colors.danger }]}>
              {error ?? runtime?.errorMessage}
            </Text>
          </View>
        )}
      </ScrollView>

      <View
        style={[
          styles.composerWrap,
          { backgroundColor: colors.surface, borderTopColor: colors.border },
        ]}
      >
        <View style={styles.composerMeta}>
          <Pressable
            accessibilityRole="switch"
            accessibilityLabel="切换是否携带长期记忆"
            accessibilityState={{ checked: includeMemory && memoryCount > 0 }}
            onPress={() => {
              if (memoryCount === 0) onOpenSettings();
              else setIncludeMemory(v => !v);
            }}
            style={[
              styles.metaChip,
              {
                backgroundColor:
                  includeMemory && memoryCount > 0 ? colors.accentSoft : colors.surfaceAlt,
              },
            ]}
          >
            <Text
              style={[
                styles.metaChipText,
                {
                  color:
                    includeMemory && memoryCount > 0 ? colors.accent : colors.muted,
                },
              ]}
            >
              {memoryCount === 0
                ? '记忆：未添加'
                : includeMemory
                  ? `记忆：已开启 (${memoryCount})`
                  : `记忆：已关闭 (${memoryCount})`}
            </Text>
          </Pressable>
          <Text numberOfLines={1} style={[styles.modelTag, { color: colors.muted }]}>
            {config?.hasCredential
              ? `${config.protocol === 'openai' ? 'OpenAI' : 'Anthropic'} · ${config.model}`
              : '未配置模型'}
          </Text>
        </View>

        <View style={styles.composerRow}>
          <TextInput
            accessibilityLabel="对话输入框"
            value={draft}
            onChangeText={setDraft}
            editable={!busy && !active}
            multiline
            placeholder="输入消息，向 PocketRun 提问…"
            placeholderTextColor={colors.muted}
            style={[
              styles.input,
              {
                color: colors.text,
                backgroundColor: colors.surfaceAlt,
                borderColor: colors.border,
              },
            ]}
          />
          {active ? (
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="停止生成"
              onPress={handleStop}
              style={[styles.sendBtn, { backgroundColor: colors.danger }]}
            >
              <Text style={styles.primaryBtnText}>停止</Text>
            </Pressable>
          ) : (
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="发送消息"
              disabled={busy || !draft.trim() || !config?.hasCredential}
              onPress={() => handleSend()}
              style={[
                styles.sendBtn,
                { backgroundColor: colors.accent },
                (busy || !draft.trim() || !config?.hasCredential) && styles.dimmed,
              ]}
            >
              <Text style={styles.primaryBtnText}>发送</Text>
            </Pressable>
          )}
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderBottomWidth: 1,
    gap: 10,
  },
  headerBtn: {
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderRadius: 16,
  },
  headerBtnText: { fontSize: 13, fontWeight: '600' },
  headerTitle: { flex: 1, fontSize: 16, fontWeight: '700', textAlign: 'center' },
  drawer: {
    maxHeight: 260,
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderBottomWidth: 1,
    gap: 8,
  },
  drawerTitle: { fontSize: 12, fontWeight: '600' },
  drawerEmpty: { fontSize: 14, paddingVertical: 8 },
  drawerList: { maxHeight: 210 },
  drawerItem: {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 10,
    marginBottom: 8,
    gap: 8,
  },
  drawerItemMain: { flex: 1, gap: 2 },
  drawerItemTitle: { fontSize: 14, fontWeight: '600' },
  drawerItemSub: { fontSize: 12 },
  drawerDeleteBtn: { paddingHorizontal: 8, paddingVertical: 4 },
  drawerDeleteText: { fontSize: 12, fontWeight: '600' },
  feed: { flex: 1 },
  feedContent: { padding: 16, gap: 14, paddingBottom: 24 },
  setupBanner: {
    borderWidth: 1,
    borderRadius: 14,
    padding: 14,
    gap: 8,
  },
  setupTitle: { fontSize: 15, fontWeight: '700' },
  setupDesc: { fontSize: 13, lineHeight: 20 },
  setupBtn: {
    alignSelf: 'flex-start',
    paddingHorizontal: 16,
    paddingVertical: 9,
    borderRadius: 10,
  },
  welcome: { paddingTop: 28, gap: 14 },
  welcomeHeading: { fontSize: 26, fontWeight: '800' },
  welcomeSub: { fontSize: 15, lineHeight: 23 },
  chips: { gap: 10, marginTop: 6 },
  chip: {
    borderWidth: 1,
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 12,
  },
  chipText: { fontSize: 14, lineHeight: 20 },
  msgRow: { width: '100%' },
  userRow: { alignItems: 'flex-end' },
  assistantRow: { alignItems: 'stretch' },
  userBubble: {
    maxWidth: '84%',
    borderRadius: 18,
    borderBottomRightRadius: 6,
    paddingHorizontal: 14,
    paddingVertical: 10,
  },
  userBubbleText: { color: '#ffffff', fontSize: 15, lineHeight: 22 },
  assistantCard: {
    borderWidth: 1,
    borderRadius: 16,
    padding: 14,
    gap: 10,
  },
  assistantText: { fontSize: 15, lineHeight: 24 },
  traceList: { gap: 6 },
  tracePill: {
    alignSelf: 'flex-start',
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 5,
  },
  traceText: { fontSize: 12 },
  metaFooter: { fontSize: 11, textAlign: 'right' },
  approvalCard: {
    borderWidth: 1,
    borderRadius: 12,
    padding: 12,
    gap: 6,
  },
  approvalTitle: { fontSize: 14, fontWeight: '700' },
  approvalLine: { fontSize: 13, lineHeight: 19 },
  approvalActions: { flexDirection: 'row', gap: 10, marginTop: 6 },
  approvalBtn: {
    flex: 1,
    minHeight: 42,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: 'transparent',
    alignItems: 'center',
    justifyContent: 'center',
  },
  approvalRejectText: { fontSize: 14, fontWeight: '700' },
  streamingBox: { minHeight: 24 },
  thinkingRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  thinkingText: { fontSize: 14 },
  errorCard: {
    borderWidth: 1,
    borderRadius: 12,
    padding: 12,
  },
  errorText: { fontSize: 13, lineHeight: 19 },
  composerWrap: {
    borderTopWidth: 1,
    paddingHorizontal: 12,
    paddingTop: 8,
    paddingBottom: 10,
    gap: 8,
  },
  composerMeta: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
  },
  metaChip: {
    borderRadius: 12,
    paddingHorizontal: 10,
    paddingVertical: 4,
  },
  metaChipText: { fontSize: 12, fontWeight: '600' },
  modelTag: { fontSize: 12, maxWidth: '50%' },
  composerRow: { flexDirection: 'row', alignItems: 'flex-end', gap: 8 },
  input: {
    flex: 1,
    minHeight: 44,
    maxHeight: 110,
    borderWidth: 1,
    borderRadius: 20,
    paddingHorizontal: 14,
    paddingVertical: 10,
    fontSize: 15,
  },
  sendBtn: {
    minWidth: 64,
    height: 44,
    borderRadius: 22,
    paddingHorizontal: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  primaryBtnText: { color: '#ffffff', fontSize: 14, fontWeight: '700' },
  dimmed: { opacity: 0.45 },
});
