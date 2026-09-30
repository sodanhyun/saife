package io.saife.evidence.chunk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * PDF → 페이지별 텍스트. Inufleet의 Vision OCR 폴백은 가져오지 않는다 — 지침 PDF는 텍스트 PDF다.
 * 품질이 0.3 미만인 페이지(스캔본·폰트 깨짐)는 호출자가 버린다.
 */
@Slf4j
public final class PdfTextExtractor {
    private PdfTextExtractor() {}

    public static List<String> extract(byte[] pdf) {
        List<String> pages = new ArrayList<>();
        if (pdf == null || pdf.length == 0) return pages;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc);
                pages.add(normalize(text));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("[PDF] 텍스트 추출 실패 ({}바이트): {}", pdf.length, e.getMessage());
            return List.of();
        }
        return pages;
    }

    /** 한글·CJK·기본 라틴 비율. 깨진 폰트는 U+FFFD나 라틴 확장으로 나온다 */
    public static double quality(String text) {
        if (text == null || text.isBlank()) return 0;
        int good = 0, total = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            total++;
            if ((c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x4E00 && c <= 0x9FFF) || c < 0x7F
                    || (c >= 0x3130 && c <= 0x318F) || "·ㆍ※○△□▶→←↑↓°㎜㎝㎡㎥㎏".indexOf(c) >= 0) good++;
        }
        return total == 0 ? 0 : (double) good / total;
    }

    private static String normalize(String s) {
        return stripControlChars(s).replace("\r", "").replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * A4 hotfix H1 (ruling R33): 공단 KOSHA GUIDE PDF를 PDFBox로 뽑으면 본문에 NUL(0x00) 등
     * 제어문자가 잔류하는 경우가 있다. Postgres는 UTF8 텍스트에 0x00을 허용하지 않아
     * "invalid byte sequence for encoding "UTF8": 0x00"로 인덱스 배치 insert가 통째로 실패한다.
     * \n \t \r은 문단 구조를 담고 있으므로 보존하고, 그 외 제어문자만 제거한다.
     */
    static String stripControlChars(String s) {
        if (s == null) return null;
        return s.replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]", "");
    }
}
