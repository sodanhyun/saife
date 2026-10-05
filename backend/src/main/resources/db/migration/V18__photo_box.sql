-- 순회점검 사진에서 위험요인이 드러난 위치 (2026-10-06)
-- 같은 위험요인도 사진마다 위치가 달라 평가와 위험요인의 연결에 둔다.
-- 형식: "ymin,xmin,ymax,xmax" (사진 크기 기준 0~1000 정수). 사진이 아닌 평가는 비어 있다
ALTER TABLE assessment_hazard ADD COLUMN photo_box VARCHAR(40);
