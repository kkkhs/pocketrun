import { Fragment } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import type { ThemeColors } from './ChatScreen';

type Block =
  | { type: 'code'; language: string; content: string }
  | { type: 'heading'; level: 1 | 2 | 3; text: string }
  | { type: 'quote'; text: string }
  | { type: 'list'; marker: string; text: string }
  | { type: 'paragraph'; text: string };

type InlineToken =
  | { kind: 'text'; text: string }
  | { kind: 'bold'; text: string }
  | { kind: 'code'; text: string };

function parseBlocks(source: string): Block[] {
  const lines = source.replace(/\r\n/g, '\n').split('\n');
  const blocks: Block[] = [];
  let inCode = false;
  let codeLang = '';
  let codeLines: string[] = [];
  let paraLines: string[] = [];

  function flushParagraph() {
    if (paraLines.length === 0) return;
    blocks.push({ type: 'paragraph', text: paraLines.join('\n') });
    paraLines = [];
  }

  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed.startsWith('```')) {
      if (inCode) {
        blocks.push({ type: 'code', language: codeLang, content: codeLines.join('\n') });
        inCode = false;
        codeLang = '';
        codeLines = [];
      } else {
        flushParagraph();
        inCode = true;
        codeLang = trimmed.slice(3).trim();
        codeLines = [];
      }
      continue;
    }
    if (inCode) {
      codeLines.push(line);
      continue;
    }
    if (trimmed === '') {
      flushParagraph();
      continue;
    }
    const headingMatch = /^(#{1,3})\s+(.+)$/.exec(trimmed);
    if (headingMatch) {
      flushParagraph();
      blocks.push({
        type: 'heading',
        level: headingMatch[1].length as 1 | 2 | 3,
        text: headingMatch[2],
      });
      continue;
    }
    if (trimmed.startsWith('> ')) {
      flushParagraph();
      blocks.push({ type: 'quote', text: trimmed.slice(2) });
      continue;
    }
    const bulletMatch = /^([-*]|\d+\.)\s+(.+)$/.exec(trimmed);
    if (bulletMatch) {
      flushParagraph();
      blocks.push({
        type: 'list',
        marker: bulletMatch[1] === '-' || bulletMatch[1] === '*' ? '•' : bulletMatch[1],
        text: bulletMatch[2],
      });
      continue;
    }
    paraLines.push(line);
  }

  if (inCode) {
    blocks.push({ type: 'code', language: codeLang, content: codeLines.join('\n') });
  } else {
    flushParagraph();
  }
  return blocks;
}

function tokenizeInline(text: string): InlineToken[] {
  const tokens: InlineToken[] = [];
  const pattern = /(`[^`]+`|\*\*[^*]+\*\*)/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null = pattern.exec(text);
  while (match !== null) {
    if (match.index > lastIndex) {
      tokens.push({ kind: 'text', text: text.slice(lastIndex, match.index) });
    }
    const raw = match[0];
    if (raw.startsWith('`') && raw.endsWith('`')) {
      tokens.push({ kind: 'code', text: raw.slice(1, -1) });
    } else if (raw.startsWith('**') && raw.endsWith('**')) {
      tokens.push({ kind: 'bold', text: raw.slice(2, -2) });
    }
    lastIndex = match.index + raw.length;
    match = pattern.exec(text);
  }
  if (lastIndex < text.length) {
    tokens.push({ kind: 'text', text: text.slice(lastIndex) });
  }
  return tokens;
}

function InlineText({
  text,
  colors,
  style,
}: {
  text: string;
  colors: ThemeColors;
  style: object;
}) {
  const tokens = tokenizeInline(text);
  return (
    <Text selectable style={[style, { color: colors.text }]}>
      {tokens.map((token, idx) => {
        if (token.kind === 'bold') {
          return (
            <Text key={idx} style={styles.bold}>
              {token.text}
            </Text>
          );
        }
        if (token.kind === 'code') {
          return (
            <Text
              key={idx}
              style={[
                styles.inlineCode,
                { backgroundColor: colors.surfaceAlt, color: colors.accent },
              ]}
            >
              {token.text}
            </Text>
          );
        }
        return <Fragment key={idx}>{token.text}</Fragment>;
      })}
    </Text>
  );
}

export default function MarkdownView({
  text,
  colors,
}: {
  text: string;
  colors: ThemeColors;
}) {
  const blocks = parseBlocks(text);
  return (
    <View style={styles.root}>
      {blocks.map((block, index) => {
        if (block.type === 'code') {
          return (
            <View
              key={index}
              style={[
                styles.codeCard,
                { backgroundColor: colors.surfaceAlt, borderColor: colors.border },
              ]}
            >
              <View style={[styles.codeHeader, { borderBottomColor: colors.border }]}>
                <Text style={[styles.codeLang, { color: colors.muted }]}>
                  {block.language || 'code'}
                </Text>
              </View>
              <ScrollView horizontal showsHorizontalScrollIndicator={false}>
                <Text selectable style={[styles.codeBody, { color: colors.text }]}>
                  {block.content}
                </Text>
              </ScrollView>
            </View>
          );
        }
        if (block.type === 'heading') {
          const headingStyle =
            block.level === 1
              ? styles.h1
              : block.level === 2
                ? styles.h2
                : styles.h3;
          return <InlineText key={index} text={block.text} colors={colors} style={headingStyle} />;
        }
        if (block.type === 'quote') {
          return (
            <View
              key={index}
              style={[
                styles.quote,
                { borderLeftColor: colors.accent, backgroundColor: colors.surfaceAlt },
              ]}
            >
              <InlineText text={block.text} colors={colors} style={styles.quoteText} />
            </View>
          );
        }
        if (block.type === 'list') {
          return (
            <View key={index} style={styles.listRow}>
              <Text style={[styles.listMarker, { color: colors.accent }]}>{block.marker}</Text>
              <View style={styles.listBody}>
                <InlineText text={block.text} colors={colors} style={styles.paragraph} />
              </View>
            </View>
          );
        }
        return <InlineText key={index} text={block.text} colors={colors} style={styles.paragraph} />;
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { gap: 8 },
  paragraph: { fontSize: 15, lineHeight: 24 },
  h1: { fontSize: 20, lineHeight: 28, fontWeight: '800', marginTop: 4 },
  h2: { fontSize: 18, lineHeight: 26, fontWeight: '700', marginTop: 4 },
  h3: { fontSize: 16, lineHeight: 24, fontWeight: '700', marginTop: 2 },
  bold: { fontWeight: '700' },
  inlineCode: {
    fontFamily: 'monospace',
    fontSize: 13,
  },
  quote: {
    borderLeftWidth: 3,
    borderRadius: 6,
    paddingHorizontal: 10,
    paddingVertical: 6,
  },
  quoteText: { fontSize: 14, lineHeight: 22 },
  listRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 8 },
  listMarker: { fontSize: 15, lineHeight: 24, fontWeight: '700', minWidth: 16 },
  listBody: { flex: 1 },
  codeCard: {
    borderWidth: 1,
    borderRadius: 10,
    overflow: 'hidden',
  },
  codeHeader: {
    borderBottomWidth: 1,
    paddingHorizontal: 10,
    paddingVertical: 5,
  },
  codeLang: { fontSize: 11, fontWeight: '700', textTransform: 'uppercase' },
  codeBody: {
    fontFamily: 'monospace',
    fontSize: 13,
    lineHeight: 20,
    padding: 10,
  },
});
