import PostalMime from "postal-mime";

export type InvoiceAttachment = {
  bytes: Uint8Array;
  fileName: string;
};

export type ParsedInvoiceEmail = {
  attachments: InvoiceAttachment[];
  messageId: string | null;
  subject: string | null;
};

const INBOX_LOCAL_PART = /^invoice-[a-f0-9]{24}$/;

export function isBudgetGuardRecipient(address: string, domain: string): boolean {
  const separator = address.lastIndexOf("@");
  if (separator <= 0) return false;
  const localPart = address.slice(0, separator).toLowerCase();
  const addressDomain = address.slice(separator + 1).toLowerCase();
  return addressDomain === domain.toLowerCase() && INBOX_LOCAL_PART.test(localPart);
}

export async function parseInvoiceEmail(
  rawMessage: ArrayBuffer,
  maxPdfBytes: number,
  maxPdfs: number,
): Promise<ParsedInvoiceEmail> {
  const email = await PostalMime.parse(rawMessage);
  const attachments = email.attachments
    .filter((attachment) => {
      const fileName = attachment.filename?.toLowerCase() ?? "";
      return attachment.mimeType.toLowerCase() === "application/pdf" || fileName.endsWith(".pdf");
    })
    .map((attachment) => ({ ...attachment, bytes: attachmentBytes(attachment.content) }))
    .filter((attachment) => attachment.bytes.byteLength > 0 && attachment.bytes.byteLength <= maxPdfBytes)
    .slice(0, maxPdfs)
    .map((attachment, index) => ({
      bytes: attachment.bytes,
      fileName: safePdfName(attachment.filename ?? `invoice-${index + 1}.pdf`),
    }));

  return {
    attachments,
    messageId: email.messageId?.trim() || null,
    subject: email.subject?.trim() || null,
  };
}

function attachmentBytes(content: string | ArrayBuffer | Uint8Array): Uint8Array {
  if (typeof content === "string") return new TextEncoder().encode(content);
  if (content instanceof Uint8Array) return Uint8Array.from(content);
  return new Uint8Array(content.slice(0));
}

export async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", Uint8Array.from(bytes).buffer);
  return toHex(new Uint8Array(digest));
}

export async function ecdsaHex(privateKey: CryptoKey, value: string): Promise<string> {
  const signature = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    privateKey,
    new TextEncoder().encode(value),
  );
  return toHex(new Uint8Array(signature));
}

export async function importP256PrivateJwk(serializedJwk: string): Promise<CryptoKey> {
  if (!serializedJwk.trim()) {
    throw new Error("Invoice ingress signing key is not configured.");
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(serializedJwk);
  } catch {
    throw new Error("Invoice ingress signing key is not valid JSON.");
  }

  if (!isP256PrivateJwk(parsed)) {
    throw new Error("Invoice ingress signing key is not a P-256 private JWK.");
  }

  return crypto.subtle.importKey(
    "jwk",
    {
      kty: "EC",
      crv: "P-256",
      x: parsed.x,
      y: parsed.y,
      d: parsed.d,
      ext: false,
      key_ops: ["sign"],
    },
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  );
}

function isP256PrivateJwk(value: unknown): value is { x: string; y: string; d: string } {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return candidate.kty === "EC" &&
    candidate.crv === "P-256" &&
    typeof candidate.x === "string" && candidate.x.length > 0 &&
    typeof candidate.y === "string" && candidate.y.length > 0 &&
    typeof candidate.d === "string" && candidate.d.length > 0;
}

function safePdfName(value: string): string {
  const safe = value.normalize("NFKD").replace(/[^a-zA-Z0-9._-]+/g, "-").replace(/^-+|-+$/g, "");
  const withExtension = safe.toLowerCase().endsWith(".pdf") ? safe : `${safe || "invoice"}.pdf`;
  return withExtension.slice(0, 120);
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}
