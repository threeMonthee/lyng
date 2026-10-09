import { useRef } from "react";

const HOLD_MS = 450;
const MOVE_TOLERANCE_PX = 8;

/** 长按：按住不动超过 HOLD_MS 触发；手指移动（在滚动）或抬起就取消。 */
export function useLongPress(onLongPress: () => void) {
  const timer = useRef<number | undefined>(undefined);
  const origin = useRef<{ x: number; y: number } | null>(null);

  const cancel = () => {
    window.clearTimeout(timer.current);
    origin.current = null;
  };

  return {
    onPointerDown: (e: React.PointerEvent) => {
      origin.current = { x: e.clientX, y: e.clientY };
      timer.current = window.setTimeout(() => {
        origin.current = null;
        onLongPress();
      }, HOLD_MS);
    },
    onPointerMove: (e: React.PointerEvent) => {
      const start = origin.current;
      if (start && Math.hypot(e.clientX - start.x, e.clientY - start.y) > MOVE_TOLERANCE_PX) cancel();
    },
    onPointerUp: cancel,
    onPointerLeave: cancel,
    onPointerCancel: cancel,
    // 触屏长按会弹出系统菜单，挡住心声
    onContextMenu: (e: React.MouseEvent) => e.preventDefault(),
  };
}
