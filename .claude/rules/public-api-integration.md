---
globs: ["backend/**/publicapi/**/*.java", "backend/**/*PublicApi*.java", "backend/**/*Crawler*.java"]
---

# 공공데이터 API 연동 규칙

2026-09-20 실측으로 확인한 내용이다. **동봉된 활용가이드 문서는 구버전이라 그대로 따르면 400이 난다.**

## ⚠️ B552468은 공유 게이트웨이다

공단 API 4종이 `apis.data.go.kr/B552468/*` 아래 같이 산다. **실제 데이터셋을 고르는 것은
경로가 아니라 `callApiId` 파라미터다.**

KOSHA GUIDE 경로에 `callApiId=1040`을 넣으면 **사고사망 데이터(totalCount 2,940)가 나온다.**
에러가 아니다. 200이 떨어지고 엉뚱한 데이터가 조용히 들어온다.

### 반드시 지킬 것

**캐싱 크롤러에 데이터셋별 응답 필드 검증을 넣는다.** `application.yml`의
`public-api.kosha.datasets.*.verify-field`가 그 용도다. 검증에 실패하면 저장하지 말고 중단한다.

이게 없으면 잘못된 데이터를 5일간 모아놓고도 아무 경고가 없다.

## 엔드포인트 표 (실측)

| 데이터셋 | callApiId | 경로 | 검증 필드 | 규모 |
|---|---|---|---|---|
| 사고사망 | **1040** | `/news_api02/getNews_api02` | `keyword` | 2,940 |
| KOSHA GUIDE | **1050** | `/koshaguide/getKoshaGuide` | `techGdlnNo` | 1,039 |
| 국내재해사례 | **1060** | `/disaster_api02/getdisaster_api02` | `business` | 6,372 |
| 재해사례 첨부 | 1070 | `/disaster_attach_api02/Disaster_attch_api02` | — | 미해결(400) |

**경로에 오타와 비일관 케이싱이 그대로 배포되어 있다.** 추측으로 만들면 전부 400이다:

- `getdisaster_api02` — 소문자 `d`
- `Disaster_attch_api02` — `attach`가 아니라 **`attch`**, `get` 접두사 없음, 대문자 `D`
- MSDS 목록은 `getChemList`가 아니라 **`getChemList001`**

새 API를 붙일 때는 **활용가이드 문서에서 샘플 호출 URL을 추출해 그대로 쓴다.**
`.docx`는 zipfile + 정규식, `.hwp`는 olefile로 읽는다(아래).

## MSDS는 2단 호출이다 (별도 체계, callApiId 안 씀)

```
1) 목록:  /msdschem1/getChemList001?serviceKey=..&searchWrd=톨루엔&searchCnd=0
          → chemId, casNo, unNo, keNo, enNo, chemNameKor
2) 상세:  /msdschem1/getChemDetail0{N}1?serviceKey=..&chemId=001032   (N = 01~16)
```

`getChemDetail011`~`getChemDetail161`이 **MSDS 법정 16개 항목에 1:1 대응**한다.
응답 XML `<item>`에 `msdsItemCode` / `msdsItemNameKor` / `itemDetail` / `lev` / `upMsdsItemCode`.
`itemDetail`은 **`|`가 줄 구분자**이고, `lev`+`upMsdsItemCode`로 2단 계층을 이룬다.

**UC3가 쓰는 건 4개 항목뿐이다**: 02(유해성) · 05(폭발화재) · 07(취급저장) · 08(노출방지·보호구).
→ **물질당 4콜.** 16콜을 다 받지 않는다.

**MSDS만 건별 호출이라 쿼터를 먹는 유일한 API다.** 나머지는 목록형이라 전수를 받아도 ~40콜이다.

## 사고사망(1040) 응답 전처리

필드가 `contents` / `keyword` / `arno` **3개뿐**이다. 일자·장소·사망자 수가 구조화 필드로
오지 않고 전부 자연어 안에 있다.

**`keyword`가 매칭의 주력이다.** 포맷이 완전히 정규화되어 있다:

```
[9/17, 충남 아산시] 차량 유도 작업 중 후진하는 타이어롤러에 부딪힘
```

`contents`는 전처리 3종이 필수다:
1. HTML 태그와 `<img>` 제거
2. `※ 위 내용은 신고 및 현재 파악된 내용으로…` 꼬리말 제거
3. 공백 정규화 — 태그 제거 부산물로 `15:29 경`, `( 수 )`, `사망 1 명`처럼 깨져 있다

## 사례 소스 우선순위

| | 사고사망 (1040) | **국내재해사례 (1060)** |
|---|---|---|
| 건수 | 2,940 | **6,372** |
| 범위 | 사망사고만 | 재해사례 전반 |
| 업종 | 텍스트 추론 필요 | **`business` 필드** (건설 39% / 제조 32% / 서비스 20% / 조선 8.4%) |
| contents | 138~155자, 전처리 필요 | **평균 82자, 깔끔** |

- **1060 = 사례 매칭 1차 소스**
- **1040 = 중대 위험 근거** ("이 위험요인으로 사람이 죽었다"를 말할 때)
- **산재통계 마이크로데이터 = 빈도 근거** (사망사고 통계만 보면 넘어짐이 과소평가된다)

## 법제처 국가법령정보 OPEN API

**별도 시스템이다.** 공공데이터포털의 `serviceKey`가 아니라 **`OC`(기관코드)**를 쓰고,
쿼리 파라미터 이름도 `OC`다. 가입 이메일의 `@` 앞부분으로 자동 결정되어 재발급 개념이 없다.

```
목록:  http://www.law.go.kr/DRF/lawSearch.do?OC=..&target=law&type=JSON&query=산업안전보건법
본문:  http://www.law.go.kr/DRF/lawService.do?OC=..&target=law&type=JSON&MST=..
```

일 1,000건 제한이 적용되지 않는다(실제 한도는 미확인). 필요 조문이 ~15건이라 문제될 일은 없다.

## 크롤러 구현 규칙

- **재개 가능(체크포인트) 방식으로 만든다.** 쿼터는 KST 자정 리셋이라 중간 실패가 하루를 날린다
- `numOfRows=300` 기준. 사고사망 전수 10회, KOSHA GUIDE 4회, 국내재해사례 22회
- **서비스키는 디코딩 키를 넣고 HTTP 클라이언트가 인코딩하게 둔다.** 인코딩 키를 다시 인코딩하면 실패한다
- 수집 결과는 `public_case` / `kosha_guide` / `msds_cache` 테이블에 적재한다.
  무대에서는 이 테이블만 읽는다 — 외부 호출 0

## 가이드 문서 읽는 법

**`.docx`**: `zipfile.ZipFile(f).read('word/document.xml')` → `<w:t>` 추출.
가이드가 글자 사이에 공백을 넣어두는 경우가 있어 공백 제거 후 정규식을 건다.

**`.hwp`** (HWP 5.x = OLE 복합문서):
1. `olefile`로 열고 `FileHeader` offset 36 플래그 확인 (bit0=압축, bit1=암호화)
2. `BodyText/SectionN` 스트림을 `zlib.decompress(raw, -15)`로 푼다
3. 레코드 헤더 DWORD: `tag=bits0-9`, `level=10-19`, `size=20-31`. `size==0xFFF`면 다음 DWORD가 실제 크기
4. **tag 67(`HWPTAG_PARA_TEXT`)**의 UTF-16LE 텍스트를 모은다
5. 확장 제어문자(1,2,3,11,12,14~18,21,22,23)는 16바이트 skip

공공기관 API 가이드가 hwp로만 배포되는 경우가 많아 재사용 가치가 높다.
