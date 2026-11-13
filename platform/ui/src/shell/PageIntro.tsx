import { HelpLinks } from "./HelpLinks";

/**
 * One line beside each page's title (13 Nov 2026 round 3, item 5): what the page does, extra small but readable, then
 * Docs and Video (item 6). The sentences were approved by the user on 13 Nov 2026; a page with none shows only the links.
 * Keys are the help keys of helpCatalogue.ts.
 */
export const PAGE_INTROS: Record<string, string> = {
  "page:home": "How your agents are doing today, and a way back to your recent work.",
  "studio:agents-public": "Every agent checked in to run, by folder, with its live version.",
  "page:automation": "Every agent checked in to run, by folder, with its live version.",
  "studio:agents-private": "Your own workspace: build, test and check in agents.",
  "page:templates": "Ready-made agents to install into a folder and adapt.",
  "page:apitasks": "Agents published as web services that other systems can call.",
  "page:tests": "Test sets that check an agent still gives the right results.",
  "page:toolkits": "The command packages agents use, their versions and where they are used.",
  "page:ideas": "Ideas for new agents, scored and voted on before anyone builds them.",
  "page:apps": "Screens built for people to start agents and see results.",
  "page:activity": "What is running, what runs next, and every run that has ended.",
  "page:inbox": "Tasks agents and workflows give to people, to take and complete.",
  "page:devices": "The computers that run agents, their status and who may use them.",
  "page:locks": "Shared systems a run holds so that two runs never clash.",
  "page:runner-status": "Each runner's health right now: online, busy, and what needs attention.",
  "page:schedules": "When each agent runs on its own, and on which runner.",
  "page:triggers": "Events that start an agent: a file, an e-mail, a document, a call.",
  "page:upcoming": "Every scheduled run coming up, soonest first.",
  "page:queues": "Work items waiting for agents, how fast they move and what is late.",
  "page:workflows": "Long processes across agents, people and approvals, stage by stage.",
  "page:globals": "Settings and values every agent can read, kept in one place.",
  "page:data": "Tables of records that agents and apps read and write.",
  "page:savedconn": "Saved sign-ins to other systems that agents connect with.",
  "page:credentials": "Passwords and secrets agents use, stored safely and shared with care.",
  "page:insights": "How things stand now, and what needs your attention first.",
  "page:alerts": "Problems found as they happen, and the rules that watch for them.",
  "page:alert-rules": "Problems found as they happen, and the rules that watch for them.",
  "page:operations": "How agents, runners and queues performed over a period.",
  "page:business": "What your agents' work is worth: hours and money saved, and KPIs.",
  "page:users": "People who can sign in, their roles and default runner.",
  "page:roles": "What each role may see and do, permission by permission.",
  "page:teams": "Groups of people with leads, used in rules and approvals.",
  "page:settings": "System-wide settings for security, runners, mail and modules.",
  "page:policies": "Rules that agents in a folder must follow when they are built and run.",
  "page:audit": "Who did what and when, kept for review and export.",
  "page:events": "Systems told at once when something happens here.",
  "page:licenses": "The licence, its seats, and who and what is using them.",
  "page:chat": "Ask in plain words, and start agents from the conversation.",
  "page:connections": "The AI models agents may use, their keys and their cost.",
  "page:skills": "Reusable, tested prompts that agents call by name.",
  "page:knowledge": "Documents that AI steps search to answer from your own content.",
  "page:governance": "A record of every AI call: who, which model, and what was masked.",
  "page:docautomation": "Every document process: how it is set up and what is happening.",
  "docs:overview": "Every document process: how it is set up and what is happening.",
  "docs:inbox": "Documents waiting for review, and the ones already handled.",
  "docs:processes": "Build a document process step by step, from intake to going live.",
  "docs:types": "Document types, reference data, engines and the test lab.",
  "docs:refdata": "Document types, reference data, engines and the test lab.",
  "docs:engines": "Document types, reference data, engines and the test lab.",
  "docs:testlab": "Document types, reference data, engines and the test lab.",
};

/** The line after a page's title and ⓘ: its one-line description, then Docs and Video. */
export function PageIntro({ k }: { k: string }) {
  const text = PAGE_INTROS[k];
  return (
    <span className="st-page-intro">
      {text && <span className="st-page-desc">{text}</span>}
      <HelpLinks k={k} />
    </span>
  );
}
