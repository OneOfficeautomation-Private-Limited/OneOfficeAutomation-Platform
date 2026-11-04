/** The built-in roles' names, one list for Users, Roles and Settings. "Operator", not "runner": a runner is a machine. */
export const ROLE_NAMES: Record<string, string> = { admin: "Administrator", bot_creator: "Agent builder", bot_runner: "Agent operator", viewer: "Viewer" };
export const roleName = (r: string) => ROLE_NAMES[r] ?? r;
