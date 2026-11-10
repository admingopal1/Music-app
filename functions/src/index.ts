import { initializeApp } from "firebase-admin/app";
import { getFirestore, FieldValue, Timestamp } from "firebase-admin/firestore";
import { defineSecret } from "firebase-functions/params";
import { HttpsError, onCall } from "firebase-functions/v2/https";

initializeApp();
const db = getFirestore();
const OPENAI_API_KEY = defineSecret("OPENAI_API_KEY");

const REGION = "asia-south1";
const MODEL = "gpt-4.1-mini";
const MAX_PROMPT_CHARS = 1800;
const DAILY_REQUEST_LIMIT = 60;
const ALLOWED_FEATURES = new Set([
  "music_assistant",
  "ai_dj",
  "playlist_ideas",
  "lyrics_explanation",
  "lyrics_translation",
  "music_discovery"
]);

type RequestData = {
  feature?: string;
  prompt?: string;
  tracks?: Array<{ title?: string; artist?: string; album?: string }>;
  lyrics?: string;
  targetLanguage?: string;
};

function cleanText(value: unknown, max: number): string {
  if (typeof value !== "string") return "";
  return value.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, "").trim().slice(0, max);
}

export const musicAi = onCall(
  {
    region: REGION,
    enforceAppCheck: true,
    secrets: [OPENAI_API_KEY],
    timeoutSeconds: 60,
    memory: "256MiB"
  },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError("unauthenticated", "Please sign in to use AI features.");
    }

    const data = (request.data ?? {}) as RequestData;
    const feature = cleanText(data.feature, 40);
    const prompt = cleanText(data.prompt, MAX_PROMPT_CHARS);
    const lyrics = cleanText(data.lyrics, 5000);
    const targetLanguage = cleanText(data.targetLanguage, 50);

    if (!ALLOWED_FEATURES.has(feature)) {
      throw new HttpsError("invalid-argument", "Unsupported AI feature.");
    }
    if (!prompt && !lyrics) {
      throw new HttpsError("invalid-argument", "A prompt or lyrics text is required.");
    }
    if (feature.startsWith("lyrics_") && !lyrics) {
      throw new HttpsError("invalid-argument", "Lyrics text is required for this feature.");
    }

    // Keep only a small amount of public metadata; never upload audio files or local paths.
    const tracks = Array.isArray(data.tracks)
      ? data.tracks.slice(0, 40).map((t) => ({
          title: cleanText(t?.title, 120),
          artist: cleanText(t?.artist, 100),
          album: cleanText(t?.album, 100)
        }))
      : [];

    const uid = request.auth.uid;
    const now = Timestamp.now();
    const dayKey = new Date().toISOString().slice(0, 10);
    const quotaRef = db.doc(`_server/aiQuota/users/${uid}/days/${dayKey}`);

    await db.runTransaction(async (tx) => {
      const snap = await tx.get(quotaRef);
      const count = snap.exists ? Number(snap.get("count") ?? 0) : 0;
      if (count >= DAILY_REQUEST_LIMIT) {
        throw new HttpsError("resource-exhausted", "Daily AI request limit reached. Try again tomorrow.");
      }
      tx.set(quotaRef, {
        count: count + 1,
        updatedAt: now,
        expiresAt: Timestamp.fromMillis(now.toMillis() + 45 * 24 * 60 * 60 * 1000)
      }, { merge: true });
    });

    const systemByFeature: Record<string, string> = {
      music_assistant: "You are a helpful music-library assistant. Use only the supplied prompt and track metadata. Do not claim to have heard audio.",
      ai_dj: "Act as a music DJ. Suggest a coherent listening order using only supplied track metadata. Do not invent tracks.",
      playlist_ideas: "Create useful playlist concepts and select only tracks present in the supplied metadata. Explain the mood briefly.",
      lyrics_explanation: "Explain the meaning and themes of the user-provided lyrics. Do not continue or reproduce missing song lyrics.",
      lyrics_translation: `Translate only the user-provided lyrics excerpt into ${targetLanguage || "the requested language"}. Do not add missing lyrics.`,
      music_discovery: "Help discover music based on the prompt and supplied metadata. Clearly distinguish suggestions from verified catalogue results."
    };

    const input = [
      `User request: ${prompt || "(no separate prompt)"}`,
      tracks.length ? `Local track metadata (not audio): ${JSON.stringify(tracks)}` : "",
      lyrics ? `User-provided lyrics excerpt: ${lyrics}` : ""
    ].filter(Boolean).join("\n\n");

    const response = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${OPENAI_API_KEY.value()}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        model: MODEL,
        instructions: systemByFeature[feature],
        input,
        max_output_tokens: 900
      })
    });

    if (!response.ok) {
      const detail = await response.text();
      console.error("AI provider request failed", response.status, detail.slice(0, 500));
      throw new HttpsError("unavailable", "AI service is temporarily unavailable.");
    }

    const result = await response.json() as {
      output?: Array<{ content?: Array<{ type?: string; text?: string }> }>;
    };
    const text = (result.output ?? [])
      .flatMap((item) => item.content ?? [])
      .filter((part) => part.type === "output_text")
      .map((part) => part.text ?? "")
      .join("\n")
      .trim();

    if (!text) throw new HttpsError("internal", "The AI service returned an empty response.");

    // Do not log prompts, lyrics, or personal library metadata.
    return { text, model: MODEL, feature };
  }
);
