import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import WorkspaceFilesScreen from '../WorkspaceFilesScreen';
import type { ThemeColors } from '../ChatScreen';
import NativeTaskHost from '../specs/NativePocketRunTaskHost';

jest.mock('../specs/NativePocketRunTaskHost', () => ({
  __esModule: true,
  default: {
    listWorkspaceFiles: jest.fn(),
    readWorkspaceFileText: jest.fn(),
    writeWorkspaceFileText: jest.fn(),
    deleteWorkspaceFile: jest.fn(),
  },
}));

jest.mock('../specs/NativePocketRunWorkspace', () => ({
  __esModule: true,
  default: {
    prepareWorkspace: jest.fn(),
  },
}));

const mockHost = NativeTaskHost as jest.Mocked<NonNullable<typeof NativeTaskHost>>;

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

beforeEach(() => {
  jest.clearAllMocks();
});

test('lists files, opens editor, saves edits, and sends file prompt to chat', async () => {
  mockHost.listWorkspaceFiles.mockResolvedValue(
    JSON.stringify([{ path: 'items.csv', bytes: 28 }]),
  );
  mockHost.readWorkspaceFileText.mockResolvedValue({
    path: 'items.csv',
    content: 'name,quantity\napple,2\n',
    bytes: 22,
    sha256: 'a'.repeat(64),
  });
  mockHost.writeWorkspaceFileText.mockResolvedValue({
    path: 'items.csv',
    content: 'name,quantity\napple,5\n',
    bytes: 22,
    sha256: 'b'.repeat(64),
  });

  const onAskInChat = jest.fn();
  let screen!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    screen = ReactTestRenderer.create(
      <WorkspaceFilesScreen colors={colors} onAskInChat={onAskInChat} />,
    );
  });

  expect(JSON.stringify(screen.toJSON())).toContain('items.csv');
  const openBtn = screen.root.find(node => node.props.accessibilityLabel === '打开文件 items.csv');
  await ReactTestRenderer.act(async () => {
    await openBtn.props.onPress();
  });

  const editor = screen.root.find(node => node.props.accessibilityLabel === '文件正文编辑器');
  await ReactTestRenderer.act(async () => {
    editor.props.onChangeText('name,quantity\napple,5\n');
  });

  const saveBtn = screen.root.find(node => node.props.accessibilityLabel === '保存工作区文件');
  await ReactTestRenderer.act(async () => {
    await saveBtn.props.onPress();
  });
  expect(mockHost.writeWorkspaceFileText).toHaveBeenCalledWith(
    'items.csv',
    'name,quantity\napple,5\n',
  );

  const askBtn = screen.root.find(node => node.props.accessibilityLabel === '在对话中处理此文件');
  await ReactTestRenderer.act(async () => {
    askBtn.props.onPress();
  });
  expect(onAskInChat).toHaveBeenCalledWith(expect.stringContaining('items.csv'));
  await ReactTestRenderer.act(async () => screen.unmount());
});
