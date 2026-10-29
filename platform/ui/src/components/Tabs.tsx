import * as RadixTabs from "@radix-ui/react-tabs";
import type { ReactNode } from "react";

/** Tabs with a blue underline on the open one (Radix gives arrow-key movement and the tab roles). */
export function Tabs({ tabs, value, onChange, label, children }:
  { tabs: { value: string; label: ReactNode }[]; value: string; onChange: (v: string) => void; label: string; children?: ReactNode }) {
  return (
    <RadixTabs.Root value={value} onValueChange={onChange} className="ui-tabs">
      <RadixTabs.List aria-label={label} className="ui-tab-list">
        {tabs.map((t) => <RadixTabs.Trigger key={t.value} value={t.value} className="ui-tab">{t.label}</RadixTabs.Trigger>)}
      </RadixTabs.List>
      {children}
    </RadixTabs.Root>
  );
}

export const TabPanel = ({ value, children }: { value: string; children: ReactNode }) =>
  <RadixTabs.Content value={value} className="ui-tab-panel">{children}</RadixTabs.Content>;
