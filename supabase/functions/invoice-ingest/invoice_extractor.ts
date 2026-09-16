export type InvoiceExtraction = {
  supplierName: string | null;
  registrationNumber: string | null;
  vatNumber: string | null;
  contactEmail: string | null;
  invoiceNumber: string | null;
  issueDate: string | null;
  dueDate: string | null;
  currency: string;
  totalCents: number | null;
  outstandingCents: number | null;
  paymentReference: string | null;
  bankName: string | null;
  bankAccountHolder: string | null;
  bankAccountNumber: string | null;
  bankBranchCode: string | null;
  bankAccountType: string | null;
  confidence: number;
  pageCount: number | null;
  warnings: string[];
  evidence: Record<string, string | null>;
};

export function extractInvoiceFields(
  rawText: string,
  sender: string,
): InvoiceExtraction {
  const text = normalize(rawText);
  const lines = text.split("\n").map((line) => line.trim()).filter(Boolean);
  const supplierName =
    labelled(text, ["supplier", "from", "company name", "trading as"]) ??
      inferSupplier(lines, sender);
  const invoiceNumber = labelled(text, [
    "invoice number",
    "invoice no",
    "invoice #",
    "tax invoice no",
    "document number",
  ]);
  const issueDate = parseDate(
    labelled(text, [
      "invoice date",
      "date issued",
      "issue date",
      "document date",
    ]),
  );
  const dueDate = parseDate(
    labelled(text, ["due date", "payment due", "pay by"]),
  );
  const outstandingText = labelled(text, [
    "outstanding amount",
    "amount outstanding",
    "amount due",
    "balance due",
    "total due",
  ]);
  const totalText = labelled(text, [
    "invoice total",
    "total incl vat",
    "total including vat",
    "grand total",
    "total",
  ]);
  const outstandingCents = parseMoney(outstandingText);
  const totalCents = parseMoney(totalText) ?? outstandingCents;
  const registrationNumber = labelled(text, [
    "registration number",
    "registration no",
    "company reg",
    "reg no",
  ]);
  const vatNumber = labelled(text, ["vat number", "vat no", "tax number"]);
  const contactEmail = labelled(text, ["email", "accounts email"]) ??
    firstEmail(text);
  const bankName = labelled(text, ["bank name", "bank"]);
  const bankAccountHolder = labelled(text, [
    "account holder",
    "account name",
    "beneficiary",
  ]);
  const bankAccountNumber = digitsOnly(
    labelled(text, ["account number", "account no", "bank account"]),
    6,
    20,
  );
  const bankBranchCode = digitsOnly(
    labelled(text, ["branch code", "branch no"]),
    4,
    10,
  );
  const bankAccountType = labelled(text, ["account type"]);
  const paymentReference = labelled(text, [
    "payment reference",
    "your reference",
    "reference",
  ]);
  const pageCount = parsePageCount(text);

  const primary = [supplierName, invoiceNumber, issueDate, totalCents];
  const supporting = [
    dueDate,
    outstandingCents,
    registrationNumber,
    vatNumber,
    bankName,
    bankAccountNumber,
    bankBranchCode,
    paymentReference,
  ];
  const confidence = Math.min(
    0.99,
    (primary.filter((value) => value != null).length * 0.16) +
      (supporting.filter((value) => value != null).length * 0.045),
  );
  const warnings: string[] = [];
  if (!supplierName) warnings.push("Supplier name was not found.");
  if (outstandingCents == null && totalCents == null) {
    warnings.push("Outstanding amount was not found.");
  }
  if (!invoiceNumber) warnings.push("Invoice number was not found.");
  if (bankAccountNumber) {
    warnings.push(
      "Bank details came from the invoice and must be independently confirmed before payment.",
    );
  }

  return {
    supplierName,
    registrationNumber,
    vatNumber,
    contactEmail,
    invoiceNumber,
    issueDate,
    dueDate,
    currency: /\b(?:USD|US\$|\$)\b/i.test(text)
      ? "USD"
      : /\bEUR\b|€/i.test(text)
      ? "EUR"
      : "ZAR",
    totalCents,
    outstandingCents: outstandingCents ?? totalCents,
    paymentReference,
    bankName,
    bankAccountHolder,
    bankAccountNumber,
    bankBranchCode,
    bankAccountType,
    confidence: Number(confidence.toFixed(3)),
    pageCount,
    warnings,
    evidence: {
      supplier_name: supplierName,
      invoice_number: invoiceNumber,
      issue_date: issueDate,
      due_date: dueDate,
      total: totalText,
      outstanding: outstandingText,
      bank_name: bankName,
      bank_account_number: bankAccountNumber
        ? `****${bankAccountNumber.slice(-4)}`
        : null,
      branch_code: bankBranchCode,
      payment_reference: paymentReference,
    },
  };
}

function normalize(value: string): string {
  return value.replace(/\r/g, "\n").replace(/[\t ]+/g, " ").replace(
    /\n{3,}/g,
    "\n\n",
  ).trim();
}

function labelled(text: string, labels: string[]): string | null {
  for (const label of labels) {
    const escaped = label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const pattern = new RegExp(
      `(?:^|\\n)\\s*${escaped}\\s*(?::|-)?\\s*([^\\n]{2,100})`,
      "im",
    );
    const match = pattern.exec(text);
    if (match?.[1]) return cleanValue(match[1]);
  }
  return null;
}

function inferSupplier(lines: string[], sender: string): string | null {
  const candidate = lines.slice(0, 12).find((line) => {
    if (line.length < 3 || line.length > 100) return false;
    if (
      /^(tax\s+)?invoice\b|statement|page\s+\d|date\b|invoice\s*(?:no|number|#)/i
        .test(line)
    ) return false;
    if (/^\d|@|https?:|www\./i.test(line)) return false;
    return /[a-z]/i.test(line);
  });
  if (candidate) return cleanValue(candidate);
  const domain = sender.split("@")[1]?.split(".")[0];
  return domain
    ? domain.replace(/[-_]+/g, " ").replace(
      /\b\w/g,
      (letter) => letter.toUpperCase(),
    )
    : null;
}

function cleanValue(value: string): string {
  return value.trim().replace(/^[:#\-\s]+/, "").replace(/[|]+.*$/, "").trim();
}

function parseMoney(value: string | null): number | null {
  if (!value) return null;
  const match = value.match(/(?:R|ZAR|USD|EUR|\$|€)?\s*(-?[\d\s,.]+)(?!\d)/i);
  if (!match?.[1]) return null;
  let normalized = match[1].replace(/\s/g, "");
  if (normalized.includes(",") && normalized.includes(".")) {
    normalized = normalized.replace(/,/g, "");
  } else if (/,[0-9]{2}$/.test(normalized)) {
    normalized = normalized.replace(",", ".");
  } else normalized = normalized.replace(/,/g, "");
  const amount = Number(normalized);
  return Number.isFinite(amount) ? Math.round(Math.abs(amount) * 100) : null;
}

function parseDate(value: string | null): string | null {
  if (!value) return null;
  const iso = value.match(/\b(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})\b/);
  if (iso) return validIso(Number(iso[1]), Number(iso[2]), Number(iso[3]));
  const numeric = value.match(
    /\b(\d{1,2})[-/.](\d{1,2})[-/.](20\d{2}|\d{2})\b/,
  );
  if (numeric) {
    return validIso(
      Number(numeric[3]) < 100 ? 2000 + Number(numeric[3]) : Number(numeric[3]),
      Number(numeric[2]),
      Number(numeric[1]),
    );
  }
  const named = value.match(
    /\b(\d{1,2})\s+(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\s+(20\d{2})\b/i,
  );
  if (!named) return null;
  const months = [
    "jan",
    "feb",
    "mar",
    "apr",
    "may",
    "jun",
    "jul",
    "aug",
    "sep",
    "oct",
    "nov",
    "dec",
  ];
  return validIso(
    Number(named[3]),
    months.indexOf(named[2].slice(0, 3).toLowerCase()) + 1,
    Number(named[1]),
  );
}

function validIso(year: number, month: number, day: number): string | null {
  const date = new Date(Date.UTC(year, month - 1, day));
  if (
    date.getUTCFullYear() != year || date.getUTCMonth() != month - 1 ||
    date.getUTCDate() != day
  ) return null;
  return `${year.toString().padStart(4, "0")}-${
    month.toString().padStart(2, "0")
  }-${day.toString().padStart(2, "0")}`;
}

function digitsOnly(
  value: string | null,
  minimum: number,
  maximum: number,
): string | null {
  if (!value) return null;
  const digits = value.replace(/\D/g, "");
  return digits.length >= minimum && digits.length <= maximum ? digits : null;
}

function firstEmail(text: string): string | null {
  return text.match(/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/i)?.[0]
    ?.toLowerCase() ?? null;
}

function parsePageCount(text: string): number | null {
  const values = Array.from(text.matchAll(/\bpage\s+\d+\s+of\s+(\d+)\b/gi)).map(
    (match) => Number(match[1]),
  );
  return values.length ? Math.max(...values.filter(Number.isFinite)) : null;
}
