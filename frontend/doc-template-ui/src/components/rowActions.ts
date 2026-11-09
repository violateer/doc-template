import { h } from 'vue';
import { NButton } from 'naive-ui';

export type ActionTone = 'primary' | 'info' | 'success' | 'warning' | 'error';

export interface RowAction {
  label: string;
  tone: ActionTone;
  disabled?: boolean;
  onClick: () => void;
}

/**
 * 渲染一组统一风格的行内操作按钮：彩色文字按钮，中间用细竖线分隔，
 * 表格再密也不会糊成一片黑字。
 *
 * 这里刻意用普通 flex 容器而不用 `NSpace`：`NSpace` 会自带一层包裹元素和逐项外边距，
 * 结果整组比列还宽，在固定列里被裁掉。这里的间距全部来自 `.row-actions` 里的
 * `gap` 一个值，渲染宽度可预期。
 */
export function rowActions(actions: RowAction[]) {
  const nodes: any[] = [];
  actions.forEach((action, index) => {
    if (index > 0) {
      nodes.push(h('span', { class: 'row-actions__sep' }));
    }
    nodes.push(
      h(
        NButton,
        {
          size: 'small',
          text: true,
          type: action.tone,
          disabled: action.disabled,
          onClick: action.onClick
        },
        { default: () => action.label }
      )
    );
  });
  return h('div', { class: 'row-actions' }, nodes);
}
