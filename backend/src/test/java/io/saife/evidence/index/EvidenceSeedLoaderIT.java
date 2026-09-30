package io.saife.evidence.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.domain.LawArticle;
import io.saife.evidence.repository.LawArticleRepository;
import io.saife.evidence.search.EvidenceChunkRepository;
import io.saife.publicapi.domain.PublicCase;
import io.saife.publicapi.repository.MsdsCacheRepository;
import io.saife.publicapi.repository.PublicCaseRepository;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 5433 테스트 DB 대상. 클래스 레벨 {@code @Transactional}이라 끝나면 전부 롤백된다 — 시드 실측치
 * (law_article 2303건, msds_cache 9건, evidence_chunk 0건)를 절대 건드리지 않는다.
 *
 * <p><b>fix round 1 주의:</b> {@code EvidenceSeedLoader.loadChunksFrom}은 이제 내부에서
 * {@code PROPAGATION_REQUIRES_NEW} 트랜잭션(별도 물리 커넥션)을 연다(F2). 이게 성공 경로의 두
 * 테스트({@code parent가_뒤에_있어도_연결}·{@code refId는_자연키로_대상DB의_id로_재해석된다})를
 * 클래스 레벨 {@code @Transactional}을 {@code Propagation.NOT_SUPPORTED}로 오버라이드하게 만든다.
 * 이유 둘:
 * <ul>
 *   <li><b>가시성</b> — REQUIRES_NEW는 별도 커넥션이라, 호출부가 같은 테스트 트랜잭션 안에서
 *       미리 저장해 둔(아직 커밋 전인) 참조 테이블 행을 보지 못한다. {@code refId는_자연키로_...}가
 *       public_case·law_article을 먼저 심어 두고 검증하므로 이 문제를 직접 만난다 — 트랜잭션을
 *       꺼서 그 save()들이 즉시 커밋되게 한다.</li>
 *   <li><b>정리</b> — 성공한 적재는 REQUIRES_NEW가 이미 별도 커넥션에 커밋해 버렸으므로 클래스
 *       레벨 롤백을 타지 않는다. 처음에는 finally에서 {@code jdbc}로 직접 delete만 하면 될 줄
 *       알았지만(REQUIRES_NEW 없이), <b>그 delete 자체가 여전히 "곧 롤백될 테스트 트랜잭션 안"</b>
 *       에서 실행되어 테스트 종료 시 그 delete마저 롤백되고 5433에 행이 영구히 남는 걸 실측으로
 *       확인했다 — 그래서 트랜잭션을 통째로 꺼서 delete도 즉시 커밋되게 한다
 *       (브리프가 제시한 두 옵션 중 "테스트를 @Transactional 없이 돌리고 직접 정리" 쪽).</li>
 * </ul>
 * 예외로 실패하는 적재({@code 임베딩_손상시_전체_롤백되고_refKey가_로그에_남는다})는 REQUIRES_NEW
 * 자체가 자기 물리 커넥션에서 진짜로 롤백하므로 클래스 레벨 트랜잭션을 그대로 두어도(정리 delete가
 * 없으므로) 문제없고, 별도 정리가 필요 없다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EvidenceSeedLoaderIT {

    @Autowired private EvidenceSeedLoader loader;
    @Autowired private EvidenceChunkRepository repo;
    @Autowired private LawArticleRepository laws;
    @Autowired private MsdsCacheRepository msds;
    @Autowired private PublicCaseRepository cases;
    @Autowired private JdbcTemplate jdbc;

    /**
     * Review Focus 2: 시드 파일 순서를 신뢰하지 않는다 — parent가 child보다 뒤에 나와도 연결돼야 한다.
     *
     * <p>클래스 레벨 {@code @Transactional}을 여기서도 {@code NOT_SUPPORTED}로 끈다 — 안 그러면
     * finally의 정리용 delete가 "같은(곧 롤백될) 테스트 트랜잭션 안"에서 실행돼, REQUIRES_NEW가
     * 이미 별도 커넥션에 커밋해 버린 insert를 실제로는 지우지 못하고(delete 자체는 성공하지만
     * 테스트 종료 시 그 delete마저 롤백된다) 5433에 영구히 남는다 — 실측으로 확인한 문제다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void parent가_뒤에_있어도_연결() throws Exception {
        float[] v = new float[768];
        v[0] = 1f;
        String child = "{\"kind\":\"GUIDE\",\"refKey\":\"SEED#c1\",\"chunkLevel\":\"child\",\"parentRefKey\":\"SEED#p1\","
                + "\"searchable\":true,\"sectionTitle\":\"s\",\"title\":\"t\",\"text\":\"child\","
                + "\"metadata\":{\"guideNo\":\"SEED\"},\"embedding\":\"" + VectorCodec.encode(v) + "\"}";
        String parent = "{\"kind\":\"GUIDE\",\"refKey\":\"SEED#p1\",\"chunkLevel\":\"parent\",\"parentRefKey\":null,"
                + "\"searchable\":false,\"sectionTitle\":\"s\",\"title\":\"T\",\"text\":\"parent\",\"metadata\":{},\"embedding\":null}";

        try {
            int n = loader.loadChunksFrom(new BufferedReader(new StringReader(child + "\n" + parent + "\n")));

            assertThat(n).isEqualTo(2);
            Long pid = repo.findIdByRefKey(EvidenceKind.GUIDE, "SEED#p1").orElseThrow();
            var hits = repo.vectorSearch(v, Set.of(EvidenceKind.GUIDE), null, 5);
            assertThat(hits).anySatisfy(h -> {
                assertThat(h.refKey()).isEqualTo("SEED#c1");
                assertThat(h.parentId()).isEqualTo(pid);
            });
        } finally {
            // loadChunksFrom은 REQUIRES_NEW라 클래스 레벨 @Transactional 롤백을 타지 않는다 — 직접 정리.
            // child(SEED#c1)가 parent(SEED#p1)를 FK로 참조하므로 child부터 지운다
            deleteChunks("SEED#c1", "SEED#p1");
        }
    }

    /**
     * "테이블이 비어 있을 때만" 적재한다 — 이미 데이터가 있으면 부분 병합하지 않고 통째로 건너뛴다.
     * F-IT1: 5433은 이제 law_article(2303건)·msds_cache(9건)뿐 아니라 evidence_chunk도
     * 실제 시드(약 4만행, GUIDE/LAW/CASE_*)로 채워져 있다 — 세 로더 모두 스킵 로그를 내고
     * 건수가 그대로인지 확인한다. 건수가 그대로인 것과 "건너뜀" 로그가 실제로 찍힌 것 둘 다
     * 확인해 우연히 0건 적재된 것과 구분한다.
     *
     * <p>Spring Data 리포지토리는 JDK 동적 프록시라 Mockito spy가 감싸지 못한다(NotAMockException) —
     * 그래서 스파이 대신 Logback {@link ListAppender}로 [SEED] 로그를 직접 캡처한다.
     */
    @Test
    void 이미_데이터가_있으면_건드리지_않는다() {
        long lawsBefore = laws.count();
        long msdsBefore = msds.count();
        Map<String, Long> chunksBefore = repo.countByKind();
        assertThat(lawsBefore).isGreaterThan(0);
        assertThat(msdsBefore).isGreaterThan(0);
        assertThat(chunksBefore).isNotEmpty();

        Logger logger = (Logger) LoggerFactory.getLogger(EvidenceSeedLoader.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level original = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            loader.load();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(original);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(m -> assertThat(m).contains("law_article에 이미 데이터가 있어"))
                .anySatisfy(m -> assertThat(m).contains("msds_cache에 이미 데이터가 있어"))
                .anySatisfy(m -> assertThat(m).contains("evidence_chunk에 이미 데이터가 있어"));
        assertThat(laws.count()).isEqualTo(lawsBefore);
        assertThat(msds.count()).isEqualTo(msdsBefore);
        assertThat(repo.countByKind()).isEqualTo(chunksBefore);
    }

    /**
     * evidence_chunk.jsonl.gz는 Task 6이 나중에 만든다 — 지금 클래스패스에 없어도 부팅이
     * 막히면 안 된다(전역 제약: 심사위원은 키가 없다 → 시드가 없어도 앱은 뜬다).
     */
    @Test
    void 시드파일이_없어도_예외없이_넘어간다() {
        assertThatCode(() -> loader.load()).doesNotThrowAnyException();
    }

    /**
     * fix round 1 F1: resolveRefId가 실제 DB 행을 상대로 자연키 → id 재해석을 하는지 검증한다.
     * public_case·law_article에 실제 행을 하나씩 심고, CaseChunkBuilder/LawChunkBuilder의
     * refKey 포맷을 그대로 재현한 청크 줄을 먹여 raw SQL로 ref_id가 진짜 id와 같은지 확인한다.
     * 자연키가 없는 refKey는 ref_id=0 + [SEED] warn(refKey 포함)으로 떨어져야 한다.
     *
     * <p>클래스 레벨 {@code @Transactional}을 {@code NOT_SUPPORTED}로 꺼둔다 — loadChunksFrom이
     * REQUIRES_NEW(별도 커넥션)로 evidence_chunk를 넣으므로, public_case·law_article 저장이
     * 테스트 트랜잭션 안에 미커밋 상태로 남아 있으면 그 별도 커넥션에서 조회(caseIdsBySourceKey 등)가
     * 보지 못한다. 그래서 이 메서드만 트랜잭션 없이(즉시 커밋) 돌리고, 끝에서 세 테이블 모두 직접 정리한다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void refId는_자연키로_대상DB의_id로_재해석된다() throws Exception {
        PublicCase savedCase = cases.save(PublicCase.builder()
                .source("DISASTER").sourceKey("IT-REF-1")
                .keyword("IT 테스트 사례").contents("테스트 본문").build());
        LawArticle savedLaw = laws.save(LawArticle.builder()
                .lawId("999999").lawName("테스트법").articleNo(1).articleSub(0).paragraphNo(1)
                .title("테스트조").text("테스트 조문 내용").build());

        // CaseChunkBuilder: refKey = source + ":" + sourceKey
        String caseRefKey = savedCase.getSource() + ":" + savedCase.getSourceKey();
        // LawChunkBuilder child: refKey = lawId + ":" + articleNo + ":" + articleSub + ":" + paragraphNo
        String lawRefKey = savedLaw.getLawId() + ":" + savedLaw.getArticleNo() + ":" + savedLaw.getArticleSub()
                + ":" + savedLaw.getParagraphNo();
        String unknownRefKey = "DISASTER:존재하지-않는-키";

        float[] v = new float[768];
        v[0] = 1f;
        String caseLine = "{\"kind\":\"CASE_DISASTER\",\"refKey\":\"" + caseRefKey + "\",\"chunkLevel\":\"child\","
                + "\"parentRefKey\":null,\"searchable\":true,\"sectionTitle\":null,\"title\":\"사례\",\"text\":\"t\","
                + "\"metadata\":{},\"embedding\":\"" + VectorCodec.encode(v) + "\"}";
        String lawLine = "{\"kind\":\"LAW\",\"refKey\":\"" + lawRefKey + "\",\"chunkLevel\":\"child\","
                + "\"parentRefKey\":null,\"searchable\":true,\"sectionTitle\":null,\"title\":\"조문\",\"text\":\"t\","
                + "\"metadata\":{},\"embedding\":\"" + VectorCodec.encode(v) + "\"}";
        String unknownLine = "{\"kind\":\"CASE_DISASTER\",\"refKey\":\"" + unknownRefKey + "\",\"chunkLevel\":\"child\","
                + "\"parentRefKey\":null,\"searchable\":true,\"sectionTitle\":null,\"title\":\"미해결\",\"text\":\"t\","
                + "\"metadata\":{},\"embedding\":\"" + VectorCodec.encode(v) + "\"}";

        Logger logger = (Logger) LoggerFactory.getLogger(EvidenceSeedLoader.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            int n = loader.loadChunksFrom(new BufferedReader(
                    new StringReader(caseLine + "\n" + lawLine + "\n" + unknownLine + "\n")));

            assertThat(n).isEqualTo(3);
            assertThat(refIdOf("CASE_DISASTER", caseRefKey)).isEqualTo(savedCase.getId());
            assertThat(refIdOf("LAW", lawRefKey)).isEqualTo(savedLaw.getId());
            assertThat(refIdOf("CASE_DISASTER", unknownRefKey)).isEqualTo(0L);
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(m -> {
                        assertThat(m).contains("자연키 미해결");
                        assertThat(m).contains(unknownRefKey);
                    });
        } finally {
            logger.detachAppender(appender);
            // 이 메서드는 NOT_SUPPORTED라 자동 롤백이 없다 — 세 테이블 모두 직접 정리
            deleteChunks(caseRefKey, lawRefKey, unknownRefKey);
            jdbc.update("delete from public_case where id = ?", savedCase.getId());
            jdbc.update("delete from law_article where id = ?", savedLaw.getId());
        }
    }

    /**
     * fix round 1 F2: 파일 중간의 손상된 임베딩(길이 불일치)이 IllegalArgumentException을 던지면
     * refKey를 담은 [SEED] error 로그가 남고, loadChunksFrom을 감싼 REQUIRES_NEW 트랜잭션 전체가
     * 자기 물리 커넥션에서 진짜로 롤백돼(부분 적재 없음) 그 이전에 insert된 parent도 남지 않는다.
     *
     * <p>이 테스트는 예외 직후 바로 0건을 확인해야 한다 — 클래스 레벨 {@code @Transactional} 하나만
     * 으로는(마킹만 하고 실제 ROLLBACK은 테스트 종료 시 실행) 같은 커넥션에서 직후 조회 시 행이 남아
     * 보일 수 있지만, REQUIRES_NEW는 별도 물리 커넥션에서 즉시 커밋/롤백하므로 이 테스트의(클래스
     * 레벨) 트랜잭션 상태와 무관하게 결과가 바로 반영된다.
     */
    @Test
    void 임베딩_손상시_전체_롤백되고_refKey가_로그에_남는다() {
        float[] badVector = new float[767]; // 768이어야 하는데 1개 모자란 손상 벡터
        String corruptRefKey = "BAD#c1";
        String parent = "{\"kind\":\"GUIDE\",\"refKey\":\"BAD#p1\",\"chunkLevel\":\"parent\",\"parentRefKey\":null,"
                + "\"searchable\":false,\"sectionTitle\":\"s\",\"title\":\"T\",\"text\":\"parent\",\"metadata\":{},\"embedding\":null}";
        String child = "{\"kind\":\"GUIDE\",\"refKey\":\"" + corruptRefKey + "\",\"chunkLevel\":\"child\","
                + "\"parentRefKey\":\"BAD#p1\",\"searchable\":true,\"sectionTitle\":\"s\",\"title\":\"t\",\"text\":\"child\","
                + "\"metadata\":{},\"embedding\":\"" + VectorCodec.encode(badVector) + "\"}";

        Logger logger = (Logger) LoggerFactory.getLogger(EvidenceSeedLoader.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> loader.loadChunksFrom(new BufferedReader(new StringReader(parent + "\n" + child + "\n"))))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(m -> {
                    assertThat(m).contains("근거 청크 임베딩 손상");
                    assertThat(m).contains(corruptRefKey);
                });
        // REQUIRES_NEW가 실제로 롤백했다면 parent("BAD#p1")도 이 시점에 이미 없어야 한다(부분 적재 없음)
        Long remaining = jdbc.queryForObject("select count(*) from evidence_chunk where ref_key like 'BAD#%'", Long.class);
        assertThat(remaining).isEqualTo(0L);
    }

    /**
     * A4 hotfix H2: SeedExporter가 이제 Q8 양자화로 벡터를 내보내므로, 로더가 그 레이아웃을
     * 자동 판별해 그대로 적재하는지 확인한다({@code VectorCodec.decode}는 EvidenceSeedLoader에서
     * 변경 없이 그대로 쓰인다). 인코딩→로드 후 DB에 저장된 embedding이 원본과 근사 일치해야
     * 한다 — 허용 오차는 성분당 {@code (max-min)/255} 이다(Q8 양자화 격자 간격).
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void Q8_인코딩된_시드도_근사값으로_적재된다() throws Exception {
        float[] v = new float[768];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) Math.sin(i) * 0.5f;
        }
        v[0] = 1f;
        v[100] = -0.9f;
        String refKey = "Q8#c1";
        String child = "{\"kind\":\"GUIDE\",\"refKey\":\"" + refKey + "\",\"chunkLevel\":\"child\","
                + "\"parentRefKey\":null,\"searchable\":true,\"sectionTitle\":\"s\",\"title\":\"t\",\"text\":\"child\","
                + "\"metadata\":{},\"embedding\":\"" + VectorCodec.encodeQ8(v) + "\"}";

        try {
            int n = loader.loadChunksFrom(new BufferedReader(new StringReader(child + "\n")));
            assertThat(n).isEqualTo(1);

            String embText = jdbc.queryForObject(
                    "select embedding::text from evidence_chunk where ref_key = ?", String.class, refKey);
            float[] stored = SeedExporter.parseVector(embText);

            float min = Float.POSITIVE_INFINITY;
            float max = Float.NEGATIVE_INFINITY;
            for (float f : v) {
                if (f < min) {
                    min = f;
                }
                if (f > max) {
                    max = f;
                }
            }
            float tolerance = (max - min) / 255f + 1e-3f;
            for (int i = 0; i < v.length; i++) {
                assertThat(stored[i]).as("index %d", i).isCloseTo(v[i], within(tolerance));
            }
        } finally {
            deleteChunks(refKey);
        }
    }

    private long refIdOf(String kind, String refKey) {
        Long id = jdbc.queryForObject("select ref_id from evidence_chunk where kind = ? and ref_key = ?", Long.class, kind, refKey);
        return id == null ? -1L : id;
    }

    private void deleteChunks(String... refKeys) {
        for (String refKey : refKeys) {
            jdbc.update("delete from evidence_chunk where ref_key = ?", refKey);
        }
    }
}
