import type { Flow, FlowStep } from "../api/client";

/**
 * An AI agent is an ordinary agent file with one Run AI agent step and the tag "ai-agent": the runtime, runs,
 * debugging and check-in treat it like any agent, and the new Studio opens it in the AI agent editor.
 */
export const AI_AGENT_TAG = "ai-agent";
export const AI_ACTION = "ooa.ai_agent/run";

export const aiStep = (flow: Flow): FlowStep | undefined => flow.steps.find((s) => s.action === AI_ACTION);
export const isAiAgent = (flow: Flow) => !!flow.tags?.includes(AI_AGENT_TAG) && !!aiStep(flow);

/** The new AI agent's content: a request in, the answer, status and trace out, and one Run AI agent step. */
export function aiAgentTemplate(flow: Flow): Flow {
  return {
    ...flow,
    tags: [...new Set([...(flow.tags ?? []), AI_AGENT_TAG])],
    variables: [
      { name: "request", type: "String", direction: "input", description: "What the agent is asked to do this time (an email, a ticket, a question)." },
      { name: "answer", type: "String", direction: "output", description: "The agent's final answer." },
      { name: "status", type: "String", direction: "output", description: "completed, limit_reached, stopped, failed or cancelled." },
      { name: "outputs", type: "Dictionary", direction: "output", description: "The output fields, when some are asked for." },
      { name: "trace", type: "List", direction: "output", description: "Each model turn and tool call." },
    ],
    steps: [{
      id: "s1", action: AI_ACTION,
      args: { goal: "${request}", tools: [], max_steps: 8 },
      out: { answer: "answer", status: "status", outputs: "outputs", trace: "trace" },
    }],
  };
}
