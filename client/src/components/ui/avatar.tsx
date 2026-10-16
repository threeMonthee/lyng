import { cn } from '@/lib/cn'

/** 人物没有画像：用名字的第一个字和由名字决定的颜色。 */
export function hue(name: string): number {
  let h = 0
  for (const ch of name) h = (h * 31 + ch.codePointAt(0)!) % 360
  return h
}

export function Avatar({ name, size = 36, className, ring }: { name: string; size?: number; className?: string; ring?: boolean }) {
  const h = hue(name)
  return (
    <span
      aria-hidden
      className={cn(
        'inline-flex shrink-0 select-none items-center justify-center rounded-full font-serif font-medium',
        ring && 'ring-2 ring-bg',
        className,
      )}
      style={{
        width: size,
        height: size,
        fontSize: Math.round(size * 0.44),
        background: `light-dark(oklch(0.92 0.035 ${h}), oklch(0.34 0.04 ${h}))`,
        color: `light-dark(oklch(0.38 0.06 ${h}), oklch(0.9 0.04 ${h}))`,
      }}
    >
      {name.slice(0, 1)}
    </span>
  )
}

export function AvatarStack({ names, size = 24 }: { names: string[]; size?: number }) {
  return (
    <span className="inline-flex -space-x-1.5">
      {names.slice(0, 5).map((n) => (
        <Avatar key={n} name={n} size={size} ring />
      ))}
    </span>
  )
}
