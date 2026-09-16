import { assertEquals } from "jsr:@std/assert@1.0.14";
import { verifyIngressSignature } from "./ingress_signature.ts";

Deno.test("accepts a current P-256 invoice ingress signature", async () => {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const publicJwk = await crypto.subtle.exportKey("jwk", pair.publicKey);
  const timestamp = 1_789_459_200;
  const recipient =
    "invoice-0123456789abcdef01234567@inbox.budget.cloudcomms.co.za";
  const digest = "a".repeat(64);
  const signature = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    pair.privateKey,
    new TextEncoder().encode(`${timestamp}.${recipient}.${digest}`),
  );
  const headers = new Headers({
    "x-budgetguard-timestamp": timestamp.toString(),
    "x-budgetguard-signature": toHex(new Uint8Array(signature)),
  });

  assertEquals(
    await verifyIngressSignature(
      headers,
      recipient,
      digest,
      JSON.stringify(publicJwk),
      timestamp,
    ),
    true,
  );
  assertEquals(
    await verifyIngressSignature(
      headers,
      recipient,
      "b".repeat(64),
      JSON.stringify(publicJwk),
      timestamp,
    ),
    false,
  );
});

Deno.test("rejects stale invoice ingress signatures", async () => {
  const headers = new Headers({
    "x-budgetguard-timestamp": "100",
    "x-budgetguard-signature": "a".repeat(128),
  });
  assertEquals(
    await verifyIngressSignature(
      headers,
      "invoice@example.com",
      "b".repeat(64),
      "{}",
      401,
    ),
    false,
  );
});

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join(
    "",
  );
}
