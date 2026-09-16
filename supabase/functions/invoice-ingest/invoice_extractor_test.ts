import { assert, assertEquals } from "jsr:@std/assert@1.0.14";
import { extractInvoiceFields } from "./invoice_extractor.ts";

Deno.test("extracts a South African supplier invoice", () => {
  const invoice = extractInvoiceFields(
    `
    xneelo (Pty) Ltd
    TAX INVOICE
    Invoice Number: INV-2026-0915
    Invoice Date: 14 September 2026
    Due Date: 30 September 2026
    Registration Number: 2000/012345/07
    VAT Number: 4123456789
    Total Incl VAT: R2,000.00
    Amount Outstanding: R2,000.00
    Bank: First National Bank
    Account Holder: xneelo (Pty) Ltd
    Account Number: 62123456789
    Branch Code: 250655
    Account Type: Current
    Payment Reference: KM-1042
  `,
    "billing@xneelo.co.za",
  );

  assertEquals(invoice.supplierName, "xneelo (Pty) Ltd");
  assertEquals(invoice.invoiceNumber, "INV-2026-0915");
  assertEquals(invoice.issueDate, "2026-09-14");
  assertEquals(invoice.dueDate, "2026-09-30");
  assertEquals(invoice.outstandingCents, 200000);
  assertEquals(invoice.bankAccountNumber, "62123456789");
  assertEquals(invoice.bankBranchCode, "250655");
  assert(invoice.confidence >= 0.8);
});

Deno.test("falls back safely when an invoice is incomplete", () => {
  const invoice = extractInvoiceFields(
    "INVOICE\nInvoice no: 48",
    "billing@acme.example",
  );

  assertEquals(invoice.supplierName, "Acme");
  assertEquals(invoice.invoiceNumber, "48");
  assertEquals(invoice.outstandingCents, null);
  assert(invoice.warnings.includes("Outstanding amount was not found."));
});
