import type { GlobalThemeOverrides } from 'naive-ui';

/** Shared visual tokens so every page looks consistent. */
export const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#2f6fed',
    primaryColorHover: '#4b84f2',
    primaryColorPressed: '#245ccd',
    primaryColorSuppl: '#4b84f2',
    infoColor: '#0ea5e9',
    infoColorHover: '#38bdf8',
    infoColorPressed: '#0284c7',
    infoColorSuppl: '#38bdf8',
    successColor: '#18a058',
    warningColor: '#f0a020',
    errorColor: '#e5484d',
    borderRadius: '10px',
    borderRadiusSmall: '8px',
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif, 'Apple Color Emoji', 'Segoe UI Emoji', 'Segoe UI Symbol', 'Noto Color Emoji'",
    textColorBase: '#1f2733',
    textColor2: '#4b5563',
    textColor3: '#8b95a5',
    borderColor: '#e6eaf2',
    dividerColor: '#eef1f7',
    bodyColor: '#f4f6fb',
    cardColor: '#ffffff',
    popoverColor: '#ffffff',
    modalColor: '#ffffff',
    tableHeaderColor: '#f6f8fc'
  },
  Card: {
    borderRadius: '16px',
    paddingMedium: '18px 22px',
    titleFontSizeMedium: '16px',
    titleFontWeight: '600',
    borderColor: '#eef1f7'
  },
  DataTable: {
    thColor: '#f6f8fc',
    thTextColor: '#5b6472',
    thFontWeight: '600',
    thPaddingMedium: '11px 14px',
    tdPaddingMedium: '12px 14px',
    borderRadius: '12px',
    borderColor: '#eef1f7',
    tdColorHover: '#f8faff'
  },
  Button: {
    borderRadiusMedium: '9px',
    borderRadiusSmall: '8px',
    fontWeight: '500'
  },
  Input: { borderRadius: '9px' },
  // 勾选框默认吃 common.borderRadius（10px），在 14px 的方框上看着像个圆点；
  // 表格里是「选中哪些行」的语义，方一点更好认
  Checkbox: { borderRadius: '3px' },
  Tag: { borderRadius: '7px' },
  Modal: { borderRadius: '16px' },
  Tabs: { tabFontWeightActive: '600', tabFontSizeMedium: '14px' },
  Descriptions: { thColor: '#f8faff' },
  Drawer: { borderRadius: '16px' },
  Layout: { siderColor: '#ffffff', headerColor: '#ffffff' }
};

/** Status → tag styling shared by template and record tables. */
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
