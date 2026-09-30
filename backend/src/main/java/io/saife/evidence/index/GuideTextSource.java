package io.saife.evidence.index;

import io.saife.publicapi.domain.KoshaGuide;
import java.util.List;

/**
 * KOSHA GUIDE 지침 PDF의 페이지별 텍스트 공급자.
 *
 * <p>구현(다운로드 + {@code PdfTextExtractor})은 Task 5에서 붙인다. 여기서는 인터페이스만
 * 정의해 {@link IndexBuilder}가 먼저 이 계약에 대해 컴파일·테스트될 수 있게 한다.
 * 다운로드 실패 등으로 본문을 못 구하면 빈 리스트를 반환한다 — 그 경우 표지 청크만 남는다.
 */
public interface GuideTextSource {
    List<String> pagesOf(KoshaGuide guide);
}
