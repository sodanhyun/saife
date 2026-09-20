-- 통과 축(PPE)에 반려된 사진 판독 후보 1건을 추가한다.
--
-- 왜 필요한가:
--   V2 시드는 반려 후보를 일부러 하나 넣어뒀다 (id=6, STRUCK 지게차 구획선).
--   채택률이 100%로 보이면 심사위원에게 신뢰가 아니라 의심을 부르기 때문이다.
--
--   그런데 2026-09-21 VLM 게이트 결과를 반영하면서 채택률 집계를
--   **통과 축(FALL·DROP·PPE)으로 한정**했고, 그 순간 id=6(STRUCK)이 분모에서
--   빠졌다. 남은 건 전부 채택 건이라 시드 채택률이 3/3 = 100%가 됐다.
--   시드 설계 의도가 게이트 반영의 부작용으로 무너진 것이다.
--
-- 왜 이 내용인가 — 지어낸 반려가 아니다:
--   게이트 30장에서 실제로 나온 오탐이다 (docs/vlm-gate-result-20260921.md, FIRE-1).
--   작업자가 안전모에 부착된 투명 안면보호구를 **내려서 얼굴을 덮고 있는데**
--   모델이 "보안경 미착용"이라고 했다. 보호구의 존재는 보는데 착용 상태를 놓친 경우다.
--   평가셋에서도 같은 계열의 반려가 1건 더 나왔다(얼굴이 프레임에 없는데 "맨눈으로 작업").
--   PPE 축의 측정된 반려율은 게이트 27% · 평가셋 25%다. 시드에 반려가 0건인 쪽이
--   오히려 실측과 어긋난다.
--
-- 평가 2번(1개월 전 상시평가)에 연결한다. id=6과 같은 방식이라
--   위험성평가표에 '반려'로 찍히고, 그게 "확정은 사람이 한다"의 증빙이 된다.

INSERT INTO hazard (id, site_id, equipment_id, process_id, accident_type, missing_control,
                    description, source, ai_suggested, ai_adopted) VALUES
  (8, 1, 2, 1, 'PPE', '보안경 미착용',
   '도장 부스 앞 작업 사진에서 보안경 미착용 후보가 올라왔으나, 확인 결과 작업자가 '
   || '안전모 부착형 안면보호구를 내린 상태였음. 담당자 반려.',
   'PHOTO', true, false);
SELECT setval('hazard_id_seq', 8, true);

INSERT INTO assessment_hazard (assessment_id, hazard_id, frequency, severity, risk_level, rule_trace) VALUES
  (2, 8, 2, 2, 'MEDIUM', '보호구 미착용 확인 → ''중''. 단 담당자 반려 — 평가에 반영하지 않음');
