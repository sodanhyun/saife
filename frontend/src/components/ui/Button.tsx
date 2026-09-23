// src/components/ui/Button.tsx

import { forwardRef } from "react";

import { buttonClassName, type ButtonSize, type ButtonVariant } from "@/components/ui/buttonStyles";
import cn from "@/lib/cn";

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
}

const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ variant = "primary", size = "md", loading = false, className, children, disabled, ...props }, ref) => {
    return (
      <button
        ref={ref}
        className={cn(buttonClassName(variant, size), className)}
        disabled={disabled || loading}
        {...props}
      >
        {loading ? "처리 중…" : children}
      </button>
    );
  }
);

Button.displayName = "Button";

export default Button;
