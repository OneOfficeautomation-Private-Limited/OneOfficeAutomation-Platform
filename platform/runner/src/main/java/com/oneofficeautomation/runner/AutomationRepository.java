package com.oneofficeautomation.runner;

import java.util.Map;

/** Source of published automations for child bots and agent tools (PostgreSQL-backed in ooa-core). */
public interface AutomationRepository {
    /** @param version a version number, or "production" */
    Map<String, Object> get(String automationId, Object version);
}
