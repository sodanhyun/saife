-- 순회점검 사진 분석의 범용화 (2026-10-06)
-- 설비를 고르지 않고 사진만 올려도 사진 속 안전 문제와 예방 방법을 알려 준다.
-- 1) 사진 상황 한 줄과 사진 속 주요 설비 이름(설비 기록 연결 제안에 쓴다)
-- 2) 위험요인별 예방 방법과 오늘 사진의 판독 내용(설비 대장의 기존 위험요인에 합쳐도 오늘 사진 서술이 남는다)
ALTER TABLE assessment ADD COLUMN photo_scene TEXT;
ALTER TABLE assessment ADD COLUMN photo_equipment VARCHAR(100);
ALTER TABLE assessment_hazard ADD COLUMN prevention TEXT;
ALTER TABLE assessment_hazard ADD COLUMN photo_note TEXT;
