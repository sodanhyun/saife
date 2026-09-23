// src/components/ui/LinkButton.tsx

import { buttonClassName } from "@/components/ui/buttonStyles";
import cn from "@/lib/cn";

interface LinkButtonProps {
  href: string;
  children: React.ReactNode;
  className?: string;
  /** true면 새 탭에서 연다(법정 서식 등) — target="_blank" rel="noreferrer" */
  external?: boolean;
  onClick?: React.MouseEventHandler<HTMLAnchorElement>;
}

/** 이동은 링크(<a>)로 하되 모양은 Button secondary·sm과 같게 — 법정 서식 링크를 손으로 다시 꾸미지 않는다. */
export default function LinkButton({ href, children, className, external = false, onClick }: LinkButtonProps) {
  return (
    <a
      href={href}
      className={cn(buttonClassName("secondary", "sm"), className)}
      onClick={onClick}
      {...(external ? { target: "_blank", rel: "noreferrer" } : {})}
    >
      {children}
    </a>
  );
}
