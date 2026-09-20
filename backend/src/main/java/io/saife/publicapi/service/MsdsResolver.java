package io.saife.publicapi.service;

import io.saife.publicapi.repository.MsdsCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 현장 제품명 → MSDS 물질 매칭.
 *
 * <p>현장은 "○○ 유성페인트"라고 말하지 "톨루엔"이라고 말하지 않는다. 캐시는 물질명으로
 * 색인되어 있으므로 그 사이를 메워야 한다. 못 메우면 브리핑의 화학물질 칸이
 * "캐시에 없습니다"로 비고, 보호구 근거가 일반론으로 떨어진다.
 *
 * <p><b>이 로직을 호출자마다 복사하지 않는다.</b> 실제로 도구에만 넣었더니
 * 도구는 톨루엔을 찾는데 브리핑은 "MSDS 없음"을 출력했다(2026-09-20 스모크 테스트).
 * 같은 질문에 두 답이 나오는 건 무대에서 그대로 드러난다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MsdsResolver {

    private final MsdsCacheRepository msdsCacheRepository;

    /** 제품명으로 chemId를 찾는다. 직접 매칭 실패 시 주성분으로 재시도한다 */
    public String resolveChemId(String productName) {
        if (productName == null || productName.isBlank()) {
            return null;
        }
        String name = productName.trim();

        List<String> direct = msdsCacheRepository.findChemIdsByName(name);
        if (!direct.isEmpty()) {
            return direct.get(0);
        }
        for (String ingredient : guessIngredients(name)) {
            List<String> hit = msdsCacheRepository.findChemIdsByName(ingredient);
            if (!hit.isEmpty()) {
                log.debug("[MSDS] '{}' → 주성분 '{}'로 매칭", name, ingredient);
                return hit.get(0);
            }
        }
        return null;
    }

    /**
     * 제품 유형에서 주성분을 추정한다.
     *
     * <p>추정이지 확정이 아니다. 그래서 브리핑에는 <b>추정이라고 밝혀서</b> 쓴다
     * — MSDS는 제품별로 발급되는 법정 문서이고, 성분은 제품마다 다르다.
     */
    public List<String> guessIngredients(String productName) {
        List<String> out = new ArrayList<>();
        if (productName == null) {
            return out;
        }
        if (productName.matches("(?s).*(유성|에나멜|락카|라카|우레탄|시너|신너|희석).*")) {
            out.add("톨루엔");
            out.add("크실렌");
        }
        if (productName.matches("(?s).*(에폭시).*")) {
            out.add("크실렌");
        }
        return out;
    }
}
