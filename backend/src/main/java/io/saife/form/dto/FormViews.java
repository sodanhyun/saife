package io.saife.form.dto;

/**
 * 법정 서식 뷰 모델.
 *
 * <p>템플릿이 조건 분기로 계산하지 않게, <b>표시할 문자열을 여기서 다 만들어 넘긴다.</b>
 * 서식은 법정 요건이라 화면 로직이 값을 바꾸면 안 된다.
 */
public final class FormViews {

    private FormViews() {}

    // 위험성평가표 뷰는 AssessmentFormService.Form이 맡는다


    // 작업 전 안전점검표 뷰는 WorkPlanFormView가 맡는다
}
