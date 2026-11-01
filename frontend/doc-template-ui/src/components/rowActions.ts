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
 * Renders a consistent row action group: text buttons in distinct colours separated by a
 * thin vertical divider, so dense tables stay readable and never fall back to black text.
 *
 * A plain flex container is used instead of `NSpace` on purpose: `NSpace` injects its own
 * wrapper element plus per-item margins, which made the group wider than the column and got
 * clipped inside fixed columns. All spacing here comes from the single `gap` value in
 * `.row-actions`, so the rendered width stays predictable.
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
