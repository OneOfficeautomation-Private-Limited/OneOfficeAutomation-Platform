import type { ButtonHTMLAttributes, ReactNode } from "react";
import { Icon, type IconName } from "./Icon";

/**
 * Buttons are never grey (approved look): save and primary are solid blue, run solid green, debug solid amber,
 * light is white (for the dark top bar), secondary is white with a blue outline, plain is text only (toolbars),
 * danger is solid red (only to confirm a delete).
 */
export type ButtonKind = "primary" | "run" | "debug" | "light" | "secondary" | "plain" | "danger";

export function Button({ kind = "secondary", icon, children, className, ...rest }:
  { kind?: ButtonKind; icon?: IconName; children?: ReactNode } & ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button type="button" className={`ui-btn ui-btn-${kind}${className ? " " + className : ""}`} {...rest}>
      {icon && <Icon name={icon} size={13} stroke={2.4} filled={icon === "play"} />}
      {children}
    </button>
  );
}

/** A button that only shows an icon: it needs a label for screen readers, which also shows as a tooltip. */
export function IconButton({ icon, label, ...rest }: { icon: IconName; label: string } & ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button type="button" className="ui-icon-btn" aria-label={label} title={label} {...rest}>
      <Icon name={icon} size={14} stroke={2.4} />
    </button>
  );
}
