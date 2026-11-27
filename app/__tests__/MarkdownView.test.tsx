import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import MarkdownView from '../MarkdownView';
import type { ThemeColors } from '../ChatScreen';

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

test('renders headings, bold, inline code, lists, quotes, and fenced code blocks', async () => {
  const sample = [
    '## 执行结果',
    '',
    '已完成 **items.csv** 统计，路径为 `/workspace/items.csv`。',
    '',
    '- 共 2 条记录',
    '> 所有写操作均经过手机批准',
    '',
    '```sh',
    'cat items.csv | wc -l',
    '```',
  ].join('\n');

  let tree!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    tree = ReactTestRenderer.create(<MarkdownView text={sample} colors={colors} />);
  });
  const json = JSON.stringify(tree.toJSON());
  expect(json).toContain('执行结果');
  expect(json).toContain('items.csv');
  expect(json).toContain('/workspace/items.csv');
  expect(json).toContain('cat items.csv | wc -l');
  expect(json).toContain('sh');
  await ReactTestRenderer.act(async () => tree.unmount());
});

test('handles unclosed code fence gracefully while streaming', async () => {
  let tree!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    tree = ReactTestRenderer.create(
      <MarkdownView text={'```kotlin\nval x = 1'} colors={colors} />,
    );
  });
  const json = JSON.stringify(tree.toJSON());
  expect(json).toContain('kotlin');
  expect(json).toContain('val x = 1');
  await ReactTestRenderer.act(async () => tree.unmount());
});
