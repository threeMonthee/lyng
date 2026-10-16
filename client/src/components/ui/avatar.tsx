import { cn } from '@/lib/cn'

/** 人物没有画像：用名字的第一个字和由名字决定的颜色。 */
export function hue(name: string): number {
  let h = 0
  for (const ch of name) h = (h * 31 + ch.codePointAt(0)!) % 360
  return h
}

/** 人物是圆的；世界用方一点的印章形，一眼分得开。 */
export function Avatar({
  name,
  size = 36,
  className,
  ring,
  square,
}: {
  name: string
  size?: number
  className?: string
  ring?: boolean
  square?: boolean
}) {
  const h = hue(name)
  return (
    <span
      aria-hidden
      className={cn(
        'inline-flex shrink-0 select-none items-center justify-center font-serif font-medium leading-none',
        square ? 'rounded-[30%]' : 'rounded-full',
        ring && 'ring-2 ring-bg',
        className,
      )}
      style={{
        width: size,
        height: size,
        fontSize: Math.round(size * (square ? 0.48 : 0.44)),
        background: `light-dark(oklch(0.93 0.03 ${h}), oklch(0.33 0.035 ${h}))`,
        color: `light-dark(oklch(0.42 0.07 ${h}), oklch(0.9 0.045 ${h}))`,
        boxShadow: ring ? undefined : `inset 0 0 0 1px light-dark(oklch(0.5 0.05 ${h} / 0.08), oklch(1 0 0 / 0.06))`,
      }}
    >
      {name.slice(0, 1)}
    </span>
  )
}

export function AvatarStack({ names, size = 22 }: { names: string[]; size?: number }) {
  return (
    <span className="inline-flex -space-x-1">
      {names.slice(0, 5).map((n, i) => (
        <span key={n} className="relative flex" style={{ zIndex: 5 - i }}>
          <Avatar name={n} size={size} ring />
        </span>
      ))}
    </span>
  )
}
