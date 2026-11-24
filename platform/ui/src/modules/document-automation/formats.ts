/**
 * Common format checks for a document type's field: each fills the pattern (a regular expression) the value must
 * match, and says in words what it accepts. Patterns are anchored (^…$) and use only what both JavaScript and DocFlow's
 * Python accept, so they mean the same on the screen and in the service. Grouped: general formats first, then each
 * country's ids; "Own pattern…" stays for anything not listed. ok / bad: examples each pattern accepts and refuses
 * (the tests check them).
 */
/** The files DocFlow reads (its SUPPORTED_EXT): every file picker of Document Automation offers these. */
export const READABLE_FILES = ".pdf,.png,.jpg,.jpeg,.tif,.tiff,.bmp,.webp,.xml";

export interface FieldFormat { id: string; label: string; group: string; pattern: string; accepts: string; ok: string[]; bad: string[] }

const D = "(?:0[1-9]|[12]\\d|3[01])", M = "(?:0[1-9]|1[0-2])";
const f = (group: string, id: string, label: string, pattern: string, accepts: string, ok: string[], bad: string[]): FieldFormat =>
  ({ id, label, group, pattern, accepts, ok, bad });

export const FORMATS: FieldFormat[] = [
  f("General", "digits", "Digits only", "^\\d+$", "Only the digits 0 to 9, such as 004512.", ["004512"], ["12a"]),
  f("General", "invoice-number", "Invoice number (letters and digits)", "^[A-Za-z]{1,6}(?:[-/]?\\d+)+$",
    "1 to 6 letters, then digits, with dashes or slashes between groups allowed, such as INV-2026-0187 or PO1234.", ["INV-2026-0187", "PO1234"], ["2026-0187"]),
  f("General", "email", "E-mail address", "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", "An e-mail address, such as name@example.com.", ["name@example.com"], ["name@"]),
  f("General", "phone", "Phone number", "^\\+?[\\d\\s().-]{7,20}$",
    "7 to 20 digits, spaces, dots, dashes or brackets, with an optional + in front, such as +44 20 7946 0958.", ["+44 20 7946 0958", "(022) 555-0101", "030.1234.5678"], ["12"]),
  f("General", "iban", "IBAN (any country)", "^[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}$",
    "Two capital letters, two digits, then 11 to 30 capital letters or digits (spaces allowed), such as DE89 3704 0044 0532 0130 00.",
    ["DE89370400440532013000", "DE89 3704 0044 0532 0130 00", "SA0380000000608010167519"], ["de89"]),
  f("General", "swift", "BIC / SWIFT code", "^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}(?:[A-Z0-9]{3})?$", "8 or 11 letters and digits, such as DEUTDEFF or DEUTDEFF500.", ["DEUTDEFF", "DEUTDEFF500"], ["DEUT1"]),
  f("General", "currency-code", "Currency code (ISO)", "^[A-Z]{3}$", "Three capital letters, such as EUR, USD or INR.", ["EUR", "INR"], ["Euro"]),
  f("General", "percent", "Percentage", "^-?\\d+(?:[.,]\\d+)?\\s?%?$", "A number with an optional %, such as 19%, 5.5 or 7,5 %.", ["19%", "5.5", "7,5 %"], ["abc"]),
  f("Amounts", "amount", "Amount 1,234.56", "^-?\\d{1,3}(?:,?\\d{3})*\\.\\d{2}$",
    "2 decimals after a point, thousands commas allowed, such as 1,250.00 or 99.50 (UK, US, India, Gulf...).", ["1,250.00", "99.50", "-12.30", "1250.00"], ["12.5", "1,25.00"]),
  f("Amounts", "amount-comma", "Amount 1.234,56", "^-?\\d{1,3}(?:\\.?\\d{3})*,\\d{2}$",
    "2 decimals after a comma, thousands points allowed, such as 1.250,00 (Germany, Spain, Brazil, Indonesia...).", ["1.250,00", "99,50"], ["1,250.00"]),
  f("Amounts", "amount-space", "Amount 1 234,56", "^-?\\d{1,3}(?:[ \\u00a0\\u202f]?\\d{3})*,\\d{2}$",
    "2 decimals after a comma, thousands spaces allowed, such as 1 250,00 (France, Nordic countries, Eastern Europe...).", ["1 250,00", "99,50"], ["1.250.00"]),
  f("Amounts", "amount-apostrophe", "Amount 1'234.56", "^-?\\d{1,3}(?:['’]?\\d{3})*\\.\\d{2}$", "2 decimals, thousands apostrophes allowed, such as 1'250.00 (Switzerland).", ["1'250.00", "99.50"], ["1,250,00"]),
  f("Amounts", "amount-lakh", "Amount 1,23,456.78 (India)", "^-?\\d{1,2}(?:,\\d{2})*,\\d{3}\\.\\d{2}$|^-?\\d{1,3}\\.\\d{2}$",
    "Indian grouping (lakh, crore) with 2 decimals, such as 1,23,456.78 or 999.00.", ["1,23,456.78", "12,34,56,789.00", "999.00"], ["123,456.78"]),
  f("Amounts", "amount-0", "Amount without decimals", "^-?\\d{1,3}(?:[,. '’]?\\d{3})*$", "A whole amount, thousands separators allowed, such as 125,000 (Japan, Korea, Chile...).", ["125,000", "125000"], ["12.50"]),
  f("Amounts", "amount-3", "Amount with 3 decimals", "^-?\\d{1,3}(?:,?\\d{3})*\\.\\d{3}$", "3 decimals, such as 1,250.500 (Kuwait, Bahrain, Oman, Jordan, Tunisia...).", ["1,250.500", "0.125"], ["1,250.50"]),
  f("Dates", "date-iso", "Date yyyy-mm-dd", `^\\d{4}-${M}-${D}$`, "Year, month and day with dashes, such as 2026-11-14.", ["2026-11-14"], ["2026-13-01"]),
  f("Dates", "date-dmy", "Date dd/mm/yyyy", `^${D}/${M}/\\d{4}$`, "Day, month and year with slashes, such as 14/11/2026.", ["14/11/2026"], ["2026-11-14"]),
  f("Dates", "date-mdy", "Date mm/dd/yyyy", `^${M}/${D}/\\d{4}$`, "Month, day and year with slashes, such as 11/14/2026 (United States).", ["11/14/2026"], ["14/11/2026"]),
  f("Dates", "date-dot", "Date dd.mm.yyyy", `^${D}\\.${M}\\.\\d{4}$`, "Day, month and year with dots, such as 14.11.2026 (Germany, Russia, Turkey...).", ["14.11.2026"], ["14/11/2026"]),
  f("Dates", "date-ymd-slash", "Date yyyy/mm/dd", `^\\d{4}/${M}/${D}$`, "Year, month and day with slashes, such as 2026/11/14 (Japan, China, Korea...).", ["2026/11/14"], ["14/11/2026"]),
  f("Dates", "date-dmy-dash", "Date dd-mm-yyyy", `^${D}-${M}-\\d{4}$`, "Day, month and year with dashes, such as 14-11-2026 (Netherlands, India...).", ["14-11-2026"], ["2026-11-14"]),
  f("Dates", "date-text", "Date 14 Nov 2026", "^\\d{1,2}\\s+[^\\d\\s]{3,}\\s+\\d{4}$", "Day, month name (any language) and year, such as 14 Nov 2026 or 14 noviembre 2026.", ["14 Nov 2026", "3 noviembre 2026"], ["14/11/2026"]),
  // tax and company ids, by country: listed so the check reads in words; any other country's format is an own pattern
  f("European Union", "eu-vat", "EU VAT number (any member state)", "^(?:AT|BE|BG|CY|CZ|DE|DK|EE|EL|ES|FI|FR|HR|HU|IE|IT|LT|LU|LV|MT|NL|PL|PT|RO|SE|SI|SK|XI)[0-9A-Z+*]{2,12}$",
    "The country code and 2 to 12 letters or digits, such as DE123456789 or FRXX999999999.", ["DE123456789", "FR12345678901", "NL123456789B01"], ["US123456789"]),
  f("United Kingdom", "uk-vat", "UK VAT number", "^GB(?:\\d{9}|\\d{12}|GD\\d{3}|HA\\d{3})$", "GB and 9 or 12 digits, such as GB123456789.", ["GB123456789"], ["GB1234"]),
  f("United Kingdom", "uk-company", "UK company number", "^(?:\\d{8}|[A-Z]{2}\\d{6})$", "8 digits, or 2 letters and 6 digits, such as 01234567 or SC123456.", ["01234567", "SC123456"], ["1234"]),
  f("United States", "us-ein", "US EIN", "^\\d{2}-\\d{7}$", "2 digits, a dash and 7 digits, such as 12-3456789.", ["12-3456789"], ["123456789"]),
  f("United States", "us-zip", "US ZIP code", "^\\d{5}(?:-\\d{4})?$", "5 digits, or 5 and 4 with a dash, such as 94105 or 94105-1234.", ["94105", "94105-1234"], ["9410"]),
  f("Canada", "ca-bn", "Canada business number", "^\\d{9}(?:\\s?RT\\s?\\d{4})?$", "9 digits, with RT and 4 digits for GST/HST, such as 123456789RT0001.", ["123456789", "123456789RT0001"], ["12345"]),
  f("India", "gstin", "Indian GSTIN", "^\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]$",
    "15 characters: 2 digits, the 10-character PAN, a digit or letter, Z, and a check character, such as 29ABCDE1234F1Z5.", ["29ABCDE1234F1Z5"], ["29ABCDE1234F1A5"]),
  f("India", "pan", "Indian PAN", "^[A-Z]{5}\\d{4}[A-Z]$", "5 capital letters, 4 digits and a capital letter, such as ABCDE1234F.", ["ABCDE1234F"], ["ABCD1234F"]),
  f("India", "ifsc", "Indian IFSC bank code", "^[A-Z]{4}0[A-Z0-9]{6}$", "4 letters, a zero and 6 letters or digits, such as HDFC0001234.", ["HDFC0001234"], ["HDFC1001234"]),
  f("Australia", "au-abn", "Australian ABN", "^\\d{2}\\s?\\d{3}\\s?\\d{3}\\s?\\d{3}$", "11 digits, spaces allowed, such as 51 824 753 556.", ["51 824 753 556", "51824753556"], ["5182475355"]),
  f("Singapore", "sg-uen", "Singapore UEN", "^(?:\\d{8}[A-Z]|\\d{9}[A-Z]|[TSR]\\d{2}[A-Z]{2}\\d{4}[A-Z])$", "8 or 9 digits and a letter, or T/S/R, 2 digits, 2 letters, 4 digits and a letter.", ["53312345A", "201912345K", "T09LL0001B"], ["1234"]),
  f("Switzerland", "ch-uid", "Swiss UID / VAT number", "^CHE-?\\d{3}\\.?\\d{3}\\.?\\d{3}(?:\\s?(?:MWST|TVA|IVA))?$", "CHE and 9 digits, such as CHE-123.456.789 MWST.", ["CHE-123.456.789", "CHE123456789 MWST"], ["CH-123"]),
  f("Brazil", "br-cnpj", "Brazil CNPJ", "^\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}$", "14 digits, such as 12.345.678/0001-95.", ["12.345.678/0001-95", "12345678000195"], ["1234"]),
  f("Mexico", "mx-rfc", "Mexico RFC", "^[A-ZÑ&]{3,4}\\d{6}[A-Z0-9]{3}$", "3 or 4 letters, 6 digits (the date) and 3 letters or digits, such as ABC010203XY1.", ["ABC010203XY1", "GODE561231GR8"], ["AB12"]),
  f("United Arab Emirates", "ae-trn", "UAE TRN", "^\\d{15}$", "15 digits, such as 100123456700003.", ["100123456700003"], ["1001234"]),
  f("Saudi Arabia", "ksa-vat", "Saudi VAT number", "^3\\d{13}3$", "15 digits that start and end with 3, such as 300012345600003.", ["300012345600003"], ["300012345600004"]),
  // the patterns DocFlow's built-in types had before 3.3, so they still read in words on types saved with them
  f("Saudi Arabia", "iban-sa", "Saudi IBAN", "SA\\d{2}[A-Z0-9]{18}", "SA, 2 digits and 18 capital letters or digits, such as SA0380000000608010167519.", ["SA0380000000608010167519"], ["GB12"]),
  f("Saudi Arabia", "ksa-vat-15", "Saudi VAT number (15 digits, starts with 3)", "3\\d{14}", "15 digits starting with 3, such as 300012345600003.", ["300012345600003"], ["12345"]),
  f("Saudi Arabia", "ksa-id", "Saudi ID or passport number", "[12]\\d{9}|[A-Z]\\d{7,8}", "A 10-digit national ID starting with 1 or 2, or a letter and 7 or 8 digits.", ["1012345678", "A1234567"], ["ABC"]),
];

/** The common format a pattern is, if it is one of them. */
export const formatOf = (pattern: string) => FORMATS.find((f) => f.pattern === pattern);
