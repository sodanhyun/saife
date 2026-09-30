package io.saife.evidence.service;

import io.saife.evidence.domain.LawArticle;
import io.saife.evidence.live.Fetched;
import io.saife.evidence.live.LawArticleParser;
import io.saife.evidence.live.LawClient;
import io.saife.evidence.live.LiveOrCache;
import io.saife.evidence.repository.LawArticleRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 조문 캐시 + 라이브 확인. 시드 크롤은 3개 법령 전체를 MST 1회 호출로 가져온다 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LawArticleService {
    public static final List<String> LAWS = List.of(
            LawCitationTable.ACT, LawCitationTable.ENFORCEMENT_RULE, LawCitationTable.RULES);
    private static final String HOST = "law.go.kr";

    private final LawClient client;
    private final LawArticleRepository repository;
    private final LiveOrCache liveOrCache;

    /** 관리자: 3개 법령 전체 조문 수집. 법령명 → 저장(upsert)된 행 수 */
    public Map<String, Integer> crawlAll() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String law : LAWS) {
            try {
                LawClient.LawMeta meta = client.findLawMst(law).orElseThrow(() -> new IllegalStateException("법령을 찾지 못함: " + law));
                List<LawArticle> parsed = LawArticleParser.parse(meta.lawId(), meta.lawName(), client.fetchLawJson(meta.mst()));
                out.put(law, upsertAll(parsed));
                log.info("[LAW] {} {}조문 저장", law, parsed.size());
            } catch (Exception e) {
                log.error("[LAW] {} 수집 실패: {}", law, e.getMessage());
                out.put(law, -1);
            }
        }
        return out;
    }

    // 이 메서드는 LiveOrCache의 store 람다(this::upsertAll 또는 this 참조 람다)와 crawlAll에서
    // 같은 빈 안에서 직접 호출된다. 프록시를 거치지 않는 자기 참조 호출이라 @Transactional을 붙여도
    // AOP가 적용되지 않으므로 애초에 붙이지 않는다. repository.save() 각 호출이 자기 트랜잭션으로
    // 개별 커밋되는 것을 그대로 받아들인다 (MsdsLiveClient.store와 동일한 패턴).
    public int upsertAll(List<LawArticle> parsed) {
        int n = 0;
        for (LawArticle a : parsed) {
            repository.findByLawIdAndArticleNoAndArticleSubAndParagraphNo(a.getLawId(), a.getArticleNo(), a.getArticleSub(), a.getParagraphNo())
                    .ifPresent(repository::delete);
            repository.save(a);
            n++;
        }
        return n;
    }

    /**
     * 조문 조회 — 라이브로 최신 시행일을 확인하고, 실패하면 캐시.
     * 라이브 성공 시 캐시를 갱신한다(항이 개정됐을 수 있다).
     *
     * <p>최종 리뷰 F10: JO 호출은 요청한 조문만 온다는 보장이 없다(장 제목·인접 조문이 섞일 수 있다).
     * 파싱 결과를 요청한 {@code (articleNo, articleSub)}로 거르고, 남는 게 없으면 라이브 결과 없음(null)으로
     * 보고 캐시로 내려간다. 캐시 갱신은 본문·시행일이 실제로 바뀐 경우에만 한다 — {@link #upsertAll}은
     * 지우고 다시 넣어 {@code law_article.id}가 바뀌므로, 매 조회마다 돌면 {@code evidence_chunk.ref_id}가
     * 옛 id를 가리키게 된다.
     */
    public Fetched<List<LawArticle>> get(String lawName, int articleNo, int articleSub) {
        return liveOrCache.fetch(HOST, client.keyPresent(),
                () -> {
                    List<LawArticle> cached = repository.findByLawNameAndArticleNoAndArticleSubOrderByParagraphNo(lawName, articleNo, articleSub);
                    String lawId = cached.isEmpty() ? null : cached.get(0).getLawId();
                    List<LawArticle> live = LawArticleParser.parse(lawId == null ? "?" : lawId, lawName,
                            client.fetchArticleJson(lawName, articleNo, articleSub)).stream()
                            .filter(a -> a.getArticleNo() == articleNo && a.getArticleSub() == articleSub)
                            .toList();
                    return live.isEmpty() ? null : live;
                },
                () -> {
                    List<LawArticle> cached = repository.findByLawNameAndArticleNoAndArticleSubOrderByParagraphNo(lawName, articleNo, articleSub);
                    return cached.isEmpty() ? Optional.empty() : Optional.of(cached);
                },
                live -> {
                    if ("?".equals(live.get(0).getLawId())) return;
                    List<LawArticle> cached = repository.findByLawNameAndArticleNoAndArticleSubOrderByParagraphNo(lawName, articleNo, articleSub);
                    if (sameContent(cached, live)) return;
                    upsertAll(live);
                });
    }

    /** 항 번호·본문·시행일이 모두 같으면 true — 캐시를 다시 쓸 필요가 없다 */
    static boolean sameContent(List<LawArticle> cached, List<LawArticle> live) {
        if (cached.size() != live.size()) return false;
        List<LawArticle> a = cached.stream().sorted(Comparator.comparingInt(LawArticle::getParagraphNo)).toList();
        List<LawArticle> b = live.stream().sorted(Comparator.comparingInt(LawArticle::getParagraphNo)).toList();
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).getParagraphNo() != b.get(i).getParagraphNo()
                    || !Objects.equals(a.get(i).getText(), b.get(i).getText())
                    || !Objects.equals(a.get(i).getEffectiveOn(), b.get(i).getEffectiveOn())) {
                return false;
            }
        }
        return true;
    }
}
