import type { GlobalThemeOverrides } from 'naive-ui';

/**
 * 全站共用的视觉变量，保证各页面视觉一致。
 *
 * 视觉方向：企业简洁（Corporate Clean）。浅石板底 + 单一企业蓝 + 白卡片配发丝边框，
 * 层级由 1px 边框与两级阴影承担，不使用渐变与重投影。
 */
export const themeOverrides: GlobalThemeOverrides = {
  common: {
    // 全站只有一族蓝：企业蓝作主色，深一档作 hover，再深一档作按下
    primaryColor: '#1e40af',
    primaryColorHover: '#1d4ed8',
    primaryColorPressed: '#1e3a8a',
    primaryColorSuppl: '#1d4ed8',
    // 信息色取行动蓝，只用于焦点环、选中态与数据高亮
    infoColor: '#3b82f6',
    infoColorHover: '#60a5fa',
    infoColorPressed: '#2563eb',
    infoColorSuppl: '#60a5fa',
    successColor: '#10b981',
    successColorHover: '#34d399',
    successColorPressed: '#059669',
    successColorSuppl: '#34d399',
    warningColor: '#f59e0b',
    warningColorHover: '#fbbf24',
    warningColorPressed: '#d97706',
    warningColorSuppl: '#fbbf24',
    errorColor: '#dc2626',
    errorColorHover: '#ef4444',
    errorColorPressed: '#b91c1c',
    errorColorSuppl: '#ef4444',
    borderRadius: '8px',
    borderRadiusSmall: '6px',
    fontFamily:
      "Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif, 'Apple Color Emoji', 'Segoe UI Emoji', 'Segoe UI Symbol', 'Noto Color Emoji'",
    fontWeightStrong: '600',
    textColorBase: '#0f172a',
    textColor1: '#0f172a',
    textColor2: '#475569',
    textColor3: '#64748b',
    borderColor: '#e5e7eb',
    dividerColor: '#eef2f6',
    bodyColor: '#f8fafc',
    cardColor: '#ffffff',
    popoverColor: '#ffffff',
    modalColor: '#ffffff',
    tableHeaderColor: '#f8fafc',
    // 只有两级阴影：静止与悬浮。更重的层级靠边框表达，不靠投影
    boxShadow1: '0 1px 2px rgba(15, 23, 42, 0.06)',
    boxShadow2: '0 4px 12px rgba(15, 23, 42, 0.08)',
    boxShadow3: '0 10px 28px -12px rgba(15, 23, 42, 0.18)'
  },
  Card: {
    borderRadius: '12px',
    paddingMedium: '20px 22px',
    titleFontSizeMedium: '16px',
    titleFontWeight: '600',
    borderColor: '#e5e7eb'
  },
  DataTable: {
    thColor: '#f8fafc',
    thTextColor: '#475569',
    thFontWeight: '600',
    thPaddingMedium: '11px 14px',
    tdPaddingMedium: '12px 14px',
    borderRadius: '12px',
    borderColor: '#e5e7eb',
    tdColorHover: '#f8fafc'
  },
  Button: {
    // 卡片 12px、控件 8px：两者相邻时圆角不打架
    borderRadiusMedium: '8px',
    borderRadiusSmall: '7px',
    borderRadiusTiny: '6px',
    fontWeight: '500'
  },
  Input: { borderRadius: '8px' },
  // 勾选框默认吃 common.borderRadius（8px），在 14px 的方框上看着像个圆点；
  // 表格里是「选中哪些行」的语义，方一点更好认
  Checkbox: { borderRadius: '3px' },
  // 状态标签按规范做成全圆角，与圆点配合，状态不单靠颜色区分
  Tag: { borderRadius: '999px' },
  Modal: { borderRadius: '12px' },
  Drawer: { borderRadius: '12px' },
  Tabs: { tabFontWeightActive: '600', tabFontSizeMedium: '14px' },
  Descriptions: { thColor: '#f8fafc' },
  Layout: { siderColor: '#ffffff', headerColor: '#ffffff' }
};

/** 状态 → 标签样式，模板表与生成记录表共用。 */
export function statusMeta(status: string): { type: 'success' | 'error' | 'warning' | 'default'; label: string } {
  switch (status) {
    case 'ENABLED':
      return { type: 'success', label: '启用' };
    case 'DISABLED':
      return { type: 'default', label: '停用' };
    case 'SUCCESS':
      return { type: 'success', label: '生成成功' };
    case 'FAILED':
      return { type: 'error', label: '生成失败' };
    case 'NOT_IMPLEMENTED':
      return { type: 'warning', label: '未实现' };
    default:
      return { type: 'default', label: status || '—' };
  }
}
