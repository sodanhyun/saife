-- V3의 손으로 넣은 표본을 걷어낸다.
--
-- V3는 크롤러가 없던 시점에 시연을 돌려보려고 사례 9건·가이드 5건을 직접 적었다.
-- 이제 실제 수집 결과 9,312건이 resources/seed/에 동봉되어 있고,
-- PublicCacheSeedLoader가 테이블이 비어 있을 때 넣는다.
--
-- 손으로 적은 행이 남아 있으면 "테이블이 비어 있지 않다"가 되어 동봉 캐시가
-- 영영 적재되지 않는다. 그래서 여기서 지운다.
--
-- ⚠️ source_key로 정확히 지목해서 지운다. 통째로 TRUNCATE하면 이미 크롤러를 돌려
--    실데이터를 갖고 있는 개발 DB에서 그 데이터가 날아간다.

DELETE FROM public_case WHERE source_key LIKE 'seed-%';

DELETE FROM kosha_guide WHERE guide_no IN
    ('C-31-2017', 'M-102-2012', 'W-18-2021', 'C-48-2012', 'P-140-2020');

-- msds_cache는 건드리지 않는다. MSDS는 물질당 4콜짜리 2단 호출이라
-- 크롤러 범위 밖이고, 톨루엔 9행이 UC3 시연에 실제로 쓰인다.
