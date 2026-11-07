import type { UiModulePages } from "../pages";
import { PoolScalingDialog } from "./PoolScaling";

/** runner-management's console pages (by menu page id) and the parts other pages show when it is installed (modules/pages.tsx finds this file). */
export const ui: UiModulePages = {
  parts: {
    "pool-scaling": PoolScalingDialog,
  },
};
