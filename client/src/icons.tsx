// 线性小图标，跟随文字颜色。
const base = { width: 18, height: 18, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 1.8, strokeLinecap: "round" as const, strokeLinejoin: "round" as const };

export const PenIcon = () => (
  <svg {...base} aria-hidden>
    <path d="M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16v4Z" />
    <path d="m13.5 6.5 4 4" />
  </svg>
);

export const PlusIcon = () => (
  <svg {...base} aria-hidden>
    <path d="M12 5v14M5 12h14" />
  </svg>
);

export const SendIcon = () => (
  <svg {...base} width={16} height={16} strokeWidth={2.2} aria-hidden>
    <path d="M12 19V5M6 11l6-6 6 6" />
  </svg>
);

export const ChevronIcon = () => (
  <svg {...base} width={14} height={14} strokeWidth={2} aria-hidden>
    <path d="m6 9 6 6 6-6" />
  </svg>
);

export const CheckIcon = () => (
  <svg {...base} width={15} height={15} strokeWidth={2.2} aria-hidden>
    <path d="m5 12 5 5 9-10" />
  </svg>
);
