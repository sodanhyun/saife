// src/components/common/SectionErrorBoundary.tsx
import { Component } from "react";
import type { ErrorInfo, ReactNode } from "react";

import { AlertTriangle } from "lucide-react";

interface Props {
  children: ReactNode;
  /** fallback 메시지 (기본: "이 섹션을 불러올 수 없습니다") */
  fallbackMessage?: string;
}

interface State {
  hasError: boolean;
}

/**
 * 섹션 레벨 Error Boundary — 위젯 간 격리.
 * 한 차트/카드의 렌더링 에러가 같은 페이지의 다른 섹션에 영향을 주지 않는다.
 */
export default class SectionErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(): State {
    return { hasError: true };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error("[SectionErrorBoundary]", error, errorInfo);
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex items-center justify-center gap-2 py-8 text-sm text-slate-400">
          <AlertTriangle size={16} />
          <span>{this.props.fallbackMessage ?? "이 섹션을 불러올 수 없습니다"}</span>
        </div>
      );
    }
    return this.props.children;
  }
}
