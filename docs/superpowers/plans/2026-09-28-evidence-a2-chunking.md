# 근거 계층 A2 — 청킹·맥락 프리픽스·청크 빌더 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사례·지침 PDF·조문을 `evidence_chunk` 행으로 바꾸는 순수 함수들을 만든다. Inufleet의 오버랩·Parent-Child·Contextual Enrichment를 이식하되 parent는 임베딩하지 않는다.

**Architecture:** `io.saife.evidence.chunk` 패키지. `ChunkDraft`(아직 DB에 없는 청크)를 만드는 빌더 3개(`CaseChunkBuilder`, `LawChunkBuilder`, `GuideChunkBuilder`)와 보조 유틸(`ChunkOverlapUtil`, `PdfTextExtractor`, `ContextualEnricher`). 임베딩·저장은 A3의 `IndexBuilder`가 한다.

**Tech Stack:** Java 21 · PDFBox 3.0.5(신규 의존성 1개) · Spring AI `ChatClient`(enrichment) · JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-28-evidence-rag-and-connectivity-design.md` §4.1, §5.6 · 선행: A1 계획(Task 1의 엔티티, Task 5의 `LawArticle`)

## Global Constraints

- A1의 Global Constraints 전부 적용.
- 새 의존성은 `org.apache.pdfbox:pdfbox:3.0.5` 하나. `.claude/rules/external-library.md`에 추가 사유 한 줄을 기록한다.
- 청킹 상수는 `ChunkPolicy` 한 곳에 둔다(`CHILD_TARGET=1200`, `CHILD_MIN=300`, `CHILD_MAX=3000`, `OVERLAP_CHARS=200`, `PARENT_MAX_CHILDREN=5`, `COVER_MAX=1200`). 테스트가 이 상수를 참조한다.
- 모델 호출(`ContextualEnricher`)은 실패 시 원문을 그대로 돌려준다. 예외를 밖으로 내지 않는다. 데모 모드에서는 호출하지 않는다.
- 맥락 프리픽스 형식은 `[맥락 한 줄]\n원문` 하나뿐이다. HyDE(예상 질문) 프리픽스는 만들지 않는다.

## Review Focus

1. 지침 PDF 텍스트에 `\n\n`이 전혀 없는 경우(한 덩어리) — 3,000자 초과분이 `CHILD_TARGET` 단위로 잘려야 하고 마지막 조각이 `CHILD_MIN` 미만이면 앞 조각에 합쳐져야 한다. → Task 3 `GuideChunkBuilderTest.단일_덩어리_텍스트`.
2. 섹션 제목이 문서 첫 줄에 있고 본문이 없는 경우 parent가 child 0개로 만들어지면 안 된다. → Task 3 `GuideChunkBuilderTest.빈_섹션은_parent를_만들지_않는다`.
3. 오버랩 꼬리가 문장 경계를 못 찾을 때(200자 안에 마침표 없음) 200자 꼬리를 그대로 붙여야 한다. → Task 1 `ChunkOverlapUtilTest.문장_경계가_없으면_꼬리_전체`.
4. 조문에 항이 1개뿐이면 parent 없이 child 1개만 나와야 한다(Inufleet "child 1개 그룹은 parent 없음"). → Task 4 `LawChunkBuilderTest.항이_하나면_parent_없음`.
5. `ContextualEnricher`가 빈 문자열이나 대괄호가 포함된 답을 돌려주면 프리픽스를 붙이지 말고 원문을 유지해야 한다(`stripContextualPrefix`가 잘못된 위치에서 자르는 것을 막는다). → Task 5 `ContextualEnricherTest.대괄호가_있는_답은_버린다`.

---

### Task 1: `ChunkPolicy` + `ChunkDraft` + `ChunkOverlapUtil`

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/chunk/ChunkPolicy.java`
- Create: `backend/src/main/java/io/saife/evidence/chunk/ChunkDraft.java`
- Create: `backend/src/main/java/io/saife/evidence/chunk/ChunkOverlapUtil.java`
- Create: `backend/src/main/java/io/saife/evidence/EvidenceKind.java`
- Test: `backend/src/test/java/io/saife/evidence/chunk/ChunkOverlapUtilTest.java`

**Interfaces:**
- Produces:
  - `enum EvidenceKind { CASE_FATALITY, CASE_DISASTER, GUIDE, LAW, MSDS }` (+ `boolean isCase()`)
  - `record ChunkDraft(EvidenceKind kind, Long refId, String refKey, String chunkLevel, String parentRefKey, boolean searchable, String sectionTitle, String title, String text, Map<String,Object> metadata)` + `static ChunkDraft child(...)`, `static ChunkDraft parent(...)`, `ChunkDraft withText(String)`
  - `ChunkOverlapUtil.addOverlap(List<String> texts) → List<String>`, `ChunkOverlapUtil.tailForOverlap(String text) → String`

- [ ] **Step 1: 실패하는 테스트**

```java
package io.saife.evidence.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkOverlapUtilTest {

    @Test
    void 이전_청크_꼬리를_문장_경계에서_잘라_앞에_붙인다() {
        String prev = "가".repeat(300) + ". 마지막 문장입니다. 진짜 마지막이다";
        List<String> out = ChunkOverlapUtil.addOverlap(List.of(prev, "다음 청크"));
        assertThat(out.get(0)).isEqualTo(prev);
        assertThat(out.get(1)).startsWith("마지막 문장입니다. 진짜 마지막이다\n다음 청크");
    }

    @Test
    void 문장_경계가_없으면_꼬리_전체() {
        String prev = "가".repeat(500);
        List<String> out = ChunkOverlapUtil.addOverlap(List.of(prev, "다음"));
        assertThat(out.get(1)).isEqualTo("가".repeat(ChunkPolicy.OVERLAP_CHARS) + "\n다음");
    }

    @Test
    void 짧은_이전_청크는_오버랩_없음() {
        List<String> out = ChunkOverlapUtil.addOverlap(List.of("짧다", "다음"));
        assertThat(out.get(1)).isEqualTo("다음");
    }

    @Test
    void 청크가_하나면_그대로() {
        assertThat(ChunkOverlapUtil.addOverlap(List.of("하나"))).containsExactly("하나");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.chunk.ChunkOverlapUtilTest`
Expected: FAIL (클래스 없음)

- [ ] **Step 3: 구현**

`EvidenceKind.java`:
```java
package io.saife.evidence;

/** 근거 종류. 카드 레이아웃과 검색 필터가 이 값으로 갈린다 */
public enum EvidenceKind {
    CASE_FATALITY, CASE_DISASTER, GUIDE, LAW, MSDS;

    public boolean isCase() { return this == CASE_FATALITY || this == CASE_DISASTER; }
}
```

`ChunkPolicy.java`:
```java
package io.saife.evidence.chunk;

/** 청킹 상수. 규칙 문서와 코드가 어긋나지 않게 여기 한 곳에만 둔다 */
public final class ChunkPolicy {
    private ChunkPolicy() {}
    public static final int CHILD_TARGET = 1200;      // 지침 child 목표 길이(자)
    public static final int CHILD_MIN = 300;          // 이보다 짧은 조각은 앞 조각에 합친다
    public static final int CHILD_MAX = 3000;         // 초과분은 CHILD_TARGET 단위로 재분할
    public static final int OVERLAP_CHARS = 200;      // 이전 청크 꼬리를 문장 경계에서 잘라 붙인다
    public static final int PARENT_MAX_CHILDREN = 5;  // 섹션 제목이 없을 때 parent 하나에 묶는 child 수
    public static final int COVER_MAX = 1200;         // 표지 청크(제목+1~2페이지) 최대 길이
    public static final String LEVEL_CHILD = "child";
    public static final String LEVEL_PARENT = "parent";
}
```

`ChunkDraft.java`:
```java
package io.saife.evidence.chunk;

import io.saife.evidence.EvidenceKind;
import java.util.Map;

/** DB에 넣기 전의 청크. refKey가 시드 재적재 시 매칭 키다. parent는 searchable=false */
public record ChunkDraft(EvidenceKind kind, Long refId, String refKey, String chunkLevel, String parentRefKey,
                         boolean searchable, String sectionTitle, String title, String text,
                         Map<String, Object> metadata) {

    public static ChunkDraft child(EvidenceKind kind, Long refId, String refKey, String parentRefKey,
                                   String sectionTitle, String title, String text, Map<String, Object> metadata) {
        return new ChunkDraft(kind, refId, refKey, ChunkPolicy.LEVEL_CHILD, parentRefKey, true, sectionTitle, title, text, metadata);
    }

    public static ChunkDraft parent(EvidenceKind kind, Long refId, String refKey, String sectionTitle,
                                    String title, String text, Map<String, Object> metadata) {
        return new ChunkDraft(kind, refId, refKey, ChunkPolicy.LEVEL_PARENT, null, false, sectionTitle, title, text, metadata);
    }

    public ChunkDraft withText(String newText) {
        return new ChunkDraft(kind, refId, refKey, chunkLevel, parentRefKey, searchable, sectionTitle, title, newText, metadata);
    }
}
```

`ChunkOverlapUtil.java` (Inufleet `ChunkOverlapUtil` 이식):
```java
package io.saife.evidence.chunk;

import java.util.ArrayList;
import java.util.List;

/** 이전 청크의 꼬리 200자를 문장 경계에서 잘라 다음 청크 앞에 붙인다. 경계 단어가 잘려 검색에서 빠지는 것을 막는다 */
public final class ChunkOverlapUtil {
    private ChunkOverlapUtil() {}

    public static List<String> addOverlap(List<String> texts) {
        if (texts.size() <= 1) return texts;
        List<String> out = new ArrayList<>(texts.size());
        out.add(texts.get(0));
        for (int i = 1; i < texts.size(); i++) {
            String tail = tailForOverlap(texts.get(i - 1));
            out.add(tail.isBlank() ? texts.get(i) : tail + "\n" + texts.get(i));
        }
        return out;
    }

    /** 꼬리 200자 중 첫 문장 경계(. \n ? ! 다) 뒤부터. 경계가 없으면 200자 전체. 200자 이하 텍스트는 빈 문자열 */
    public static String tailForOverlap(String text) {
        if (text == null || text.length() <= ChunkPolicy.OVERLAP_CHARS) return "";
        String tail = text.substring(text.length() - ChunkPolicy.OVERLAP_CHARS);
        for (int j = 0; j < tail.length(); j++) {
            char c = tail.charAt(j);
            if (c == '.' || c == '\n' || c == '?' || c == '!' || c == '다') {
                int start = j + 1;
                if (start < tail.length()) return tail.substring(start).trim();
            }
        }
        return tail.trim();
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.chunk.ChunkOverlapUtilTest`
Expected: PASS 4건

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/io/saife/evidence backend/src/test/java/io/saife/evidence/chunk
git commit -m "feat(evidence): 청킹 정책·ChunkDraft·문장 경계 오버랩(Inufleet 이식)"
```

---

### Task 2: `PdfTextExtractor` (PDFBox)

**Files:**
- Modify: `backend/build.gradle` (의존성 1줄), `.claude/rules/external-library.md` (사유 1줄)
- Create: `backend/src/main/java/io/saife/evidence/chunk/PdfTextExtractor.java`
- Test: `backend/src/test/java/io/saife/evidence/chunk/PdfTextExtractorTest.java`

**Interfaces:**
- Produces: `PdfTextExtractor.extract(byte[] pdf) → List<String> pages`(페이지 순서, 공백 정규화), `PdfTextExtractor.quality(String text) → double`(0~1, 한글·CJK·기본 라틴 비율).

- [ ] **Step 1: 의존성 추가**

`build.gradle` dependencies에:
```groovy
    implementation 'org.apache.pdfbox:pdfbox:3.0.5'   // KOSHA GUIDE PDF 텍스트 추출 (근거 청크)
```
`.claude/rules/external-library.md` 끝에: `- pdfbox 3.0.5 — KOSHA GUIDE PDF 본문을 청크로 임베딩하기 위해 추가(2026-09-29). 파일은 저장하지 않고 텍스트만 쓴다.`

- [ ] **Step 2: 실패하는 테스트 (PDFBox로 테스트 PDF를 만들어 쓴다 — 픽스처 파일 없음)**

```java
package io.saife.evidence.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class PdfTextExtractorTest {

    private byte[] twoPagePdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : List.of("Page one text", "Page two text")) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(line);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void 페이지_순서대로_텍스트를_뽑는다() throws Exception {
        List<String> pages = PdfTextExtractor.extract(twoPagePdf());
        assertThat(pages).hasSize(2);
        assertThat(pages.get(0)).contains("Page one");
        assertThat(pages.get(1)).contains("Page two");
    }

    @Test
    void 깨진_바이트는_빈_리스트() {
        assertThat(PdfTextExtractor.extract("not a pdf".getBytes())).isEmpty();
    }

    @Test
    void 품질_점수는_한글_라틴이_높고_깨진_문자가_낮다() {
        assertThat(PdfTextExtractor.quality("안전난간을 설치한다 install guard")).isGreaterThan(0.9);
        assertThat(PdfTextExtractor.quality("����")).isLessThan(0.3);
        assertThat(PdfTextExtractor.quality("")).isZero();
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.chunk.PdfTextExtractorTest`
Expected: FAIL

- [ ] **Step 4: 구현**

```java
package io.saife.evidence.chunk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * PDF → 페이지별 텍스트. Inufleet의 Vision OCR 폴백은 가져오지 않는다 — 지침 PDF는 텍스트 PDF다.
 * 품질이 0.3 미만인 페이지(스캔본·폰트 깨짐)는 호출자가 버린다.
 */
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
        return s.replace("\r", "").replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }
}
```

- [ ] **Step 5: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.chunk.PdfTextExtractorTest`
Expected: PASS 3건

```bash
git add backend/build.gradle .claude/rules/external-library.md backend/src/main/java/io/saife/evidence/chunk/PdfTextExtractor.java backend/src/test/java/io/saife/evidence/chunk/PdfTextExtractorTest.java
git commit -m "feat(evidence): PDFBox 텍스트 추출기 — KOSHA GUIDE 본문 청크용"
```

---

### Task 3: `GuideChunkBuilder` — 섹션 parent + child + 오버랩

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/chunk/GuideChunkBuilder.java`
- Test: `backend/src/test/java/io/saife/evidence/chunk/GuideChunkBuilderTest.java`

**Interfaces:**
- Produces: `GuideChunkBuilder.build(Long guideId, String guideNo, String guideName, LocalDate announcedOn, List<String> pages) → List<ChunkDraft>` — 표지 child(`refKey = guideNo + "#cover"`, parent 없음) + 섹션 parent(`guideNo + "#p" + n`) + child(`guideNo + "#c" + n`). metadata: `guideNo, guideName, page, announcedOn, section`.
- Consumes: `ChunkDraft`, `ChunkPolicy`, `ChunkOverlapUtil`, `PdfTextExtractor.quality`.

- [ ] **Step 1: 실패하는 테스트**

```java
package io.saife.evidence.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuideChunkBuilderTest {

    private static final String NO = "G-1-2024";

    @Test
    void 표지_청크는_parent_없이_1개() {
        List<ChunkDraft> out = GuideChunkBuilder.build(1L, NO, "사다리 안전작업 지침", LocalDate.of(2024, 1, 1),
                List.of("1. 목적\n이 지침은 사다리 작업의 안전을 위한 것이다.", "2. 적용범위\n모든 사업장"));
        ChunkDraft cover = out.get(0);
        assertThat(cover.refKey()).isEqualTo(NO + "#cover");
        assertThat(cover.parentRefKey()).isNull();
        assertThat(cover.text()).startsWith("사다리 안전작업 지침");
        assertThat(cover.text().length()).isLessThanOrEqualTo(ChunkPolicy.COVER_MAX + 40);
    }

    @Test
    void 제목줄로_섹션을_나누고_parent는_searchable_false() {
        String body = "제1장 총칙\n" + "가".repeat(400) + ".\n제2장 작업 전 확인\n" + "나".repeat(400) + ".\n" + "다".repeat(400) + ".";
        List<ChunkDraft> out = GuideChunkBuilder.build(1L, NO, "지침", null, List.of(body));
        List<ChunkDraft> parents = out.stream().filter(c -> ChunkPolicy.LEVEL_PARENT.equals(c.chunkLevel())).toList();
        List<ChunkDraft> children = out.stream().filter(c -> ChunkPolicy.LEVEL_CHILD.equals(c.chunkLevel()) && c.parentRefKey() != null).toList();
        assertThat(parents).hasSize(2);
        assertThat(parents).allMatch(p -> !p.searchable());
        assertThat(parents.get(1).sectionTitle()).isEqualTo("제2장 작업 전 확인");
        assertThat(children).allMatch(c -> c.searchable());
        assertThat(children).allMatch(c -> c.parentRefKey().startsWith(NO + "#p"));
    }

    @Test
    void 단일_덩어리_텍스트() {
        String blob = "라".repeat(ChunkPolicy.CHILD_MAX + ChunkPolicy.CHILD_MIN - 10);
        List<ChunkDraft> out = GuideChunkBuilder.build(1L, NO, "지침", null, List.of(blob));
        List<ChunkDraft> children = out.stream().filter(c -> ChunkPolicy.LEVEL_CHILD.equals(c.chunkLevel()) && c.parentRefKey() != null).toList();
        // 3290자 → 1200/1200/890 (마지막이 300 이상이라 유지) = 3
        assertThat(children).hasSize(3);
        assertThat(children.get(2).text().length()).isGreaterThanOrEqualTo(ChunkPolicy.CHILD_MIN);
        // 오버랩: 두 번째 child는 첫 child 꼬리로 시작
        assertThat(children.get(1).text()).startsWith("라".repeat(ChunkPolicy.OVERLAP_CHARS));
    }

    @Test
    void 빈_섹션은_parent를_만들지_않는다() {
        List<ChunkDraft> out = GuideChunkBuilder.build(1L, NO, "지침", null, List.of("제1장 총칙\n\n제2장 정의\n" + "마".repeat(500)));
        List<ChunkDraft> parents = out.stream().filter(c -> ChunkPolicy.LEVEL_PARENT.equals(c.chunkLevel())).toList();
        assertThat(parents).hasSize(1);
        assertThat(parents.get(0).sectionTitle()).isEqualTo("제2장 정의");
    }

    @Test
    void child가_하나인_섹션은_parent_없이_child만() {
        List<ChunkDraft> out = GuideChunkBuilder.build(1L, NO, "지침", null, List.of("제1장 총칙\n" + "바".repeat(500)));
        assertThat(out.stream().filter(c -> ChunkPolicy.LEVEL_PARENT.equals(c.chunkLevel()))).isEmpty();
        ChunkDraft only = out.stream().filter(c -> c.refKey().startsWith(NO + "#c")).findFirst().orElseThrow();
        assertThat(only.parentRefKey()).isNull();
        assertThat(only.sectionTitle()).isEqualTo("제1장 총칙");
    }

    @Test
    void 품질_낮은_페이지는_버린다() {
        List<ChunkDraft> out = GuideChunkBuilder.build(1L, NO, "지침", null, List.of("�".repeat(300), "정상 본문 " + "사".repeat(400)));
        assertThat(out.stream().noneMatch(c -> c.text().contains("�"))).isTrue();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.chunk.GuideChunkBuilderTest`
Expected: FAIL

- [ ] **Step 3: 구현**

```java
package io.saife.evidence.chunk;

import io.saife.evidence.EvidenceKind;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * KOSHA GUIDE PDF 텍스트 → 표지 child 1 + 섹션 parent/child.
 *
 * <p>Inufleet의 Tika 처리기에서 제목 휴리스틱과 "섹션 제목이 없으면 연속 5개를 parent로"를 가져왔다.
 * 다른 점: parent는 임베딩하지 않으므로 텍스트만 만들고 {@code searchable=false}.
 */
public final class GuideChunkBuilder {
    private GuideChunkBuilder() {}

    /** 제목줄: 제N장/제N절, 숫자 접두(1. / 1.2 / 1)), 로마숫자, 60자 이하이며 . , 로 끝나지 않는 줄 */
    private static final Pattern HEADING = Pattern.compile(
            "^(제\\s?\\d+\\s?[장절조]|\\d+(\\.\\d+)*[.)]?\\s+\\S|[IVXⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+[.)]\\s+\\S).{0,60}$");
    private static final double MIN_QUALITY = 0.3;

    public static List<ChunkDraft> build(Long guideId, String guideNo, String guideName, LocalDate announcedOn, List<String> pages) {
        List<ChunkDraft> out = new ArrayList<>();
        List<String> good = pages.stream().filter(p -> PdfTextExtractor.quality(p) >= MIN_QUALITY).toList();
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("guideNo", guideNo);
        base.put("guideName", guideName);
        if (announcedOn != null) base.put("announcedOn", announcedOn.toString());

        // 표지: 제목 + 앞 2페이지(적용범위·목적)
        String head = String.join("\n", good.stream().limit(2).toList());
        String cover = (guideName + "\n" + head).strip();
        if (cover.length() > ChunkPolicy.COVER_MAX) cover = cover.substring(0, ChunkPolicy.COVER_MAX);
        out.add(ChunkDraft.child(EvidenceKind.GUIDE, guideId, guideNo + "#cover", null, "표지",
                "[KOSHA GUIDE " + guideNo + "] " + guideName, cover, withPage(base, 1, "표지")));

        // 섹션 분할
        List<Section> sections = splitSections(good);
        int p = 0, c = 0;
        for (Section s : sections) {
            List<String> pieces = ChunkOverlapUtil.addOverlap(splitToChildren(s.text));
            if (pieces.isEmpty()) continue;
            String parentKey = null;
            if (pieces.size() > 1) {
                p++;
                parentKey = guideNo + "#p" + p;
                out.add(ChunkDraft.parent(EvidenceKind.GUIDE, guideId, parentKey, s.title,
                        "[KOSHA GUIDE " + guideNo + "] " + guideName + " — " + s.title, s.text, withPage(base, s.page, s.title)));
            }
            for (String piece : pieces) {
                c++;
                out.add(ChunkDraft.child(EvidenceKind.GUIDE, guideId, guideNo + "#c" + c, parentKey, s.title,
                        "[KOSHA GUIDE " + guideNo + "] " + guideName + " — " + s.title, piece, withPage(base, s.page, s.title)));
            }
        }
        return out;
    }

    private record Section(String title, int page, String text) {}

    /** 제목줄마다 섹션을 연다. 제목이 하나도 없으면 문단 5개씩 묶는다. 본문 없는 섹션은 버린다 */
    static List<Section> splitSections(List<String> pages) {
        List<Section> out = new ArrayList<>();
        String title = "본문";
        int page = 1;
        StringBuilder buf = new StringBuilder();
        boolean anyHeading = false;
        for (int i = 0; i < pages.size(); i++) {
            for (String line : pages.get(i).split("\n")) {
                String t = line.strip();
                if (isHeading(t)) {
                    anyHeading = true;
                    flush(out, title, page, buf);
                    title = t; page = i + 1;
                } else {
                    buf.append(line).append('\n');
                }
            }
        }
        flush(out, title, page, buf);
        if (!anyHeading) {
            // 제목이 없으면 문단(빈 줄) 단위로 PARENT_MAX_CHILDREN개씩
            String all = String.join("\n", pages);
            String[] paras = all.split("\n\\s*\n");
            List<Section> grouped = new ArrayList<>();
            for (int i = 0; i < paras.length; i += ChunkPolicy.PARENT_MAX_CHILDREN) {
                String text = String.join("\n\n", Arrays.copyOfRange(paras, i, Math.min(paras.length, i + ChunkPolicy.PARENT_MAX_CHILDREN))).strip();
                if (!text.isBlank()) grouped.add(new Section("본문 " + (grouped.size() + 1), 1, text));
            }
            return grouped;
        }
        return out;
    }

    private static void flush(List<Section> out, String title, int page, StringBuilder buf) {
        String text = buf.toString().strip();
        buf.setLength(0);
        if (!text.isBlank()) out.add(new Section(title, page, text));
    }

    static boolean isHeading(String line) {
        if (line.isEmpty() || line.length() > 60) return false;
        if (line.endsWith(".") || line.endsWith(",")) return false;
        return HEADING.matcher(line).matches();
    }

    /** 문단 경계를 존중하며 CHILD_TARGET까지 모으고, CHILD_MAX 초과는 강제 분할. 마지막 조각이 CHILD_MIN 미만이면 앞에 합친다 */
    static List<String> splitToChildren(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String para : text.split("\n\\s*\n")) {
            String p = para.strip();
            if (p.isEmpty()) continue;
            if (cur.length() + p.length() + 1 > ChunkPolicy.CHILD_TARGET && cur.length() >= ChunkPolicy.CHILD_MIN) {
                out.add(cur.toString().strip());
                cur.setLength(0);
            }
            cur.append(p).append("\n\n");
        }
        if (!cur.isEmpty()) out.add(cur.toString().strip());
        // 초과분 강제 분할
        List<String> sized = new ArrayList<>();
        for (String piece : out) {
            if (piece.length() <= ChunkPolicy.CHILD_MAX) { sized.add(piece); continue; }
            for (int i = 0; i < piece.length(); i += ChunkPolicy.CHILD_TARGET) {
                sized.add(piece.substring(i, Math.min(piece.length(), i + ChunkPolicy.CHILD_TARGET)));
            }
        }
        // 짧은 꼬리 합치기
        if (sized.size() >= 2 && sized.get(sized.size() - 1).length() < ChunkPolicy.CHILD_MIN) {
            String tail = sized.remove(sized.size() - 1);
            sized.set(sized.size() - 1, sized.get(sized.size() - 1) + "\n\n" + tail);
        }
        return sized;
    }

    private static Map<String, Object> withPage(Map<String, Object> base, int page, String section) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        m.put("page", page);
        m.put("section", section);
        return m;
    }
}
```

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.chunk.GuideChunkBuilderTest`
Expected: PASS 6건 (테스트 3의 `startsWith("라".repeat(200))`은 "라"에 문장 경계가 없어 꼬리 전체가 붙는 경우다)

```bash
git add backend/src/main/java/io/saife/evidence/chunk/GuideChunkBuilder.java backend/src/test/java/io/saife/evidence/chunk/GuideChunkBuilderTest.java
git commit -m "feat(evidence): KOSHA GUIDE 청크 빌더 — 표지·섹션 parent·child·오버랩"
```

---

### Task 4: `CaseChunkBuilder` + `LawChunkBuilder`

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/chunk/CaseChunkBuilder.java`
- Create: `backend/src/main/java/io/saife/evidence/chunk/LawChunkBuilder.java`
- Test: `backend/src/test/java/io/saife/evidence/chunk/CaseChunkBuilderTest.java`, `LawChunkBuilderTest.java`

**Interfaces:**
- Produces:
  - `CaseChunkBuilder.build(PublicCase c) → ChunkDraft` (kind는 source에 따라 CASE_FATALITY/CASE_DISASTER, refKey=`source:sourceKey`, title=`[축 라벨] [업종] keyword`, metadata: `accidentType, business, region, occurredOn, hasImage`)
  - `LawChunkBuilder.build(List<LawArticle> paragraphsOfOneArticle) → List<ChunkDraft>` (항 여러 개면 parent `lawId:조:sub#p` + child `lawId:조:sub:항`, 항 1개면 child만)

- [ ] **Step 1: 실패하는 테스트**

`CaseChunkBuilderTest.java`:
```java
package io.saife.evidence.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.core.domain.AccidentType;
import io.saife.evidence.EvidenceKind;
import io.saife.publicapi.domain.PublicCase;
import org.junit.jupiter.api.Test;

class CaseChunkBuilderTest {
    @Test
    void 사고사망은_CASE_FATALITY이고_사진_유무를_메타에_넣는다() {
        PublicCase c = PublicCase.builder().source("FATALITY").sourceKey("A1").keyword("[7/28, 경남 함양군] 스크류에 끼임")
                .contents("설비 내 슬러지 제거 작업 중 설비가 가동되어 끼임").accidentType(AccidentType.CAUGHT)
                .imageUrl("https://portal.kosha.or.kr/x.png").build();
        ChunkDraft d = CaseChunkBuilder.build(c);
        assertThat(d.kind()).isEqualTo(EvidenceKind.CASE_FATALITY);
        assertThat(d.refKey()).isEqualTo("FATALITY:A1");
        assertThat(d.title()).startsWith("[협착]");
        assertThat(d.text()).contains("스크류에 끼임").contains("슬러지");
        assertThat(d.metadata()).containsEntry("accidentType", "CAUGHT").containsEntry("hasImage", true);
        assertThat(d.parentRefKey()).isNull();
        assertThat(d.searchable()).isTrue();
    }

    @Test
    void 재해사례는_업종이_제목에_들어간다() {
        PublicCase c = PublicCase.builder().source("DISASTER").sourceKey("B1").business("제조업").keyword("지붕교체 중 떨어짐")
                .contents("본문").accidentType(AccidentType.FALL).build();
        ChunkDraft d = CaseChunkBuilder.build(c);
        assertThat(d.kind()).isEqualTo(EvidenceKind.CASE_DISASTER);
        assertThat(d.title()).isEqualTo("[추락] [제조업] 지붕교체 중 떨어짐");
        assertThat(d.metadata()).containsEntry("hasImage", false);
    }
}
```

`LawChunkBuilderTest.java`:
```java
package io.saife.evidence.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.evidence.domain.LawArticle;
import java.util.List;
import org.junit.jupiter.api.Test;

class LawChunkBuilderTest {
    private LawArticle para(int no, int p, String text) {
        return LawArticle.builder().lawId("001766").lawName("산업안전보건법").articleNo(no).articleSub(0)
                .paragraphNo(p).title("위험성평가의 실시").text(text).build();
    }

    @Test
    void 항이_여러개면_parent와_child() {
        List<ChunkDraft> out = LawChunkBuilder.build(List.of(para(36, 1, "① 첫째"), para(36, 2, "② 둘째")));
        assertThat(out).hasSize(3);
        ChunkDraft parent = out.get(0);
        assertThat(parent.chunkLevel()).isEqualTo(ChunkPolicy.LEVEL_PARENT);
        assertThat(parent.refKey()).isEqualTo("001766:36:0#p");
        assertThat(parent.text()).contains("① 첫째").contains("② 둘째");
        assertThat(out.get(1).refKey()).isEqualTo("001766:36:0:1");
        assertThat(out.get(1).parentRefKey()).isEqualTo("001766:36:0#p");
        assertThat(out.get(1).title()).isEqualTo("산업안전보건법 제36조(위험성평가의 실시) ①");
    }

    @Test
    void 항이_하나면_parent_없음() {
        List<ChunkDraft> out = LawChunkBuilder.build(List.of(para(36, 0, "본문만")));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).parentRefKey()).isNull();
        assertThat(out.get(0).refKey()).isEqualTo("001766:36:0:0");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests "io.saife.evidence.chunk.*ChunkBuilderTest"`
Expected: FAIL

- [ ] **Step 3: 구현**

`CaseChunkBuilder.java`:
```java
package io.saife.evidence.chunk;

import io.saife.evidence.EvidenceKind;
import io.saife.publicapi.domain.PublicCase;
import java.util.LinkedHashMap;
import java.util.Map;

/** 사례 1건 = 청크 1개. 제목에 축·업종 라벨을 넣어 키워드 검색이 "추락 제조업"으로도 맞게 한다 */
public final class CaseChunkBuilder {
    private CaseChunkBuilder() {}

    public static ChunkDraft build(PublicCase c) {
        EvidenceKind kind = "FATALITY".equals(c.getSource()) ? EvidenceKind.CASE_FATALITY : EvidenceKind.CASE_DISASTER;
        StringBuilder title = new StringBuilder();
        if (c.getAccidentType() != null) title.append('[').append(c.getAccidentType().getLabel()).append("] ");
        if (c.getBusiness() != null && !c.getBusiness().isBlank()) title.append('[').append(c.getBusiness()).append("] ");
        title.append(c.getKeyword() == null ? "" : c.getKeyword());
        String text = ((c.getKeyword() == null ? "" : c.getKeyword()) + " " + (c.getContents() == null ? "" : c.getContents())).strip();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("accidentType", c.getAccidentType() == null ? null : c.getAccidentType().name());
        meta.put("business", c.getBusiness());
        meta.put("region", c.getRegion());
        meta.put("occurredOn", c.getOccurredOn() == null ? null : c.getOccurredOn().toString());
        meta.put("hasImage", c.getImageUrl() != null && !c.getImageUrl().isBlank());
        return ChunkDraft.child(kind, c.getId(), c.getSource() + ":" + c.getSourceKey(), null, null,
                title.toString().strip(), text, meta);
    }
}
```

`LawChunkBuilder.java`:
```java
package io.saife.evidence.chunk;

import io.saife.evidence.EvidenceKind;
import io.saife.evidence.domain.LawArticle;
import java.util.*;

/** 조(條) 하나의 항 목록 → parent(조 전체) + child(항). 항이 하나면 child만 */
public final class LawChunkBuilder {
    private LawChunkBuilder() {}

    public static List<ChunkDraft> build(List<LawArticle> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) return List.of();
        LawArticle first = paragraphs.get(0);
        String base = first.getLawId() + ":" + first.getArticleNo() + ":" + first.getArticleSub();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("lawId", first.getLawId());
        meta.put("lawName", first.getLawName());
        meta.put("articleNo", first.getArticleNo());
        meta.put("articleSub", first.getArticleSub());
        meta.put("effectiveOn", first.getEffectiveOn() == null ? null : first.getEffectiveOn().toString());
        List<ChunkDraft> out = new ArrayList<>();
        String parentKey = null;
        if (paragraphs.size() > 1) {
            parentKey = base + "#p";
            String joined = String.join("\n", paragraphs.stream().map(LawArticle::getText).toList());
            String articleTitle = first.citation().replaceAll(" [①-⑳]$", "");
            out.add(ChunkDraft.parent(EvidenceKind.LAW, first.getId(), parentKey, articleTitle, articleTitle, joined, meta));
        }
        for (LawArticle p : paragraphs) {
            Map<String, Object> m = new LinkedHashMap<>(meta);
            m.put("paragraphNo", p.getParagraphNo());
            out.add(ChunkDraft.child(EvidenceKind.LAW, p.getId(), base + ":" + p.getParagraphNo(), parentKey,
                    first.getTitle(), p.citation(), p.citation() + "\n" + p.getText(), m));
        }
        return out;
    }
}
```

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests "io.saife.evidence.chunk.*"`
Expected: PASS

```bash
git add backend/src/main/java/io/saife/evidence/chunk backend/src/test/java/io/saife/evidence/chunk
git commit -m "feat(evidence): 사례·조문 청크 빌더 — 사례 1건=1청크, 조=parent·항=child"
```

---

### Task 5: `ContextualEnricher` + 프롬프트 + 프리픽스 제거기

**Files:**
- Create: `backend/src/main/resources/prompts/contextual-enrichment.txt`
- Create: `backend/src/main/java/io/saife/evidence/chunk/ContextualEnricher.java`
- Create: `backend/src/main/java/io/saife/evidence/chunk/ContextualPrefix.java`
- Test: `backend/src/test/java/io/saife/evidence/chunk/ContextualEnricherTest.java`, `ContextualPrefixTest.java`

**Interfaces:**
- Produces:
  - `ContextualEnricher.enrich(String fullDocument, String chunkText, int chunkOffset, String documentName) → String` (`[맥락]\n원문` 또는 원문)
  - `ContextualPrefix.strip(String text) → String`, `ContextualPrefix.of(String context, String text) → String`
  - 생성자 주입: `ChatClient.Builder`, `DemoModeConfig`

- [ ] **Step 1: 프롬프트 파일 (Inufleet 것을 SAIFE 어휘로)**

`prompts/contextual-enrichment.txt`:
```
<system-prompt>
<role>문서 청크의 위치와 맥락을 설명하는 한 줄 요약을 생성하세요.</role>

<section name="rules">
<rule>문서 전체 내용을 참고하여 이 청크가 어떤 섹션/주제에 해당하는지 설명하세요.</rule>
<rule>지침 번호, 대상 작업(예: 사다리 작업, 도장 작업), 카테고리(적용범위/작업 전 확인/보호구/방호장치/비상조치/용어정의 등)를 반드시 포함하세요.</rule>
<rule>설비·기구 이름은 원문 표기를 그대로 쓰세요.</rule>
<rule>50~150자 이내로 작성하세요.</rule>
<rule>설명만 출력하세요. 따옴표, 대괄호, 부가 텍스트 없이.</rule>
</section>

<context type="document-name">{fileName}</context>
<context type="full-document">{document}</context>
<context type="target-chunk">{chunk}</context>

<output-cue>맥락 설명:</output-cue>
</system-prompt>
```

- [ ] **Step 2: 실패하는 테스트**

`ContextualPrefixTest.java`:
```java
package io.saife.evidence.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ContextualPrefixTest {
    @Test
    void 붙이고_벗기면_원문() {
        String withPrefix = ContextualPrefix.of("사다리 지침 — 작업 전 확인", "원문 본문");
        assertThat(withPrefix).isEqualTo("[사다리 지침 — 작업 전 확인]\n원문 본문");
        assertThat(ContextualPrefix.strip(withPrefix)).isEqualTo("원문 본문");
    }

    @Test
    void 프리픽스가_없거나_200자를_넘으면_그대로() {
        assertThat(ContextualPrefix.strip("원문")).isEqualTo("원문");
        String longPrefix = "[" + "가".repeat(250) + "]\n원문";
        assertThat(ContextualPrefix.strip(longPrefix)).isEqualTo(longPrefix);
        assertThat(ContextualPrefix.strip(null)).isEmpty();
    }
}
```

`ContextualEnricherTest.java` (ChatClient를 가짜로):
```java
package io.saife.evidence.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.saife.common.config.DemoModeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class ContextualEnricherTest {

    private ContextualEnricher enricherReturning(String answer, boolean demo) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(builder.build()).thenReturn(client);
        if (answer == null) {
            when(client.prompt().user(any(String.class)).options(any()).call().content()).thenThrow(new RuntimeException("down"));
        } else {
            when(client.prompt().user(any(String.class)).options(any()).call().content()).thenReturn(answer);
        }
        DemoModeConfig cfg = mock(DemoModeConfig.class);
        when(cfg.isDemoMode()).thenReturn(demo);
        return new ContextualEnricher(builder, cfg, "<system-prompt>{fileName}{document}{chunk}</system-prompt>");
    }

    @Test
    void 정상_답은_프리픽스로_붙는다() {
        String out = enricherReturning("사다리 지침 — 작업 전 확인 절", false).enrich("전체 문서", "청크", 0, "G-1");
        assertThat(out).isEqualTo("[사다리 지침 — 작업 전 확인 절]\n청크");
    }

    @Test
    void 실패하면_원문() {
        assertThat(enricherReturning(null, false).enrich("전체", "청크", 0, "G-1")).isEqualTo("청크");
    }

    @Test
    void 대괄호가_있는_답은_버린다() {
        assertThat(enricherReturning("[이미 대괄호] 설명", false).enrich("전체", "청크", 0, "G-1")).isEqualTo("청크");
        assertThat(enricherReturning("   ", false).enrich("전체", "청크", 0, "G-1")).isEqualTo("청크");
    }

    @Test
    void 데모_모드는_호출하지_않는다() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        DemoModeConfig cfg = mock(DemoModeConfig.class);
        when(cfg.isDemoMode()).thenReturn(true);
        ContextualEnricher e = new ContextualEnricher(builder, cfg, "x");
        assertThat(e.enrich("전체", "청크", 0, "G-1")).isEqualTo("청크");
        verifyNoInteractions(builder);
    }

    @Test
    void 긴_문서는_앞_3000자와_주변_2500자만_보낸다() {
        String doc = "앞".repeat(3000) + "중".repeat(6000) + "뒤".repeat(3000);
        ContextualEnricher e = enricherReturning("맥락", false);
        String ctx = e.buildDocumentContext(doc, 7000);
        assertThat(ctx).startsWith("앞".repeat(3000) + "\n...\n");
        assertThat(ctx.length()).isLessThanOrEqualTo(3000 + 5 + 5000);
        assertThat(e.buildDocumentContext("짧은 문서", 0)).isEqualTo("짧은 문서");
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `cd backend && ./gradlew test --tests "io.saife.evidence.chunk.Contextual*"`
Expected: FAIL

- [ ] **Step 4: 구현**

`ContextualPrefix.java`:
```java
package io.saife.evidence.chunk;

/** `[맥락 한 줄]\n원문` 형식. 검색용이므로 모델 컨텍스트·카드에 넣을 때는 벗긴다 */
public final class ContextualPrefix {
    private ContextualPrefix() {}
    static final int MAX_PREFIX = 200;

    public static String of(String context, String text) {
        return "[" + context.strip() + "]\n" + text;
    }

    public static String strip(String text) {
        if (text == null) return "";
        String r = text;
        while (r.startsWith("[")) {
            int end = r.indexOf("]\n");
            if (end <= 0 || end > MAX_PREFIX) break;
            r = r.substring(end + 2);
        }
        return r;
    }
}
```

`ContextualEnricher.java`:
```java
package io.saife.evidence.chunk;

import io.saife.common.config.DemoModeConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Contextual Retrieval(HyDE 대체, Inufleet 이식). 청크가 문서의 어느 자리인지 한 줄을 만들어 앞에 붙인다.
 * 실패·빈 답·대괄호 포함 답은 버리고 원문을 돌려준다. 데모 모드에서는 부르지 않는다.
 */
@Slf4j
@Component
public class ContextualEnricher {
    static final int HEAD_CHARS = 3000;
    static final int AROUND_CHARS = 2500;
    static final int WHOLE_DOC_LIMIT = 8000;

    private final ChatClient.Builder builder;
    private final DemoModeConfig demoModeConfig;
    private final String template;
    @Value("${spring.ai.google.genai.chat.options.model:gemini-3.8-flash}")
    private String model = "gemini-3.8-flash";

    @Autowired
    public ContextualEnricher(ChatClient.Builder builder, DemoModeConfig demoModeConfig,
                              @Value("classpath:prompts/contextual-enrichment.txt") Resource prompt) throws IOException {
        this(builder, demoModeConfig, prompt.getContentAsString(StandardCharsets.UTF_8));
    }

    ContextualEnricher(ChatClient.Builder builder, DemoModeConfig demoModeConfig, String template) {
        this.builder = builder; this.demoModeConfig = demoModeConfig; this.template = template;
    }

    public String enrich(String fullDocument, String chunkText, int chunkOffset, String documentName) {
        if (demoModeConfig.isDemoMode() || chunkText == null || chunkText.isBlank()) return chunkText;
        try {
            String prompt = template.replace("{fileName}", documentName == null ? "" : documentName)
                    .replace("{document}", buildDocumentContext(fullDocument, chunkOffset))
                    .replace("{chunk}", chunkText);
            String answer = builder.build().prompt().user(prompt)
                    .options(GoogleGenAiChatOptions.builder().model(model).temperature(0.0)
                            .maxOutputTokens(150).thinkingBudget(0).build())
                    .call().content();
            if (answer == null) return chunkText;
            String ctx = answer.strip().replaceAll("^맥락 설명:\\s*", "");
            if (ctx.isBlank() || ctx.contains("[") || ctx.contains("]") || ctx.length() > ContextualPrefix.MAX_PREFIX - 2) return chunkText;
            return ContextualPrefix.of(ctx, chunkText);
        } catch (Exception e) {
            log.warn("[ENRICH] 실패 doc={}: {}", documentName, e.getMessage());
            return chunkText;
        }
    }

    /** 8,000자 이하 문서는 전체. 아니면 앞 3,000자 + 청크 주변 ±2,500자 */
    String buildDocumentContext(String doc, int offset) {
        if (doc == null) return "";
        if (doc.length() <= WHOLE_DOC_LIMIT) return doc;
        if (offset <= HEAD_CHARS + AROUND_CHARS) return doc.substring(0, WHOLE_DOC_LIMIT);
        int start = Math.max(HEAD_CHARS, offset - AROUND_CHARS);
        int end = Math.min(doc.length(), offset + AROUND_CHARS);
        return doc.substring(0, HEAD_CHARS) + "\n...\n" + doc.substring(start, end);
    }
}
```

- [ ] **Step 5: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests "io.saife.evidence.chunk.*"`
Expected: PASS

```bash
git add backend/src/main/resources/prompts/contextual-enrichment.txt backend/src/main/java/io/saife/evidence/chunk backend/src/test/java/io/saife/evidence/chunk
git commit -m "feat(evidence): Contextual Enrichment(HyDE 대체) — 지침 청크에 맥락 한 줄 프리픽스"
```

---

## Self-Review

- 스펙 §4.1 커버: 사례(Task 4), 지침 표지/섹션/child/오버랩/enrichment(Task 2·3·5), 조문 parent/child(Task 4), parent 미임베딩(searchable=false, 텍스트만 — A3의 IndexBuilder가 searchable=false는 임베딩하지 않는다).
- 플레이스홀더 없음. 타입: `ChunkDraft`·`EvidenceKind`·`ChunkPolicy`는 A3·B가 그대로 참조.
- Review Focus 5건 모두 테스트 존재(Task 1·3·4·5).
