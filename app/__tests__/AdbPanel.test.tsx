import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import AdbPanel from '../AdbPanel';
import NativeAdb from '../specs/NativePocketRunAdb';

jest.mock('../specs/NativePocketRunAdb', () => ({
  __esModule: true,
  default: {
    pair: jest.fn(),
    connect: jest.fn(),
    probe: jest.fn(),
    disconnect: jest.fn(),
    getStatus: jest.fn(),
  },
}));
const adb = NativeAdb as jest.Mocked<NonNullable<typeof NativeAdb>>;
let screen: ReactTestRenderer.ReactTestRenderer;
const control = (label: string) =>
  screen.root.find(
    n =>
      n.props.accessibilityLabel === label &&
      typeof n.props.onPress === 'function',
  );
const input = (label: string) =>
  screen.root.find(
    n =>
      n.props.accessibilityLabel === label &&
      typeof n.props.onChangeText === 'function',
  );

beforeEach(async () => {
  jest.resetAllMocks();
  adb.getStatus.mockResolvedValue({
    connected: false,
    busy: false,
    port: 0,
    uid: -1,
  });
  await ReactTestRenderer.act(async () => {
    screen = ReactTestRenderer.create(
      <AdbPanel colors={{ text: '#111', muted: '#555' }} />,
    );
  });
});
afterEach(async () => {
  await ReactTestRenderer.act(() => screen.unmount());
});

test('keeps pairing and connection ports separate, and clears the pairing code', async () => {
  adb.pair.mockResolvedValueOnce(undefined);
  await ReactTestRenderer.act(() => {
    input('配对端口').props.onChangeText('37001');
    input('六位配对码').props.onChangeText('123456');
    input('连接端口').props.onChangeText('38001');
  });
  await ReactTestRenderer.act(async () => {
    await control('配对').props.onPress();
  });
  expect(adb.pair).toHaveBeenCalledWith(37001, '123456');
  expect(input('六位配对码').props.value).toBe('');
  expect(JSON.stringify(screen.toJSON())).toContain('配对完成');
  adb.connect.mockResolvedValueOnce(2000);
  await ReactTestRenderer.act(async () => {
    await control('连接').props.onPress();
  });
  expect(adb.connect).toHaveBeenCalledWith(38001);
  expect(JSON.stringify(screen.toJSON())).toContain('UID：2000');
});

test('cancellation ignores a late pairing result', async () => {
  let finish!: () => void;
  adb.pair.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        finish = resolve;
      }),
  );
  adb.disconnect.mockResolvedValueOnce(undefined);
  await ReactTestRenderer.act(() => {
    control('配对').props.onPress();
  });
  expect(control('连接').props.disabled).toBe(true);
  await ReactTestRenderer.act(async () => {
    await control('取消 / 断开').props.onPress();
  });
  await ReactTestRenderer.act(async () => finish());
  expect(JSON.stringify(screen.toJSON())).toContain('连接已关闭');
  expect(JSON.stringify(screen.toJSON())).not.toContain('配对完成');
});

test('shows native validation errors without logging or retaining a pairing code', async () => {
  adb.pair.mockRejectedValueOnce(new Error('请输入系统显示的六位配对码。'));
  await ReactTestRenderer.act(() =>
    input('六位配对码').props.onChangeText('123'),
  );
  await ReactTestRenderer.act(async () => {
    await control('配对').props.onPress();
  });
  expect(JSON.stringify(screen.toJSON())).toContain(
    '请输入系统显示的六位配对码',
  );
  expect(input('六位配对码').props.value).toBe('');
  expect(control('配对').props.disabled).toBe(false);
});
