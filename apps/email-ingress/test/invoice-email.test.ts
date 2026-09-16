import { describe, expect, it } from "vitest";
import {
  ecdsaHex,
  importP256PrivateJwk,
  isBudgetGuardRecipient,
  parseInvoiceEmail,
  sha256Hex,
} from "../src/invoice-email";

describe("invoice email intake", () => {
  it("only accepts private aliases on the invoice subdomain", () => {
    expect(isBudgetGuardRecipient("invoice-0123456789abcdef01234567@inbox.budget.cloudcomms.co.za", "inbox.budget.cloudcomms.co.za")).toBe(true);
    expect(isBudgetGuardRecipient("hello@inbox.budget.cloudcomms.co.za", "inbox.budget.cloudcomms.co.za")).toBe(false);
    expect(isBudgetGuardRecipient("invoice-0123456789abcdef01234567@cloudcomms.co.za", "inbox.budget.cloudcomms.co.za")).toBe(false);
  });

  it("extracts and sanitizes PDF attachments", async () => {
    const raw = new TextEncoder().encode([
      "From: billing@xneelo.co.za",
      "To: invoice-0123456789abcdef01234567@inbox.budget.cloudcomms.co.za",
      "Subject: September invoice",
      "Message-ID: <invoice-42@example.com>",
      "MIME-Version: 1.0",
      "Content-Type: multipart/mixed; boundary=budgetguard",
      "",
      "--budgetguard",
      "Content-Type: text/plain",
      "",
      "Please find the invoice attached.",
      "--budgetguard",
      "Content-Type: application/pdf; name=September Invoice.pdf",
      "Content-Disposition: attachment; filename=September Invoice.pdf",
      "Content-Transfer-Encoding: base64",
      "",
      "JVBERi0xLjQKJSVFT0Y=",
      "--budgetguard--",
      "",
    ].join("\r\n"));

    const parsed = await parseInvoiceEmail(raw.buffer, 1024, 5);
    expect(parsed.subject).toBe("September invoice");
    expect(parsed.messageId).toBe("<invoice-42@example.com>");
    expect(parsed.attachments).toHaveLength(1);
    expect(parsed.attachments[0].fileName).toBe("September-Invoice.pdf");
    expect(new TextDecoder().decode(parsed.attachments[0].bytes)).toContain("%PDF-1.4");
  });

  it("produces stable content hashes and verifiable signatures", async () => {
    const bytes = new TextEncoder().encode("invoice");
    expect(await sha256Hex(bytes)).toBe("52d6e3de4fa0dcc29946695f93940c3e7f26f30e1e39f4b1a49ad98839112786");
    const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
    const signature = await ecdsaHex(pair.privateKey, "value");
    const verified = await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      pair.publicKey,
      Uint8Array.from(signature.match(/.{2}/g) ?? [], (part) => Number.parseInt(part, 16)),
      new TextEncoder().encode("value"),
    );
    expect(verified).toBe(true);
  });

  it("imports the encrypted-text P-256 JWK used by the deployed Worker", async () => {
    const pair = await crypto.subtle.generateKey(
      { name: "ECDSA", namedCurve: "P-256" },
      true,
      ["sign", "verify"],
    );
    const serialized = JSON.stringify(await crypto.subtle.exportKey("jwk", pair.privateKey));
    const imported = await importP256PrivateJwk(serialized);
    const signature = await ecdsaHex(imported, "production-shape");

    expect(await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      pair.publicKey,
      Uint8Array.from(signature.match(/.{2}/g) ?? [], (part) => Number.parseInt(part, 16)),
      new TextEncoder().encode("production-shape"),
    )).toBe(true);
  });

  it("rejects missing or malformed signing keys with a useful error", async () => {
    await expect(importP256PrivateJwk("")).rejects.toThrow("not configured");
    await expect(importP256PrivateJwk("{}"))
      .rejects.toThrow("not a P-256 private JWK");
  });
});
