package io.github.violateer.doctemplate.starter.annotate;

import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;

import java.util.List;

/**
 * DOCX 段落级的文本读写。
 *
 * <p>Word 经常把一个词拆到好几个 run 里（{@code 2026-} + {@code 0011}），所以按「整段文本 + 字符偏移」
 * 定位，再按 run 边界切开写回：只改文本真的变了的 run，被标记吃掉的 run 归并成空串清掉，
 * 没被覆盖的 run（制表符、换行、域代码）一个字节都不动。替换出来的文字沿用<b>起点所在 run</b> 的格式。</p>
 *
 * <p>与渲染器 {@code DocTemplateRenderer} 里的同名逻辑是同一套做法，但那边是 private 且服务于
 * 「占位符 → 值」，这里是「原文 → 标记」，方向相反，所以各留一份；合并成一份要动渲染器最核心的
 * 替换路径，风险大于收益（见内部仓 TODO）。</p>
 */
final class DocxRuns {

    private DocxRuns() {
    }

    /** 整段文本（只拼 {@code w:t}，不含制表符和换行）。 */
    static String text(XWPFParagraph paragraph) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : runs) sb.append(textOf(run));
        return sb.toString();
    }

    /**
     * 把 {@code [from, to)} 这段文字换成 {@code replacement}。
     *
     * @return 是否真的改了内容；段落没有 run 且替换为空时返回 {@code false}
     */
    static boolean replaceRange(XWPFParagraph paragraph, int from, int to, String replacement) {
        List<XWPFRun> runs = paragraph.getRuns();
        String old = text(paragraph);
        if (from < 0 || to > old.length() || from > to)
            throw new IllegalArgumentException("替换范围 [" + from + ", " + to + ") 超出了段落长度 " + old.length());
        if (runs == null || runs.isEmpty()) {
            if (replacement.isEmpty()) return false;
            paragraph.createRun().setText(replacement);
            return true;
        }
        int[] starts = starts(runs);
        int owner = owner(starts, from, old.length());
        String[] next = new String[runs.size()];
        boolean changed = false;
        for (int i = 0; i < runs.size(); i++) {
            StringBuilder sb = new StringBuilder();
            int start = starts[i];
            int end = starts[i + 1];
            if (start < from) sb.append(old, start, Math.min(end, from));
            if (i == owner) sb.append(replacement);
            if (end > to) sb.append(old, Math.max(start, to), end);
            next[i] = sb.toString();
            if (!next[i].equals(textOf(runs.get(i)))) changed = true;
        }
        if (!changed) return false;
        for (int i = 0; i < runs.size(); i++) setRunText(runs.get(i), next[i]);
        return true;
    }

    /** 在段首插入文字，沿用第一个 run 的格式。 */
    static void prepend(XWPFParagraph paragraph, String text) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.isEmpty()) {
            paragraph.createRun().setText(text);
            return;
        }
        XWPFRun first = runs.get(0);
        setRunText(first, text + textOf(first));
    }

    /** 在段尾追加文字，沿用最后一个 run 的格式。 */
    static void append(XWPFParagraph paragraph, String text) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.isEmpty()) {
            paragraph.createRun().setText(text);
            return;
        }
        XWPFRun last = runs.get(runs.size() - 1);
        setRunText(last, textOf(last) + text);
    }

    /** 每个 run 的起始字符偏移；末尾多一个元素是整段长度。 */
    private static int[] starts(List<XWPFRun> runs) {
        int[] starts = new int[runs.size() + 1];
        for (int i = 0; i < runs.size(); i++) starts[i + 1] = starts[i] + textOf(runs.get(i)).length();
        return starts;
    }

    /** 偏移 {@code offset} 落在第几个 run；落在段尾时归最后一个 run。 */
    private static int owner(int[] starts, int offset, int length) {
        int last = starts.length - 2;
        if (offset >= length) return last;
        int run = 0;
        while (run < last && starts[run + 1] <= offset) run++;
        return run;
    }

    private static String textOf(XWPFRun run) {
        String text = run.text();
        return text == null ? "" : text;
    }

    /**
     * 整体换掉一个 run 的文字，只留一个 {@code w:t}。
     *
     * <p>POI 的 {@code setText(value, 0)} 只改第 0 个 {@code w:t}，一个 run 里有多段文本时后面的会残留，
     * 看起来像重影，所以多出来的手动删掉。</p>
     */
    static void setRunText(XWPFRun run, String text) {
        run.setText(text, 0);
        CTR ctr = run.getCTR();
        for (int i = ctr.sizeOfTArray() - 1; i >= 1; i--) ctr.removeT(i);
    }
}
