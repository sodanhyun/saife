// src/components/common/RouteErrorBoundary.tsx
import { Component, Fragment } from "react";
import type { ErrorInfo, ReactNode } from "react";

import { AlertTriangle } from "lucide-react";

import Button from "@/components/ui/Button";
import PageLayout from "@/components/ui/PageLayout";

interface Props {
  children: ReactNode;
  /** 이 값이 변경되면 에러 상태를 자동 리셋 (라우트 key 전달용) */
  resetKey?: string;
}

interface State {
  hasError: boolean;
  /** 에러 발생 시점의 resetKey — 이후 변경 감지용 */
  prevResetKey?: string;
  /** 다시 시도 횟수 — children의 key로 써서 같은 서브트리를 새로 마운트한다. */
  resetCount: number;
}

/**
 * 라우트 레벨 Error Boundary — 페이지 간 격리.
 * 한 페이지의 렌더링 에러가 다른 페이지에 영향을 주지 않는다.
 * resetKey(라우트 pathname)가 변경되면 에러 상태를 자동 리셋하여
 * 다른 메뉴 전환 시 정상 렌더링을 복구한다.
 *
 * **복구 버튼이 두 개인 이유** — React는 폴백을 보여 주기 전에 렌더를 한 번 다시 시도한다.
 * 즉 이 화면이 떴다는 것은 그 재시도에서도 같은 에러가 났다는 뜻이라, 같은 입력으로 서브트리만
 * 다시 마운트하는 "다시 시도"는 대부분 같은 결과로 끝난다. 실제로 복구되는 경로는 새로고침이다
 * (배포 직후 낡은 청크, 깨진 클라이언트 상태처럼 문서를 다시 받아야 풀리는 원인이 대부분).
 * 그래서 새로고침을 기본 버튼으로 두고, 다시 시도는 보조로 남긴다.
 */
export default class RouteErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false, resetCount: 0 };

  static getDerivedStateFromError(): Partial<State> {
    return { hasError: true };
  }

  static getDerivedStateFromProps(props: Props, state: State): Partial<State> | null {
    // resetKey가 에러 발생 시점과 달라졌으면 에러 상태 초기화 (라우트 전환)
    if (state.hasError && state.prevResetKey !== undefined && props.resetKey !== state.prevResetKey) {
      return { hasError: false, prevResetKey: props.resetKey };
    }
    return null;
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error("[RouteErrorBoundary]", error, errorInfo);
    // 에러 발생 시점의 resetKey 기록 — 이후 라우트 변경 감지에 사용
    if (this.props.resetKey !== undefined) {
      this.setState({ prevResetKey: this.props.resetKey });
    }
  }

  /** 다시 시도 — key를 올려 같은 서브트리를 처음부터 다시 마운트한다(상태·요청 초기화). */
  handleReset = () => {
    this.setState((prev) => ({ hasError: false, resetCount: prev.resetCount + 1 }));
  };

  /** 새로고침 — 문서를 다시 받는다. 낡은 청크·깨진 클라이언트 상태가 원인일 때 유일한 복구 경로다. */
  handleReload = () => {
    window.location.reload();
  };

  render() {
    if (this.state.hasError) {
      return (
        <PageLayout>
          <div className="flex flex-col items-center justify-center gap-4 py-20">
            <AlertTriangle size={48} className="text-risk-high-border" />
            <h2 className="text-xl font-bold text-slate-900">오류가 발생했습니다</h2>
            <p className="text-sm text-slate-500">페이지를 새로고침하거나 다시 시도해주세요.</p>
            <div className="flex gap-2">
              <Button onClick={this.handleReload}>새로고침</Button>
              <Button variant="secondary" onClick={this.handleReset}>
                다시 시도
              </Button>
            </div>
          </div>
        </PageLayout>
      );
    }
    // key를 바꿔 마운트를 강제한다 — 상태를 들고 있는 자식이 에러 직전 값을 그대로 되살리지 않게.
    return <Fragment key={this.state.resetCount}>{this.props.children}</Fragment>;
  }
}
