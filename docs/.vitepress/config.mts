import { defineConfig } from 'vitepress'

export default defineConfig({
  lang: 'zh-CN',
  // GitHub Pages 的项目站点挂在 /doc-template/ 下，资源路径必须带这个前缀，否则整站 404。
  // 本地 dev 不带这个环境变量，仍然跑在 / 下，不用改配置来回切。
  base: process.env.DOCS_BASE || '/',
  title: 'Vio Doc Template',
  titleTemplate: ':title · Vio Doc Template',
  description: 'Spring Boot 3 / Java 17 的文档模板引擎 Starter：模板版本、数据块取数、DOCX/XLSX 渲染、内置 PDF 导出、可插拔认证',
  cleanUrls: true,
  lastUpdated: true,

  // 示例模板的下载链接指向 docs/public/demo/ 下的 .docx / .xlsx / .sql。
  // 这些是「公共静态资源」，不是 markdown 页面，VitePress 的死链检查解析不到
  // （它按 docs/demo/... 找，而不是 docs/public/demo/...），一律放行。
  ignoreDeadLinks: [/^\/demo\//],

  // 文档里大量出现 {{#each}} / ${key} 这类模板标记。
  // 不能改 Vue 的插值分隔符（会影响默认主题自己的组件），
  // 所以在 markdown-it 渲染阶段给代码块和行内代码补上 v-pre，让标记原样输出。
  markdown: {
    config: (md) => {
      // 行内代码：<code v-pre>{{...}}</code>
      md.renderer.rules.code_inline = (tokens, idx) =>
        `<code v-pre>${md.utils.escapeHtml(tokens[idx].content)}</code>`

      // 代码块：VitePress 会给带语言标记的块加 v-pre，无语言的块不会，这里统一补一次
      const fence = md.renderer.rules.fence
      if (fence) {
        md.renderer.rules.fence = (tokens, idx, options, env, self) => {
          const html = fence(tokens, idx, options, env, self)
          return html.replace(/<pre(?![^>]*\bv-pre\b)/, '<pre v-pre')
        }
      }
    }
  },

  head: [
    ['meta', { name: 'theme-color', content: '#2f6fed' }],
    ['meta', { name: 'viewport', content: 'width=device-width,initial-scale=1' }]
  ],
  themeConfig: {
    nav: [
      { text: '指南', link: '/guide/quick-start', activeMatch: '/guide/' },
      { text: '参考', link: '/reference/api', activeMatch: '/reference/' },
      { text: '集成', link: '/guide/integration' }
    ],

    sidebar: {
      '/guide/': [
        {
          text: '开始',
          items: [
            { text: '快速开始', link: '/guide/quick-start' },
            { text: '工程架构', link: '/guide/architecture' },
            { text: '示例模板', link: '/guide/examples' }
          ]
        },
        {
          text: '核心能力',
          items: [
            { text: '模板标记写法', link: '/guide/template-syntax' },
            { text: '数据块取数', link: '/guide/data-blocks' },
            { text: '字段值转换与字典', link: '/guide/converters' },
            { text: '生成与记录', link: '/guide/generate' },
            { text: 'PDF 导出', link: '/guide/pdf' }
          ]
        },
        {
          text: '接入',
          items: [
            { text: '认证接入', link: '/guide/auth' },
            { text: '数据源接入', link: '/guide/datasource' },
            { text: '文件存储', link: '/guide/storage' },
            { text: '内嵌管理界面', link: '/guide/ui' },
            { text: '宿主集成', link: '/guide/integration' }
          ]
        },
        {
          text: '参考',
          items: [
            { text: 'REST API', link: '/reference/api' },
            { text: '配置项', link: '/reference/config' },
            { text: '边界与取舍', link: '/reference/limits' }
          ]
        }
      ],

      '/reference/': [
        {
          text: '参考',
          items: [
            { text: 'REST API', link: '/reference/api' },
            { text: '配置项', link: '/reference/config' },
            { text: '边界与取舍', link: '/reference/limits' }
          ]
        },
        {
          text: '相关指南',
          items: [
            { text: '快速开始', link: '/guide/quick-start' },
            { text: '认证接入', link: '/guide/auth' },
            { text: '数据块取数', link: '/guide/data-blocks' }
          ]
        }
      ]
    },

    search: {
      provider: 'local',
      options: {
        translations: {
          button: { buttonText: '搜索文档', buttonAriaLabel: '搜索文档' },
          modal: {
            noResultsText: '没有找到结果',
            resetButtonTitle: '清空',
            footer: { selectText: '选择', navigateText: '切换', closeText: '关闭' }
          }
        }
      }
    },

    outline: { level: [2, 3], label: '本页目录' },
    docFooter: { prev: '上一页', next: '下一页' },
    returnToTopLabel: '回到顶部',
    sidebarMenuLabel: '菜单',
    darkModeSwitchLabel: '主题',
    lastUpdatedText: '最后更新',
    footer: {
      message: '发布物仅包含 doc-template-core 与 doc-template-spring-boot-starter',
      copyright: 'Vio Doc Template · 内部工程文档'
    }
  }
})
