// src/components/ui/EmptyState.tsx

import cn from "@/lib/cn";

interface EmptyStateProps {
  icon?: React.ReactNode;
  message: string;
  description?: string;
  action?: React.ReactNode;
  className?: string;
}

export default function EmptyState({ icon, message, description, action, className }: EmptyStateProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center py-20 bg-white rounded-lg border border-dashed border-slate-300",
        className
      )}
    >
      {icon}
      <span className="text-slate-500 font-medium">{message}</span>
      {description && (
        <span className="text-slate-400 text-sm mt-1">{description}</span>
      )}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}
