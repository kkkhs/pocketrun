import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
  title: 'PocketRun',
  tagline: '面向 React / TypeScript 开发者的 Android AI 助手实战教程',
  url: 'https://kkkhs.github.io',
  baseUrl: '/pocketrun/',
  organizationName: 'kkkhs',
  projectName: 'pocketrun',
  trailingSlash: true,
  onBrokenLinks: 'throw',
  onBrokenAnchors: 'throw',
  markdown: {
    hooks: {
      onBrokenMarkdownLinks: 'throw',
      onBrokenMarkdownImages: 'throw',
    },
  },
  i18n: {
    defaultLocale: 'zh-Hans',
    locales: ['zh-Hans'],
  },
  presets: [
    [
      'classic',
      {
        docs: {
          routeBasePath: '/',
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/kkkhs/pocketrun/edit/main/website/',
        },
        blog: false,
      } satisfies Preset.Options,
    ],
  ],
  themeConfig: {
    colorMode: {
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'PocketRun',
      items: [
        {type: 'doc', docId: 'intro', label: '导读', position: 'left'},
        {type: 'doc', docId: 'architecture', label: '系统全景', position: 'left'},
        {type: 'doc', docId: 'ditto-parity', label: '功能路线', position: 'left'},
        {type: 'doc', docId: 'chapter-0', label: '站点工程', position: 'left'},
        {
          href: 'https://github.com/kkkhs/pocketrun',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    prism: {
      additionalLanguages: ['java', 'kotlin', 'bash', 'cpp', 'json'],
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
