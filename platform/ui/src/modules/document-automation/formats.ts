/**
 * Common format checks for a document type's field: each fills the pattern (a regular expression) the value must
 * match, and says in words what it accepts. Patterns are anchored (^…$) and use only what both JavaScript and DocFlow's
 * Python accept, so they mean the same on the screen and in the service.
 */
export interface FieldFormat { id: string; label: string; pattern: string; accepts: string }

export const FORMATS: FieldFormat[] = [
  { id: "digits", label: "Digits only", pattern: "^\\d+$", accepts: "Only the digits 0 to 9, such as 004512." },
  { id: "amount", label: "Amount with 2 decimals", pattern: "^-?\\d{1,3}(?:,?\\d{3})*\\.\\d{2}$",
    accepts: "A number with exactly 2 decimals, thousands commas allowed, such as 1,250.00 or 99.50." },
  { id: "date-iso", label: "Date yyyy-mm-dd", pattern: "^\\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])$",
    accepts: "Year, month and day with dashes, such as 2026-11-14." },
  { id: "date-dmy", label: "Date dd/mm/yyyy", pattern: "^(?:0[1-9]|[12]\\d|3[01])/(?:0[1-9]|1[0-2])/\\d{4}$",
    accepts: "Day, month and year with slashes, such as 14/11/2026." },
  { id: "email", label: "E-mail address", pattern: "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", accepts: "An e-mail address, such as name@example.com." },
  { id: "phone", label: "Phone number", pattern: "^\\+?[\\d\\s()-]{7,20}$",
    accepts: "7 to 20 digits, spaces, dashes or brackets, with an optional + in front, such as +966 11 234 5678." },
  { id: "iban", label: "IBAN", pattern: "^[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}$",
    accepts: "Two capital letters, two digits, then 11 to 30 capital letters or digits (spaces allowed), such as SA03 8000 0000 6080 1016 7519." },
  { id: "ksa-vat", label: "Saudi VAT number", pattern: "^3\\d{13}3$", accepts: "15 digits that start and end with 3, such as 300012345600003." },
  { id: "gstin", label: "Indian GSTIN", pattern: "^\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]$",
    accepts: "15 characters: 2 digits, the 10-character PAN, a digit or letter, Z, and a check character, such as 29ABCDE1234F1Z5." },
  { id: "pan", label: "Indian PAN", pattern: "^[A-Z]{5}\\d{4}[A-Z]$", accepts: "5 capital letters, 4 digits and a capital letter, such as ABCDE1234F." },
  { id: "us-ein", label: "US EIN", pattern: "^\\d{2}-\\d{7}$", accepts: "2 digits, a dash and 7 digits, such as 12-3456789." },
  { id: "invoice-number", label: "Invoice number (letters and digits)", pattern: "^[A-Za-z]{1,6}(?:[-/]?\\d+)+$",
    accepts: "1 to 6 letters, then digits, with dashes or slashes between groups allowed, such as INV-2026-0187 or PO1234." },
];

/** The common format a pattern is, if it is one of them. */
export const formatOf = (pattern: string) => FORMATS.find((f) => f.pattern === pattern);
