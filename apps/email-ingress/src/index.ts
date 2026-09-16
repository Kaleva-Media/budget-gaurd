import {
  ecdsaHex,
  importP256PrivateJwk,
  isBudgetGuardRecipient,
  parseInvoiceEmail,
  sha256Hex,
} from "./invoice-email";

const MAX_RAW_EMAIL_BYTES = 25 * 1024 * 1024;

export default {
  async email(message, env): Promise<void> {
    try {
      await handleEmail(message, env);
    } catch (error) {
      console.error(
        JSON.stringify({
          event: "invoice_email_exception",
          error: error instanceof Error ? error.message : "Unknown Worker error",
        }),
      );
      throw error;
    }
  },
} satisfies ExportedHandler<Env>;

async function handleEmail(message: ForwardableEmailMessage, env: Env): Promise<void> {
  if (!isBudgetGuardRecipient(message.to, env.INVOICE_DOMAIN)) {
    message.setReject("Unknown BudgetGuard invoice inbox.");
    return;
  }

  const declaredSize = Number(message.rawSize);
  if (Number.isFinite(declaredSize) && declaredSize > MAX_RAW_EMAIL_BYTES) {
    message.setReject("This email is too large for BudgetGuard.");
    return;
  }

  const rawMessage = await new Response(message.raw).arrayBuffer();
  if (rawMessage.byteLength > MAX_RAW_EMAIL_BYTES) {
    message.setReject("This email is too large for BudgetGuard.");
    return;
  }

  const parsed = await parseInvoiceEmail(
    rawMessage,
    positiveInteger(env.MAX_PDF_BYTES, 15 * 1024 * 1024),
    positiveInteger(env.MAX_PDFS_PER_EMAIL, 5),
  );
  if (parsed.attachments.length === 0) {
    message.setReject("Attach at least one PDF invoice smaller than 15 MB.");
    return;
  }

  const privateKey = await importP256PrivateJwk(env.INVOICE_INGRESS_PRIVATE_JWK);
  const results = [];
  for (const attachment of parsed.attachments) {
    results.push(await ingestAttachment(message, env, parsed, attachment, privateKey));
  }

  const unknownInbox = results.some((response) => response.status === 404);
  if (unknownInbox) {
    message.setReject("Unknown BudgetGuard invoice inbox.");
    return;
  }
  const failure = results.find((response) => !response.ok);
  if (failure) {
    console.error(JSON.stringify({ event: "invoice_email_failed", status: failure.status }));
    throw new Error(`Invoice ingestion returned ${failure.status}.`);
  }

  console.log(
    JSON.stringify({
      event: "invoice_email_accepted",
      attachmentCount: parsed.attachments.length,
      messageIdPresent: parsed.messageId != null,
    }),
  );
}

async function ingestAttachment(
  message: ForwardableEmailMessage,
  env: Env,
  parsed: Awaited<ReturnType<typeof parseInvoiceEmail>>,
  attachment: Awaited<ReturnType<typeof parseInvoiceEmail>>["attachments"][number],
  privateKey: CryptoKey,
): Promise<Response> {
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const contentSha256 = await sha256Hex(attachment.bytes);
  const signature = await ecdsaHex(
    privateKey,
    `${timestamp}.${message.to.toLowerCase()}.${contentSha256}`,
  );
  const form = new FormData();
  form.set("recipient", message.to.toLowerCase());
  form.set("sender", message.from);
  form.set("subject", parsed.subject ?? "");
  form.set("message_id", parsed.messageId ?? "");
  form.set("received_at", new Date().toISOString());
  form.set(
    "file",
    new File([Uint8Array.from(attachment.bytes).buffer], attachment.fileName, { type: "application/pdf" }),
  );

  const response = await fetch(env.INGEST_URL, {
    method: "POST",
    redirect: "manual",
    headers: {
      "apikey": env.SUPABASE_ANON_KEY,
      "authorization": `Bearer ${env.SUPABASE_ANON_KEY}`,
      "x-budgetguard-timestamp": timestamp,
      "x-budgetguard-signature": signature,
    },
    body: form,
  });

  if (response.status >= 300 && response.status < 400) {
    console.error(
      JSON.stringify({
        event: "invoice_ingest_redirect",
        status: response.status,
        location: response.headers.get("location"),
      }),
    );
  }
  return response;
}

function positiveInteger(value: string, fallback: number): number {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}
