package io.saife.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** V9가 만든 테이블·컬럼이 실제로 있는지. 엔티티 validate가 통과했다는 뜻이기도 하다 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaSmokeTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void evidence_chunk_테이블과_tsv_생성컬럼이_있다() {
        Integer n = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'evidence_chunk' and column_name in ('embedding','tsv','searchable','parent_id')
                """, Integer.class);
        assertThat(n).isEqualTo(4);
    }

    @Test
    void public_case에_image_url이_있다() {
        Integer n = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'public_case' and column_name in ('image_url','source_url')
                """, Integer.class);
        assertThat(n).isEqualTo(2);
    }

    @Test
    void law_article_유니크가_있다() {
        Integer n = jdbc.queryForObject("""
                select count(*) from pg_indexes where tablename = 'law_article'
                """, Integer.class);
        assertThat(n).isGreaterThanOrEqualTo(2);
    }
}
