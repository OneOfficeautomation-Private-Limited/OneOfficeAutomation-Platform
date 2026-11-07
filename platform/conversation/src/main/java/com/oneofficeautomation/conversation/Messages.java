package com.oneofficeautomation.conversation;

import java.util.*;

/**
 * What One Chat says, in the languages of the console (English, German, French, Spanish, Japanese). The language is the
 * person's console language, kept with the chat, so updates that arrive later (a run finished, an approval decided)
 * speak it too. Agent names, input values and texts written by agent authors (their prompts) are shown as written.
 * Each {} is filled in order.
 */
public final class Messages {
    private Messages() {}

    public static final List<String> LANGUAGES = List.of("en", "de", "fr", "es", "ja");

    /** A supported language from a code such as de or de-DE; English otherwise. */
    public static String language(String code) {
        if (code == null || code.length() < 2) return "en";
        String l = code.substring(0, 2).toLowerCase(Locale.ROOT);
        return LANGUAGES.contains(l) ? l : "en";
    }

    /** The language's own name, for telling a model which language to answer in. */
    static String name(String lang) {
        return switch (language(lang)) { case "de" -> "German"; case "fr" -> "French"; case "es" -> "Spanish"; case "ja" -> "Japanese"; default -> "English"; };
    }

    public static String t(String lang, String key, Object... args) {
        Map<String, String> table = TEXTS.get(language(lang));
        String pattern = table.getOrDefault(key, TEXTS.get("en").get(key));
        if (pattern == null) throw new IllegalArgumentException("No chat text " + key);
        StringBuilder out = new StringBuilder();
        int from = 0, i = 0;
        for (int at = pattern.indexOf("{}"); at >= 0; at = pattern.indexOf("{}", from)) {
            out.append(pattern, from, at).append(i < args.length ? String.valueOf(args[i++]) : "");
            from = at + 2;
        }
        return out.append(pattern.substring(from)).toString();
    }

    /** Every key in every language: the same {} count as English (checked by MessagesTest). */
    static Map<String, Map<String, String>> all() { return TEXTS; }

    private static final Map<String, Map<String, String>> TEXTS = new LinkedHashMap<>();

    private static void put(String key, String en, String de, String fr, String es, String ja) {
        TEXTS.computeIfAbsent("en", k -> new LinkedHashMap<>()).put(key, en);
        TEXTS.computeIfAbsent("de", k -> new LinkedHashMap<>()).put(key, de);
        TEXTS.computeIfAbsent("fr", k -> new LinkedHashMap<>()).put(key, fr);
        TEXTS.computeIfAbsent("es", k -> new LinkedHashMap<>()).put(key, es);
        TEXTS.computeIfAbsent("ja", k -> new LinkedHashMap<>()).put(key, ja);
    }

    static {
        put("summary", "I'll run {}.", "Ich führe {} aus.", "Je vais exécuter {}.", "Voy a ejecutar {}.", "{} を実行します。");
        put("summary_with", "I'll run {} with {}.", "Ich führe {} aus mit {}.", "Je vais exécuter {} avec {}.", "Voy a ejecutar {} con {}.",
                "{} を次の内容で実行します: {}。");
        put("confirm", "Shall I go ahead? (yes/no)", "Soll ich fortfahren? (ja/nein)", "Je continue ? (oui/non)", "¿Continúo? (sí/no)",
                "実行してよろしいですか？ (はい/いいえ)");
        put("confirm_r3", "This is a high-impact (R3) agent: after your yes, someone other than you must approve it in the Task inbox before it runs.\nShall I ask for approval? (yes/no)",
                "Dies ist ein Agent mit hoher Tragweite (R3): Nach Ihrem Ja muss eine andere Person ihn im Aufgabeneingang freigeben, bevor er läuft.\nSoll ich die Freigabe anfordern? (ja/nein)",
                "Cet agent a un fort impact (R3) : après votre oui, une autre personne doit l'approuver dans la boîte de tâches avant son exécution.\nJe demande l'approbation ? (oui/non)",
                "Este agente es de alto impacto (R3): tras su sí, otra persona debe aprobarlo en la bandeja de tareas antes de ejecutarlo.\n¿Solicito la aprobación? (sí/no)",
                "これは影響の大きい (R3) エージェントです。あなたの承認の後、実行前に別の人がタスク受信箱で承認する必要があります。\n承認を依頼しますか？ (はい/いいえ)");
        put("declined", "OK, I won't run {}.", "In Ordnung, ich führe {} nicht aus.", "D'accord, je n'exécute pas {}.", "De acuerdo, no ejecutaré {}.",
                "了解しました。{} は実行しません。");
        put("expired", "That confirmation expired. Ask again and I'll prepare it afresh.",
                "Diese Bestätigung ist abgelaufen. Fragen Sie erneut, dann bereite ich sie neu vor.",
                "Cette confirmation a expiré. Redemandez et je la préparerai à nouveau.",
                "Esa confirmación caducó. Vuelva a pedirlo y la prepararé de nuevo.",
                "この確認は期限切れです。もう一度依頼していただければ、改めて準備します。");
        put("no_longer", "You can no longer run {}.", "Sie dürfen {} nicht mehr ausführen.", "Vous ne pouvez plus exécuter {}.", "Ya no puede ejecutar {}.",
                "{} を実行する権限がなくなりました。");
        put("not_available", "That agent is not available to you in chat.", "Dieser Agent steht Ihnen im Chat nicht zur Verfügung.",
                "Cet agent ne vous est pas proposé dans le chat.", "Ese agente no está disponible para usted en el chat.",
                "そのエージェントはチャットでは利用できません。");
        put("started", "Started {} (run {}). I'll tell you here when it finishes.",
                "{} wurde gestartet (Lauf {}). Ich sage Ihnen hier Bescheid, wenn er fertig ist.",
                "{} a démarré (exécution {}). Je vous préviens ici quand elle se termine.",
                "{} se ha iniciado (ejecución {}). Le avisaré aquí cuando termine.",
                "{} を開始しました (実行 {})。終了したらここでお知らせします。");
        put("awaiting", "I've asked for approval (task {}). {} runs once someone other than you approves it in the Task inbox; I'll tell you here.",
                "Ich habe die Freigabe angefordert (Aufgabe {}). {} läuft, sobald eine andere Person sie im Aufgabeneingang freigibt; ich sage Ihnen hier Bescheid.",
                "J'ai demandé l'approbation (tâche {}). {} s'exécutera dès qu'une autre personne l'approuvera dans la boîte de tâches ; je vous préviendrai ici.",
                "He solicitado la aprobación (tarea {}). {} se ejecutará cuando otra persona la apruebe en la bandeja de tareas; le avisaré aquí.",
                "承認を依頼しました (タスク {})。別の人がタスク受信箱で承認すると {} が実行されます。ここでお知らせします。");
        put("no_runner", "No runner is online to run {} right now. Try again later.",
                "Gerade ist kein Runner online, um {} auszuführen. Versuchen Sie es später erneut.",
                "Aucun runner n'est en ligne pour exécuter {} pour le moment. Réessayez plus tard.",
                "Ahora no hay ningún runner en línea para ejecutar {}. Inténtelo más tarde.",
                "現在 {} を実行できるランナーがオンラインではありません。後でもう一度お試しください。");
        put("not_started", "The Command Center did not start {}: {}", "Das Command Center hat {} nicht gestartet: {}",
                "Le Command Center n'a pas démarré {} : {}", "El Command Center no inició {}: {}", "Command Center は {} を開始しませんでした: {}");
        put("no_runs", "You have no runs yet.", "Sie haben noch keine Läufe.", "Vous n'avez encore aucune exécution.", "Todavía no tiene ejecuciones.",
                "まだ実行はありません。");
        put("recent_runs", "Your recent runs:", "Ihre letzten Läufe:", "Vos exécutions récentes :", "Sus ejecuciones recientes:", "最近の実行:");
        put("not_understood", "Sorry, I didn't understand that. Ask for an agent by its name, or ask \"what are my runs?\".",
                "Das habe ich leider nicht verstanden. Fragen Sie nach einem Agenten über seinen Namen oder fragen Sie „what are my runs?“.",
                "Désolé, je n'ai pas compris. Demandez un agent par son nom, ou demandez « what are my runs? ».",
                "Lo siento, no lo he entendido. Pida un agente por su nombre o pregunte «what are my runs?».",
                "すみません、理解できませんでした。エージェントを名前で指定するか、「what are my runs?」と聞いてください。");
        put("not_understood_example", "Sorry, I didn't understand that. Ask for an agent by its name, for example \"{}\", or ask \"what are my runs?\".",
                "Das habe ich leider nicht verstanden. Fragen Sie nach einem Agenten über seinen Namen, zum Beispiel „{}“, oder fragen Sie „what are my runs?“.",
                "Désolé, je n'ai pas compris. Demandez un agent par son nom, par exemple « {} », ou demandez « what are my runs? ».",
                "Lo siento, no lo he entendido. Pida un agente por su nombre, por ejemplo «{}», o pregunte «what are my runs?».",
                "すみません、理解できませんでした。エージェントを名前で指定するか (例:「{}」)、「what are my runs?」と聞いてください。");
        put("model_failed", "I couldn't get an answer from the AI model ({}). Try again, or ask for an agent by its name.",
                "Ich habe keine Antwort vom KI-Modell erhalten ({}). Versuchen Sie es erneut oder fragen Sie nach einem Agenten über seinen Namen.",
                "Je n'ai pas obtenu de réponse du modèle d'IA ({}). Réessayez, ou demandez un agent par son nom.",
                "No he obtenido respuesta del modelo de IA ({}). Inténtelo de nuevo o pida un agente por su nombre.",
                "AI モデルから回答を得られませんでした ({})。もう一度試すか、エージェントを名前で指定してください。");
        put("run_success", "{} (run {}) finished.", "{} (Lauf {}) ist fertig.", "{} (exécution {}) est terminé.", "{} (ejecución {}) ha terminado.",
                "{} (実行 {}) が完了しました。");
        put("run_results", "{} (run {}) finished. Results: {}.", "{} (Lauf {}) ist fertig. Ergebnisse: {}.", "{} (exécution {}) est terminé. Résultats : {}.",
                "{} (ejecución {}) ha terminado. Resultados: {}.", "{} (実行 {}) が完了しました。結果: {}。");
        put("run_stopped", "{} (run {}) was stopped.", "{} (Lauf {}) wurde gestoppt.", "{} (exécution {}) a été arrêté.", "{} (ejecución {}) se detuvo.",
                "{} (実行 {}) は停止されました。");
        put("run_failed", "{} (run {}) failed.", "{} (Lauf {}) ist fehlgeschlagen.", "{} (exécution {}) a échoué.", "{} (ejecución {}) ha fallado.",
                "{} (実行 {}) は失敗しました。");
        put("run_failed_reason", "{} (run {}) failed: {}", "{} (Lauf {}) ist fehlgeschlagen: {}", "{} (exécution {}) a échoué : {}",
                "{} (ejecución {}) ha fallado: {}", "{} (実行 {}) は失敗しました: {}");
        put("approved_started", "{} approved {}; it started as run {}.", "{} hat {} freigegeben; es läuft als Lauf {}.",
                "{} a approuvé {} ; l'exécution {} a démarré.", "{} aprobó {}; se inició la ejecución {}.", "{} が {} を承認しました。実行 {} として開始しました。");
        put("approved_not_started", "{} approved {}, but it did not start: {}", "{} hat {} freigegeben, aber es ist nicht gestartet: {}",
                "{} a approuvé {}, mais l'exécution n'a pas démarré : {}", "{} aprobó {}, pero no se inició: {}", "{} が {} を承認しましたが、開始されませんでした: {}");
        put("approved_no_account", "{} approved {}, but {} no longer has an account, so it did not start.",
                "{} hat {} freigegeben, aber {} hat kein Konto mehr; es wurde nicht gestartet.",
                "{} a approuvé {}, mais {} n'a plus de compte ; l'exécution n'a pas démarré.",
                "{} aprobó {}, pero {} ya no tiene cuenta, así que no se inició.",
                "{} が {} を承認しましたが、{} のアカウントがもうないため開始されませんでした。");
        put("requester_cannot", "{} can no longer run {}.", "{} darf {} nicht mehr ausführen.", "{} ne peut plus exécuter {}.", "{} ya no puede ejecutar {}.",
                "{} は {} を実行できなくなりました。");
        put("rejected", "{} rejected {}.", "{} hat {} abgelehnt.", "{} a refusé {}.", "{} rechazó {}.", "{} が {} を却下しました。");
        put("rejected_comment", "{} rejected {}: {}", "{} hat {} abgelehnt: {}", "{} a refusé {} : {}", "{} rechazó {}: {}", "{} が {} を却下しました: {}");
        put("notice_approved", "Approved: {}", "Freigegeben: {}", "Approuvé : {}", "Aprobado: {}", "承認済み: {}");
        put("notice_rejected", "Rejected: {}", "Abgelehnt: {}", "Refusé : {}", "Rechazado: {}", "却下: {}");
        put("did_you_mean", "Did you mean {} or {}?", "Meinten Sie {} oder {}?", "Vouliez-vous dire {} ou {} ?", "¿Se refiere a {} o a {}?",
                "{} と {} のどちらですか？");
        put("dropped", "OK, I've dropped that request.", "In Ordnung, ich habe diese Anfrage verworfen.", "D'accord, j'ai abandonné cette demande.",
                "De acuerdo, he descartado esa solicitud.", "了解しました。その依頼は取り消しました。");
        put("could_not_read", "Sorry, I couldn't read that. {}", "Das konnte ich leider nicht lesen. {}", "Désolé, je n'ai pas pu lire cela. {}",
                "Lo siento, no he podido leer eso. {}", "すみません、読み取れませんでした。{}");
        put("what_for", "What should I use for {}?", "Was soll ich für {} verwenden?", "Que dois-je utiliser pour {} ?", "¿Qué debo usar para {}?",
                "{} には何を使いますか？");
    }
}
