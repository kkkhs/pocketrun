import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import ChatScreen, { type ThemeColors } from '../ChatScreen';
import NativeAdb from '../specs/NativePocketRunAdb';
import NativeTaskHost, {
  type ChatRuntimeState,
} from '../specs/NativePocketRunTaskHost';

jest.mock('../specs/NativePocketRunAdb', () => ({
  __esModule: true,
  default: {
    captureScreenshot: jest.fn(),
  },
}));

jest.mock('../specs/NativePocketRunTaskHost', () => ({
  __esModule: true,
  default: {
    listConversations: jest.fn(),
    getConversation: jest.fn(),
    createConversation: jest.fn(),
    deleteConversation: jest.fn(),
    sendChatMessage: jest.fn(),
    getChatRuntimeState: jest.fn(),
    cancelChatTurn: jest.fn(),
    getModelConfiguration: jest.fn(),
    getHostReadiness: jest.fn(),
    getMemorySnapshot: jest.fn(),
    decideFromAppUi: jest.fn(),
  },
}));

const mockHost = NativeTaskHost as jest.Mocked<NonNullable<typeof NativeTaskHost>>;
const mockAdb = NativeAdb as jest.Mocked<NonNullable<typeof NativeAdb>>;

const colors: ThemeColors = {
  background: '#ffffff',
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

const idleRuntime: ChatRuntimeState = {
  phase: 'idle',
  conversationId: null,
  taskId: null,
  streamingText: '',
  pendingNonce: null,
  pendingHash: null,
  pendingTool: null,
  pendingTarget: null,
  pendingArgs: null,
  activeToolsJson: '[]',
  rounds: 0,
  outputTokens: 0,
  code: null,
  errorMessage: null,
};

beforeEach(() => {
  jest.useFakeTimers();
  jest.clearAllMocks();
  mockHost.listConversations.mockResolvedValue({ conversationsJson: '[]' });
  mockHost.getModelConfiguration.mockResolvedValue({
    endpoint: 'https://api.anthropic.com',
    model: 'claude-opus-5-5',
    hasCredential: true,
  });
  mockHost.getHostReadiness.mockResolvedValue({
    packageUid: 10150,
    linuxReady: true,
    rootfsGeneration: 'v1',
    displayReady: false,
    displayEpoch: null,
    displayId: null,
  });
  mockHost.getMemorySnapshot.mockResolvedValue({
    revision: 'r_00000000000000000000000000000001',
    notesJson: JSON.stringify([{ id: 'note_00000000000000000000000000000001', text: '简短回答' }]),
    maxNotes: 8,
    maxNoteBytes: 256,
    maxSelected: 4,
    maxSelectedBytes: 2048,
  });
  mockHost.getChatRuntimeState.mockResolvedValue(idleRuntime);
});

afterEach(() => {
  jest.useRealTimers();
});

test('shows setup banner when API credential is missing and opens settings on tap', async () => {
  mockHost.getModelConfiguration.mockResolvedValueOnce({
    endpoint: 'https://api.anthropic.com',
    model: 'claude-opus-5-5',
    hasCredential: false,
  });
  const onOpenSettings = jest.fn();
  let screen!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    screen = ReactTestRenderer.create(
      <ChatScreen colors={colors} onOpenSettings={onOpenSettings} />,
    );
  });
  expect(JSON.stringify(screen.toJSON())).toContain('先配置模型，再开始对话');
  const btn = screen.root.find(node => node.props.accessibilityLabel === '前往模型设置');
  await ReactTestRenderer.act(async () => {
    btn.props.onPress();
  });
  expect(onOpenSettings).toHaveBeenCalledTimes(1);
  await ReactTestRenderer.act(async () => screen.unmount());
});

test('sends user prompt with optional memory toggle and renders inline streaming and tool approval card', async () => {
  const convId = 'conv_00000000000000000000000000000001';
  const waitingRuntime: ChatRuntimeState = {
    ...idleRuntime,
    phase: 'waiting_approval',
    conversationId: convId,
    taskId: 'task-1',
    pendingNonce: '1_nonce123456789012',
    pendingHash: 'abcdef',
    pendingTool: 'workspace.write_file',
    pendingTarget: '写入工作区文件 summary.md（24 字节） · UID 10150',
    pendingArgs: 'path=summary.md · content=items=2',
    activeToolsJson: JSON.stringify([
      { tool: 'workspace.list_files', status: 'succeeded', summary: '共 1 个文件' },
    ]),
  };

  mockHost.sendChatMessage.mockResolvedValueOnce(waitingRuntime);
  mockHost.listConversations.mockResolvedValue({
    conversationsJson: JSON.stringify([
      {
        id: convId,
        title: '帮我写一份摘要',
        createdAtMillis: 1000,
        updatedAtMillis: 1000,
        messageCount: 1,
        lastPreview: '帮我写一份摘要',
      },
    ]),
  });
  mockHost.getConversation.mockResolvedValue({
    id: convId,
    title: '帮我写一份摘要',
    createdAtMillis: 1000,
    updatedAtMillis: 1000,
    messagesJson: JSON.stringify([
      {
        id: 'msg_00000000000000000000000000000001',
        role: 'user',
        text: '帮我写一份摘要',
        timestampMillis: 1000,
        tools: [],
        outputTokens: 0,
      },
    ]),
  });
  mockHost.getChatRuntimeState.mockResolvedValueOnce(idleRuntime);
  mockHost.getChatRuntimeState.mockResolvedValue(waitingRuntime);
  mockHost.decideFromAppUi.mockResolvedValue({} as never);

  let screen!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    screen = ReactTestRenderer.create(
      <ChatScreen colors={colors} onOpenSettings={jest.fn()} />,
    );
  });

  const memSwitch = screen.root.find(
    node => node.props.accessibilityLabel === '切换是否携带长期记忆',
  );
  await ReactTestRenderer.act(async () => {
    memSwitch.props.onPress();
  });

  const input = screen.root.find(node => node.props.accessibilityLabel === '对话输入框');
  await ReactTestRenderer.act(async () => {
    input.props.onChangeText('帮我写一份摘要');
  });

  const sendBtn = screen.root.find(node => node.props.accessibilityLabel === '发送消息');
  await ReactTestRenderer.act(async () => {
    await sendBtn.props.onPress();
  });

  expect(mockHost.sendChatMessage).toHaveBeenCalledWith(convId, '帮我写一份摘要', true);
  const rendered = JSON.stringify(screen.toJSON());
  expect(rendered).toContain('需要你在手机上确认此工具调用');
  expect(rendered).toContain('写入工作区文件');

  const approveBtn = screen.root.find(node => node.props.accessibilityLabel === '允许工具执行');
  await ReactTestRenderer.act(async () => {
    await approveBtn.props.onPress();
  });
  expect(mockHost.decideFromAppUi).toHaveBeenCalledWith('1_nonce123456789012', 'approve');
  await ReactTestRenderer.act(async () => screen.unmount());
});

test('new chat button clears current thread and creates a fresh conversation on send', async () => {
  const newConvId = 'conv_00000000000000000000000000000002';
  mockHost.sendChatMessage.mockResolvedValueOnce({
    ...idleRuntime,
    phase: 'streaming',
    conversationId: newConvId,
    streamingText: '你好！',
  });
  mockHost.listConversations
    .mockResolvedValueOnce({ conversationsJson: '[]' })
    .mockResolvedValueOnce({
      conversationsJson: JSON.stringify([
        {
          id: newConvId,
          title: '你好',
          createdAtMillis: 2000,
          updatedAtMillis: 2000,
          messageCount: 1,
          lastPreview: '你好',
        },
      ]),
    });
  mockHost.getConversation.mockResolvedValue({
    id: newConvId,
    title: '你好',
    createdAtMillis: 2000,
    updatedAtMillis: 2000,
    messagesJson: JSON.stringify([
      {
        id: 'msg_00000000000000000000000000000002',
        role: 'user',
        text: '你好',
        timestampMillis: 2000,
        tools: [],
        outputTokens: 0,
      },
    ]),
  });

  let screen!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    screen = ReactTestRenderer.create(
      <ChatScreen colors={colors} onOpenSettings={jest.fn()} />,
    );
  });

  const input = screen.root.find(node => node.props.accessibilityLabel === '对话输入框');
  await ReactTestRenderer.act(async () => {
    input.props.onChangeText('你好');
  });
  const sendBtn = screen.root.find(node => node.props.accessibilityLabel === '发送消息');
  await ReactTestRenderer.act(async () => {
    await sendBtn.props.onPress();
  });

  expect(mockHost.sendChatMessage).toHaveBeenCalledWith('', '你好', false);
  await ReactTestRenderer.act(async () => screen.unmount());
});

test('renders virtual display preview bar and refreshes live frame on demand', async () => {
  mockHost.getHostReadiness.mockResolvedValueOnce({
    packageUid: 10150,
    linuxReady: true,
    rootfsGeneration: 'v1',
    displayReady: true,
    displayEpoch: '5',
    displayId: 2,
  });
  mockAdb.captureScreenshot.mockResolvedValueOnce({
    displayId: 2,
    width: 720,
    height: 1280,
    frameTimestampNanos: '12345',
    uri: 'file:///cache/vd-live.jpg',
  });

  let screen!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    screen = ReactTestRenderer.create(
      <ChatScreen colors={colors} onOpenSettings={jest.fn()} onOpenLab={jest.fn()} />,
    );
  });

  const treeJson = JSON.stringify(screen.toJSON());
  expect(treeJson).toContain('● 虚拟屏 #');
  expect(treeJson).toContain('已就绪（后台操控第三方 App）');
  const refreshBtn = screen.root.find(node => node.props.accessibilityLabel === '刷新虚拟屏画面');
  await ReactTestRenderer.act(async () => {
    await refreshBtn.props.onPress();
  });

  expect(mockAdb.captureScreenshot).toHaveBeenCalledWith(2);
  const preview = screen.root.find(node => node.props.accessibilityLabel === '虚拟屏实时预览画面');
  expect(preview.props.source).toEqual({ uri: 'file:///cache/vd-live.jpg' });
  await ReactTestRenderer.act(async () => screen.unmount());
});
