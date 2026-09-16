import { createClient } from "npm:@supabase/supabase-js@2.116.0";
import { extractText } from "npm:unpdf@1.8.1";
import { verifyIngressSignature } from "./ingress_signature.ts";
import { extractInvoiceFields } from "./invoice_extractor.ts";

const MAX_PDF_BYTES = 15 * 1024 * 1024;

Deno.serve(async (request) => {
  if (request.method != "POST") {
    return json({ error: "Method not allowed." }, 405);
  }

  try {
    const form = await request.formData();
    const recipient = requiredText(form, "recipient").toLowerCase();
    const sender = requiredText(form, "sender");
    const subject = optionalText(form, "subject");
    const messageId = optionalText(form, "message_id");
    const receivedAt = optionalText(form, "received_at") ??
      new Date().toISOString();
    const file = form.get("file");
    if (!(file instanceof File)) {
      return json({ error: "A PDF attachment is required." }, 400);
    }
    if (file.size <= 0 || file.size > MAX_PDF_BYTES) {
      return json({ error: "PDF size is outside the allowed range." }, 413);
    }
    if (file.type && file.type != "application/pdf") {
      return json({ error: "Only PDF attachments are accepted." }, 415);
    }

    const bytes = new Uint8Array(await file.arrayBuffer());
    if (!isPdf(bytes)) {
      return json({ error: "The attachment is not a valid PDF." }, 415);
    }
    const contentSha256 = await sha256Hex(bytes);
    if (
      !await verifyIngressSignature(
        request.headers,
        recipient,
        contentSha256,
        requiredEnv("INVOICE_INGRESS_PUBLIC_JWK"),
      )
    ) {
      return json({ error: "Invalid ingress signature." }, 401);
    }

    const supabase = createClient(
      requiredEnv("SUPABASE_URL"),
      requiredEnv("SUPABASE_SERVICE_ROLE_KEY"),
      {
        auth: { persistSession: false, autoRefreshToken: false },
      },
    );
    const { data: inbox, error: inboxError } = await supabase
      .from("invoice_inboxes")
      .select("id,user_id")
      .eq("address", recipient)
      .eq("is_active", true)
      .maybeSingle();
    if (inboxError) throw inboxError;
    if (!inbox) return json({ error: "Invoice inbox not found." }, 404);

    const { data: duplicate, error: duplicateError } = await supabase
      .from("invoices")
      .select("id")
      .eq("user_id", inbox.user_id)
      .eq("content_sha256", contentSha256)
      .maybeSingle();
    if (duplicateError) throw duplicateError;
    if (duplicate) {
      return json(
        { accepted: true, duplicate: true, invoice_id: duplicate.id },
        200,
      );
    }

    let extractedText = "";
    let extractionStatus: "complete" | "needs_ocr" | "failed" = "complete";
    let extractionFailure: string | null = null;
    try {
      const result = await extractText(bytes, { mergePages: true });
      extractedText = Array.isArray(result.text)
        ? result.text.join("\n")
        : result.text;
      if (extractedText.replace(/\s/g, "").length < 40) {
        extractionStatus = "needs_ocr";
      }
    } catch (error) {
      extractionStatus = "failed";
      extractionFailure = error instanceof Error
        ? error.name
        : "PDF extraction failed";
    }

    const extraction = extractInvoiceFields(extractedText, sender);
    const warnings = [...extraction.warnings];
    if (extractionStatus == "needs_ocr") {
      warnings.push(
        "This PDF appears to contain scanned images and needs OCR.",
      );
    }
    if (extractionStatus == "failed") {
      warnings.push("The PDF text layer could not be read.");
    }
    const invoiceId = crypto.randomUUID();
    const safeName = sanitizeFileName(file.name || "invoice.pdf");
    const documentPath = `${inbox.user_id}/${invoiceId}/${safeName}`;

    const { error: uploadError } = await supabase.storage
      .from("invoice-documents")
      .upload(documentPath, bytes, {
        contentType: "application/pdf",
        upsert: false,
      });
    if (uploadError) throw uploadError;

    const { error: insertError } = await supabase.from("invoices").insert({
      id: invoiceId,
      user_id: inbox.user_id,
      inbox_id: inbox.id,
      email_message_id: messageId,
      email_from: sender,
      email_subject: subject,
      received_at: receivedAt,
      original_file_name: file.name || "invoice.pdf",
      document_path: documentPath,
      content_sha256: contentSha256,
      status: "needs_review",
      extraction_status: extractionStatus,
      extraction_confidence: extraction.confidence,
      supplier_name: extraction.supplierName,
      supplier_registration_number: extraction.registrationNumber,
      supplier_vat_number: extraction.vatNumber,
      supplier_contact_email: extraction.contactEmail,
      invoice_number: extraction.invoiceNumber,
      issue_date: extraction.issueDate,
      due_date: extraction.dueDate,
      currency: extraction.currency,
      total_cents: extraction.totalCents,
      outstanding_cents: extraction.outstandingCents,
      payment_reference: extraction.paymentReference,
      bank_name: extraction.bankName,
      bank_account_holder: extraction.bankAccountHolder,
      bank_account_number: extraction.bankAccountNumber,
      bank_branch_code: extraction.bankBranchCode,
      bank_account_type: extraction.bankAccountType,
      extracted_fields: {
        ...extraction.evidence,
        page_count: extraction.pageCount,
        extraction_failure: extractionFailure,
      },
      extraction_warnings: warnings,
    });
    if (insertError) {
      await supabase.storage.from("invoice-documents").remove([documentPath]);
      throw insertError;
    }

    console.log(
      JSON.stringify({
        event: "invoice_ingested",
        invoiceId,
        extractionStatus,
        confidence: extraction.confidence,
      }),
    );
    return json(
      { accepted: true, duplicate: false, invoice_id: invoiceId },
      202,
    );
  } catch (error) {
    const message = error instanceof Error
      ? error.message
      : "Unknown invoice ingestion error";
    console.error(
      JSON.stringify({ event: "invoice_ingest_failed", error: message }),
    );
    return json({ error: "Invoice ingestion failed." }, 500);
  }
});

function requiredText(form: FormData, name: string): string {
  const value = form.get(name);
  if (typeof value != "string" || !value.trim()) {
    throw new Error(`Missing ${name}.`);
  }
  return value.trim();
}

function optionalText(form: FormData, name: string): string | null {
  const value = form.get(name);
  return typeof value == "string" && value.trim() ? value.trim() : null;
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Missing ${name}.`);
  return value;
}

function isPdf(bytes: Uint8Array): boolean {
  return bytes.length >= 5 &&
    new TextDecoder().decode(bytes.slice(0, 5)) == "%PDF-";
}

function sanitizeFileName(value: string): string {
  const cleaned = value.normalize("NFKD").replace(/[^a-zA-Z0-9._-]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return cleaned.toLowerCase().endsWith(".pdf")
    ? cleaned.slice(0, 120)
    : `${cleaned.slice(0, 116) || "invoice"}.pdf`;
}

async function sha256Hex(value: Uint8Array): Promise<string> {
  return hex(
    new Uint8Array(
      await crypto.subtle.digest("SHA-256", Uint8Array.from(value).buffer),
    ),
  );
}

function hex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join(
    "",
  );
}

function json(body: unknown, status: number): Response {
  return Response.json(body, {
    status,
    headers: { "cache-control": "no-store" },
  });
}
