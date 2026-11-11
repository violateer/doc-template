package io.github.violateer.doctemplate.starter.annotate;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 行级改动时合并区的搬移。
 *
 * <p>删掉合并区里的行会让它缩成一格，而单格合并区在 POI 里是非法的
 * （{@code Merged region A8 must contain 2 or more cells}）。这里锁住三种情况：
 * 缩成单格要消失、还剩多行要保留、整行合并随行一起消失。</p>
 */
class DocTemplateMergedRegionTest {

    @Test
    @DisplayName("删掉纵向合并区的最后一行后，合并区消失而不是变成单格")
    void dropsRegionThatWouldBecomeASingleCell() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("占位符");
            sheet.createRow(7).createCell(0).setCellValue("a");
            sheet.createRow(8).createCell(0).setCellValue("b");
            sheet.addMergedRegion(new CellRangeAddress(7, 8, 0, 0));

            DocTemplateAnnotator.shiftMerges(sheet, 8, -1);

            assertEquals(0, sheet.getNumMergedRegions(),
                    "A8:A9 删掉第 9 行只剩 A8，单格合并区必须去掉");
        }
    }

    @Test
    @DisplayName("合并区还剩多行时照常保留，只是收缩一行")
    void keepsRegionWithMoreThanOneRow() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("占位符");
            for (int row = 5; row <= 8; row++) sheet.createRow(row).createCell(0).setCellValue("x");
            sheet.addMergedRegion(new CellRangeAddress(5, 8, 0, 0));

            DocTemplateAnnotator.shiftMerges(sheet, 8, -1);

            assertEquals(1, sheet.getNumMergedRegions());
            CellRangeAddress region = sheet.getMergedRegion(0);
            assertEquals(5, region.getFirstRow());
            assertEquals(7, region.getLastRow(), "删掉最后一行后合并区收缩一行");
        }
    }

    @Test
    @DisplayName("整行合并的那一行被删掉时，合并区一起消失")
    void dropsHorizontalRegionWhenItsRowIsRemoved() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("占位符");
            sheet.createRow(8).createCell(0).setCellValue("a");
            sheet.addMergedRegion(new CellRangeAddress(8, 8, 0, 1));

            DocTemplateAnnotator.shiftMerges(sheet, 8, -1);

            assertEquals(0, sheet.getNumMergedRegions(), "这一行没了，横向合并区也不该留下");
        }
    }

    @Test
    @DisplayName("插行时合并区整体下移，不丢也不缩")
    void movesRegionDownWhenARowIsInserted() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("占位符");
            for (int row = 5; row <= 6; row++) sheet.createRow(row).createCell(0).setCellValue("x");
            sheet.addMergedRegion(new CellRangeAddress(5, 6, 0, 0));

            DocTemplateAnnotator.shiftMerges(sheet, 5, 1);

            assertEquals(1, sheet.getNumMergedRegions());
            CellRangeAddress region = sheet.getMergedRegion(0);
            assertEquals(6, region.getFirstRow());
            assertEquals(7, region.getLastRow());
        }
    }
}
