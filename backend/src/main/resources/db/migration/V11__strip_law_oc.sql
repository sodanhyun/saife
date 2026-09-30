-- 최종 리뷰 F1: 법제처 OC(기관코드) 자격증명 제거.
-- 예전 LawArticleParser가 목록 응답의 법령상세링크(DRF API URL, 쿼리에 OC 포함)를 원문 링크로 저장했다.
-- 이미 만들어진 볼륨에서 그 값을 지운다. 새 볼륨은 스크럽된 시드가 적재되므로 대부분 no-op이다.
-- 멱등: 두 번 돌려도 결과가 같다(조건절이 이미 고친 행을 다시 고르지 않는다).
--
-- 조문 링크 규칙은 LawUrls.articlePage와 같다:
--   https://www.law.go.kr/{enc(법령)}/{enc(법령명)}/{enc(제N조[의M])}
--   enc = UTF-8 퍼센트 인코딩, 비예약 문자는 [A-Za-z0-9._*-] (java.net.URLEncoder와 동일, 공백은 %20)

CREATE OR REPLACE FUNCTION pg_temp.saife_urlenc(t text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT coalesce(string_agg(
           CASE WHEN ch ~ '^[A-Za-z0-9._*-]$' THEN ch
                ELSE upper(regexp_replace(encode(convert_to(ch, 'UTF8'), 'hex'), '(..)', '%\1', 'g'))
           END, '' ORDER BY ord), '')
  FROM regexp_split_to_table(t, '') WITH ORDINALITY AS s(ch, ord)
$$;

CREATE OR REPLACE FUNCTION pg_temp.saife_law_url(law_name text, article_no int, article_sub int) RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT 'https://www.law.go.kr/' || pg_temp.saife_urlenc('법령') || '/' || pg_temp.saife_urlenc(law_name) || '/'
         || pg_temp.saife_urlenc('제' || article_no || '조' || CASE WHEN coalesce(article_sub, 0) > 0 THEN '의' || article_sub ELSE '' END)
$$;

-- 문자열 안의 OC 쿼리 파라미터 제거. 구분자 정리는 치환 지점에서만 한다(LawUrls.stripOc와 같은 결과):
--   ?OC=x&b → ?b   /   ?OC=x → (없음)   /   &OC=x → (없음)
CREATE OR REPLACE FUNCTION pg_temp.saife_strip_oc(t text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT regexp_replace(
           regexp_replace(
             regexp_replace(t, '\?OC=[^&#"<>'' \t\r\n]*&', '?', 'g'),
           '\?OC=[^&#"<>'' \t\r\n]*', '', 'g'),
         '&OC=[^&#"<>'' \t\r\n]*', '', 'g')
$$;

-- 1) 조문 캐시: DRF 링크(OC를 지워도 키 없이는 안 열린다)는 사람용 조문 페이지로 다시 만든다
UPDATE law_article
   SET source_url = pg_temp.saife_law_url(law_name, article_no, article_sub)
 WHERE source_url IS NULL OR source_url LIKE '%/DRF/%' OR source_url ~ '[?&]OC=';

-- 2) 근거 청크 메타데이터: 조문 청크는 링크를 갖지 않지만, 어떤 문자열이든 OC가 있으면 지운다
UPDATE evidence_chunk
   SET metadata = pg_temp.saife_strip_oc(metadata::text)::jsonb
 WHERE metadata::text ~ '[?&]OC=';

-- 3) 대화·작업계획서에 얼린 근거 카드: LAW 카드의 DRF 링크는 조문 페이지로 바꾸고(카드·meta 둘 다),
--    그 밖의 문자열에 남은 OC는 지운다
UPDATE conversation_evidence
   SET payload = jsonb_set(jsonb_set(payload, '{sourceUrl}',
                   to_jsonb(pg_temp.saife_law_url(payload->'meta'->>'lawName', (payload->'meta'->>'articleNo')::int,
                                                  coalesce((payload->'meta'->>'articleSub')::int, 0)))),
                 '{meta,sourceUrl}',
                   to_jsonb(pg_temp.saife_law_url(payload->'meta'->>'lawName', (payload->'meta'->>'articleNo')::int,
                                                  coalesce((payload->'meta'->>'articleSub')::int, 0))))
 WHERE payload->>'kind' = 'LAW' AND payload->>'sourceUrl' LIKE '%/DRF/%'
   AND payload->'meta'->>'lawName' IS NOT NULL AND payload->'meta'->>'articleNo' ~ '^[0-9]+$';

UPDATE work_plan_evidence
   SET payload = jsonb_set(jsonb_set(payload, '{sourceUrl}',
                   to_jsonb(pg_temp.saife_law_url(payload->'meta'->>'lawName', (payload->'meta'->>'articleNo')::int,
                                                  coalesce((payload->'meta'->>'articleSub')::int, 0)))),
                 '{meta,sourceUrl}',
                   to_jsonb(pg_temp.saife_law_url(payload->'meta'->>'lawName', (payload->'meta'->>'articleNo')::int,
                                                  coalesce((payload->'meta'->>'articleSub')::int, 0))))
 WHERE payload->>'kind' = 'LAW' AND payload->>'sourceUrl' LIKE '%/DRF/%'
   AND payload->'meta'->>'lawName' IS NOT NULL AND payload->'meta'->>'articleNo' ~ '^[0-9]+$';

UPDATE conversation_evidence
   SET payload = pg_temp.saife_strip_oc(payload::text)::jsonb
 WHERE payload::text ~ '[?&]OC=';

UPDATE work_plan_evidence
   SET payload = pg_temp.saife_strip_oc(payload::text)::jsonb
 WHERE payload::text ~ '[?&]OC=';
