package com.oneofficeautomation.runner;

import java.util.Map;

/** Runs a published bot by id (used by AI agents to call bots as tools). Provided by Studio, the Bot Agent or Control Center. */
public interface BotInvoker {
    RunResult invoke(String automationId, Map<String, Object> inputs);
}
