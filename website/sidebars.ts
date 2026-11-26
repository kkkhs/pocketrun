import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  tutorialSidebar: [
    'intro',
    'architecture',
    {
      type: 'category',
      label: '第一部分 · Android 权限与屏幕控制',
      collapsed: false,
      items: [
        'chapter-1',
        'chapter-2',
        'chapter-3',
        'chapter-4',
        'chapter-5',
      ],
    },
    {
      type: 'category',
      label: '第二部分 · 在 App 里跑起 Linux 与终端',
      collapsed: false,
      items: [
        'chapter-6',
        'chapter-7',
        'chapter-8',
      ],
    },
    {
      type: 'category',
      label: '第三部分 · 工具宿主与 AI Agent 循环',
      collapsed: false,
      items: [
        'chapter-9',
        'chapter-10',
        'chapter-11',
        'chapter-12',
        'chapter-13',
      ],
    },
    {
      type: 'category',
      label: '第四部分 · 走向真正的产品助手',
      collapsed: false,
      items: [
        'chapter-14',
      ],
    },
    {
      type: 'category',
      label: '参考与工程档案',
      collapsed: false,
      items: [
        'chapter-0',
        'ditto-parity',
        'verification-records',
      ],
    },
  ],
};

export default sidebars;
