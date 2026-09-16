const SIGNATURE_WINDOW_SECONDS = 300;

export async function verifyIngressSignature(
  headers: Headers,
  recipient: string,
  contentSha256: string,
  publicJwkJson: string,
  nowSeconds: number = Date.now() / 1000,
): Promise<boolean> {
  const timestampText = headers.get("x-budgetguard-timestamp") ?? "";
  const signatureHex = headers.get("x-budgetguard-signature") ?? "";
  const timestamp = Number(timestampText);
  if (
    !Number.isInteger(timestamp) ||
    Math.abs(nowSeconds - timestamp) > SIGNATURE_WINDOW_SECONDS
  ) return false;
  if (!/^[a-f0-9]{128}$/i.test(signatureHex)) return false;

  try {
    const publicJwk = JSON.parse(publicJwkJson) as JsonWebKey;
    const key = await crypto.subtle.importKey(
      "jwk",
      publicJwk,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"],
    );
    return crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      key,
      fromHex(signatureHex).buffer,
      new TextEncoder().encode(
        `${timestampText}.${recipient}.${contentSha256}`,
      ),
    );
  } catch {
    return false;
  }
}

function fromHex(value: string): Uint8Array<ArrayBuffer> {
  return Uint8Array.from(
    value.match(/.{2}/g) ?? [],
    (pair) => Number.parseInt(pair, 16),
  );
}
