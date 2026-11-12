/**
 * Plain words for the values in a field's list (command review X1, 10 Nov 2026): 273 of the catalogue's 472 lists hold
 * coded values (eq, gt, ISO_LOCAL_DATE, azure_openai). Only the label changes; the value saved in the agent stays the
 * same, so agents and toolkits are untouched. Common codes have words of their own; any other is tidied (spaces for _
 * and -, a capital first letter, known names kept as written).
 */
const WORDS: Record<string, string> = {
  // comparisons
  eq: "equals", ne: "does not equal", gt: "is greater than", ge: "is greater than or equal to", lt: "is less than", le: "is less than or equal to",
  contains: "contains", not_contains: "does not contain", starts_with: "starts with", ends_with: "ends with", matches: "matches the pattern (regular expression)",
  empty: "is empty", not_empty: "is not empty", in: "is one of", not_in: "is not one of", between: "is between",
  // date standards (what the text looks like)
  ISO_LOCAL_DATE: "ISO date (2026-11-10)", ISO_LOCAL_TIME: "ISO time (14:30:00)", ISO_LOCAL_DATE_TIME: "ISO date and time (2026-11-10T14:30:00)",
  ISO_OFFSET_DATE_TIME: "ISO with offset (2026-11-10T14:30:00+05:30)", ISO_ZONED_DATE_TIME: "ISO with zone (…+05:30[Asia/Kolkata])",
  ISO_INSTANT: "ISO UTC instant (2026-11-10T09:00:00Z)", ISO_DATE: "ISO date, offset allowed", ISO_DATE_TIME: "ISO date and time, offset allowed",
  ISO_ORDINAL_DATE: "Day of the year (2026-314)", ISO_WEEK_DATE: "Week date (2026-W46-2)", BASIC_ISO_DATE: "Compact date (20261110)",
  RFC_1123_DATE_TIME: "Web and e-mail date (Tue, 10 Nov 2026 14:30:00 GMT)",
  // AI and cloud providers, as their makers write them
  openai: "OpenAI", azure_openai: "Azure OpenAI", openai_compatible: "OpenAI-compatible", anthropic: "Anthropic", aws_bedrock: "AWS Bedrock",
  aws_bedrock_sagemaker: "AWS SageMaker", google_vertex: "Google Vertex AI", huggingface: "Hugging Face", ollama: "Ollama", gemini: "Google Gemini",
  // sign-in and connections
  OAUTH: "OAuth", KEYPAIR_JWT: "Key pair (JWT)", PROGRAMMATIC_ACCESS_TOKEN: "Programmatic access token", ssl: "SSL/TLS", starttls: "STARTTLS",
  ntlm: "NTLM", kerberos: "Kerberos", basic: "Basic (user name and password)", bearer: "Bearer token", api_key: "API key",
  // browsers
  chrome: "Google Chrome", edge: "Microsoft Edge", firefox: "Mozilla Firefox", chromium: "Chromium",
  // formats and encodings
  json: "JSON", xml: "XML", csv: "CSV", html: "HTML", pdf: "PDF", txt: "Text (.txt)", hex: "Hexadecimal", base64: "Base64", utf8: "UTF-8", "utf-8": "UTF-8",
  // casing
  upper: "UPPER CASE", lower: "lower case", title: "Title Case", sentence: "Sentence case",
  // rounding
  half_up: "Round half up", half_even: "Round half to even (banker's)", down: "Down", up: "Up", floor: "Floor (towards lower)", ceiling: "Ceiling (towards higher)",
};

/** Names written as their makers write them, kept when a code is tidied. */
const NAMES = ["SQL", "URL", "API", "ID", "PDF", "CSV", "JSON", "XML", "HTML", "HTTP", "HTTPS", "FTP", "SFTP", "SMTP", "IMAP", "POP3", "OCR", "AI", "UTC", "IANA", "VM", "SAP"];

export function enumWord(value: unknown): string {
  const v = String(value);
  if (WORDS[v] !== undefined) return WORDS[v];
  if (WORDS[v.toLowerCase()] !== undefined && v.length > 3) return WORDS[v.toLowerCase()];
  // tidy the rest: day_of_week -> Day of week; KEY_PAIR -> Key pair; known names stay as written
  if (!/[_-]/.test(v) && !/^[A-Z0-9]+$/.test(v)) return v.charAt(0).toUpperCase() + v.slice(1);
  const words = v.split(/[_-]+/).filter(Boolean).map((w) => NAMES.includes(w.toUpperCase()) ? w.toUpperCase() : w.toLowerCase());
  const text = words.join(" ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}
