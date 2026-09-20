package io.saife.form;

import io.saife.form.service.FormDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 법정 서식 출력.
 *
 * <p><b>PDF는 브라우저 인쇄로 만든다.</b> 기획 단계에서는 headless Chrome을 백엔드에
 * 두는 안을 적었지만, 그러면 제출 이미지에 크롬이 통째로 들어가 300MB가 붙고
 * "5분 룰"(clone 후 5분 안에 기동)이 위태로워진다. 심사위원 머신에 크롬 의존을
 * 만드는 것도 위험 요소다.
 *
 * <p>대신 A4 인쇄 CSS를 입힌 HTML을 서버가 그리고, 화면에서 인쇄(Ctrl+P →
 * PDF로 저장)한다. 결과물은 동일한 PDF이고 의존성은 0이다. 서식의 <b>항목 순서와
 * 문구가 법정 요건을 만족하는지</b>가 심사 대상이지 생성 방식이 아니다.
 *
 * <p>위험성평가표의 항목 순서는 시행규칙 제37조의 3요소를 그대로 따른다:
 * 유해·위험요인 → 위험성 결정 내용 → 조치 내용. <b>이 순서를 바꾸지 않는다.</b>
 */
@Controller
@RequestMapping("/form")
@RequiredArgsConstructor
@Slf4j
public class FormController {

    private final FormDataService formDataService;

    /** 위험성평가표 — 시행규칙 제37조, 3년 보존 대상 */
    @GetMapping("/assessment/{assessmentId}")
    public String assessment(@PathVariable Long assessmentId, Model model) {
        model.addAttribute("form", formDataService.assessmentForm(assessmentId));
        return "form/assessment";
    }

    /** 산업재해조사표 — 휴업 3일 이상, 발생일로부터 1개월 이내 제출 */
    @GetMapping("/incident/{incidentId}")
    public String incident(@PathVariable Long incidentId, Model model) {
        model.addAttribute("form", formDataService.incidentForm(incidentId));
        return "form/incident";
    }

    /** 위험작업 작업계획서 + 작업 전 브리핑(TBM 기록) */
    @GetMapping("/work-plan/{workPlanId}")
    public String workPlan(@PathVariable Long workPlanId, Model model) {
        model.addAttribute("form", formDataService.workPlanForm(workPlanId));
        return "form/work-plan";
    }
}
